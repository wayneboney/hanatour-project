package com.hanatour.air.ols.flightPriceSnapshot.cbc.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotVo;
import com.hanatour.air.ols.flightPriceSnapshot.ec.qc.FlightPriceSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 항공권 가격 스냅샷 조회 Cbc
 *
 * <pre>
 * dynamicPricing 과 동일하게 Controller 에 JSON 문자열을 돌려준다.
 * 차이점: 항공API 호출이 아니라 Mapper(MyBatis)로 내부 DB 를 직접 조회한다. (단순 조회라 Bc 생략)
 *
 * 핵심 동작
 * 1) Mapper 호출 (DB 예외는 삼키지 않고 그대로 전파)
 * 2) 결과를 JSON 문자열로 직렬화
 * </pre>
 */
@Slf4j
@Component
@hntframe.run.common.annotation.ServiceComponent
@RequiredArgsConstructor
public class FlightPriceSnapshotCbcImpl implements FlightPriceSnapshotCbc {

    private final FlightPriceSnapshotMapper flightPriceSnapshotMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getSnapshotList(FlightPriceSnapshotReqVo vo) {
        List<FlightPriceSnapshotVo> list = flightPriceSnapshotMapper.findFlightPriceSnapshotList(vo);
        log.info("[FLIGHT-PRICE-SNAPSHOT] 목록 조회 reservationCode={}, result={}", vo.getReservationCode(), list.size());

        // TOTAL_COUNT 는 maxRows 로 자르기 전 전체 건수 (모든 행에 동일한 값)
        int totalCount = list.isEmpty() ? 0 : list.get(0).getTotalCount();

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("totalCount", totalCount);
        body.put("list", list);

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 JSON 생성 실패", e);
        }
    }
}
