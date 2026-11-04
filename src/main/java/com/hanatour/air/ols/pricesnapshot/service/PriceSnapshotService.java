package com.hanatour.air.ols.pricesnapshot.service;

import com.hanatour.air.ols.pricesnapshot.vo.PriceSnapshotVo;

import java.util.List;

public interface PriceSnapshotService {

	/**
	 * 예약코드별 가격 스냅샷 조회 (captured_at 최신순)
	 *
	 * @param reservationCode 예약 코드 (필수)
	 * @param fromDate        수집일 시작 yyyy-MM-dd (포함, nullable)
	 * @param toDate          수집일 종료 yyyy-MM-dd (포함, nullable)
	 * @param limit           최대 건수 (nullable → 기본값 적용)
	 * @throws IllegalArgumentException 입력값 오류
	 */
	List<PriceSnapshotVo> getSnapshots(String reservationCode, String fromDate, String toDate, Integer limit);
}
