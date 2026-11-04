package com.hanatour.air.ols.flightPriceSnapshot;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 항공권 가격 스냅샷 조회 Controller
 * UI(HTML) -> ols -> 내부 DB(FLIGHT_PRICE_SNAPSHOT)
 *
 * 핵심 동작
 * 1) 요청(JSON)을 FlightPriceSnapshotVo 로 받아 Cbc 에 위임한다. (Controller 는 입출력/예외 변환만 담당)
 * 2) 입력값 검증과 조회 로직은 Cbc 에서 수행하고, 검증 실패는 IllegalArgumentException 으로 던진다.
 * 3) 예외는 이 Controller 에서 JSON({"error":"..."}) 으로 변환한다.
 *
 * [TODO - 운영 반영 전 필수] 인증/인가 및 사내망 접근 제한 (내부 DB 직접 조회 API 이므로 필수)
 */
@Slf4j
@RestController
@RequestMapping("/air/flightPriceSnapshot")
@RequiredArgsConstructor
public class FlightPriceSnapshotController {

    private static final String JSON_UTF8 = MediaType.APPLICATION_JSON_UTF8_VALUE;

    private final FlightPriceSnapshotCbc flightPriceSnapshotCbc;

    /**
     * 예약코드별 가격 스냅샷 목록 조회
     * - 조회 조건이 많아질 수 있으므로 GET 쿼리스트링 대신 POST + RequestBody 사용 (dynamicPricing 과 동일)
     */
    @PostMapping(value = "/list", produces = JSON_UTF8)
    public String getSnapshotList(@RequestBody FlightPriceSnapshotVo vo) {
        return flightPriceSnapshotCbc.getSnapshotList(vo);
    }

    /**
     * 요청값 검증 실패 -> 400
     * - Cbc 의 검증 메시지만 클라이언트에 노출된다. (SQL/내부 정보가 담긴 메시지를 IllegalArgumentException 으로 던지지 말 것)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 그 외 (DB 오류 등) -> 500
     * - 상세 원인은 로그에만 남기고 클라이언트에는 고정 메시지만 내려준다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception e) {
        log.error("[FLIGHT-PRICE-SNAPSHOT] 처리 실패", e);

        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "가격 스냅샷 조회 중 오류가 발생했습니다.");
    }

    private ResponseEntity<String> errorResponse(HttpStatus status, String message) {
        // 메시지에 따옴표/개행이 섞여도 JSON 이 깨지지 않도록 이스케이프
        String safeMessage = message == null ? "" : message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON_UTF8)
                .body("{\"error\":\"" + safeMessage + "\"}");
    }
}
