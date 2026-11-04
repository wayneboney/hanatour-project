package com.hanatour.air.ols.flightPriceSnapshot;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 항공권 가격 스냅샷 조회 Controller
 * UI(flightPriceSnapshot.html) -> ols -> 내부 DB (MyBatis)
 *
 * [TODO - 운영 반영 전 필수] 인증/인가 및 사내망 접근 제한
 *   내부 DB 를 직접 조회하는 API 이므로, 사내 인증(세션/토큰) 또는 접근 IP 제한이 반드시 필요하다.
 */
@RestController
@RequestMapping("/air/flightPriceSnapshot")
@RequiredArgsConstructor
public class FlightPriceSnapshotController {

    private static final String JSON_UTF8 = MediaType.APPLICATION_JSON_UTF8_VALUE;

    private final FlightPriceSnapshotCbc flightPriceSnapshotCbc;

    /**
     * 스냅샷 목록 조회
     */
    @PostMapping(value = "/list", produces = JSON_UTF8)
    public String getSnapshotList(@RequestBody FlightPriceSnapshotReqVo vo) {
        return flightPriceSnapshotCbc.getSnapshotList(vo);
    }

    /**
     * 스냅샷 1건의 경쟁 운임 조회
     */
    @PostMapping(value = "/competitors", produces = JSON_UTF8)
    public String getSnapshotCompetitors(@RequestBody FlightPriceSnapshotReqVo vo) {
        return flightPriceSnapshotCbc.getSnapshotCompetitors(vo);
    }
}
