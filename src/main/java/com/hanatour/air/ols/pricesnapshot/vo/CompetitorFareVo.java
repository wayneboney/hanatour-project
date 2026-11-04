package com.hanatour.air.ols.pricesnapshot.vo;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 경쟁 운임 1건 (테이블의 fareN / fareN_agency_code / fareN_agency_name 3컬럼 묶음)
 * - 20개 컬럼 세트를 리스트로 풀어서 내려주어 화면/클라이언트 코드를 단순화한다.
 */
@Getter
@Setter
public class CompetitorFareVo {
	private int rank;               // 경쟁 운임 순번 (1~20)
	private BigDecimal fare;        // 경쟁 운임
	private String agencyCode;      // 여행사 코드
	private String agencyName;      // 경쟁사 명
}
