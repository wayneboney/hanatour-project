package com.hanatour.air.ols.pricesnapshot.controller;

import com.hanatour.air.ols.pricesnapshot.service.PriceSnapshotService;
import com.hanatour.air.ols.pricesnapshot.vo.ApiResponse;
import com.hanatour.air.ols.pricesnapshot.vo.PriceSnapshotVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 항공권 가격 스냅샷 조회 API (조회 전용 / GET only)
 *
 * GET /api/price-snapshots?reservationCode=ABC123&fromDate=2026-11-01&toDate=2026-11-04&limit=50
 *
 * [TODO - 운영 반영 전 필수]
 *  - 인증/인가: 사내 인증(세션/토큰) 인터셉터 또는 Spring Security 로 /api/price-snapshots/** 보호
 *  - 접근 제한: 사내망 IP 대역만 허용 (게이트웨이/WAF 또는 필터)
 */
@RestController
@RequestMapping("/api/price-snapshots")
@RequiredArgsConstructor
public class PriceSnapshotController {

	private final PriceSnapshotService priceSnapshotService;

	@GetMapping
	public ApiResponse<List<PriceSnapshotVo>> list(
			@RequestParam("reservationCode") String reservationCode,
			@RequestParam(value = "fromDate", required = false) String fromDate,
			@RequestParam(value = "toDate", required = false) String toDate,
			@RequestParam(value = "limit", required = false) Integer limit) {
		return ApiResponse.ok(priceSnapshotService.getSnapshots(reservationCode, fromDate, toDate, limit));
	}
}
