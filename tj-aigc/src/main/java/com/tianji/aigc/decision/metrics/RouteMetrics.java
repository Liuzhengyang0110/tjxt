package com.tianji.aigc.decision.metrics;

import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.enums.AgentTypeEnum;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RouteMetrics {
    private final MeterRegistry registry;

    public enum Mode {
        MAIN("main"),
        SHADOW("shadow");

        private final String tag;

        Mode(String tag) {
            this.tag = tag;
        }
    }

    public void recordRequest(Mode mode) {
        safely("jev.route.requests", () -> registry.counter("jev.route.requests", "mode", mode.tag).increment());
    }

    public void recordJevLatency(Mode mode, long durationNanos) {
        safely("jev.route.latency", () -> Timer.builder("jev.route.latency")
                .tag("mode", mode.tag)
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Math.max(0, durationNanos), TimeUnit.NANOSECONDS));
    }

    public void recordDecision(RouteDecision decision, long durationNanos) {
        if (decision == null) {
            return;
        }
        String agent = agentTag(decision.agentType());
        String source = decision.source() == null ? "UNKNOWN" : decision.source().name();
        safely("jev.route.decisions", () -> registry.counter(
                "jev.route.decisions", "agent", agent, "source", source).increment());
        safely("route.decision.latency", () -> Timer.builder("route.decision.latency")
                .tag("source", source)
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Math.max(0, durationNanos), TimeUnit.NANOSECONDS));

        if (decision.source() == RouteDecisionSource.LLM_FALLBACK
                || decision.source() == RouteDecisionSource.SAFE_DEFAULT) {
            String reason = reasonTag(decision.fallbackReason());
            safely("jev.route.fallbacks", () -> registry.counter(
                    "jev.route.fallbacks", "reason", reason, "agent", agent).increment());
        }
    }

    public void recordError(RouteFallbackReason reason) {
        safely("jev.route.errors", () -> registry.counter(
                "jev.route.errors", "reason", reasonTag(reason)).increment());
    }

    public void recordDecisionError(RouteFallbackReason reason) {
        safely("route.decision.errors", () -> registry.counter(
                "route.decision.errors", "reason", reasonTag(reason)).increment());
    }

    public void recordConfidence(AgentTypeEnum agent, double confidence) {
        safely("jev.route.confidence", () -> DistributionSummary.builder("jev.route.confidence")
                .tag("agent", agentTag(agent))
                .register(registry)
                .record(confidence));
    }

    public void recordDisagreement(AgentTypeEnum jevAgent, AgentTypeEnum llmAgent) {
        safely("jev.route.disagreements", () -> registry.counter(
                "jev.route.disagreements",
                "jevAgent", agentTag(jevAgent),
                "llmAgent", agentTag(llmAgent)).increment());
    }

    public void recordShadowRejected() {
        safely("jev.shadow.rejected", () -> registry.counter("jev.shadow.rejected").increment());
    }

    private String agentTag(AgentTypeEnum agent) {
        return agent == null ? "UNKNOWN" : agent.name();
    }

    private String reasonTag(RouteFallbackReason reason) {
        return reason == null ? RouteFallbackReason.UNKNOWN_ERROR.name() : reason.name();
    }

    private void safely(String metricName, Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            // 监控故障不能影响用户路由；不输出异常消息或请求内容。
            log.warn("route metric unavailable: name={}", metricName);
        }
    }
}
