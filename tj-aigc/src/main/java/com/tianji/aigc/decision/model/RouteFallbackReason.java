package com.tianji.aigc.decision.model;

public enum RouteFallbackReason {
    NONE,
    JEV_DISABLED,
    LOW_CONFIDENCE,
    TIMEOUT,
    NETWORK_ERROR,
    RATE_LIMITED,
    OVERLOADED,
    AUTH_FAILURE,
    REGION_UNAVAILABLE,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    SERVER_ERROR,
    LLM_ERROR,
    UNKNOWN_ERROR
}
