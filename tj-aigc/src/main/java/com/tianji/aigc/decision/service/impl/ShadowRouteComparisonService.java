package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.decision.context.RoutingState;
import com.tianji.aigc.decision.exception.JevClientException;
import com.tianji.aigc.decision.metrics.RouteMetrics;
import com.tianji.aigc.decision.metrics.RouteMetrics.Mode;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowRouteComparisonService {
    private final JevRouteDecisionService jevService;
    private final RouteMetrics routeMetrics;

    @Async("jevShadowExecutor")
    public void compareAsync(
            String routeRequestId,
            String sessionHash,
            RoutingState state,
            RouteDecision actualDecision
    ) {
        try {
            long start = System.nanoTime();
            RouteDecision shadow = evaluateJev(state);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            routeMetrics.recordConfidence(shadow.agentType(), shadow.confidence());
            boolean disagreed = shadow.agentType() != actualDecision.agentType();
            if (disagreed) {
                routeMetrics.recordDisagreement(shadow.agentType(), actualDecision.agentType());
            }

            log.info("jev shadow result: routeRequestId={}, sessionHash={}, "
                            + "actualAgent={}, jevAgent={}, confidence={}, "
                            + "latencyMs={}, disagreed={}, model={}",
                    routeRequestId,
                    sessionHash,
                    actualDecision.agentType(),
                    shadow.agentType(),
                    shadow.confidence(),
                    latencyMs,
                    disagreed,
                    shadow.model());
        } catch (JevClientException e) {
            log.warn("jev shadow failed: routeRequestId={}, sessionHash={}, reason={}",
                    routeRequestId, sessionHash, e.getReason());
        } catch (Exception e) {
            log.warn("jev shadow failed: routeRequestId={}, sessionHash={}, reason={}",
                    routeRequestId, sessionHash, RouteFallbackReason.UNKNOWN_ERROR);
        }
    }

    private RouteDecision evaluateJev(RoutingState state) {
        routeMetrics.recordRequest(Mode.SHADOW);
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
            routeMetrics.recordJevLatency(Mode.SHADOW, System.nanoTime() - start);
        }
    }
}
