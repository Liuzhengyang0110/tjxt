package com.tianji.aigc.decision.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
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
import com.tianji.aigc.decision.service.RouteDecisionService;
import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class FallbackRouteDecisionService implements RouteDecisionService {
    private final JevRouteDecisionService jevService;
    private final LlmRouteDecisionService llmService;
    private final RoutingContextBuilder contextBuilder;
    private final RouteProperties routeProperties;
    private final JevProperties jevProperties;
    private final ShadowRouteComparisonService shadowService;
    private final RouteMetrics routeMetrics;

    @Override
    public RouteDecision decide(String question, String sessionId) {
        String routeRequestId = IdUtil.fastSimpleUUID();
        long start = System.nanoTime();
        try {
            RouteDecision decision = decideRoute(question, sessionId, routeRequestId);
            long duration = System.nanoTime() - start;
            routeMetrics.recordDecision(decision, duration);
            log.info("route decision: routeRequestId={}, agent={}, source={}, "
                            + "fallbackReason={}, model={}, latencyMs={}",
                    routeRequestId,
                    decision == null ? null : decision.agentType(),
                    decision == null ? null : decision.source(),
                    decision == null ? null : decision.fallbackReason(),
                    decision == null ? null : decision.model(),
                    TimeUnit.NANOSECONDS.toMillis(duration));
            return decision;
        } catch (RuntimeException e) {
            RouteFallbackReason reason = routeProperties.isShadowEnabled()
                    || routeProperties.getProvider() == RouteProvider.LLM
                    ? RouteFallbackReason.LLM_ERROR : RouteFallbackReason.UNKNOWN_ERROR;
            routeMetrics.recordDecisionError(reason);
            log.warn("route decision failed: routeRequestId={}, reason={}", routeRequestId, reason);
            throw e;
        }
    }

    private RouteDecision decideRoute(String question, String sessionId, String routeRequestId) {
        if (routeProperties.isShadowEnabled()) {
            return decideInShadow(question, sessionId, routeRequestId);
        }
        if (routeProperties.getProvider() == RouteProvider.LLM) {
            return llmService.decide(question, sessionId);
        }
        if (!jevProperties.isEnabled()) {
            return fallbackToLlm(question, sessionId, RouteFallbackReason.JEV_DISABLED);
        }

        try {
            RoutingState state = contextBuilder.build(question, sessionId);
            RouteDecision jev = evaluateJev(state);
            routeMetrics.recordConfidence(jev.agentType(), jev.confidence());
            double threshold = routeProperties.thresholdFor(jev.agentType());
            if (jev.confidence() >= threshold) {
                return jev;
            }
            return fallbackToLlm(question, sessionId, RouteFallbackReason.LOW_CONFIDENCE);
        } catch (JevClientException e) {
            return fallbackToLlm(question, sessionId, e.getReason());
        } catch (RuntimeException e) {
            return fallbackToLlm(question, sessionId, RouteFallbackReason.UNKNOWN_ERROR);
        }
    }

    private RouteDecision evaluateJev(RoutingState state) {
        routeMetrics.recordRequest(Mode.MAIN);
        long start = System.nanoTime();
        try {
            return jevService.decide(state);
        } catch (JevClientException e) {
            routeMetrics.recordError(e.getReason());
            throw e;
        } catch (RuntimeException e) {
            routeMetrics.recordError(RouteFallbackReason.UNKNOWN_ERROR);
            throw e;
        } finally {
            routeMetrics.recordJevLatency(Mode.MAIN, System.nanoTime() - start);
        }
    }

    private RouteDecision decideInShadow(String question, String sessionId, String routeRequestId) {
        RoutingState state;
        try {
            // 必须在请求线程且在 LLM 写入/清理路由记忆之前取快照。
            state = contextBuilder.build(question, sessionId);
        } catch (RuntimeException e) {
            log.warn("jev shadow skipped: routeRequestId={}, reason=context_build_failed", routeRequestId);
            return llmService.decide(question, sessionId);
        }

        RouteDecision actual = llmService.decide(question, sessionId);
        try {
            shadowService.compareAsync(routeRequestId, hashSession(sessionId), state, actual);
        } catch (TaskRejectedException e) {
            routeMetrics.recordShadowRejected();
            log.warn("jev shadow skipped: routeRequestId={}, reason=task_rejected", routeRequestId);
        } catch (RuntimeException e) {
            log.warn("jev shadow skipped: routeRequestId={}, reason=dispatch_failed", routeRequestId);
        }
        return actual;
    }

    private String hashSession(String sessionId) {
        return DigestUtil.sha256Hex(sessionId == null ? "" : sessionId).substring(0, 16);
    }

    private RouteDecision fallbackToLlm(
            String question,
            String sessionId,
            RouteFallbackReason reason
    ) {
        if (!routeProperties.isFallbackEnabled()) {
            return safeDefault(reason);
        }

        try {
            RouteDecision llm = llmService.decide(question, sessionId);
            if (llm == null || llm.agentType() == null || llm.agentType() == AgentTypeEnum.ROUTE) {
                throw new IllegalStateException("Invalid LLM route decision");
            }
            return new RouteDecision(
                    llm.agentType(),
                    null,
                    Map.of(),
                    RouteDecisionSource.LLM_FALLBACK,
                    reason,
                    null
            );
        } catch (Exception e) {
            routeMetrics.recordDecisionError(RouteFallbackReason.LLM_ERROR);
            return safeDefault(RouteFallbackReason.LLM_ERROR);
        }
    }

    private RouteDecision safeDefault(RouteFallbackReason reason) {
        AgentTypeEnum safeAgent = routeProperties.getSafeDefaultAgent();
        if (safeAgent == null || safeAgent == AgentTypeEnum.ROUTE || safeAgent == AgentTypeEnum.BUY) {
            safeAgent = AgentTypeEnum.KNOWLEDGE;
        }
        return new RouteDecision(
                safeAgent,
                null,
                Map.of(),
                RouteDecisionSource.SAFE_DEFAULT,
                reason,
                null
        );
    }
}
