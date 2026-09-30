package com.tianji.aigc.decision.exception;

import com.tianji.aigc.decision.model.RouteFallbackReason;
import lombok.Getter;

@Getter
public class JevClientException extends RuntimeException {
    private final RouteFallbackReason reason;

    public JevClientException(RouteFallbackReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public JevClientException(RouteFallbackReason reason, String message) {
        this(reason, message, null);
    }
}
