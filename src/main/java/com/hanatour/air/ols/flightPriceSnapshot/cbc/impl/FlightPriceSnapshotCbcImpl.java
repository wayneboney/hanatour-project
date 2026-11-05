package com.hanatour.air.ols.flightPriceSnapshot.cbc.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanatour.air.cmn.common.ApiException;
import com.hanatour.air.cmn.webclient.RestServiceClient;
import com.hanatour.air.cmn.webclient.ServiceEndpoint;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import com.hanatour.common.cmn.restclient.vo.Header;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 항공권 가격 스냅샷 조회 중계 Cbc
 * ols -> 항공API (DB 조회는 항공API 에서 수행. ols 에는 price(MariaDB) 데이터소스 설정이 없음)
 *
 * <pre>
 * dynamicPricing 의 callApiService(MCI 방식, POST) 와 동일
 *
 * 핵심 동작
 * 1) 항공API 경로는 서버에 고정한다. (UI 에서 URL 을 받아 호출하면 내부망 임의 호출 통로가 되므로 금지)
 * 2) 조회 조건을 요청 JSON 본문으로 만들어 MCI 로 POST 호출한다. (ObjectMapper 로 직렬화되어 값 이스케이프 보장)
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

    // 영업일 기준은 한국시간 고정 (UI 의 timezone 값은 사용하지 않음)
    private static final String TIME_ZONE = "Asia/Seoul";

    private final RestServiceClient restServiceClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getSnapshots(FlightPriceSnapshotReqVo vo) {
        final String response = callApiService(SNAPSHOT_PATH, toRequestJson(vo));

        // 응답 본문(대용량 가능)은 로그에 남기지 않고 크기만 기록
        log.info("[FLIGHT-PRICE-SNAPSHOT] MCI response length={}", response == null ? 0 : response.length());
        if (response == null) {
            throw new IllegalStateException("항공API 응답이 비어 있습니다: " + SNAPSHOT_PATH);
        }
        return response;
    }

    /**
     * 요청 JSON 생성 (TODO: 항공API 개발자와 요청 키 이름 확정 필요)
     * { "dateStart" : "2026-11-04", "dateEnd" : "2026-11-04", "timeZone" : "Asia/Seoul" }
     */
    private String toRequestJson(FlightPriceSnapshotReqVo vo) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("dateStart", vo.getDateStart());
        body.put("dateEnd", vo.getDateEnd());
        body.put("timeZone", TIME_ZONE);

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("요청 JSON 생성 실패", e);
        }
    }

    /**
     * 항공API MCI 방식 호출 (DynamicPricingCbcImpl.callApiService 와 동일)
     */
    private String callApiService(String url, String requestBody) {
        log.info("[FLIGHT-PRICE-SNAPSHOT] MCI {} body={}", url, requestBody);

        try {
            final Header mciHeader = new Header();
            mciHeader.setJobSysCd("P_AIR_A0SLE"); //업무시스템코드
            mciHeader.setPupsInttCd("BP_AIR"); //목적기관코드
            mciHeader.setFltxTycd("4280"); //전문유형코드
            mciHeader.setTrncDvcd("550"); // 처리구분코드
            mciHeader.setRspnTrnrMdcd("R"); // 응답전달방식코드
            mciHeader.setSvcDmnInfo(RestServiceClient.HANATOUR_AIR_API); //서비스도메인정보
            mciHeader.setSvcTrncId(url); //서비스거래ID

            return restServiceClient.sendWithMciHeaderToString(ServiceEndpoint.ENDPOINT_API, url, mciHeader, requestBody);
        } catch (ApiException e) {
            log.error("[FLIGHT-PRICE-SNAPSHOT] MCI 호출 실패 url={}", url, e);
            throw new IllegalStateException("항공API 호출 실패: " + url, e);
        }
    }
}
