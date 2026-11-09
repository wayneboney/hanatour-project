package com.hanatour.air.ols.flightPriceSnapshot.cbc;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 검증된 Prometheus JSON 오류만 HTTP 상태와 함께 Controller로 전달한다. */
public final class PrometheusUpstreamException extends ResponseStatusException {
    private final String responseBody;

    public PrometheusUpstreamException(HttpStatus status, String responseBody) {
        super(status, "Prometheus upstream error");
        this.responseBody = responseBody;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
