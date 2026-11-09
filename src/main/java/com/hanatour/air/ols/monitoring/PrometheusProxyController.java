package com.hanatour.air.ols.monitoring;

import com.hanatour.air.ols.monitoring.cbc.PrometheusProxyCbc;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prometheus 읽기 전용 중계 Controller
 * UI(flightPricingDashboard.html) -> portal /air/** -> ols -> Prometheus
 *
 * UI 에서는 Prometheus(내부망)에 직접 접근할 수 없어 ols 가 대신 조회한다.
 * HTML 설정: prometheus.proxyPath = '/air/monitoring/prometheus'
 *
 * <pre>
 * 허용 경로 (Prometheus HTTP API 표준 형태 — TODO: 실제 HTML/TRAFFIC_MONITORING.md 의 호출 형태와 대조 필요)
 *   GET /air/monitoring/prometheus/api/v1/query        ?query=&time=
 *   GET /air/monitoring/prometheus/api/v1/query_range  ?query=&start=&end=&step=
 * 그 외 경로(/api/v1/admin, /-/reload 등)는 매핑하지 않으므로 전달되지 않는다. (임의 경로 중계 금지)
 * </pre>
 *
 * [TODO - 운영 반영 전 필수] 인증/인가: 인프라 지표를 내려주는 API 이므로 포털 SSO/권한 검증 필요
 */
@RestController
@RequestMapping("/air/monitoring/prometheus")
@RequiredArgsConstructor
public class PrometheusProxyController {

    private static final String JSON_UTF8 = MediaType.APPLICATION_JSON_UTF8_VALUE;

    private final PrometheusProxyCbc prometheusProxyCbc;

    /** 즉시 조회 (instant query) */
    @GetMapping(value = "/api/v1/query", produces = JSON_UTF8)
    public String query(@RequestParam("query") String query,
                        @RequestParam(value = "time", required = false) String time) {
        return prometheusProxyCbc.query(query, time);
    }

    /** 범위 조회 (range query) */
    @GetMapping(value = "/api/v1/query_range", produces = JSON_UTF8)
    public String queryRange(@RequestParam("query") String query,
                             @RequestParam("start") String start,
                             @RequestParam("end") String end,
                             @RequestParam("step") String step) {
        return prometheusProxyCbc.queryRange(query, start, end, step);
    }
}
