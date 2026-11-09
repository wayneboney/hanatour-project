package com.hanatour.air.ols.flightPriceSnapshot.cbc.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanatour.air.cmn.common.ApiException;
import com.hanatour.air.cmn.webclient.RestServiceClient;
import com.hanatour.air.cmn.webclient.ServiceEndpoint;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import com.hanatour.common.cmn.restclient.vo.Header;
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
 * 항공권 가격 대시보드 Cbc (스냅샷 조회 + Prometheus 중계)
 *
 * <pre>
 * [스냅샷] ols -> 항공API (DB 조회는 항공API 에서 수행. ols 에는 price(MariaDB) 데이터소스 설정이 없음)
 *   dynamicPricing 의 callApiService(MCI 방식, POST) 와 동일
 *   1) 항공API 경로는 서버에 고정한다. (UI 에서 URL 을 받아 호출하면 내부망 임의 호출 통로가 되므로 금지)
 *   2) 조회 조건을 요청 JSON 본문으로 만들어 MCI 로 POST 호출한다.
 *   3) 항공API 응답(JSON 문자열)을 가공 없이 그대로 돌려준다. (응답 형식은 프론트 계약을 항공API 가 책임)
 *
 * [Prometheus] ols -> Prometheus (읽기 전용 중계)
 *   1) 주소는 서버 설정(monitoring.prometheus.base-url)으로만 지정한다. 경로는 query/query_range 로 고정, GET 만 사용
 *   2) 파라미터 검증 (PromQL 길이, 시간 형식, 조회 범위/해상도 상한 → Prometheus 과부하 방지)
 *   3) 값은 URI 변수로 바인딩해 인코딩한다. (PromQL 의 { } " & # 등이 파라미터를 주입하지 못함)
 *   4) 응답(JSON 문자열)을 가공 없이 그대로 돌려준다. 입력 오류 400, Prometheus 실패/설정 누락 502
 * </pre>
 */
@Slf4j
@Component
@hntframe.run.common.annotation.ServiceComponent
public class FlightPriceSnapshotCbcImpl implements FlightPriceSnapshotCbc {

    // ---------------------------------------------------------------- 스냅샷 (항공API)

    // 항공API 경로 (TODO: 항공API 개발자와 최종 경로 확인 필요)
    private static final String SNAPSHOT_PATH = "/flightPriceSnapshot/air/api/service/snapshots/v1.00";

    // 영업일 기준은 한국시간 고정 (UI 의 timezone 값은 사용하지 않음)
    private static final String TIME_ZONE = "Asia/Seoul";

    // ---------------------------------------------------------------- Prometheus

    private static final String PROMETHEUS_QUERY = "/api/v1/query";
    private static final String PROMETHEUS_QUERY_RANGE = "/api/v1/query_range";

    // UI 요청 제한(12초)보다 짧게
    private static final Duration PROMETHEUS_TIMEOUT = Duration.ofSeconds(10);

    // 입력 제한값 (운영하면서 조정. TODO: 실제 UI 의 조회 범위/해상도에 맞춰 확정)
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final long MAX_RANGE_SECONDS = 7L * 24 * 3600;   // 최대 조회 범위 7일
    private static final double MIN_STEP_SECONDS = 1.0;             // 최소 해상도 1초
    private static final long MAX_POINTS = 5000;                    // 시계열당 최대 포인트 수

