package com.hanatour.air.ols.flightPriceSnapshot.cbc.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hanatour.air.cmn.webclient.RestServiceClient;
import com.hanatour.air.cmn.webclient.ServiceEndpoint;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.PrometheusUpstreamException;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import hntframe.run.oltp.ext.service.context.ServiceContextConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 항공권 가격 대시보드 Cbc (스냅샷 조회 + Prometheus 중계)
 *
 * <pre>
 * [스냅샷] ols -> 항공API (DB 조회는 항공API 에서 수행. ols 에는 price(MariaDB) 데이터소스 설정이 없음)
 *   dynamicPricing 의 callGetApi(WebClient GET) 와 동일. 항공API 경로는 서버에 고정하고, 응답은 가공 없이 그대로 반환
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
public class FlightPriceSnapshotCbcImpl implements FlightPriceSnapshotCbc, org.springframework.beans.factory.DisposableBean {

    // ---------------------------------------------------------------- 스냅샷 (항공API)

    // 항공API 경로 (GET /lambda/naver/flight/snapshots?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD)
    private static final String SNAPSHOT_PATH = "/lambda/naver/flight/snapshots";

    // HTML 요청 제한(30초)보다 짧게
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(25);

    // 스냅샷 응답은 대용량일 수 있어 WebClient 기본 버퍼(256KB) 제한을 넓힌다.
    private static final int SNAPSHOT_MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    // ---------------------------------------------------------------- Prometheus

    // Prometheus 기본 주소 (내부망). 설정 monitoring.prometheus.base-url 이 있으면 설정이 우선
    private static final String DEFAULT_PROMETHEUS_BASE_URL = "http://10.100.23.95:9090";

    // HTML 이 조회하는 레코딩 규칙: (air:stage_ | air:naver:stage_) + 아래 22종 (HTML 의 TRAFFIC_RULES 와 같은 순서)
    private static final String[] METRIC_SUFFIXES = {
            "events:rate10m", "attempts:rate10m", "success:rate10m", "outcomes:rate10m", "failures:rate10m", "failure_ratio:rate10m",
            "events:rate30m", "attempts:rate30m", "success:rate30m", "outcomes:rate30m", "failures:rate30m", "failure_ratio:rate30m",
            "failure_ratio:zscore30m", "events:increase7d", "attempts:increase7d", "success:increase7d", "outcomes:increase7d", "failures:increase7d",
            "failure_ratio:7d", "events:rate30m:wow_min", "attempts:rate30m:wow_min", "success:rate30m:wow_min"};
    private static final String[] METRIC_PREFIXES = {"air:stage_", "air:naver:stage_"};   // [1] 이 naver (site_code 조건 없음)
    private static final String UP = "up{job=\"air-api\"}";
    // 원본 GDS 카운터는 공급사·사이트를 보존한다. 아래 별칭은 조회 결과 이름이며 recording rule이 아니다.
    private static final String GDS_DIMENSIONS = "environment,application,site_code,gds,gds_operation";
    private static final String GDS_EVENT_PREFIX = "air:dashboard_gds_events:";

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
    private static final int READ_TIMEOUT_MS = 40000;             // HTML 요청 제한(45초)보다 짧게
    private static final String PROMETHEUS_QUERY_TIMEOUT = "30000ms";   // Prometheus 쪽 쿼리 실행 제한 (브라우저가 늘릴 수 없음)
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    // ---- 무거운 7일 기준선 조회(increase(...[7d])) 캐시
    // 값이 천천히 변하므로 백그라운드에서 길게 계산해 캐시하고, 화면 요청은 캐시를 즉시 돌려준다.
    private static final String HEAVY_QUERY_MARKER = "[7d] offset 1h";
    private static final String HEAVY_QUERY_TIMEOUT = "110000ms";      // Prometheus 서버 설정(--query.timeout)보다 길면 서버 제한이 적용됨
    private static final int HEAVY_READ_TIMEOUT_MS = 115000;
    private static final long HEAVY_FRESH_MS = 10L * 60 * 1000;         // 이 시간 안이면 캐시를 그대로 사용
    private static final long HEAVY_STALE_MAX_MS = 60L * 60 * 1000;     // 갱신 실패 시 이전 값을 최대 이 시간까지 사용
    private static final long HEAVY_WAIT_MS = 35000;                    // 첫 계산을 기다리는 시간 (HTML 요청 제한 45초보다 짧게)
    private static final long HEAVY_RETRY_BACKOFF_MS = 120000;          // 계산 실패 후 재시도 간격 (Prometheus 부하 방지)
    private static final int HEAVY_CACHE_MAX_ENTRIES = 50;

