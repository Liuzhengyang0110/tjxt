package com.tianji.aigc.decision.service.impl;

import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.agent.RouteAgent;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.decision.service.RouteDecisionService;
import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

@Service("llmRouteDecisionService")
@RequiredArgsConstructor
public class LlmRouteDecisionService implements RouteDecisionService {
    private final RouteAgent routeAgent;

    @Override
    public RouteDecision decide(String question, String sessionId) {
        String raw = routeAgent.process(question, sessionId);
        String normalized = (raw == null ? "" : StrUtil.trim(raw)).toUpperCase(Locale.ROOT);
        AgentTypeEnum agentType = AgentTypeEnum.agentNameOf(normalized);

        if (agentType == null || agentType == AgentTypeEnum.ROUTE) {
            throw new IllegalStateException("Invalid LLM route result");
        }

        return new RouteDecision(
                agentType,
                null,
                Map.of(),
                RouteDecisionSource.LLM_PRIMARY,
                RouteFallbackReason.NONE,
                null
        );
    }
}
