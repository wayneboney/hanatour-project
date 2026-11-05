package com.hanatour.air.ols.flightPriceSnapshot.cbc.impl;

import com.hanatour.air.cmn.webclient.RestServiceClient;
import com.hanatour.air.cmn.webclient.ServiceEndpoint;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import hntframe.run.oltp.ext.service.context.ServiceContextConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.UUID;

/**
 * 항공권 가격 스냅샷 조회 중계 Cbc
 * ols -> 항공API (DB 조회는 항공API 에서 수행. ols 에는 price(MariaDB) 데이터소스 설정이 없음)
 *
 * <pre>
 * dynamicPricing 의 callGetApi 방식과 동일
 *
 * 핵심 동작
 * 1) 항공API 경로는 서버에 고정한다. (UI 에서 URL 을 받아 호출하면 내부망 임의 호출 통로가 되므로 금지)
 * 2) 조회 조건은 URI 변수로 바인딩한다. (값이 자동 인코딩되어 쿼리스트링 주입 방지)
 * 3) 항공API 응답(JSON 문자열)을 가공 없이 그대로 Controller 로 돌려준다.
 *    응답 형식은 프론트 계약 {business_date, as_of, is_complete, total, rows} 을 항공API 가 책임진다.
 * </pre>
 */
@Slf4j
@Component
@hntframe.run.common.annotation.ServiceComponent
@RequiredArgsConstructor
public class FlightPriceSnapshotCbcImpl implements FlightPriceSnapshotCbc {

    // 항공API 경로 (TODO: 항공API 개발자와 최종 경로 확인 필요)
    private static final String SNAPSHOT_PATH = "/flightPriceSnapshot/air/api/service/snapshots/v1.00";

    // 프론트 요청 제한(30초)보다 짧게. 기간 전체 조회라 dynamicPricing(10초)보다 길게 설정
    private static final Duration GET_TIMEOUT = Duration.ofSeconds(25);

    // 영업일 기준은 한국시간 고정 (UI 의 timezone 값은 사용하지 않음)
    private static final String TIME_ZONE = "Asia/Seoul";

    private final ServiceEndpoint serviceEndpoint;
    private final WebClient webClient;

    @Override
    public String getSnapshots(FlightPriceSnapshotReqVo vo) {
        final String uri = serviceEndpoint.findEndpoint(ServiceEndpoint.ENDPOINT_API) + SNAPSHOT_PATH
                + "?date_start={dateStart}&date_end={dateEnd}&timezone={timeZone}";
        log.info("[FLIGHT-PRICE-SNAPSHOT] GET {} dateStart={}, dateEnd={}", SNAPSHOT_PATH, vo.getDateStart(), vo.getDateEnd());

        final String response = webClient.get()
                .uri(uri, vo.getDateStart(), vo.getDateEnd(), TIME_ZONE)
                .accept(MediaType.APPLICATION_JSON_UTF8)
                .header(ServiceContextConstants.HTTP_REQUEST_GUID, currentGuid())
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(throwable -> log.error("[FLIGHT-PRICE-SNAPSHOT] GET 호출 실패 path={}", SNAPSHOT_PATH, throwable))
                .contextWrite(RestServiceClient.createContextWithGuid())
                .block(GET_TIMEOUT);

        if (response == null) {
            throw new IllegalStateException("항공API 응답이 비어 있습니다: " + SNAPSHOT_PATH);
        }

        // 응답 본문(대용량 가능)은 로그에 남기지 않고 크기만 기록
        log.info("[FLIGHT-PRICE-SNAPSHOT] GET response length={}", response.length());
        return response;
    }

    private String currentGuid() {
        final String guid = MDC.get(ServiceContextConstants.HTTP_REQUEST_GUID);

        return guid == null ? UUID.randomUUID().toString() : guid;
    }
}
