package com.tianji.aigc.decision.client.dto;

import java.util.Map;

public record JevSystemOneRequest(
        Object state,
        String model,
        Map<String, JevChoiceQuestion> questions
) {
}
