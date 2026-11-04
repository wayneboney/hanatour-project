package com.hanatour.air.ols.pricesnapshot.service.impl;

import com.hanatour.air.ols.pricesnapshot.mapper.PriceSnapshotMapper;
import com.hanatour.air.ols.pricesnapshot.service.PriceSnapshotService;
import com.hanatour.air.ols.pricesnapshot.vo.CompetitorFareVo;
import com.hanatour.air.ols.pricesnapshot.vo.PriceSnapshotSearchVo;
import com.hanatour.air.ols.pricesnapshot.vo.PriceSnapshotVo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 핵심 동작
 * 1) 입력값 검증 (예약코드 형식, 날짜 형식/순서, 조회 건수 상한)
 * 2) Mapper 로 조회 (파라미터 바인딩 #{} 만 사용 → SQL Injection 차단)
 * 3) fare1~fare20 반복 컬럼을 CompetitorFareVo 리스트로 변환 (fare 가 null 인 슬롯은 제외)
 */
@Service
@RequiredArgsConstructor
public class PriceSnapshotServiceImpl implements PriceSnapshotService {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private static final Pattern RESERVATION_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,50}$");
	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 200;
	private static final int COMPETITOR_COUNT = 20;

	private final PriceSnapshotMapper priceSnapshotMapper;

	@Override
	public List<PriceSnapshotVo> getSnapshots(String reservationCode, String fromDate, String toDate, Integer limit) {
		PriceSnapshotSearchVo search = buildSearch(reservationCode, fromDate, toDate, limit);

		List<Map<String, Object>> rows = priceSnapshotMapper.selectSnapshotList(search);
		List<PriceSnapshotVo> result = new ArrayList<PriceSnapshotVo>();
		if (rows == null) {
			return result;
		}
		for (Map<String, Object> row : rows) {
			result.add(toVo(row));
		}

		logger.info("price snapshot 조회 reservationCode={}, from={}, to={}, count={}",
				search.getReservationCode(), search.getFromDt(), search.getToDt(), result.size());
		return result;
	}

	// ---------------------------------------------------------------- 입력 검증

	private PriceSnapshotSearchVo buildSearch(String reservationCode, String fromDate, String toDate, Integer limit) {
		if (reservationCode == null || !RESERVATION_CODE_PATTERN.matcher(reservationCode.trim()).matches()) {
			throw new IllegalArgumentException("예약코드 형식이 올바르지 않습니다. (영문/숫자/-/_ 최대 50자)");
		}

		LocalDate from = parseDate(fromDate, "시작일");
		LocalDate to = parseDate(toDate, "종료일");
		if (from != null && to != null && from.isAfter(to)) {
			throw new IllegalArgumentException("시작일이 종료일보다 클 수 없습니다.");
		}

		int safeLimit = DEFAULT_LIMIT;
		if (limit != null) {
			if (limit.intValue() < 1 || limit.intValue() > MAX_LIMIT) {
				throw new IllegalArgumentException("조회 건수는 1~" + MAX_LIMIT + " 범위여야 합니다.");
			}
			safeLimit = limit.intValue();
		}

		PriceSnapshotSearchVo search = new PriceSnapshotSearchVo();
		search.setReservationCode(reservationCode.trim());
		// 반열림 구간: from 00:00:00 이상 ~ (to + 1일) 00:00:00 미만
		if (from != null) {
			search.setFromDt(from.format(DATE_FMT) + " 00:00:00");
		}
		if (to != null) {
			search.setToDt(to.plusDays(1).format(DATE_FMT) + " 00:00:00");
		}
		search.setLimit(safeLimit);
		return search;
	}

	private LocalDate parseDate(String value, String label) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return LocalDate.parse(value.trim(), DATE_FMT);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(label + " 형식이 올바르지 않습니다. (yyyy-MM-dd)");
		}
	}

	// ---------------------------------------------------------------- Row → VO 변환

	private PriceSnapshotVo toVo(Map<String, Object> row) {
		PriceSnapshotVo vo = new PriceSnapshotVo();
		vo.setSnapshotId(toLong(row.get("snapshotId")));
		vo.setReservationCode(toStr(row.get("reservationCode")));
		vo.setTripType(toStr(row.get("tripType")));
		vo.setDirectOnly(toBool(row.get("directOnly")));
		vo.setDepDate(toStr(row.get("depDate")));
		vo.setDepAirport(toStr(row.get("depAirport")));
		vo.setDepAirlineCode(toStr(row.get("depAirlineCode")));
		vo.setDepFlightNo(toStr(row.get("depFlightNo")));
		vo.setReturnDate(toStr(row.get("returnDate")));
		vo.setReturnAirport(toStr(row.get("returnAirport")));
		vo.setReturnAirlineCode(toStr(row.get("returnAirlineCode")));
		vo.setReturnFlightNo(toStr(row.get("returnFlightNo")));
		vo.setPromoCardCode(toStr(row.get("promoCardCode")));
		vo.setSeatClass(toStr(row.get("seatClass")));
		vo.setPassengerType(toStr(row.get("passengerType")));
		vo.setBaseFare(toDecimal(row.get("baseFare")));
		vo.setTax(toDecimal(row.get("tax")));
		vo.setQCharge(toDecimal(row.get("qCharge")));
		vo.setCurrentRank(toInt(row.get("currentRank")));
		vo.setCapturedAt(toStr(row.get("capturedAt")));

		// fare1 ~ fare20 → 리스트 (fare 가 없는 슬롯은 건너뜀)
		for (int i = 1; i <= COMPETITOR_COUNT; i++) {
			BigDecimal fare = toDecimal(row.get("fare" + i));
			if (fare == null) {
				continue;
			}
			CompetitorFareVo c = new CompetitorFareVo();
			c.setRank(i);
			c.setFare(fare);
			c.setAgencyCode(toStr(row.get("fare" + i + "AgencyCode")));
			c.setAgencyName(toStr(row.get("fare" + i + "AgencyName")));
			vo.getCompetitors().add(c);
		}
		return vo;
	}

	// JDBC 드라이버/버전에 따라 반환 타입(BigInteger, Long, Integer, Boolean 등)이 달라지므로 Number 기준으로 안전 변환
	private String toStr(Object o) {
		return o == null ? null : o.toString();
	}

	private Long toLong(Object o) {
		return o instanceof Number ? Long.valueOf(((Number) o).longValue()) : null;
	}

	private Integer toInt(Object o) {
		return o instanceof Number ? Integer.valueOf(((Number) o).intValue()) : null;
	}

	private BigDecimal toDecimal(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof BigDecimal) {
			return (BigDecimal) o;
		}
		return new BigDecimal(o.toString());
	}

	private boolean toBool(Object o) {
		if (o instanceof Boolean) {
			return ((Boolean) o).booleanValue();
		}
		return o instanceof Number && ((Number) o).intValue() != 0;
	}
}
