package com.tianji.aigc.decision.metrics;

import com.tianji.aigc.decision.metrics.RouteMetrics.Mode;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.enums.AgentTypeEnum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouteMetricsTest {

    @Test
    void recordsJevRequestsLatencyErrorsAndConfidenceWithFixedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            RouteMetrics metrics = new RouteMetrics(registry);
            metrics.recordRequest(Mode.MAIN);
            metrics.recordRequest(Mode.SHADOW);
            metrics.recordJevLatency(Mode.MAIN, TimeUnit.MILLISECONDS.toNanos(25));
            metrics.recordJevLatency(Mode.SHADOW, TimeUnit.MILLISECONDS.toNanos(40));
            metrics.recordError(RouteFallbackReason.TIMEOUT);
            metrics.recordDecisionError(RouteFallbackReason.LLM_ERROR);
            metrics.recordConfidence(AgentTypeEnum.CONSULT, 0.92);
            metrics.recordDisagreement(AgentTypeEnum.CONSULT, AgentTypeEnum.RECOMMEND);
            metrics.recordShadowRejected();

            assertEquals(1.0, registry.get("jev.route.requests").tag("mode", "main").counter().count());
            assertEquals(1.0, registry.get("jev.route.requests").tag("mode", "shadow").counter().count());
            assertEquals(1, registry.get("jev.route.latency").tag("mode", "main").timer().count());
            assertEquals(25.0, registry.get("jev.route.latency").tag("mode", "main")
                    .timer().totalTime(TimeUnit.MILLISECONDS), 0.001);
            assertEquals(1.0, registry.get("jev.route.errors").tag("reason", "TIMEOUT").counter().count());
            assertEquals(1.0, registry.get("route.decision.errors")
                    .tag("reason", "LLM_ERROR").counter().count());
            assertEquals(1, registry.get("jev.route.confidence").tag("agent", "CONSULT").summary().count());
            assertEquals(0.92, registry.get("jev.route.confidence").tag("agent", "CONSULT")
                    .summary().totalAmount(), 0.001);
            assertEquals(1.0, registry.get("jev.route.disagreements")
                    .tag("jevAgent", "CONSULT").tag("llmAgent", "RECOMMEND").counter().count());
            assertEquals(1.0, registry.get("jev.shadow.rejected").counter().count());
        } finally {
            registry.close();
        }
    }

    @Test
    void countsFinalDecisionsAndFallbacksOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            RouteMetrics metrics = new RouteMetrics(registry);
            metrics.recordDecision(decision(AgentTypeEnum.CONSULT, RouteDecisionSource.JEV,
                    RouteFallbackReason.NONE), TimeUnit.MILLISECONDS.toNanos(20));
            metrics.recordDecision(decision(AgentTypeEnum.KNOWLEDGE, RouteDecisionSource.LLM_FALLBACK,
                    RouteFallbackReason.LOW_CONFIDENCE), TimeUnit.MILLISECONDS.toNanos(100));
            metrics.recordDecision(decision(AgentTypeEnum.KNOWLEDGE, RouteDecisionSource.SAFE_DEFAULT,
                    RouteFallbackReason.LLM_ERROR), TimeUnit.MILLISECONDS.toNanos(120));

            assertEquals(1.0, registry.get("jev.route.decisions")
                    .tag("agent", "CONSULT").tag("source", "JEV").counter().count());
            assertEquals(1.0, registry.get("jev.route.decisions")
                    .tag("agent", "KNOWLEDGE").tag("source", "LLM_FALLBACK").counter().count());
            assertEquals(1.0, registry.get("jev.route.fallbacks")
                    .tag("reason", "LOW_CONFIDENCE").tag("agent", "KNOWLEDGE").counter().count());
            assertEquals(1.0, registry.get("jev.route.fallbacks")
                    .tag("reason", "LLM_ERROR").tag("agent", "KNOWLEDGE").counter().count());
            assertEquals(1, registry.get("route.decision.latency").tag("source", "JEV").timer().count());
            assertNull(registry.find("jev.route.fallbacks").tag("reason", "NONE").counter());
            assertNotNull(registry.find("route.decision.latency").tag("source", "LLM_FALLBACK").timer());
        } finally {
            registry.close();
        }
    }

    private RouteDecision decision(AgentTypeEnum agent, RouteDecisionSource source,
                                   RouteFallbackReason reason) {
        return new RouteDecision(agent, null, Map.of(), source, reason, null);
    }
}
