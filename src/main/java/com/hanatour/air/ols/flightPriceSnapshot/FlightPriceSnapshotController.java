package com.hanatour.air.ols.flightPriceSnapshot;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 *    GET /air/monitoring/prometheus/api/v1/rules        ?type=alert   (알림 규칙 조회, alert 만 허용)
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

    private static final String JSON_UTF8 = MediaType.APPLICATION_JSON_UTF8_VALUE;
    private static final String PROMETHEUS_PATH = "/air/monitoring/prometheus";

    private final FlightPriceSnapshotCbc flightPriceSnapshotCbc;

    /**
     * 스냅샷 조회 (수집일자 기준 KST 영업일, 양끝 포함)
     * - timezone 파라미터는 프론트가 항상 Asia/Seoul 로 보내며, 서버는 KST 기준으로만 동작하므로 사용하지 않는다.
     */
    @GetMapping(value = "/air/flightPrice/snapshots", produces = JSON_UTF8)
    public String getSnapshots(@RequestParam("date_start") String dateStart,
                               @RequestParam("date_end") String dateEnd,
                               @RequestParam(value = "timezone", required = false) String timezone) {
        FlightPriceSnapshotReqVo vo = new FlightPriceSnapshotReqVo();
        vo.setDateStart(dateStart);
        vo.setDateEnd(dateEnd);

        return flightPriceSnapshotCbc.getSnapshots(vo);
    }

    /** Prometheus 즉시 조회 (instant query) */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/query", produces = JSON_UTF8)
    public String prometheusQuery(@RequestParam("query") String query,
                                  @RequestParam(value = "time", required = false) String time) {
        return flightPriceSnapshotCbc.prometheusQuery(query, time);
    }

    /** Prometheus 범위 조회 (range query) */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/query_range", produces = JSON_UTF8)
    public String prometheusQueryRange(@RequestParam("query") String query,
                                       @RequestParam("start") String start,
                                       @RequestParam("end") String end,
                                       @RequestParam("step") String step) {
        return flightPriceSnapshotCbc.prometheusQueryRange(query, start, end, step);
    }

    /** Prometheus 알림 규칙 조회 (type: alert 만 허용, 생략 가능) */
    @GetMapping(value = PROMETHEUS_PATH + "/api/v1/rules", produces = JSON_UTF8)
    public String prometheusRules(@RequestParam(value = "type", required = false) String type) {
        return flightPriceSnapshotCbc.prometheusRules(type);
    }
}
