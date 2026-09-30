package com.tianji.aigc.decision.client.dto;

import java.util.Map;

public record JevSystemOneResponse(
        String model,
        Map<String, JevChoiceAnswer> answers,
        JevUsage usage
) {
}
