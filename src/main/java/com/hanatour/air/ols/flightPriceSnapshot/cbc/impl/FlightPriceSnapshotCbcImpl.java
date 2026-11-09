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
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 항공권 가격 대시보드 Cbc (스냅샷 조회 + Prometheus 중계)
 *
 * <pre>
 * [스냅샷] ols -> 항공API (DB 조회는 항공API 에서 수행. ols 에는 price(MariaDB) 데이터소스 설정이 없음)
 *   dynamicPricing 의 callApiService(MCI 방식, POST) 와 동일. 항공API 경로는 서버에 고정하고, 응답은 가공 없이 그대로 반환
 *
 * [Prometheus] ols -> Prometheus (읽기 전용 중계, flightPricingDashboard.html 전용)
 *   HTML 이 보내는 쿼리 형태만 허용한다. HTML 의 selector/freshness 템플릿을 서버가 같은 규칙으로 다시 만들어 보고
 *   요청 쿼리와 한 글자라도 다르면 거부한다. (임의 PromQL 실행 불가)
 *   조회 범위/해상도는 HTML 선택지(최대 24시간, 약 240포인트)에 맞춘다.
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

    // Prometheus 기본 주소 (내부망). 설정 monitoring.prometheus.base-url 이 있으면 설정이 우선
    private static final String DEFAULT_PROMETHEUS_BASE_URL = "http://10.100.23.95:9090";

    // HTML 이 조회하는 레코딩 규칙: (air:stage_ | air:naver:stage_) + 아래 16종 (HTML 의 TRAFFIC_RULES 와 같은 순서)
    private static final String[] METRIC_SUFFIXES = {
            "events:rate10m", "attempts:rate10m", "success:rate10m", "failure_ratio:rate10m",
            "events:rate30m", "attempts:rate30m", "success:rate30m", "failure_ratio:rate30m",
            "failure_ratio:zscore30m", "events:increase7d", "attempts:increase7d", "success:increase7d",
            "failure_ratio:7d", "events:rate30m:wow_min", "attempts:rate30m:wow_min", "success:rate30m:wow_min"};
    private static final String[] METRIC_PREFIXES = {"air:stage_", "air:naver:stage_"};   // [1] 이 naver (site_code 조건 없음)
    private static final String UP = "up{job=\"air-api\"}";

    // 라벨 값(environment/application/site_code) 허용 문자
    private static final Pattern LABEL_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern ENV_IN_QUERY = Pattern.compile("environment=\"([^\"]*)\"");
    private static final Pattern APP_IN_QUERY = Pattern.compile("application=\"([^\"]*)\"");
    private static final Pattern SITE_IN_QUERY = Pattern.compile("site_code=~\"([^\"]*)\\|\"");

    // HTML 선택지 최대 24시간 / step 은 60초 배수(약 240포인트) → 250포인트 상한
    private static final long MAX_RANGE_SECONDS = 24L * 3600;
    private static final long MAX_POINTS = 250;
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;      // 브라우저 시계가 서버보다 앞선 허용치
    private static final Pattern EPOCH_SECONDS = Pattern.compile("[0-9]{1,12}");
    private static final Pattern STEP_SECONDS = Pattern.compile("[0-9]{1,6}");

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 8000;             // HTML 요청 제한(12초)보다 짧게
    private static final String PROMETHEUS_QUERY_TIMEOUT = "6000ms";   // Prometheus 쪽 쿼리 실행 제한 (브라우저가 늘릴 수 없음)
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final RestServiceClient restServiceClient;
    private final ObjectMapper objectMapper;
    private final String prometheusBaseUrl;

    @Autowired
    public FlightPriceSnapshotCbcImpl(RestServiceClient restServiceClient,
                                      ObjectMapper objectMapper,
                                      // 설정 파일 없이 동작하도록 기본값을 지정. 다른 주소가 필요하면 설정에
                                      // monitoring.prometheus.base-url 을 추가해서 덮어쓸 수 있다. (설정 값이 우선)
                                      @Value("${monitoring.prometheus.base-url:" + DEFAULT_PROMETHEUS_BASE_URL + "}") String prometheusBaseUrl) {
        this.restServiceClient = restServiceClient;
        this.objectMapper = objectMapper;
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

    /** 즉시 조회: HTML 쿼리 4종(selector / selector+up / freshness / up) 만 허용 */
    @Override
    public String prometheusQuery(String query, String time) {
        requireHtmlQuery(query, false);

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("query", query);
        if (StringUtils.isNotBlank(time)) {
            params.put("time", String.valueOf(epochSeconds(time, "time")));
        }
        params.put("timeout", PROMETHEUS_QUERY_TIMEOUT);
        return prometheusGet("query", params);
    }

    /** 범위 조회: selector / selector+up 만 허용, 범위 24시간·250포인트 이하 */
    @Override
    public String prometheusQueryRange(String query, String start, String end, String step) {
        requireHtmlQuery(query, true);

        final long startSec = epochSeconds(start, "start");
        final long endSec = epochSeconds(end, "end");
        if (StringUtils.isBlank(step) || !STEP_SECONDS.matcher(step).matches() || Long.parseLong(step) < 1) {
            throw badRequest();
        }
        final long stepSec = Long.parseLong(step);
        if (startSec >= endSec || endSec - startSec > MAX_RANGE_SECONDS || (endSec - startSec) / stepSec + 1 > MAX_POINTS) {
            throw badRequest();
        }

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("query", query);
        params.put("start", String.valueOf(startSec));
        params.put("end", String.valueOf(endSec));
        params.put("step", String.valueOf(stepSec));
        params.put("timeout", PROMETHEUS_QUERY_TIMEOUT);
        return prometheusGet("query_range", params);
    }

    /** 알림 규칙 조회: HTML 은 type=alert 만 사용 */
    @Override
    public String prometheusRules(String type) {
        if (StringUtils.isNotBlank(type) && !"alert".equals(type)) {
            throw badRequest();
        }
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("type", "alert");
        return prometheusGet("rules", params);
    }

    /** Prometheus GET 호출. 리다이렉트는 따르지 않고, 응답 크기/시간을 제한한다. */
    private String prometheusGet(String operation, Map<String, String> params) {
        if (StringUtils.isBlank(prometheusBaseUrl)) {
            log.error("[PROMETHEUS-PROXY] monitoring.prometheus.base-url 설정이 없습니다.");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 연결 설정이 없습니다.");
        }

        final StringBuilder url = new StringBuilder(prometheusBaseUrl).append("/api/v1/").append(operation).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                url.append('&');
            }
            first = false;
            url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        log.info("[PROMETHEUS-PROXY] GET {} params={}", operation, StringUtils.abbreviate(String.valueOf(params), 300));

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");   // 인바운드 쿠키/인증 헤더는 전달하지 않는다

            final int status = conn.getResponseCode();
            final InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            final String body = in == null ? "" : readLimited(in);
            if (status != HttpURLConnection.HTTP_OK) {
                // 잘못된 PromQL(400), 쿼리 시간 초과(503), 리다이렉트(3xx) 등. 내부 오류 본문은 로그에만 남긴다.
                log.error("[PROMETHEUS-PROXY] 호출 실패 operation={}, status={}, body={}", operation, status, StringUtils.abbreviate(body, 500));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 조회에 실패했습니다.");
            }
            return body;
        } catch (SocketTimeoutException e) {
            log.error("[PROMETHEUS-PROXY] 시간 초과 operation={}", operation, e);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Prometheus 응답 제한 시간을 초과했습니다.");
        } catch (IOException e) {
            log.error("[PROMETHEUS-PROXY] 호출 실패 operation={}", operation, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 에 연결할 수 없습니다.");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 응답을 MAX_RESPONSE_BYTES 까지만 읽는다. 초과하면 중단. */
    private String readLimited(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("Prometheus 응답이 허용 크기(" + MAX_RESPONSE_BYTES + " bytes)를 초과했습니다.");
                }
                out.write(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- HTML 쿼리 템플릿 검사

    /**
     * 쿼리에서 라벨 값(environment/application/site_code)만 뽑아 검증한 뒤,
     * HTML 과 같은 규칙으로 쿼리를 다시 만들어 요청 쿼리와 완전히 같을 때만 허용한다.
     */
    private void requireHtmlQuery(String query, boolean rangeOnly) {
        if (StringUtils.isBlank(query) || !isHtmlQuery(query, rangeOnly)) {
            throw badRequest();
        }
    }

    private boolean isHtmlQuery(String query, boolean rangeOnly) {
        if (UP.equals(query)) {
            return true;
        }
        final String env = labelValue(ENV_IN_QUERY, query, false);
        final String app = labelValue(APP_IN_QUERY, query, false);
        final String site = labelValue(SITE_IN_QUERY, query, true);
        if ("".equals(env) || "".equals(app) || "".equals(site)) {      // 값이 있는데 허용 문자가 아님
            return false;
        }

        for (int scope = 0; scope < METRIC_PREFIXES.length; scope++) {
            final boolean naver = scope == 1;
            final String select = selector(METRIC_PREFIXES[scope], null, env, app, naver ? null : site);
            if (query.equals(select) || query.equals(select + " or " + UP)) {
                return true;
            }
            if (!rangeOnly && query.equals(freshness(METRIC_PREFIXES[scope], env, app, naver ? null : site))) {
                return true;
            }
        }
        return false;
    }

    /** 쿼리에서 첫 라벨 값을 꺼낸다. 없으면 null, 있는데 허용 문자가 아니면 ""(거부 표시). */
    private String labelValue(Pattern pattern, String query, boolean siteRegex) {
        final Matcher matcher = pattern.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        if (siteRegex) {
            value = value.replace("\\\\.", ".");     // 쿼리 문자열의 \\. → 리터럴 '.'
        }
        return LABEL_VALUE.matcher(value).matches() ? value : "";
    }

    /** HTML selector(): {__name__=~"이름|이름...",environment="..",application="..",site_code=~"..|"} */
    private String selector(String prefix, String exactName, String env, String app, String site) {
        final StringBuilder sb = new StringBuilder("{");
        if (exactName == null) {
            final StringBuilder names = new StringBuilder();
            for (String suffix : METRIC_SUFFIXES) {
                if (names.length() > 0) {
                    names.append('|');
                }
                names.append(escapeRegex(prefix + suffix));
            }
            sb.append("__name__=~").append(quote(names.toString()));
        } else {
            sb.append("__name__=").append(quote(exactName));
        }
        if (env != null) {
            sb.append(",environment=").append(quote(env));
        }
        if (app != null) {
            sb.append(",application=").append(quote(app));
        }
        if (site != null) {
            sb.append(",site_code=~").append(quote(escapeRegex(site) + "|"));
        }
        return sb.append('}').toString();
    }

    /** HTML freshnessQuery(): 규칙별 label_replace(time()-timestamp(...)) 16개 + up 1개를 " or " 로 연결 */
    private String freshness(String prefix, String env, String app, String site) {
        final StringBuilder sb = new StringBuilder();
        for (String suffix : METRIC_SUFFIXES) {
            final String name = prefix + suffix;
            sb.append("(label_replace(time()-timestamp(").append(selector(prefix, name, env, app, site))
                    .append("),\"dashboard_metric\",").append(quote(name)).append(",\"\",\"\")) or ");
        }
        return sb.append("(label_replace(time()-timestamp(").append(UP)
                .append("),\"dashboard_metric\",\"up\",\"\",\"\"))").toString();
    }

    /** JSON.stringify 와 동일 (값은 검증을 거친 안전 문자뿐이므로 역슬래시만 처리) */
    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\") + "\"";
    }

    private String escapeRegex(String value) {
        final StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (".*+?^${}()|[]\\".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** epoch 초(정수). 음수/과도하게 먼 미래(서버 시각 + 60초 초과) 거부 */
    private long epochSeconds(String value, String name) {
        if (StringUtils.isBlank(value) || !EPOCH_SECONDS.matcher(value).matches()) {
            throw badRequest();
        }
        final long seconds = Long.parseLong(value);
        if (seconds > System.currentTimeMillis() / 1000 + MAX_CLOCK_SKEW_SECONDS) {
            throw badRequest();
        }
        return seconds;
    }

    /** 입력 오류 (HTML 의 정상 요청은 항상 통과하므로 상세 사유는 내려주지 않는다) */
    private ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용된 대시보드 조회 형식과 파라미터를 확인하세요.");
    }
}
