package com.tianji.aigc.decision.config;

import com.tianji.aigc.decision.model.RouteProvider;
import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "tj.ai.route")
public class RouteProperties {
    private RouteProvider provider = RouteProvider.LLM;
    private boolean shadowEnabled = false;
    private boolean fallbackEnabled = true;
    private double defaultConfidenceThreshold = 0.85;
    private AgentTypeEnum safeDefaultAgent = AgentTypeEnum.KNOWLEDGE;
    private Map<AgentTypeEnum, Double> confidenceThresholds =
            new EnumMap<>(AgentTypeEnum.class);

    public double thresholdFor(AgentTypeEnum agentType) {
        return confidenceThresholds.getOrDefault(agentType, defaultConfidenceThreshold);
    }
}
