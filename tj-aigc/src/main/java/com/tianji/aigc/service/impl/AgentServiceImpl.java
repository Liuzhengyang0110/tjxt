package com.tianji.aigc.service.impl;

import cn.hutool.core.util.IdUtil;
import com.tianji.aigc.agent.AbstractAgent;
import com.tianji.aigc.agent.Agent;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.service.RouteDecisionService;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "tj.ai", name = "chat-type", havingValue = "ROUTE")
public class AgentServiceImpl implements ChatService {
    private final RouteDecisionService routeDecisionService;
    private final Map<AgentTypeEnum, Agent> agentMap;

    public AgentServiceImpl(RouteDecisionService routeDecisionService, List<Agent> agents) {
        this.routeDecisionService = routeDecisionService;
        this.agentMap = agents.stream().collect(Collectors.toUnmodifiableMap(
                Agent::getAgentType,
                Function.identity()
        ));
    }

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        String requestId = IdUtil.fastSimpleUUID();
        try {
            // 在请求线程内完成路由和工具上下文构造，保留 UserContext。
            return dispatch(question, sessionId).onErrorResume(error -> {
                log.warn("agent stream failed: requestId={}, errorType={}",
                        requestId, error.getClass().getSimpleName());
                return friendlyErrorEvents();
            });
        } catch (RuntimeException error) {
            log.warn("agent chat failed: requestId={}, errorType={}",
                    requestId, error.getClass().getSimpleName());
            return friendlyErrorEvents();
        }
    }

    private Flux<ChatEventVO> dispatch(String question, String sessionId) {
        RouteDecision decision = routeDecisionService.decide(question, sessionId);
        AgentTypeEnum agentType = decision == null ? null : decision.agentType();
        Agent agent = findAgentByType(agentType);

        if (agent == null || agentType == AgentTypeEnum.ROUTE) {
            log.warn("Invalid route decision: routeRequestId={}, agent={}, source={}, fallbackReason={}",
                    IdUtil.fastSimpleUUID(),
                    agentType,
                    decision == null ? null : decision.source(),
                    decision == null ? null : decision.fallbackReason());
            Agent safeAgent = findAgentByType(AgentTypeEnum.KNOWLEDGE);
            if (safeAgent == null) {
                return friendlyErrorEvents();
            }
            return safeAgent.processStream(question, sessionId);
        }
        return agent.processStream(question, sessionId);
    }

    private Flux<ChatEventVO> friendlyErrorEvents() {
        ChatEventVO event = ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.DATA.getValue())
                .eventData("抱歉，当前服务暂时不可用，请稍后重试。")
                .build();
        return Flux.just(event, AbstractAgent.STOP_EVENT);
    }

    @Override
    public void stop(String sessionId) {
        this.findAgentByType(AgentTypeEnum.ROUTE).stop(sessionId);
    }

    @Override
    public String chatText(String question) {
        return "";
    }

    /**
     * 根据代理类型查找对应的Agent实例
     *
     * @param agentTypeEnum 要查找的代理类型
     * @return 与给定类型匹配的Agent实例，如果未找到或类型为null则返回null
     */
    private Agent findAgentByType(AgentTypeEnum agentTypeEnum) {
        if (agentTypeEnum == null) {
            return null;
        }
        return agentMap.get(agentTypeEnum);
    }
}
