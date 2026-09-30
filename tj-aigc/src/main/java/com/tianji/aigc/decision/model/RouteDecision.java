package com.tianji.aigc.decision.model;

import com.tianji.aigc.enums.AgentTypeEnum;

import java.util.Map;

public record RouteDecision(
        AgentTypeEnum agentType,
        Double confidence,
        Map<String, Double> probabilities,
        RouteDecisionSource source,
        RouteFallbackReason fallbackReason,
        String model
) {
    public RouteDecision {
        probabilities = probabilities == null
                ? Map.of()
                : Map.copyOf(probabilities);
        fallbackReason = fallbackReason == null
                ? RouteFallbackReason.NONE
                : fallbackReason;
    }
}
