package com.tianji.aigc.decision.client.dto;

import java.util.Map;

public record JevChoiceAnswer(
        String type,
        String choice,
        Double confidence,
        Map<String, Double> probabilities
) {
}
