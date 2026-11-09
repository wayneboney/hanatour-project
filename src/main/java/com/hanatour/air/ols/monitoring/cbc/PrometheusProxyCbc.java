package com.hanatour.air.ols.monitoring.cbc;

public interface PrometheusProxyCbc {

    /** Prometheus 즉시 조회 → Prometheus 응답(JSON 문자열)을 그대로 반환 */
    public String query(String query, String time);

    /** Prometheus 범위 조회 → Prometheus 응답(JSON 문자열)을 그대로 반환 */
    public String queryRange(String query, String start, String end, String step);

}
