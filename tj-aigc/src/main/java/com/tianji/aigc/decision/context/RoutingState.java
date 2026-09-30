package com.tianji.aigc.decision.context;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record RoutingState(
        @JsonProperty("latest_user_message") String latestUserMessage,
        @JsonProperty("recent_context") List<RoutingMessage> recentContext,
        @JsonProperty("available_agents") Map<String, String> availableAgents
) {
    public RoutingState {
        recentContext = List.copyOf(recentContext);
        availableAgents = Map.copyOf(availableAgents);
    }
}
