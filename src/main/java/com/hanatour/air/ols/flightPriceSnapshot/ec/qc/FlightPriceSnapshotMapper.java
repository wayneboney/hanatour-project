package com.hanatour.air.ols.flightPriceSnapshot.ec.qc;

import com.hanatour.air.cmn.common.config.datasource.AirMapper;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotVo;

import java.util.List;

/**
 * 항공권 가격 스냅샷
 *
 * <pre>
 * 예약코드별 항공권 가격 스냅샷 조회 (조회 전용)
 * </pre>
 *
 * @version 1.0
 * @since 2026.11.04 신규작성
 */
@AirMapper
public interface FlightPriceSnapshotMapper {

    /**
     * 스냅샷 목록 조회 (예약코드 필수, 수집일자 기간 선택, 최신순, maxRows 로 건수 제한)
     */
    List<FlightPriceSnapshotVo> findFlightPriceSnapshotList(FlightPriceSnapshotReqVo vo);
}
