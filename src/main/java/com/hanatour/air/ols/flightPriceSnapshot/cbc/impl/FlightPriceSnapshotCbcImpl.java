package com.hanatour.air.ols.flightPriceSnapshot.cbc.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.FlightPriceSnapshotCbc;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotCompetitorVo;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;
import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotVo;
import com.hanatour.air.ols.flightPriceSnapshot.ec.qc.FlightPriceSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 항공권 가격 스냅샷 조회 Cbc
 *
 * <pre>
 * dynamicPricing 과 동일하게 Controller 에 JSON 문자열을 돌려준다.
 * 차이점: 항공API 호출이 아니라 Mapper(MyBatis)로 내부 DB 를 직접 조회한다. (단순 조회라 Bc 생략)
 *
 * 핵심 동작
 * 1) 입력값 검증 + 정규화 (예약코드/스냅샷ID 형식, 날짜 형식·순서, 조회 건수 상한/기본값)
 * 2) Mapper 호출 (DB 예외는 삼키지 않고 전파)
 * 3) 결과를 JSON 문자열로 직렬화
 * </pre>
 */
@Slf4j
@Component
@hntframe.run.common.annotation.ServiceComponent
@RequiredArgsConstructor
public class FlightPriceSnapshotCbcImpl implements FlightPriceSnapshotCbc {

    private static final Pattern RESERVATION_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,50}$");
    private static final Pattern SNAPSHOT_ID_PATTERN = Pattern.compile("^[0-9]{1,19}$");
    // STRICT: 20261301, 20260230 같은 존재하지 않는 날짜 거부 (uuuu 필수)
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private static final int DEFAULT_MAX_ROWS = 50;
    private static final int MAX_ROWS_LIMIT = 200;

    private final FlightPriceSnapshotMapper flightPriceSnapshotMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getSnapshotList(FlightPriceSnapshotReqVo vo) {
        validateListCondition(vo);

        List<FlightPriceSnapshotVo> list = flightPriceSnapshotMapper.findFlightPriceSnapshotList(vo);
        if (list == null) {
            list = new ArrayList<FlightPriceSnapshotVo>();
        }
        log.info("[FLIGHT-PRICE-SNAPSHOT] 목록 조회 reservationCode={}, startDt={}, endDt={}, maxRows={}, result={}",
                vo.getReservationCode(), vo.getStartDt(), vo.getEndDt(), vo.getMaxRows(), list.size());

        // TOTAL_COUNT 는 maxRows 로 자르기 전 전체 건수 (모든 행에 동일한 값)
        int totalCount = list.isEmpty() ? 0 : list.get(0).getTotalCount();

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("totalCount", totalCount);
        body.put("list", list);
        return toJson(body);
    }

    @Override
    public String getSnapshotCompetitors(FlightPriceSnapshotReqVo vo) {
        validateCompetitorCondition(vo);

        List<FlightPriceSnapshotCompetitorVo> list = flightPriceSnapshotMapper.findFlightPriceSnapshotCompetitorList(vo);
        if (list == null) {
            list = new ArrayList<FlightPriceSnapshotCompetitorVo>();
        }
        log.info("[FLIGHT-PRICE-SNAPSHOT] 경쟁운임 조회 reservationCode={}, snapshotId={}, result={}",
                vo.getReservationCode(), vo.getSnapshotId(), list.size());

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("list", list);
        return toJson(body);
    }

    // ---------------------------------------------------------------- 검증 (정규화한 값을 vo 에 다시 설정)

    private void validateListCondition(FlightPriceSnapshotReqVo vo) {
        if (vo == null) {
            throw new IllegalArgumentException("조회 조건을 입력하세요.");
        }
        vo.setReservationCode(validateReservationCode(vo.getReservationCode()));

        String startDt = validateDate(vo.getStartDt(), "수집시작일자");
        String endDt = validateDate(vo.getEndDt(), "수집종료일자");
        if (startDt != null && endDt != null && startDt.compareTo(endDt) > 0) {
            throw new IllegalArgumentException("수집시작일자가 수집종료일자보다 클 수 없습니다.");
        }
        vo.setStartDt(startDt);
        vo.setEndDt(endDt);
        vo.setMaxRows(resolveMaxRows(vo.getMaxRows()));
    }

    private void validateCompetitorCondition(FlightPriceSnapshotReqVo vo) {
        if (vo == null) {
            throw new IllegalArgumentException("조회 조건을 입력하세요.");
        }
        vo.setReservationCode(validateReservationCode(vo.getReservationCode()));

        String snapshotId = StringUtils.trimToNull(vo.getSnapshotId());
        if (snapshotId == null || !SNAPSHOT_ID_PATTERN.matcher(snapshotId).matches()) {
            throw new IllegalArgumentException("스냅샷ID 형식이 올바르지 않습니다.");
        }
        vo.setSnapshotId(snapshotId);
    }

    private String validateReservationCode(String reservationCode) {
        String value = StringUtils.trimToNull(reservationCode);
        if (value == null || !RESERVATION_CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("예약코드 형식이 올바르지 않습니다. (영문/숫자/-/_ 최대 50자)");
        }
        return value;
    }

    /** 빈 값이면 null(조건 없음), 값이 있으면 YYYYMMDD 실제 존재하는 날짜인지 검증 */
    private String validateDate(String date, String label) {
        String value = StringUtils.trimToNull(date);
        if (value == null) {
            return null;
        }
        try {
            LocalDate.parse(value, DATE_FMT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " 형식이 올바르지 않습니다. (YYYYMMDD)");
        }
        return value;
    }

    /** 미입력(null)은 기본값, 1~상한 범위만 허용 */
    private Integer resolveMaxRows(Integer maxRows) {
        if (maxRows == null) {
            return Integer.valueOf(DEFAULT_MAX_ROWS);
        }
        if (maxRows.intValue() < 1 || maxRows.intValue() > MAX_ROWS_LIMIT) {
            throw new IllegalArgumentException("조회 건수는 1~" + MAX_ROWS_LIMIT + " 범위여야 합니다.");
        }
        return maxRows;
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답 JSON 생성 실패", e);
        }
    }
}
