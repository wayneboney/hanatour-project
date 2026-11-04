package com.hanatour.air.ols.flightPriceSnapshot.cbc.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanatour.air.ols.flightPriceSnapshot.bc.FlightPriceSnapshotBc;
import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotCompetitorVo;
import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotSearchVo;
import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotVo;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
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
 * 차이점: 항공API 호출이 아니라 Bc -> MyBatis(내부 DB) 로 조회한다.
 *
 * 핵심 동작
 * 1) 요청 VO(cbc.vo) -> 조회조건 VO(bc.vo) 변환
 * 2) Bc 호출 (검증은 Bc 에서 수행, 예외는 삼키지 않고 전파)
 * 3) 결과를 JSON 문자열로 직렬화
 * </pre>
 */
@Slf4j
@Component
@hntframe.run.common.annotation.ServiceComponent
@RequiredArgsConstructor
public class FlightPriceSnapshotCbcImpl implements FlightPriceSnapshotCbc {

    private final FlightPriceSnapshotBc flightPriceSnapshotBc;
    private final ObjectMapper objectMapper;

    @Override
    public String getSnapshotList(FlightPriceSnapshotReqVo vo) {
        List<FlightPriceSnapshotVo> list = flightPriceSnapshotBc.findFlightPriceSnapshotList(toSearchVo(vo));

        // TOTAL_COUNT 는 maxRows 로 자르기 전 전체 건수 (모든 행에 동일한 값)
        int totalCount = list.isEmpty() ? 0 : list.get(0).getTotalCount();

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("totalCount", totalCount);
        body.put("list", list);
        return toJson(body);
    }

    @Override
    public String getSnapshotCompetitors(FlightPriceSnapshotReqVo vo) {
        List<FlightPriceSnapshotCompetitorVo> list = flightPriceSnapshotBc.findFlightPriceSnapshotCompetitorList(toSearchVo(vo));

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("list", list);
        return toJson(body);
    }

    /** 요청 VO -> 조회조건 VO (maxRows 미입력은 0 → Bc 에서 기본값 적용) */
    private FlightPriceSnapshotSearchVo toSearchVo(FlightPriceSnapshotReqVo vo) {
        if (vo == null) {
            throw new IllegalArgumentException("조회 조건을 입력하세요.");
        }

        FlightPriceSnapshotSearchVo search = new FlightPriceSnapshotSearchVo();
        search.setReservationCode(vo.getReservationCode());
        search.setSnapshotId(vo.getSnapshotId());
        search.setStartDt(vo.getStartDt());
        search.setEndDt(vo.getEndDt());
        search.setMaxRows(vo.getMaxRows() == null ? 0 : vo.getMaxRows().intValue());
        return search;
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 JSON 생성 실패", e);
        }
    }
}
