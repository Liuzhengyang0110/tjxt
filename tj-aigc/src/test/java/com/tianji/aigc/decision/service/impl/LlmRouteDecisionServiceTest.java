package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.agent.RouteAgent;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.enums.AgentTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRouteDecisionServiceTest {
    private final RouteAgent routeAgent = mock(RouteAgent.class);
    private final LlmRouteDecisionService service = new LlmRouteDecisionService(routeAgent);

    @Test
    void normalizesWhitespaceAndCaseWithoutInventingConfidence() {
        when(routeAgent.process("问题", "会话")).thenReturn("  consult  ");

        RouteDecision decision = service.decide("问题", "会话");

        assertEquals(AgentTypeEnum.CONSULT, decision.agentType());
        assertNull(decision.confidence());
        assertEquals(RouteDecisionSource.LLM_PRIMARY, decision.source());
        assertEquals(RouteFallbackReason.NONE, decision.fallbackReason());
    }

    @Test
    void rejectsInvalidOrNonFinalRouteResult() {
        for (String raw : new String[]{"ROUTE", "CONSULT，因为用户问价格", "UNKNOWN", ""}) {
            when(routeAgent.process("问题", "会话")).thenReturn(raw);
            assertThrows(IllegalStateException.class, () -> service.decide("问题", "会话"));
        }
        when(routeAgent.process("问题", "会话")).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.decide("问题", "会话"));
    }
}
