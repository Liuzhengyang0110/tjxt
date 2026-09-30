package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.decision.client.JevDecisionClient;
import com.tianji.aigc.decision.client.dto.JevChoiceAnswer;
import com.tianji.aigc.decision.client.dto.JevSystemOneResponse;
import com.tianji.aigc.decision.context.RoutingContextBuilder;
import com.tianji.aigc.decision.context.RoutingState;
import com.tianji.aigc.decision.exception.JevClientException;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.enums.AgentTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JevRouteDecisionServiceTest {
    private final RoutingContextBuilder contextBuilder = mock(RoutingContextBuilder.class);
    private final JevDecisionClient client = mock(JevDecisionClient.class);
    private final JevRouteDecisionService service = new JevRouteDecisionService(contextBuilder, client);
    private final RoutingState state = new RoutingState("问题", List.of(), Map.of());

    @Test
    void convertsAllFourChoicesToProjectDecision() {
        for (AgentTypeEnum agent : List.of(AgentTypeEnum.RECOMMEND, AgentTypeEnum.CONSULT,
                AgentTypeEnum.BUY, AgentTypeEnum.KNOWLEDGE)) {
            when(client.evaluate(state)).thenReturn(response(answer(agent.name(), 0.9, Map.of(agent.name(), 1.0))));

            RouteDecision decision = service.decide(state);

            assertEquals(agent, decision.agentType());
            assertEquals(0.9, decision.confidence());
            assertEquals(Map.of(agent.name(), 1.0), decision.probabilities());
            assertEquals(RouteDecisionSource.JEV, decision.source());
            assertEquals(RouteFallbackReason.NONE, decision.fallbackReason());
            assertEquals("jev-1.13.0", decision.model());
        }
    }

    @Test
    void buildsStateOnlyOnStringEntryPoint() {
        when(contextBuilder.build("问题", "会话")).thenReturn(state);
        when(client.evaluate(state)).thenReturn(response(answer("CONSULT", 0.9, Map.of("CONSULT", 1.0))));

        assertEquals(AgentTypeEnum.CONSULT, service.decide("问题", "会话").agentType());

        verify(contextBuilder).build("问题", "会话");
        verify(client).evaluate(state);
    }

    @Test
    void rejectsMissingOrMalformedResponseFields() {
        assertInvalid(null);
        assertInvalid(new JevSystemOneResponse(null, Map.of(), null));
        assertInvalid(new JevSystemOneResponse(" ", Map.of(), null));
        assertInvalid(new JevSystemOneResponse("jev-1.13.0", null, null));
        assertInvalid(new JevSystemOneResponse("jev-1.13.0", Map.of(), null));
        assertInvalid(new JevSystemOneResponse("jev-1.13.0", Map.of("route_agent",
                new JevChoiceAnswer("text", "CONSULT", 0.9, Map.of("CONSULT", 1.0))), null));
        assertInvalid(response(answer("ROUTE", 0.9, Map.of("CONSULT", 1.0))));
        assertInvalid(response(answer("UNKNOWN", 0.9, Map.of("CONSULT", 1.0))));
        assertInvalid(response(answer(null, 0.9, Map.of("CONSULT", 1.0))));
    }

    @Test
    void rejectsInvalidConfidence() {
        for (Double confidence : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
            assertInvalid(response(answer("CONSULT", confidence, Map.of("CONSULT", 1.0))));
        }
    }

    @Test
    void rejectsInvalidProbabilityDistribution() {
        assertInvalid(response(answer("CONSULT", 0.9, null)));
        assertInvalid(response(answer("CONSULT", 0.9, Map.of())));
        assertInvalid(response(answer("CONSULT", 0.9, Map.of("ROUTE", 1.0))));
        assertInvalid(response(answer("CONSULT", 0.9, Map.of("UNKNOWN", 1.0))));
        assertInvalid(response(answer("CONSULT", 0.9, Map.of("CONSULT", Double.NaN))));
        assertInvalid(response(answer("CONSULT", 0.9, Map.of("CONSULT", -0.1))));
        assertInvalid(response(answer("CONSULT", 0.9, Map.of("CONSULT", 1.1))));
        assertInvalid(response(answer("CONSULT", 0.9, Map.of("CONSULT", 0.7))));
    }

    private void assertInvalid(JevSystemOneResponse response) {
        when(client.evaluate(state)).thenReturn(response);
        JevClientException exception = assertThrows(JevClientException.class, () -> service.decide(state));
        assertEquals(RouteFallbackReason.INVALID_RESPONSE, exception.getReason());
    }

    private JevChoiceAnswer answer(String choice, Double confidence, Map<String, Double> probabilities) {
        return new JevChoiceAnswer("choice", choice, confidence, probabilities);
    }

    private JevSystemOneResponse response(JevChoiceAnswer answer) {
        return new JevSystemOneResponse("jev-1.13.0", Map.of("route_agent", answer), null);
    }
}
