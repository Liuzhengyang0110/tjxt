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
import com.tianji.aigc.decision.service.RouteDecisionService;
import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service("jevRouteDecisionService")
@RequiredArgsConstructor
public class JevRouteDecisionService implements RouteDecisionService {
    private static final String ROUTE_QUESTION_ID = "route_agent";
    private static final double PROBABILITY_SUM_TOLERANCE = 0.001;
    private static final Set<AgentTypeEnum> ALLOWED_AGENTS = Set.of(
            AgentTypeEnum.RECOMMEND,
            AgentTypeEnum.CONSULT,
            AgentTypeEnum.BUY,
            AgentTypeEnum.KNOWLEDGE
    );

    private final RoutingContextBuilder contextBuilder;
    private final JevDecisionClient client;

    @Override
    public RouteDecision decide(String question, String sessionId) {
        return decide(contextBuilder.build(question, sessionId));
    }

    public RouteDecision decide(RoutingState state) {
        JevSystemOneResponse response = client.evaluate(state);
        if (response == null || response.model() == null || response.model().isBlank()
                || response.answers() == null) {
            throw invalidResponse("Jev response is missing model or answers");
        }

        JevChoiceAnswer answer = response.answers().get(ROUTE_QUESTION_ID);
        if (answer == null || !"choice".equals(answer.type())) {
            throw invalidResponse("Jev route answer is missing or has an invalid type");
        }

        AgentTypeEnum agentType = answer.choice() == null
                ? null : AgentTypeEnum.agentNameOf(answer.choice());
        if (agentType == null || !ALLOWED_AGENTS.contains(agentType)) {
            throw invalidResponse("Jev route choice is invalid");
        }
        if (!isProbability(answer.confidence())) {
            throw invalidResponse("Jev route confidence is invalid");
        }

        Map<String, Double> probabilities = answer.probabilities();
        if (probabilities == null || probabilities.isEmpty()) {
            throw invalidResponse("Jev route probabilities are missing");
        }

        double sum = 0;
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            AgentTypeEnum probabilityAgent = entry.getKey() == null
                    ? null : AgentTypeEnum.agentNameOf(entry.getKey());
            if (probabilityAgent == null || !ALLOWED_AGENTS.contains(probabilityAgent)
                    || !isProbability(entry.getValue())) {
                throw invalidResponse("Jev route probabilities are invalid");
            }
            sum += entry.getValue();
        }
        if (Math.abs(sum - 1.0) > PROBABILITY_SUM_TOLERANCE) {
            throw invalidResponse("Jev route probabilities do not sum to one");
        }

        return new RouteDecision(
                agentType,
                answer.confidence(),
                probabilities,
                RouteDecisionSource.JEV,
                RouteFallbackReason.NONE,
                response.model()
        );
    }

    private boolean isProbability(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private JevClientException invalidResponse(String message) {
        return new JevClientException(RouteFallbackReason.INVALID_RESPONSE, message);
    }
}
