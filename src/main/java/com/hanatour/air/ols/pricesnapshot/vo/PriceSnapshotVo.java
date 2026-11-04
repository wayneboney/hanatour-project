package com.hanatour.air.ols.pricesnapshot.vo;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * FLIGHT_PRICE_SNAPSHOT 조회 응답 VO
 * - 날짜/시각은 SQL 단계에서 문자열(yyyy-MM-dd / yyyy-MM-dd HH:mm:ss)로 변환해 타입/타임존 이슈를 피한다.
 */
@Getter
@Setter
public class PriceSnapshotVo {
	private Long snapshotId;
	private String reservationCode;
	private String tripType;
	private boolean directOnly;
	private String depDate;
	private String depAirport;
	private String depAirlineCode;
	private String depFlightNo;
	private String returnDate;
	private String returnAirport;
	private String returnAirlineCode;
	private String returnFlightNo;
	private String promoCardCode;
	private String seatClass;
	private String passengerType;
	private BigDecimal baseFare;
	private BigDecimal tax;
	private BigDecimal qCharge;
	private Integer currentRank;
	private String capturedAt;
	private List<CompetitorFareVo> competitors = new ArrayList<CompetitorFareVo>();
}
