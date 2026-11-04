package com.hanatour.air.ols.pricesnapshot.vo;

import lombok.Getter;

/**
 * 공통 응답 포맷 (code / message / data)
 * - 성공: code="0000"
 * - 실패: code 는 에러 구분용 코드, message 는 사용자 노출용 메시지 (내부 오류 상세는 절대 담지 않는다)
 */
@Getter
public class ApiResponse<T> {

	public static final String CODE_OK = "0000";
	public static final String CODE_INVALID_PARAM = "4000";
	public static final String CODE_SERVER_ERROR = "5000";

	private final String code;
	private final String message;
	private final T data;

	private ApiResponse(String code, String message, T data) {
		this.code = code;
		this.message = message;
		this.data = data;
	}

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<T>(CODE_OK, "OK", data);
	}

	public static <T> ApiResponse<T> fail(String code, String message) {
		return new ApiResponse<T>(code, message, null);
	}
}
