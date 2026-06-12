package com.example.gw.gateway;

import java.time.Instant;

public class RequestContext {

    public static final String ATTR_KEY = RequestContext.class.getName();

    private final String traceId;
    private final Instant requestedAt;

    public RequestContext(String traceId, Instant requestedAt) {
        this.traceId = traceId;
        this.requestedAt = requestedAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }
}
