package com.hanatour.air.ols.flightPriceSnapshot.ec.qc;

import com.hanatour.air.cmn.common.config.datasource.PriceMapper;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotVo;

import java.util.List;

/**
 * 항공권 가격 스냅샷
 *
 * <pre>
 * 항공권 가격 스냅샷 조회 (조회 전용, price 데이터소스 = MariaDB)
 * </pre>
 *
 * @version 1.0
 * @since 2026.11.04 신규작성
 */
@PriceMapper
public interface FlightPriceSnapshotMapper {

    /**
     * 스냅샷 목록 조회 (수집일자 captured_at 기준 KST 영업일 기간, 양끝 포함, 최신순, 건수 제한 없음)
     */
    List<FlightPriceSnapshotVo> findFlightPriceSnapshotList(FlightPriceSnapshotReqVo vo);
}
