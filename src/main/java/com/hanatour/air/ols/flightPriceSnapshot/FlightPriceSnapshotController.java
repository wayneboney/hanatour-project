package com.hanatour.air.ols.flightPriceSnapshot;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.PrometheusUpstreamException;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 항공권 가격 대시보드 Controller (스냅샷 조회 + Prometheus 중계)
 * UI(flightPricingDashboard.html) -> portal /air/** -> ols
 *
 * <pre>
 * 1) 스냅샷 조회: ols -> 항공API (DB 조회는 항공API 에서 수행)
 *    GET /air/flightPrice/snapshots?date_start=2026-11-04&date_end=2026-11-04&timezone=Asia%2FSeoul
 *
 * 2) Prometheus 읽기 전용 중계: ols -> Prometheus (UI 에서는 내부망 Prometheus 에 직접 접근 불가)
 *    HTML 설정 prometheus.proxyPath = '/air/monitoring/prometheus' 를 그대로 사용하기 위해 경로를 메서드마다 전체로 지정
 *    GET /air/monitoring/prometheus/api/v1/query        ?query=&time=
 *    GET /air/monitoring/prometheus/api/v1/query_range  ?query=&start=&end=&step=
 *    GET /air/monitoring/prometheus/api/v1/rules        ?type=alert   (알림 규칙 조회)
 *    (HTML 의 호출부 `proxyPath + '/api/v1/' + path` 확인 완료: path = query, query_range, rules 3종)
 *    그 외 경로(/api/v1/admin, /-/reload 등)는 매핑하지 않으므로 전달되지 않는다.
 * </pre>
 *
 * [TODO - 운영 반영 전 필수] 인증/인가 및 사용자 권한별 조회 범위 제한
 *   스냅샷(예약/탑승객 정보 포함)과 인프라 지표를 내려주는 API 이므로, 포털 인증(SSO) 적용 여부와 권한 검증이 반드시 필요하다.
 */
@RestController
@RequiredArgsConstructor
public class FlightPriceSnapshotController {

    private static final String JSON_UTF8 = "application/json;charset=UTF-8";
    private static final String PROMETHEUS_PATH = "/air/monitoring/prometheus";

    private final FlightPriceSnapshotCbc flightPriceSnapshotCbc;

    /**
     * 스냅샷 조회 (수집일자 기준 KST 영업일, 양끝 포함)
     * - timezone은 Asia/Seoul만 허용한다. 생략하면 기존 호출과 동일하게 KST로 처리한다.
     */
    @GetMapping(value = "/air/flightPrice/snapshots", produces = JSON_UTF8)
    public ResponseEntity<String> getSnapshots(@RequestParam("date_start") String dateStart,
                               @RequestParam("date_end") String dateEnd,
                               @RequestParam(value = "timezone", required = false) String timezone) {
        if (timezone != null && !"Asia/Seoul".equals(timezone)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timezone은 Asia/Seoul만 지원합니다.");
        }
        FlightPriceSnapshotReqVo vo = new FlightPriceSnapshotReqVo();
        vo.setDateStart(dateStart);
        vo.setDateEnd(dateEnd);

        return jsonResponse(HttpStatus.OK, flightPriceSnapshotCbc.getSnapshots(vo));
    }

    /** Prometheus 즉시 조회 (instant query) */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/query", produces = JSON_UTF8)
    public ResponseEntity<String> prometheusQuery(@RequestParam("query") String query,
                                  @RequestParam(value = "time", required = false) String time) {
        return jsonResponse(HttpStatus.OK, flightPriceSnapshotCbc.prometheusQuery(query, time));
    }

    /** Prometheus 범위 조회 (range query) */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/query_range", produces = JSON_UTF8)
    public ResponseEntity<String> prometheusQueryRange(@RequestParam("query") String query,
                                       @RequestParam("start") String start,
                                       @RequestParam("end") String end,
                                       @RequestParam("step") String step) {
        return jsonResponse(HttpStatus.OK, flightPriceSnapshotCbc.prometheusQueryRange(query, start, end, step));
    }

    /** Prometheus 알림 규칙 조회 (type: alert, 생략 가능). record 조회는 허용하지 않는다. */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/rules", produces = JSON_UTF8)
    public ResponseEntity<String> prometheusRules(@RequestParam(value = "type", required = false) String type) {
        return jsonResponse(HttpStatus.OK, flightPriceSnapshotCbc.prometheusRules(type));
    }

    private ResponseEntity<String> jsonResponse(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.parseMediaType(JSON_UTF8))
                .cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(PrometheusUpstreamException.class)
    public ResponseEntity<String> prometheusError(PrometheusUpstreamException error) {
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.parseMediaType(JSON_UTF8))
                .cacheControl(CacheControl.noStore()).header("X-Monitoring-Error-Source", "prometheus")
                .body(error.getResponseBody());
    }

    // Spring 5.x 용. 운영 전역 예외 처리/SSO 필터의 응답 정책과 함께 확인한다.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> requestError(ResponseStatusException error) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("status", "error");
        body.put("errorType", error.getStatus() == HttpStatus.BAD_REQUEST ? "bad_data"
                : error.getStatus() == HttpStatus.GATEWAY_TIMEOUT ? "timeout" : "proxy_error");
        body.put("error", error.getReason() == null ? "조회에 실패했습니다." : error.getReason());
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.parseMediaType(JSON_UTF8))
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
