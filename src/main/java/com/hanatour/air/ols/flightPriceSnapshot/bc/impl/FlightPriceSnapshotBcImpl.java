package com.hanatour.air.ols.flightPriceSnapshot.bc.impl;

import com.hanatour.air.ols.flightPriceSnapshot.bc.FlightPriceSnapshotBc;
import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotCompetitorVo;
import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotSearchVo;
import com.hanatour.air.ols.flightPriceSnapshot.bc.vo.FlightPriceSnapshotVo;
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
import java.util.List;
import java.util.regex.Pattern;

/**
 * 항공권 가격 스냅샷
 *
 * <pre>
 * 핵심 동작
 * 1) 입력값 검증 (예약코드/스냅샷ID 형식, 날짜 형식·순서, 조회 건수 상한)
 * 2) 검증된 값만 복사한 새 조건 VO 로 Mapper 호출 (호출자의 VO 는 변경하지 않음)
 * 3) DB 예외는 잡지 않고 상위(Controller)로 전파한다. (삼키면 장애를 알 수 없음)
 * </pre>
 *
 * @version 1.0
 * @since 2026.11.04 신규작성
 */
@Slf4j
@Component
@hntframe.run.common.annotation.ServiceComponent
@RequiredArgsConstructor
public class FlightPriceSnapshotBcImpl implements FlightPriceSnapshotBc {

    private static final Pattern RESERVATION_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,50}$");
    private static final Pattern SNAPSHOT_ID_PATTERN = Pattern.compile("^[0-9]{1,19}$");
    // STRICT: 20261301, 20260230 같은 존재하지 않는 날짜 거부 (uuuu 필수)
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private static final int DEFAULT_MAX_ROWS = 50;
    private static final int MAX_ROWS_LIMIT = 200;

    private final FlightPriceSnapshotMapper flightPriceSnapshotMapper;

    @Override
    public List<FlightPriceSnapshotVo> findFlightPriceSnapshotList(FlightPriceSnapshotSearchVo vo) {
        if (vo == null) {
            throw new IllegalArgumentException("조회 조건을 입력하세요.");
        }

        FlightPriceSnapshotSearchVo cond = new FlightPriceSnapshotSearchVo();
        cond.setReservationCode(validateReservationCode(vo.getReservationCode()));

        String startDt = validateDate(vo.getStartDt(), "수집시작일자");
        String endDt = validateDate(vo.getEndDt(), "수집종료일자");
        if (startDt != null && endDt != null && startDt.compareTo(endDt) > 0) {
            throw new IllegalArgumentException("수집시작일자가 수집종료일자보다 클 수 없습니다.");
        }
        cond.setStartDt(startDt);
        cond.setEndDt(endDt);
        cond.setMaxRows(resolveMaxRows(vo.getMaxRows()));

        List<FlightPriceSnapshotVo> list = flightPriceSnapshotMapper.findFlightPriceSnapshotList(cond);
        if (list == null) {
            list = new ArrayList<FlightPriceSnapshotVo>();
        }

        log.info("[FLIGHT-PRICE-SNAPSHOT] 목록 조회 reservationCode={}, startDt={}, endDt={}, maxRows={}, result={}",
                cond.getReservationCode(), startDt, endDt, cond.getMaxRows(), list.size());
        return list;
    }

    @Override
    public List<FlightPriceSnapshotCompetitorVo> findFlightPriceSnapshotCompetitorList(FlightPriceSnapshotSearchVo vo) {
        if (vo == null) {
            throw new IllegalArgumentException("조회 조건을 입력하세요.");
        }

        FlightPriceSnapshotSearchVo cond = new FlightPriceSnapshotSearchVo();
        cond.setReservationCode(validateReservationCode(vo.getReservationCode()));

        String snapshotId = StringUtils.trimToNull(vo.getSnapshotId());
        if (snapshotId == null || !SNAPSHOT_ID_PATTERN.matcher(snapshotId).matches()) {
            throw new IllegalArgumentException("스냅샷ID 형식이 올바르지 않습니다.");
        }
        cond.setSnapshotId(snapshotId);

        List<FlightPriceSnapshotCompetitorVo> list = flightPriceSnapshotMapper.findFlightPriceSnapshotCompetitorList(cond);
        if (list == null) {
            list = new ArrayList<FlightPriceSnapshotCompetitorVo>();
        }

        log.info("[FLIGHT-PRICE-SNAPSHOT] 경쟁운임 조회 reservationCode={}, snapshotId={}, result={}",
                cond.getReservationCode(), snapshotId, list.size());
        return list;
    }

    // ---------------------------------------------------------------- 검증

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

    /** 0 이하 미입력(0)은 기본값, 음수 또는 상한 초과는 오류 */
    private int resolveMaxRows(int maxRows) {
        if (maxRows == 0) {
            return DEFAULT_MAX_ROWS;
        }
        if (maxRows < 0 || maxRows > MAX_ROWS_LIMIT) {
            throw new IllegalArgumentException("조회 건수는 1~" + MAX_ROWS_LIMIT + " 범위여야 합니다.");
        }
        return maxRows;
    }
}
