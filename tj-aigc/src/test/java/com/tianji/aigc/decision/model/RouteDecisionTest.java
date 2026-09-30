package com.tianji.aigc.decision.model;

import com.tianji.aigc.enums.AgentTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteDecisionTest {

    @Test
    void defaultsOptionalFieldsForLlmDecision() {
        RouteDecision decision = new RouteDecision(
                AgentTypeEnum.CONSULT,
                null,
                null,
                RouteDecisionSource.LLM_PRIMARY,
                null,
                null
        );

        assertEquals(AgentTypeEnum.CONSULT, decision.agentType());
        assertNull(decision.confidence());
        assertEquals(Map.of(), decision.probabilities());
        assertEquals(RouteDecisionSource.LLM_PRIMARY, decision.source());
        assertEquals(RouteFallbackReason.NONE, decision.fallbackReason());
        assertNull(decision.model());
    }

    @Test
    void defensivelyCopiesJevProbabilities() {
        Map<String, Double> probabilities = new HashMap<>();
        probabilities.put("CONSULT", 0.93);
        RouteDecision decision = new RouteDecision(
                AgentTypeEnum.CONSULT,
                0.93,
                probabilities,
                RouteDecisionSource.JEV,
                RouteFallbackReason.NONE,
                "jev-1.13.0"
        );

        probabilities.put("CONSULT", 0.1);

        assertEquals(0.93, decision.probabilities().get("CONSULT"));
        assertThrows(UnsupportedOperationException.class,
                () -> decision.probabilities().put("BUY", 0.1));
    }
}
