package com.hanatour.air.ols.monitoring.cbc.impl;

import com.hanatour.air.ols.monitoring.cbc.PrometheusProxyCbc;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prometheus 읽기 전용 중계 Cbc
 *
 * <pre>
 * 핵심 동작
 * 1) Prometheus 주소는 서버 설정으로만 지정한다. (UI 가 주소/경로를 정하면 내부망 임의 호출 통로가 되므로 금지)
 *      application 설정: monitoring.prometheus.base-url = http://10.100.23.95:9090  (환경별로 다름)
 * 2) 허용 경로는 /api/v1/query, /api/v1/query_range 두 개로 고정, GET 만 사용
 * 3) 파라미터를 검증한다. (PromQL 길이, 시간 형식, 조회 범위/해상도 상한 → Prometheus 과부하 방지)
 * 4) 쿼리 값은 URI 변수로 바인딩해 자동 인코딩한다. (PromQL 의 { } " 등 특수문자 안전)
 * 5) Prometheus 응답(JSON 문자열)을 가공 없이 그대로 반환한다.
 *
 * 입력 오류는 400, Prometheus 호출 실패/설정 누락은 502 로 응답한다. (ResponseStatusException)
 * </pre>
 */
@Slf4j
@Component
@hntframe.run.common.annotation.ServiceComponent
public class PrometheusProxyCbcImpl implements PrometheusProxyCbc {

    private static final String PATH_QUERY = "/api/v1/query";
    private static final String PATH_QUERY_RANGE = "/api/v1/query_range";

    // UI 요청 제한(12초)보다 짧게
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // ---- 입력 제한값 (운영하면서 조정. TODO: 실제 UI 의 조회 범위/해상도에 맞춰 확정)
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final long MAX_RANGE_SECONDS = 7L * 24 * 3600;   // 최대 조회 범위 7일
    private static final double MIN_STEP_SECONDS = 1.0;             // 최소 해상도 1초
    private static final long MAX_POINTS = 5000;                    // 시계열당 최대 포인트 수

    // epoch 초 (소수 허용) / 기간 표기 (예: 15, 15s, 1m)
    private static final Pattern EPOCH_PATTERN = Pattern.compile("^[0-9]{1,12}(\\.[0-9]{1,3})?$");
    private static final Pattern STEP_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h|d)?$");
    // 줄바꿈/탭 외 제어문자 금지
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\t\\r\\n]]");

    private final WebClient webClient;
    private final String baseUrl;

    @Autowired
    public PrometheusProxyCbcImpl(WebClient webClient,
                                  @Value("${monitoring.prometheus.base-url:}") String baseUrl) {
        this.webClient = webClient;
        this.baseUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(baseUrl), "/");
    }

    @Override
    public String query(String query, String time) {
        validateQuery(query);

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("query", query);
        if (StringUtils.isNotBlank(time)) {
            toEpochSeconds(time.trim(), "time");
            params.put("time", time.trim());
        }
        return get(PATH_QUERY, params);
    }

    @Override
    public String queryRange(String query, String start, String end, String step) {
        validateQuery(query);

        final double startSec = toEpochSeconds(StringUtils.trimToEmpty(start), "start");
        final double endSec = toEpochSeconds(StringUtils.trimToEmpty(end), "end");
        final double stepSec = toStepSeconds(StringUtils.trimToEmpty(step));

        if (endSec <= startSec) {
            throw badRequest("end 는 start 보다 커야 합니다.");
        }
        if (endSec - startSec > MAX_RANGE_SECONDS) {
            throw badRequest("조회 범위는 최대 " + (MAX_RANGE_SECONDS / 3600) + "시간까지 가능합니다.");
        }
        if ((endSec - startSec) / stepSec > MAX_POINTS) {
            throw badRequest("조회 해상도가 너무 촘촘합니다. step 을 늘려주세요. (최대 " + MAX_POINTS + "포인트)");
        }

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("query", query);
        params.put("start", start.trim());
        params.put("end", end.trim());
        params.put("step", step.trim());
        return get(PATH_QUERY_RANGE, params);
    }

    // ---------------------------------------------------------------- Prometheus 호출

    private String get(String path, Map<String, String> params) {
        if (StringUtils.isBlank(baseUrl)) {
            log.error("[PROMETHEUS-PROXY] monitoring.prometheus.base-url 설정이 없습니다.");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 연결 설정이 없습니다.");
        }

        // 값은 URI 변수로 바인딩 → 엔코딩 (PromQL 의 { } " = ~ 등이 쿼리스트링을 깨거나 파라미터를 주입하지 못함)
        final UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl).path(path);
        final Map<String, Object> vars = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), "{" + entry.getKey() + "}");
            vars.put(entry.getKey(), entry.getValue());
        }
        final URI uri = builder.encode().buildAndExpand(vars).toUri();

        log.info("[PROMETHEUS-PROXY] GET {} query={}", path, StringUtils.abbreviate(params.get("query"), 200));

        final String response;
        try {
            response = webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
        } catch (WebClientResponseException e) {
            // Prometheus 가 4xx/5xx 로 응답 (예: 잘못된 PromQL 400, 쿼리 시간 초과 503)
            log.error("[PROMETHEUS-PROXY] 호출 실패 path={}, status={}, body={}",
                    path, e.getRawStatusCode(), StringUtils.abbreviate(e.getResponseBodyAsString(), 500));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 조회에 실패했습니다.");
        } catch (RuntimeException e) {
            // 연결 실패, 타임아웃(block), 응답 크기 초과 등
            log.error("[PROMETHEUS-PROXY] 호출 실패 path={}", path, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 에 연결할 수 없습니다.");
        }

        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 응답이 비어 있습니다.");
        }
        return response;
    }

    // ---------------------------------------------------------------- 검증

    private void validateQuery(String query) {
        if (StringUtils.isBlank(query)) {
            throw badRequest("query 를 입력하세요.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw badRequest("query 가 너무 깁니다. (최대 " + MAX_QUERY_LENGTH + "자)");
        }
        if (CONTROL_CHAR_PATTERN.matcher(query).find()) {
            throw badRequest("query 에 허용되지 않는 문자가 있습니다.");
        }
    }

    /** epoch 초(소수 허용) 또는 RFC3339(오프셋 필수) → epoch 초 */
    private double toEpochSeconds(String value, String name) {
        if (EPOCH_PATTERN.matcher(value).matches()) {
            return Double.parseDouble(value);
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli() / 1000.0;
        } catch (DateTimeParseException e) {
            throw badRequest(name + " 형식이 올바르지 않습니다. (epoch 초 또는 RFC3339)");
        }
    }

    /** 15, 15s, 1m, 1h ... → 초 */
    private double toStepSeconds(String value) {
        final Matcher matcher = STEP_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw badRequest("step 형식이 올바르지 않습니다. (예: 15, 15s, 1m)");
        }
        final double number = Double.parseDouble(matcher.group(1));
        final String unit = matcher.group(2);

        double seconds;
        if ("ms".equals(unit)) {
            seconds = number / 1000.0;
        } else if ("m".equals(unit)) {
            seconds = number * 60;
        } else if ("h".equals(unit)) {
            seconds = number * 3600;
        } else if ("d".equals(unit)) {
            seconds = number * 86400;
        } else {
            seconds = number;   // 단위 없음 또는 s
        }
        if (seconds < MIN_STEP_SECONDS) {
            throw badRequest("step 은 최소 " + (int) MIN_STEP_SECONDS + "초 이상이어야 합니다.");
        }
        return seconds;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