    private static final class HeavyEntry {
        final String body;
        final long fetchedAt;
        HeavyEntry(String body, long fetchedAt) { this.body = body; this.fetchedAt = fetchedAt; }
    }

    private final Map<String, HeavyEntry> heavyCache = new ConcurrentHashMap<String, HeavyEntry>();
    private final Map<String, Future<String>> heavyInflight = new ConcurrentHashMap<String, Future<String>>();
    private final Map<String, Long> heavyFailedAt = new ConcurrentHashMap<String, Long>();
    private final ExecutorService heavyExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "prometheus-baseline");
            t.setDaemon(true);
            return t;
        }
    });

    private final ServiceEndpoint serviceEndpoint;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String prometheusBaseUrl;

    @Autowired
    public FlightPriceSnapshotCbcImpl(ServiceEndpoint serviceEndpoint,
                                      WebClient webClient,
                                      ObjectMapper objectMapper,
                                      // 설정 파일 없이 동작하도록 기본값을 지정. 다른 주소가 필요하면 설정에
                                      // monitoring.prometheus.base-url 을 추가해서 덮어쓸 수 있다. (설정 값이 우선)
                                      @Value("${monitoring.prometheus.base-url:" + DEFAULT_PROMETHEUS_BASE_URL + "}") String prometheusBaseUrl) {
        this.serviceEndpoint = serviceEndpoint;
        // 공통 WebClient 설정(필터·커넥터)은 유지하고 응답 버퍼 제한만 넓힌 복사본을 사용한다.
        this.webClient = webClient.mutate()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(SNAPSHOT_MAX_IN_MEMORY_BYTES))
                .build();
        this.objectMapper = objectMapper;
        this.prometheusBaseUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(prometheusBaseUrl), "/");
        try {
            URI uri = URI.create(this.prometheusBaseUrl);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Invalid Prometheus base URL");
            }
        } catch (IllegalArgumentException e) {
            // 설정에 포함되었을 수 있는 인증정보를 예외/로그에 노출하지 않는다.
            throw new IllegalArgumentException("monitoring.prometheus.base-url must be an HTTP(S) URL without credentials, query or fragment");
        }
    }

    // ================================================================ 스냅샷

    @Override
    public String getSnapshots(FlightPriceSnapshotReqVo vo) {
        validateSnapshotDates(vo);
        // 날짜는 위에서 YYYY-MM-DD 형식·실제 날짜로 검증했으므로 URL 에 그대로 붙여도 안전하다.
        final String path = SNAPSHOT_PATH + "?startDate=" + vo.getDateStart() + "&endDate=" + vo.getDateEnd();
        final String response = callGetApi(path);

        // 응답 본문(대용량 가능)은 로그에 남기지 않고 크기만 기록
        log.info("[FLIGHT-PRICE-SNAPSHOT] GET response length={}", response == null ? 0 : response.length());
        if (StringUtils.isBlank(response)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "항공API 응답이 비어 있습니다.");
        }
        return response;
    }

    private void validateSnapshotDates(FlightPriceSnapshotReqVo vo) {
        if (vo == null || vo.getDateStart() == null || vo.getDateEnd() == null
                || !vo.getDateStart().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                || !vo.getDateEnd().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date_start/date_end는 YYYY-MM-DD 날짜여야 합니다.");
        }
        try {
            if (LocalDate.parse(vo.getDateStart()).isAfter(LocalDate.parse(vo.getDateEnd()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시작일은 종료일보다 늦을 수 없습니다.");
            }
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "실제 존재하는 조회 날짜를 입력하세요.");
        }
    }

    /**
     * 항공API GET 호출 (DynamicPricingCbcImpl.callGetApi 와 동일: 공통 WebClient + 엔드포인트 + GUID 헤더)
     */
    private String callGetApi(String path) {
        final String uri = serviceEndpoint.findEndpoint(ServiceEndpoint.ENDPOINT_API) + path;
        log.info("[FLIGHT-PRICE-SNAPSHOT] GET {}", uri);

        try {
            return webClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON_UTF8)
                    .header(ServiceContextConstants.HTTP_REQUEST_GUID, currentGuid())
                    .retrieve()
                    .bodyToMono(String.class)
                    .contextWrite(RestServiceClient.createContextWithGuid())
                    .block(SNAPSHOT_TIMEOUT);
        } catch (WebClientResponseException e) {
            // 항공API 가 오류 상태를 반환. 본문은 브라우저에 전달하지 않고 로그에만 남긴다.
            log.error("[FLIGHT-PRICE-SNAPSHOT] 항공API 오류 응답 status={} uri={} body={}", e.getRawStatusCode(), uri,
                    StringUtils.abbreviate(e.getResponseBodyAsString(), 500));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "항공API 조회에 실패했습니다.");
        } catch (RuntimeException e) {
            // Reactor block(Duration) 시간 초과는 IllegalStateException("Timeout on blocking read ...") 로 전달된다.
            if (e instanceof IllegalStateException && String.valueOf(e.getMessage()).startsWith("Timeout on blocking read")) {
                log.error("[FLIGHT-PRICE-SNAPSHOT] 시간 초과 uri={}", uri, e);
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "항공API 응답 제한 시간을 초과했습니다.");
            }
            log.error("[FLIGHT-PRICE-SNAPSHOT] GET 호출 실패 uri={}", uri, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "항공API 에 연결할 수 없습니다.");
        }
    }

    private String currentGuid() {
        final String guid = MDC.get(ServiceContextConstants.HTTP_REQUEST_GUID);
        return guid == null ? UUID.randomUUID().toString() : guid;
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
        if (query.contains(HEAVY_QUERY_MARKER)) {
            return heavyQuery(query);
        }
        params.put("timeout", PROMETHEUS_QUERY_TIMEOUT);
        return prometheusGet("query", params, READ_TIMEOUT_MS);
    }

    /** 범위 조회: selector / selector+up 만 허용, 범위 24시간·250포인트 이하 */
    @Override
    public String prometheusQueryRange(String query, String start, String end, String step) {
        requireHtmlQuery(query, true);

        final long startSec = epochSeconds(start, "start");
        final long endSec = epochSeconds(end, "end");
        if (StringUtils.isBlank(step) || !STEP_SECONDS.matcher(step).matches()
                || Long.parseLong(step) < 60 || Long.parseLong(step) % 60 != 0) {
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
        return prometheusGet("query_range", params, READ_TIMEOUT_MS);
    }

    /** 알림 규칙 조회: HTML 은 type=alert 만 사용 */
    @Override
    public String prometheusRules(String type) {
        if (StringUtils.isNotBlank(type) && !"alert".equals(type)) {
            throw badRequest();
        }
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("type", "alert");
        return prometheusGet("rules", params, READ_TIMEOUT_MS);
    }

    /** Prometheus 조회 호출(query/query_range POST, rules GET). 응답을 필터링/집계하지 않는다. read timeout은 읽기 대기 기준이다. */
    private String heavyQuery(final String query) {
        final long now = System.currentTimeMillis();
        final HeavyEntry cached = heavyCache.get(query);
        if (cached != null && now - cached.fetchedAt < HEAVY_FRESH_MS) {
            return cached.body;
        }
        final Future<String> running = startHeavyRefresh(query, now);
        if (cached != null && now - cached.fetchedAt < HEAVY_STALE_MAX_MS) {
            return cached.body;     // 갱신은 백그라운드에서 진행, 이전 값을 사용
        }
        if (running == null) {
            // 최근 계산 실패 직후: 부하 방지를 위해 재시도를 미룬다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "7일 기준선 계산에 실패했습니다. 잠시 후 다시 시도됩니다.");
        }
        try {
            return running.get(HEAVY_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("[PROMETHEUS-PROXY] 7일 기준선 계산 중 (백그라운드 진행)");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "7일 기준선을 계산 중입니다. 잠시 후 자동으로 다시 조회됩니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "조회가 중단되었습니다.");
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 조회에 실패했습니다.");
        }
    }

    /** 같은 쿼리의 계산은 하나만 실행(single-flight). 최근 실패 직후면 null. */
    private synchronized Future<String> startHeavyRefresh(final String query, long now) {
        final Future<String> existing = heavyInflight.get(query);
        if (existing != null) {
            return existing;
        }
        final Long failedAt = heavyFailedAt.get(query);
        if (failedAt != null && now - failedAt < HEAVY_RETRY_BACKOFF_MS) {
            return null;
        }
        if (heavyCache.size() >= HEAVY_CACHE_MAX_ENTRIES && !heavyCache.containsKey(query)) {
            heavyCache.clear();
        }
        final FutureTask<String> task = new FutureTask<String>(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                try {
                    Map<String, String> params = new LinkedHashMap<String, String>();
                    params.put("query", query);
                    params.put("timeout", HEAVY_QUERY_TIMEOUT);
                    final String body = prometheusGet("query", params, HEAVY_READ_TIMEOUT_MS);
                    heavyCache.put(query, new HeavyEntry(body, System.currentTimeMillis()));
                    heavyFailedAt.remove(query);
                    return body;
                } catch (RuntimeException e) {
                    heavyFailedAt.put(query, System.currentTimeMillis());
                    throw e;
                } finally {
                    heavyInflight.remove(query);
                }
            }
        });
        heavyInflight.put(query, task);
        heavyExecutor.execute(task);
        return task;
    }

    /** 컨테이너 종료 시 백그라운드 스레드 정리 */
    @Override
    public void destroy() {
        heavyExecutor.shutdownNow();
    }

    private String prometheusGet(String operation, Map<String, String> params, int readTimeoutMs) {
        if (StringUtils.isBlank(prometheusBaseUrl)) {
            log.error("[PROMETHEUS-PROXY] monitoring.prometheus.base-url 설정이 없습니다.");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 연결 설정이 없습니다.");
        }

        final StringBuilder form = new StringBuilder();
        final StringBuilder url = new StringBuilder(prometheusBaseUrl).append("/api/v1/").append(operation);
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                form.append('&');
            }
            first = false;
            form.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        final boolean post = !"rules".equals(operation);
        if (!post) url.append('?').append(form);
        log.info("[PROMETHEUS-PROXY] {} params={}", operation, StringUtils.abbreviate(String.valueOf(params), 300));

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setRequestMethod(post ? "POST" : "GET");
            conn.setDoOutput(post);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json");   // 인바운드 쿠키/인증 헤더는 전달하지 않는다
            conn.setRequestProperty("Cache-Control", "no-store");

            if (post) try (java.io.OutputStream out = conn.getOutputStream()) {
                out.write(form.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            final int status = conn.getResponseCode();
            final InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            final String body = in == null ? "" : readLimited(in);
            JsonNode document;
            try {
                document = objectMapper.readTree(body);
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus가 JSON이 아닌 응답을 반환했습니다.");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                // JSON Prometheus 오류는 원래 HTTP 상태/errorType/error/warnings를 보존한다.
                // HTML 로그인/리다이렉트/임의 오류 본문은 브라우저에 전달하지 않는다.
                // 원인 추적용: Prometheus 오류 JSON(errorType/error)만 짧게 남긴다. (쿼리 결과 데이터는 포함되지 않음)
                log.warn("[PROMETHEUS-PROXY] upstream error operation={}, status={}, errorType={}, error={}", operation, status,
                        document == null ? "" : StringUtils.abbreviate(document.path("errorType").asText(""), 60),
                        document == null ? "" : StringUtils.abbreviate(document.path("error").asText(""), 300));
                HttpStatus upstreamStatus = HttpStatus.resolve(status);
                if (status >= 400 && status <= 599 && upstreamStatus != null && document != null
                        && document.isObject() && "error".equals(document.path("status").asText())) {
                    throw new PrometheusUpstreamException(upstreamStatus, body);
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 조회에 실패했습니다.");
            }
            if (document == null || !document.isObject()
                    || !"success".equals(document.path("status").asText())
                    || !document.path("data").isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prometheus 응답 형식이 올바르지 않습니다.");
            }
            // 재직렬화하지 않음: 라벨, NaN 문자열, 빈 배열, warnings, 숫자 정밀도를 그대로 유지.
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
        if (StringUtils.isBlank(query) || query.length() > 16384 || !isHtmlQuery(query, rangeOnly)) {
            throw badRequest();
        }
    }

    private boolean isHtmlQuery(String query, boolean rangeOnly) {
        if (UP.equals(query)) {
            return true;
        }
        final String env = labelValue(ENV_IN_QUERY, query, false);
        final String app = labelValue(APP_IN_QUERY, query, false);
        final String siteRegex = labelValue(SITE_IN_QUERY, query, true);
        final String site = siteRegex != null ? siteRegex : labelValue(Pattern.compile("site_code=\"([^\"]*)\""), query, false);
        if ("".equals(env) || "".equals(app) || "".equals(site)) {      // 값이 있는데 허용 문자가 아님
            return false;
        }

        for (int scope = 0; scope < METRIC_PREFIXES.length; scope++) {
            final boolean naver = scope == 1;
            final String select = selector(METRIC_PREFIXES[scope], null, env, app, naver ? null : site);
            final String combined = naver ? select : select + " or " + gdsQuery(env, app, site, rangeOnly) + (rangeOnly ? "" : " or " + supplementalQuery(env, app, site));
            if (query.equals(combined) || query.equals(combined + " or " + UP)
                    || splitUnion(combined + " or " + UP).contains(query)) {
                return true;
            }
            if (!rangeOnly && allowedFreshnessShard(query, freshness(METRIC_PREFIXES[scope], env, app, naver ? null : site))) {
                return true;
            }
        }
        return false;
    }

    /** Frontend splitUnion: only the outer union, with strings/escapes preserved. */
    private java.util.List<String> splitUnion(String expression) {
        java.util.List<String> parts = new java.util.ArrayList<String>();
        int start = 0, depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (quoted) {
                if (!escaped && ch == '"') quoted = false;
                if (!escaped && ch == '\\') escaped = true; else escaped = false;
                continue;
            }
            if (ch == '"') { quoted = true; continue; }
            if (ch == '(' || ch == '{' || ch == '[') depth++;
            else if (ch == ')' || ch == '}' || ch == ']') depth--;
            if (depth == 0 && expression.startsWith(" or ", i)) {
                parts.add(expression.substring(start, i)); i += 3; start = i + 1;
            }
        }
        parts.add(expression.substring(start)); return parts;
    }

    private boolean allowedFreshnessShard(String query, String full) {
        if (query.equals(full)) return true;
        java.util.List<String> parts = splitUnion(full);
        for (int i = 0; i < parts.size(); i += 4) {
            if (query.equals(String.join(" or ", parts.subList(i, Math.min(i + 4, parts.size()))))) return true;
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
        if (METRIC_PREFIXES[0].equals(prefix)) {
            sb.append(",stage!=\"GDS\"");
        }
        return sb.append('}').toString();
    }

    private String gdsCounterSelector(String env, String app, String site, String result) {
        final StringBuilder sb = new StringBuilder("air_gds_operation_events_total{result=~").append(quote(result));
        if (env != null) sb.append(",environment=").append(quote(env));
        if (app != null) sb.append(",application=").append(quote(app));
        if (site != null) sb.append(",site_code=").append(quote(site));
        return sb.append('}').toString();
    }

    private String gdsAlias(String expression, String name) {
        return "label_replace(" + expression + ",\"dashboard_gds_record\"," + quote(name) + ",\"\",\"\")";
    }

    /** HTML gdsQuery(): 고정된 집계식만 허용하며 임의 PromQL·라벨·시간창은 허용하지 않는다. */
    private String gdsQuery(String env, String app, String site, boolean rangeOnly) {
        final String counter = gdsCounterSelector(env, app, site, "SUCCESS|FAILURE");
        final String group = "sum by (" + GDS_DIMENSIONS + ",result) (";
        final StringBuilder sb = new StringBuilder("(").append(gdsAlias(group + "rate(" + counter + "[10m]))", GDS_EVENT_PREFIX + "rate10m")).append(')');
        sb.append(" or (").append(gdsAlias(group + "rate(" + counter + "[30m]))", GDS_EVENT_PREFIX + "rate30m")).append(')');
        // 7일 누적 기준선은 즉시 조회만 한다. 그래프의 매 시점에 7일 범위를 재계산하지 않는다.
        if (!rangeOnly) sb.append(" or (").append(gdsAlias(group + "increase(" + counter + "[7d] offset 1h))", GDS_EVENT_PREFIX + "increase7d")).append(')');
        for (String kind : new String[]{"attempts", "success"}) {
            final String source = gdsCounterSelector(env, app, site, "success".equals(kind) ? "SUCCESS" : "SUCCESS|FAILURE");
            final StringBuilder weeks = new StringBuilder();
            for (int week = 1; week <= 2; week++) {
                if (week > 1) weeks.append(" or ");
                weeks.append("label_replace(sum by (").append(GDS_DIMENSIONS).append(") (rate(").append(source)
                        .append("[30m] offset ").append(week).append("w)),\"dashboard_week_ago\",").append(quote(String.valueOf(week))).append(",\"\",\"\")");
            }
            sb.append(" or (").append(gdsAlias("min without (dashboard_week_ago) (" + weeks + ")", "air:dashboard_gds_" + kind + ":rate30m:wow_min")).append(')');
        }
        return sb.toString();
    }

    /** Instant-only diagnostics; never included in final outcome aggregation. */
    private String supplementalQuery(String env, String app, String site) {
        String tail = "";
        if (env != null) tail += ",environment=" + quote(env);
        if (app != null) tail += ",application=" + quote(app);
        if (site != null) tail += ",site_code=" + quote(site);
        return "(label_replace(sum by (" + GDS_DIMENSIONS + ",exception_type,error_code,gds_error_code,airline) (rate(air_gds_operation_events_total{result=\"FAILURE\"" + tail
                + "}[10m])),\"dashboard_detail\",\"gds_failures\",\"\",\"\")) or (label_replace(sum by (environment,application,site_code,stage) (rate(air_api_stage_events_total{result=\"REQUEST\",stage=~\"AUTO_TICKET|BSP_APPROVAL|PACKAGE_RESERVATION_CANCEL\"" + tail
                + "}[10m])),\"dashboard_detail\",\"api_requests\",\"\",\"\"))";
    }

    /** HTML freshnessQuery(): 레코딩 22개 + up + 전체 범위의 원본 GDS 표본 나이. */
    private String freshness(String prefix, String env, String app, String site) {
        final StringBuilder sb = new StringBuilder();
        for (String suffix : METRIC_SUFFIXES) {
            final String name = prefix + suffix;
            sb.append("(label_replace(time()-timestamp(").append(selector(prefix, name, env, app, site))
                    .append("),\"dashboard_metric\",").append(quote(name)).append(",\"\",\"\")) or ");
        }
        sb.append("(label_replace(time()-timestamp(").append(UP)
                .append("),\"dashboard_metric\",\"up\",\"\",\"\"))");
        if (METRIC_PREFIXES[0].equals(prefix)) {
            sb.append(" or (label_replace(max by (").append(GDS_DIMENSIONS).append(",result) (time()-timestamp(")
                    .append(gdsCounterSelector(env, app, site, "SUCCESS|FAILURE"))
                    .append(")),\"dashboard_metric\",").append(quote(GDS_EVENT_PREFIX + "rate10m")).append(",\"\",\"\"))");
        }
        return sb.toString();
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
