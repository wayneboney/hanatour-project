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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 항공권 가격 스냅샷 조회 Cbc
 *
 * <pre>
 * 프론트 응답 계약 (API_CONTRACT.md)
 *   { business_date, as_of, is_complete, total, rows:[...] }
 *
 * 핵심 동작
 * 1) Mapper 호출 (조회 기간 전체를 반환. DB 예외는 삼키지 않고 그대로 전파)
 * 2) total 은 rows 건수와 반드시 같아야 한다. (다르면 프론트가 부분 응답 오류로 처리)
 * 3) 결과를 JSON 문자열로 직렬화
 * </pre>
 */
@Slf4j
@Component
@hntframe.run.common.annotation.ServiceComponent
@RequiredArgsConstructor
public class FlightPriceSnapshotCbcImpl implements FlightPriceSnapshotCbc {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 예: 2026-11-04T16:20:00+09:00 (프론트는 UTC 오프셋이 포함된 ISO 시각만 허용)
    private static final DateTimeFormatter AS_OF_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final FlightPriceSnapshotMapper flightPriceSnapshotMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getSnapshots(FlightPriceSnapshotReqVo vo) {
        List<FlightPriceSnapshotVo> rows = flightPriceSnapshotMapper.findFlightPriceSnapshotList(vo);
        log.info("[FLIGHT-PRICE-SNAPSHOT] 조회 dateStart={}, dateEnd={}, result={}", vo.getDateStart(), vo.getDateEnd(), rows.size());

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        // 하루 조회일 때만 영업일자 반환 (프론트 실시간 탭이 오늘(KST)과 일치하는지 검사함)
        body.put("business_date", vo.getDateStart().equals(vo.getDateEnd()) ? vo.getDateStart() : null);
        body.put("as_of", ZonedDateTime.now(KST).format(AS_OF_FMT));
        body.put("is_complete", true);
        body.put("total", rows.size());
        body.put("rows", rows);

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 JSON 생성 실패", e);
        }
    }
}
