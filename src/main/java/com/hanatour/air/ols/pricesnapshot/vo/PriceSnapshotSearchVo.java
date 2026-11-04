package com.hanatour.air.ols.pricesnapshot.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 조회 조건 (Mapper 파라미터)
 * - fromDt 포함(>=), toDt 미포함(<) 의 반열림 구간 → 인덱스(reservation_code, captured_at) 활용
 */
@Getter
@Setter
public class PriceSnapshotSearchVo {
	private String reservationCode;   // 필수
	private String fromDt;            // yyyy-MM-dd HH:mm:ss (nullable)
	private String toDt;              // yyyy-MM-dd HH:mm:ss (nullable)
	private int limit;                // 최대 조회 건수
}
