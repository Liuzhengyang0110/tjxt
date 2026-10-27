package com.tianji.aigc.service.impl;

import com.tianji.aigc.agent.AbstractAgent;
import com.tianji.aigc.agent.Agent;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.decision.service.RouteDecisionService;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.vo.ChatEventVO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceImplTest {
    private final RouteDecisionService routeDecisionService = mock(RouteDecisionService.class);

    @Test
    void dispatchesEveryFinalDecisionToItsAgentStream() {
        List<Agent> agents = new ArrayList<>();
        Agent route = agent(AgentTypeEnum.ROUTE);
        agents.add(route);
        for (AgentTypeEnum type : List.of(AgentTypeEnum.RECOMMEND, AgentTypeEnum.CONSULT,
                AgentTypeEnum.BUY, AgentTypeEnum.KNOWLEDGE)) {
            agents.add(agent(type));
        }
        AgentServiceImpl service = new AgentServiceImpl(routeDecisionService, agents);

        for (AgentTypeEnum type : List.of(AgentTypeEnum.RECOMMEND, AgentTypeEnum.CONSULT,
                AgentTypeEnum.BUY, AgentTypeEnum.KNOWLEDGE)) {
            Agent selected = agents.stream().filter(agent -> agent.getAgentType() == type).findFirst().orElseThrow();
            ChatEventVO event = ChatEventVO.builder()
                    .eventType(ChatEventTypeEnum.DATA.getValue())
                    .eventData(type.name())
                    .build();
            when(routeDecisionService.decide("问题", "会话")).thenReturn(decision(type));
            when(selected.processStream("问题", "会话")).thenReturn(Flux.just(event));

            assertEquals(List.of(event), service.chat("问题", "会话").collectList().block());
            verify(selected).processStream("问题", "会话");
        }
        verify(route, never()).process("问题", "会话");
    }

    @Test
    void routeDecisionCannotBeDispatchedBackToRouteAgent() {
        Agent route = agent(AgentTypeEnum.ROUTE);
        Agent knowledge = agent(AgentTypeEnum.KNOWLEDGE);
        AgentServiceImpl service = new AgentServiceImpl(routeDecisionService, List.of(route, knowledge));
        ChatEventVO event = ChatEventVO.builder().eventType(1001).eventData("知识解答").build();
        when(routeDecisionService.decide("问题", "会话")).thenReturn(decision(AgentTypeEnum.ROUTE));
        when(knowledge.processStream("问题", "会话")).thenReturn(Flux.just(event));

        assertEquals(List.of(event), service.chat("问题", "会话").collectList().block());

        verify(route, never()).processStream("问题", "会话");
        verify(knowledge).processStream("问题", "会话");
    }

    @Test
    void nullDecisionUsesKnowledgeAgent() {
        Agent knowledge = agent(AgentTypeEnum.KNOWLEDGE);
        AgentServiceImpl service = new AgentServiceImpl(routeDecisionService, List.of(knowledge));
        ChatEventVO event = ChatEventVO.builder().eventType(1001).eventData("安全回答").build();
        when(knowledge.processStream("问题", "会话")).thenReturn(Flux.just(event));

        assertEquals(List.of(event), service.chat("问题", "会话").collectList().block());

        verify(knowledge).processStream("问题", "会话");
    }

    @Test
    void missingSafeAgentProducesFriendlyErrorAndStopEvent() {
        Agent route = agent(AgentTypeEnum.ROUTE);
        AgentServiceImpl service = new AgentServiceImpl(routeDecisionService, List.of(route));
        when(routeDecisionService.decide("问题", "会话")).thenReturn(decision(AgentTypeEnum.CONSULT));

        List<ChatEventVO> events = service.chat("问题", "会话").collectList().block();

        assertEquals(2, events.size());
        assertEquals(ChatEventTypeEnum.DATA.getValue(), events.get(0).getEventType());
        assertEquals("抱歉，当前服务暂时不可用，请稍后重试。", events.get(0).getEventData());
        assertSame(AbstractAgent.STOP_EVENT, events.get(1));
        verify(route, never()).processStream("问题", "会话");
    }

    @Test
    void duplicateAgentTypeFailsAtConstruction() {
        Agent first = agent(AgentTypeEnum.CONSULT);
        Agent second = agent(AgentTypeEnum.CONSULT);

        assertThrows(IllegalStateException.class,
                () -> new AgentServiceImpl(routeDecisionService, List.of(first, second)));
    }

    @Test
    void stopStillDelegatesToRouteAgent() {
        Agent route = agent(AgentTypeEnum.ROUTE);
        AgentServiceImpl service = new AgentServiceImpl(routeDecisionService, List.of(route));

        service.stop("会话");

        verify(route).stop("会话");
    }

    private Agent agent(AgentTypeEnum type) {
        Agent agent = mock(Agent.class);
        when(agent.getAgentType()).thenReturn(type);
        return agent;
    }

    @Test
    void routeExceptionProducesFriendlyErrorAndStopInsteadOfEscapingToMvc() {
        AgentServiceImpl service = new AgentServiceImpl(routeDecisionService, List.of());
        when(routeDecisionService.decide("问题", "会话")).thenThrow(new IllegalStateException("route failed"));

        assertFriendlyErrorAndStop(service.chat("问题", "会话").collectList().block());
    }

    @Test
    void agentSetupExceptionProducesFriendlyErrorAndStop() {
        Agent recommend = agent(AgentTypeEnum.RECOMMEND);
        AgentServiceImpl service = new AgentServiceImpl(routeDecisionService, List.of(recommend));
        when(routeDecisionService.decide("问题", "会话")).thenReturn(decision(AgentTypeEnum.RECOMMEND));
        when(recommend.processStream("问题", "会话")).thenThrow(new IllegalStateException("tool setup failed"));

        assertFriendlyErrorAndStop(service.chat("问题", "会话").collectList().block());
    }

    @Test
    void asynchronousAgentErrorKeepsPartialReplyAndEndsWithStop() {
        Agent recommend = agent(AgentTypeEnum.RECOMMEND);
        AgentServiceImpl service = new AgentServiceImpl(routeDecisionService, List.of(recommend));
        ChatEventVO partial = ChatEventVO.builder().eventType(1001).eventData("部分回答").build();
        when(routeDecisionService.decide("问题", "会话")).thenReturn(decision(AgentTypeEnum.RECOMMEND));
        when(recommend.processStream("问题", "会话")).thenReturn(
                Flux.concat(Flux.just(partial), Flux.error(new IllegalStateException("upstream failed"))));

        List<ChatEventVO> events = service.chat("问题", "会话").collectList().block();

        assertEquals(3, events.size());
        assertSame(partial, events.get(0));
        assertFriendlyErrorAndStop(events.subList(1, 3));
    }

    private void assertFriendlyErrorAndStop(List<ChatEventVO> events) {
        assertEquals(2, events.size());
        assertEquals(ChatEventTypeEnum.DATA.getValue(), events.get(0).getEventType());
        assertEquals("抱歉，当前服务暂时不可用，请稍后重试。", events.get(0).getEventData());
        assertSame(AbstractAgent.STOP_EVENT, events.get(1));
    }

    private RouteDecision decision(AgentTypeEnum type) {
        return new RouteDecision(type, null, Map.of(),
                RouteDecisionSource.LLM_PRIMARY, RouteFallbackReason.NONE, null);
    }
}
