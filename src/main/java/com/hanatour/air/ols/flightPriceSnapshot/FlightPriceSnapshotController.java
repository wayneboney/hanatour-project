package com.hanatour.air.ols.flightPriceSnapshot;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 항공권 가격 스냅샷 조회 Controller
 * UI(flightPricingDashboard.html) -> portal /air/** -> ols -> 항공API (DB 조회는 항공API 에서 수행)
 *
 * GET /air/flightPrice/snapshots?date_start=2026-11-04&date_end=2026-11-04&timezone=Asia%2FSeoul
 *
 * 프론트(flightPricingDashboard.html)의 endpoint 설정(/air/flightPrice/snapshots)에 맞춘 경로.
 *
 * [TODO - 운영 반영 전 필수] 인증/인가 및 사용자 권한별 조회 범위 제한
 *   내부 데이터(예약/탑승객 정보 포함)를 내려주는 API 이므로, 포털 인증(SSO) 적용 여부와 권한 검증이 반드시 필요하다.
 */
@RestController
@RequestMapping("/air/flightPrice")
@RequiredArgsConstructor
public class FlightPriceSnapshotController {

    private static final String JSON_UTF8 = MediaType.APPLICATION_JSON_UTF8_VALUE;

    private final FlightPriceSnapshotCbc flightPriceSnapshotCbc;

    /**
     * 스냅샷 조회 (수집일자 기준 KST 영업일, 양끝 포함)
     * - timezone 파라미터는 프론트가 항상 Asia/Seoul 로 보내며, 서버는 KST 기준으로만 동작하므로 사용하지 않는다.
     */
    @GetMapping(value = "/snapshots", produces = JSON_UTF8)
    public String getSnapshots(@RequestParam("date_start") String dateStart,
                               @RequestParam("date_end") String dateEnd,
                               @RequestParam(value = "timezone", required = false) String timezone) {
        FlightPriceSnapshotReqVo vo = new FlightPriceSnapshotReqVo();
        vo.setDateStart(dateStart);
        vo.setDateEnd(dateEnd);

        return flightPriceSnapshotCbc.getSnapshots(vo);
    }
}
