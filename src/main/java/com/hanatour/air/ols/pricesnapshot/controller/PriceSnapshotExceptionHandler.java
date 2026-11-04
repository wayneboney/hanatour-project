package com.hanatour.air.ols.pricesnapshot.controller;

import com.hanatour.air.ols.pricesnapshot.vo.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 가격 스냅샷 API 전용 예외 처리
 * - assignableTypes 로 범위를 제한해 기존 프로젝트의 공통 예외 처리에 영향을 주지 않는다.
 * - 클라이언트에는 코드/안전한 메시지만 내려주고, 스택트레이스/SQL 등 내부 정보는 로그에만 남긴다.
 */
@RestControllerAdvice(assignableTypes = PriceSnapshotController.class)
public class PriceSnapshotExceptionHandler {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	/** 입력값 오류 (Service 검증 실패) → 400 */
	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
		return ApiResponse.fail(ApiResponse.CODE_INVALID_PARAM, e.getMessage());
	}

	/** 필수 파라미터 누락 / 타입 불일치 → 400 */
	@ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiResponse<Void> handleParam(Exception e) {
		return ApiResponse.fail(ApiResponse.CODE_INVALID_PARAM, "요청 파라미터가 올바르지 않습니다.");
	}

	/** 그 외 모든 예외 (DB 오류 등) → 500, 상세는 로그에만 */
	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ApiResponse<Void> handleServerError(Exception e) {
		logger.error("price snapshot 조회 중 서버 오류", e);
		return ApiResponse.fail(ApiResponse.CODE_SERVER_ERROR, "조회 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
	}
}
