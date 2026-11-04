package com.hanatour.air.ols.flightPriceSnapshot.bc;

import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotCompetitorVo;
import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotSearchVo;
import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotVo;

import java.util.List;

/**
 * 항공권 가격 스냅샷
 *
 * <pre>
 * 예약코드별 항공권 가격 스냅샷 조회 (조회 전용)
 * 입력값 검증과 기본값 적용은 이 계층에서 수행한다. (어떤 경로로 호출돼도 동일하게 적용)
 * </pre>
 *
 * @version 1.0
 * @since 2026.11.04 신규작성
 */
public interface FlightPriceSnapshotBc {

    /**
     * 스냅샷 목록 조회 (수집시각 최신순)
     *
     * @param vo reservationCode(필수), startDt/endDt(YYYYMMDD, 선택), maxRows(0이면 기본값)
     * @throws IllegalArgumentException 입력값 오류
     */
    public List<FlightPriceSnapshotVo> findFlightPriceSnapshotList(FlightPriceSnapshotSearchVo vo);

    /**
     * 스냅샷 1건의 경쟁 운임 조회 (최저가순)
     *
     * @param vo reservationCode(필수), snapshotId(필수)
     * @throws IllegalArgumentException 입력값 오류
     */
    public List<FlightPriceSnapshotCompetitorVo> findFlightPriceSnapshotCompetitorList(FlightPriceSnapshotSearchVo vo);
}
