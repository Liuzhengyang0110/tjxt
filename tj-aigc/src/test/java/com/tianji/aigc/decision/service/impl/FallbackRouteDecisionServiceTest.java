package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.decision.config.JevProperties;
import com.tianji.aigc.decision.config.RouteProperties;
import com.tianji.aigc.decision.context.RoutingContextBuilder;
import com.tianji.aigc.decision.context.RoutingState;
import com.tianji.aigc.decision.exception.JevClientException;
import com.tianji.aigc.decision.metrics.RouteMetrics;
import com.tianji.aigc.decision.metrics.RouteMetrics.Mode;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.decision.model.RouteProvider;
import com.tianji.aigc.enums.AgentTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.core.task.TaskRejectedException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FallbackRouteDecisionServiceTest {
    private final JevRouteDecisionService jevService = mock(JevRouteDecisionService.class);
    private final LlmRouteDecisionService llmService = mock(LlmRouteDecisionService.class);
    private final RoutingContextBuilder contextBuilder = mock(RoutingContextBuilder.class);
    private final ShadowRouteComparisonService shadowService = mock(ShadowRouteComparisonService.class);
    private final RouteMetrics routeMetrics = mock(RouteMetrics.class);
    private final RouteProperties routeProperties = new RouteProperties();
    private final JevProperties jevProperties = new JevProperties();
    private final RoutingState state = new RoutingState("问题", List.of(), Map.of());
    private final FallbackRouteDecisionService service = new FallbackRouteDecisionService(
            jevService, llmService, contextBuilder, routeProperties, jevProperties,
            shadowService, routeMetrics);

    @BeforeEach
    void setUp() {
        routeProperties.setProvider(RouteProvider.JEV);
        jevProperties.setEnabled(true);
        when(contextBuilder.build("问题", "会话")).thenReturn(state);
    }

    @Test
    void llmProviderDoesNotCallJevOrBuildJevState() {
        routeProperties.setProvider(RouteProvider.LLM);
        RouteDecision llm = llmDecision();
        when(llmService.decide("问题", "会话")).thenReturn(llm);

        assertEquals(llm, service.decide("问题", "会话"));

        verify(llmService).decide("问题", "会话");
        verifyNoInteractions(jevService);
        verify(contextBuilder, never()).build("问题", "会话");
    }

    @Test
    void shadowBuildsStateBeforeLlmAndSubmitsComparisonWithoutChangingActualDecision() {
        routeProperties.setShadowEnabled(true);
        RouteDecision llm = llmDecision();
        when(llmService.decide("问题", "会话")).thenReturn(llm);

        assertEquals(llm, service.decide("问题", "会话"));

        verifyNoInteractions(jevService);
        InOrder order = inOrder(contextBuilder, llmService, shadowService);
        order.verify(contextBuilder).build("问题", "会话");
        order.verify(llmService).decide("问题", "会话");
        order.verify(shadowService).compareAsync(anyString(), anyString(), eq(state), eq(llm));
        org.mockito.ArgumentCaptor<String> sessionHash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(shadowService).compareAsync(anyString(), sessionHash.capture(), eq(state), eq(llm));
        assertEquals(16, sessionHash.getValue().length());
        assertNotEquals("会话", sessionHash.getValue());
        verify(routeMetrics).recordDecision(eq(llm), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectedShadowSubmissionIsCountedAndDoesNotChangeLlmResult() {
        routeProperties.setShadowEnabled(true);
        RouteDecision llm = llmDecision();
        when(llmService.decide("问题", "会话")).thenReturn(llm);
        doThrow(new TaskRejectedException("queue full"))
                .when(shadowService).compareAsync(anyString(), anyString(), eq(state), eq(llm));
        assertEquals(llm, service.decide("问题", "会话"));

        verify(routeMetrics).recordShadowRejected();
        verifyNoInteractions(jevService);
    }

    @Test
    void shadowContextFailureDoesNotPreventLlmRoute() {
        routeProperties.setShadowEnabled(true);
        RouteDecision llm = llmDecision();
        when(contextBuilder.build("问题", "会话")).thenThrow(new IllegalStateException("memory unavailable"));
        when(llmService.decide("问题", "会话")).thenReturn(llm);

        assertEquals(llm, service.decide("问题", "会话"));

        verifyNoInteractions(shadowService, jevService);
    }

    @Test
    void shadowDispatchFailureDoesNotChangeLlmResult() {
        routeProperties.setShadowEnabled(true);
        RouteDecision llm = llmDecision();
        when(llmService.decide("问题", "会话")).thenReturn(llm);
        doThrow(new IllegalStateException("executor unavailable"))
                .when(shadowService).compareAsync(anyString(), anyString(), eq(state), eq(llm));

        assertEquals(llm, service.decide("问题", "会话"));

        verifyNoInteractions(jevService);
    }

    @Test
    void acceptsJevDecisionAtConfiguredThreshold() {
        routeProperties.getConfidenceThresholds().put(AgentTypeEnum.BUY, 0.95);
        RouteDecision jev = jevDecision(AgentTypeEnum.BUY, 0.95);
        when(jevService.decide(state)).thenReturn(jev);

        assertEquals(jev, service.decide("问题", "会话"));

        verify(contextBuilder).build("问题", "会话");
        verify(jevService).decide(state);
        verifyNoInteractions(llmService);
        verify(routeMetrics).recordRequest(Mode.MAIN);
        verify(routeMetrics).recordConfidence(AgentTypeEnum.BUY, 0.95);
    }

    @Test
    void lowConfidenceFallsBackOnceAndDoesNotReuseJevScore() {
        routeProperties.getConfidenceThresholds().put(AgentTypeEnum.BUY, 0.95);
        when(jevService.decide(state)).thenReturn(jevDecision(AgentTypeEnum.BUY, 0.94));
        when(llmService.decide("问题", "会话")).thenReturn(llmDecision());

        RouteDecision result = service.decide("问题", "会话");

        assertEquals(AgentTypeEnum.CONSULT, result.agentType());
        assertEquals(RouteDecisionSource.LLM_FALLBACK, result.source());
        assertEquals(RouteFallbackReason.LOW_CONFIDENCE, result.fallbackReason());
        assertNull(result.confidence());
        assertEquals(Map.of(), result.probabilities());
        verify(jevService, times(1)).decide(state);
        verify(llmService, times(1)).decide("问题", "会话");
        verify(routeMetrics).recordRequest(Mode.MAIN);
        verify(routeMetrics).recordConfidence(AgentTypeEnum.BUY, 0.94);
    }

    @Test
    void jevClientErrorFallsBackOnceWithOriginalReason() {
        when(jevService.decide(state)).thenThrow(
                new JevClientException(RouteFallbackReason.RATE_LIMITED, "Jev returned HTTP 429"));
        when(llmService.decide("问题", "会话")).thenReturn(llmDecision());

        RouteDecision result = service.decide("问题", "会话");

        assertEquals(RouteDecisionSource.LLM_FALLBACK, result.source());
        assertEquals(RouteFallbackReason.RATE_LIMITED, result.fallbackReason());
        verify(jevService, times(1)).decide(state);
        verify(llmService, times(1)).decide("问题", "会话");
        verify(routeMetrics).recordError(RouteFallbackReason.RATE_LIMITED);
    }

    @ParameterizedTest
    @EnumSource(value = RouteFallbackReason.class, names = {
            "TIMEOUT", "NETWORK_ERROR", "RATE_LIMITED", "OVERLOADED",
            "AUTH_FAILURE", "INVALID_REQUEST", "INVALID_RESPONSE",
            "SERVER_ERROR", "UNKNOWN_ERROR"
    })
    void everyJevClientErrorFallsBackExactlyOnce(RouteFallbackReason reason) {
        when(jevService.decide(state)).thenThrow(new JevClientException(reason, "Jev failed"));
        when(llmService.decide("问题", "会话")).thenReturn(llmDecision());

        RouteDecision result = service.decide("问题", "会话");

        assertEquals(RouteDecisionSource.LLM_FALLBACK, result.source());
        assertEquals(reason, result.fallbackReason());
        verify(jevService, times(1)).decide(state);
        verify(llmService, times(1)).decide("问题", "会话");
        verify(routeMetrics, times(1)).recordError(reason);
    }

    @Test
    void disabledJevFallsBackWithoutBuildingState() {
        jevProperties.setEnabled(false);
        when(llmService.decide("问题", "会话")).thenReturn(llmDecision());

        RouteDecision result = service.decide("问题", "会话");

        assertEquals(RouteFallbackReason.JEV_DISABLED, result.fallbackReason());
        assertEquals(RouteDecisionSource.LLM_FALLBACK, result.source());
        verifyNoInteractions(jevService);
        verify(contextBuilder, never()).build("问题", "会话");
        verify(llmService, times(1)).decide("问题", "会话");
    }

    @Test
    void disabledFallbackReturnsSafeDefaultWithoutCallingLlm() {
        routeProperties.setFallbackEnabled(false);
        when(jevService.decide(state)).thenThrow(
                new JevClientException(RouteFallbackReason.TIMEOUT, "Jev timed out"));

        RouteDecision result = service.decide("问题", "会话");

        assertEquals(AgentTypeEnum.KNOWLEDGE, result.agentType());
        assertEquals(RouteDecisionSource.SAFE_DEFAULT, result.source());
        assertEquals(RouteFallbackReason.TIMEOUT, result.fallbackReason());
        verifyNoInteractions(llmService);
    }

    @Test
    void bothRoutesFailUsesSafeDefault() {
        when(jevService.decide(state)).thenThrow(
                new JevClientException(RouteFallbackReason.NETWORK_ERROR, "Jev network failed"));
        when(llmService.decide("问题", "会话")).thenThrow(new IllegalStateException("LLM route failed"));

        RouteDecision result = service.decide("问题", "会话");

        assertEquals(AgentTypeEnum.KNOWLEDGE, result.agentType());
        assertEquals(RouteDecisionSource.SAFE_DEFAULT, result.source());
        assertEquals(RouteFallbackReason.LLM_ERROR, result.fallbackReason());
        verify(jevService, times(1)).decide(state);
        verify(llmService, times(1)).decide("问题", "会话");
        verify(routeMetrics).recordDecisionError(RouteFallbackReason.LLM_ERROR);
    }

    @Test
    void unsafeConfiguredDefaultNeverEntersBuy() {
        routeProperties.setFallbackEnabled(false);
        routeProperties.setSafeDefaultAgent(AgentTypeEnum.BUY);
        when(jevService.decide(state)).thenThrow(new IllegalStateException("unexpected error"));

        RouteDecision result = service.decide("问题", "会话");

        assertEquals(AgentTypeEnum.KNOWLEDGE, result.agentType());
        assertEquals(RouteFallbackReason.UNKNOWN_ERROR, result.fallbackReason());
    }

    private RouteDecision llmDecision() {
        return new RouteDecision(AgentTypeEnum.CONSULT, null, Map.of(),
                RouteDecisionSource.LLM_PRIMARY, RouteFallbackReason.NONE, null);
    }

    private RouteDecision jevDecision(AgentTypeEnum agent, double confidence) {
        return new RouteDecision(agent, confidence, Map.of(agent.name(), 1.0),
                RouteDecisionSource.JEV, RouteFallbackReason.NONE, "jev-1.13.0");
    }
}
