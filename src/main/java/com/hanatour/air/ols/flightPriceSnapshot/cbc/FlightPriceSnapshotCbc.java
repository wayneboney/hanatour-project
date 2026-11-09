package com.hanatour.air.ols.flightPriceSnapshot.cbc;

import com.hanatour.air.ols.flightPriceSnapshot.cbc.vo.FlightPriceSnapshotReqVo;

public interface FlightPriceSnapshotCbc {

    /** 스냅샷 조회 → 항공API 응답(JSON 문자열)을 그대로 반환 */
    public String getSnapshots(FlightPriceSnapshotReqVo vo);

    /** Prometheus 즉시 조회 → Prometheus 응답(JSON 문자열)을 그대로 반환 */
    public String prometheusQuery(String query, String time);

    /** Prometheus 범위 조회 → Prometheus 응답(JSON 문자열)을 그대로 반환 */
    public String prometheusQueryRange(String query, String start, String end, String step);

    /** Prometheus 알림 규칙 조회(type: alert 만 허용, 생략 가능) → Prometheus 응답(JSON 문자열)을 그대로 반환 */
    public String prometheusRules(String type);

}
