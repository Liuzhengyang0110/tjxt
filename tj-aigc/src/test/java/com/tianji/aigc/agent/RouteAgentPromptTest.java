package com.tianji.aigc.agent;

import com.tianji.aigc.config.SystemPromptConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteAgentPromptTest {

    @Test
    void appendsRoutingContractToLegacyPromptAndKeepsConfigurationRefresh() {
        SystemPromptConfig config = new SystemPromptConfig(null, null);
        config.getRouteAgentSystemMessage().set("问候语场景返回您好");
        RouteAgent agent = new RouteAgent(config);

        String prompt = agent.systemMessage();

        assertTrue(prompt.startsWith("问候语场景返回您好"));
        assertTrue(prompt.indexOf("## 路由接口输出协议") > prompt.indexOf("问候语场景"));
        assertTrue(prompt.contains("只能输出 RECOMMEND、CONSULT、BUY、KNOWLEDGE"));
        config.getRouteAgentSystemMessage().set("已更新的分类规则");
        assertTrue(agent.systemMessage().startsWith("已更新的分类规则"));
        assertFalse(agent.systemMessage().contains("问候语场景返回您好"));
    }

    @Test
    void retainsOutputContractWhenRemotePromptIsUnavailable() {
        RouteAgent agent = new RouteAgent(new SystemPromptConfig(null, null));

        assertTrue(agent.systemMessage().contains("只能输出 RECOMMEND、CONSULT、BUY、KNOWLEDGE"));
    }
}