    // epoch 초 (소수 허용) / 기간 표기 (예: 15, 15s, 1m)
    private static final Pattern EPOCH_PATTERN = Pattern.compile("^[0-9]{1,12}(\\.[0-9]{1,3})?$");
    private static final Pattern STEP_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h|d)?$");
    // 줄바꿈/탭 외 제어문자 금지
    private static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\t\\r\\n]]");

    private final RestServiceClient restServiceClient;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final String prometheusBaseUrl;

    @Autowired
    public FlightPriceSnapshotCbcImpl(RestServiceClient restServiceClient,
                                      ObjectMapper objectMapper,
                                      WebClient webClient,
                                      @Value("${monitoring.prometheus.base-url:}") String prometheusBaseUrl) {
        this.restServiceClient = restServiceClient;
        this.objectMapper = objectMapper;
        this.webClient = webClient;
        this.prometheusBaseUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(prometheusBaseUrl), "/");
    }

    // ================================================================ 스냅샷

    @Override
    public String getSnapshots(FlightPriceSnapshotReqVo vo) {
        final String response = callApiService(SNAPSHOT_PATH, toRequestJson(vo));

        // 응답 본문(대용량 가능)은 로그에 남기지 않고 크기만 기록
        log.info("[FLIGHT-PRICE-SNAPSHOT] MCI response length={}", response == null ? 0 : response.length());
        if (response == null) {
            throw new IllegalStateException("항공API 응답이 비어 있습니다: " + SNAPSHOT_PATH);
        }
        return response;
    }

    /**
     * 요청 JSON 생성 (TODO: 항공API 개발자와 요청 키 이름 확정 필요)
     * { "dateStart" : "2026-11-04", "dateEnd" : "2026-11-04", "timeZone" : "Asia/Seoul" }
     */
    private String toRequestJson(FlightPriceSnapshotReqVo vo) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("dateStart", vo.getDateStart());
        body.put("dateEnd", vo.getDateEnd());
        body.put("timeZone", TIME_ZONE);

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("요청 JSON 생성 실패", e);
        }
    }

    /**
     * 항공API MCI 방식 호출 (DynamicPricingCbcImpl.callApiService 와 동일)
     */
    private String callApiService(String url, String requestBody) {
        log.info("[FLIGHT-PRICE-SNAPSHOT] MCI {} body={}", url, requestBody);

        try {
            final Header mciHeader = new Header();
            mciHeader.setJobSysCd("P_AIR_A0SLE"); //업무시스템코드
            mciHeader.setPupsInttCd("BP_AIR"); //목적기관코드
            mciHeader.setFltxTycd("4280"); //전문유형코드
            mciHeader.setTrncDvcd("550"); // 처리구분코드
            mciHeader.setRspnTrnrMdcd("R"); // 응답전달방식코드
            mciHeader.setSvcDmnInfo(RestServiceClient.HANATOUR_AIR_API); //서비스도메인정보
            mciHeader.setSvcTrncId(url); //서비스거래ID

            return restServiceClient.sendWithMciHeaderToString(ServiceEndpoint.ENDPOINT_API, url, mciHeader, requestBody);
        } catch (ApiException e) {
            log.error("[FLIGHT-PRICE-SNAPSHOT] MCI 호출 실패 url={}", url, e);
            throw new IllegalStateException("항공API 호출 실패: " + url, e);
        }
    }

    // ================================================================ Prometheus

    @Override
    public String prometheusQuery(String query, String time) {
        validateQuery(query);

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("query", query);
        if (StringUtils.isNotBlank(time)) {
            toEpochSeconds(time.trim(), "time");
            params.put("time", time.trim());
        }
        return prometheusGet(PROMETHEUS_QUERY, params);
    }

    @Override
    public String prometheusQueryRange(String query, String start, String end, String step) {
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
        return prometheusGet(PROMETHEUS_QUERY_RANGE, params);
    }

    private String prometheusGet(String path, Map<String, String> params) {
        if (StringUtils.isBlank(prometheusBaseUrl)) {
            log.error("[PROMETHEUS-PROXY] monitoring.prometheus.base-url 설정이 없습니다.");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 연결 설정이 없습니다.");
        }

        // 값은 URI 변수로 바인딩 → 인코딩 (PromQL 의 { } " = ~ 등이 쿼리스트링을 깨거나 파라미터를 주입하지 못함)
        final UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(prometheusBaseUrl).path(path);
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
                    .block(PROMETHEUS_TIMEOUT);
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
