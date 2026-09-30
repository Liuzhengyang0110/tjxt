package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.advisor.RecordOptimizationAdvisor;
import com.tianji.aigc.agent.Agent;
import com.tianji.aigc.agent.RouteAgent;
import com.tianji.aigc.decision.client.JevDecisionClient;
import com.tianji.aigc.decision.client.dto.JevChoiceAnswer;
import com.tianji.aigc.decision.client.dto.JevSystemOneResponse;
import com.tianji.aigc.decision.config.JevProperties;
import com.tianji.aigc.decision.config.RouteProperties;
import com.tianji.aigc.decision.context.RoutingContextBuilder;
import com.tianji.aigc.decision.context.RoutingState;
import com.tianji.aigc.decision.context.RoutingTextSanitizer;
import com.tianji.aigc.decision.exception.JevClientException;
import com.tianji.aigc.decision.metrics.RouteMetrics;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.decision.model.RouteProvider;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.memory.MyChatMemoryRepository;
import com.tianji.aigc.memory.RedisChatMemoryRepository;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.impl.AgentServiceImpl;
import com.tianji.aigc.vo.ChatEventVO;
import com.tianji.common.utils.UserContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.data.redis.core.BoundListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JevRouteChatMemoryIntegrationTest {
    private ChatMemory chatMemory;
    private JevDecisionClient client;
    private JevRouteDecisionService jevService;
    private JevProperties jevProperties;
    private SimpleMeterRegistry meterRegistry;
    private String conversationId;

    @BeforeEach
    void setUp() {
        UserContext.setUser(1L);
        conversationId = ChatService.getConversationId("s1");
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(100)
                .build();
        chatMemory.add(conversationId, List.of(
                new UserMessage("想学 Java"),
                new AssistantMessage("推荐课程 A")
        ));
        jevProperties = new JevProperties();
        jevProperties.setEnabled(true);
        RoutingContextBuilder contextBuilder = new RoutingContextBuilder(
                chatMemory, jevProperties, new RoutingTextSanitizer());
        client = mock(JevDecisionClient.class);
        jevService = new JevRouteDecisionService(contextBuilder, client);
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
        UserContext.removeUser();
    }

    @Test
    void jevRouteReadsTwoHistoricalMessagesWithoutWritingRouteMessages() {
        when(client.evaluate(any(RoutingState.class))).thenReturn(consultResponse());

        RouteDecision decision = jevService.decide("这个多少钱？", "s1");

        assertEquals(AgentTypeEnum.CONSULT, decision.agentType());
        assertEquals(2, chatMemory.get(conversationId).size());
        ArgumentCaptor<RoutingState> state = ArgumentCaptor.forClass(RoutingState.class);
        verify(client).evaluate(state.capture());
        assertEquals("这个多少钱？", state.getValue().latestUserMessage());
        assertEquals(List.of("想学 Java", "推荐课程 A"),
                state.getValue().recentContext().stream().map(message -> message.content()).toList());
    }

    @Test
    void finalAgentAddsOnlyNormalUserAndAssistantMessages() {
        when(client.evaluate(any(RoutingState.class))).thenReturn(consultResponse());
        RouteProperties routeProperties = new RouteProperties();
        routeProperties.setProvider(RouteProvider.JEV);
        FallbackRouteDecisionService routeService = new FallbackRouteDecisionService(
                jevService,
                mock(LlmRouteDecisionService.class),
                new RoutingContextBuilder(chatMemory, jevProperties, new RoutingTextSanitizer()),
                routeProperties,
                jevProperties,
                mock(ShadowRouteComparisonService.class),
                new RouteMetrics(meterRegistry)
        );
        Agent consultAgent = mock(Agent.class);
        when(consultAgent.getAgentType()).thenReturn(AgentTypeEnum.CONSULT);
        ChatEventVO event = ChatEventVO.builder()
                .eventType(ChatEventTypeEnum.DATA.getValue())
                .eventData("课程 A 的价格是 199 元")
                .build();
        when(consultAgent.processStream("这个多少钱？", "s1")).thenAnswer(invocation -> {
            // 测试替身模拟专项 Agent 的正常用户/助手写入；不连接真实模型。
            chatMemory.add(conversationId, new UserMessage("这个多少钱？"));
            chatMemory.add(conversationId, new AssistantMessage("课程 A 的价格是 199 元"));
            return Flux.just(event);
        });

        List<ChatEventVO> events = new AgentServiceImpl(routeService, List.of(consultAgent))
                .chat("这个多少钱？", "s1").collectList().block();

        assertEquals(List.of(event), events);
        List<Message> history = chatMemory.get(conversationId);
        assertEquals(4, history.size());
        assertEquals(List.of("想学 Java", "推荐课程 A", "这个多少钱？", "课程 A 的价格是 199 元"),
                history.stream().map(Message::getText).toList());
        verify(consultAgent).processStream("这个多少钱？", "s1");
        verify(client).evaluate(any(RoutingState.class));
    }

    @Test
    void llmFallbackAdvisorRemovesOnlyTemporaryRouteMessages() {
        when(client.evaluate(any(RoutingState.class))).thenThrow(
                new JevClientException(RouteFallbackReason.TIMEOUT, "Jev timed out"));
        MyChatMemoryRepository inMemoryCleanup = id -> {
            List<Message> messages = chatMemory.get(id);
            chatMemory.clear(id);
            chatMemory.add(id, messages.subList(0, messages.size() - 2));
        };
        RecordOptimizationAdvisor advisor = new RecordOptimizationAdvisor(inMemoryCleanup);
        RouteAgent routeAgent = mock(RouteAgent.class);
        when(routeAgent.process("这个多少钱？", "s1")).thenAnswer(invocation -> {
            chatMemory.add(conversationId, new UserMessage("这个多少钱？"));
            chatMemory.add(conversationId, new AssistantMessage("CONSULT"));
            advisor.after(chatResponse("CONSULT"), null);
            return "CONSULT";
        });
        RouteProperties routeProperties = new RouteProperties();
        routeProperties.setProvider(RouteProvider.JEV);
        FallbackRouteDecisionService routeService = new FallbackRouteDecisionService(
                jevService,
                new LlmRouteDecisionService(routeAgent),
                new RoutingContextBuilder(chatMemory, jevProperties, new RoutingTextSanitizer()),
                routeProperties,
                jevProperties,
                mock(ShadowRouteComparisonService.class),
                new RouteMetrics(meterRegistry)
        );

        RouteDecision decision = routeService.decide("这个多少钱？", "s1");

        assertEquals(RouteDecisionSource.LLM_FALLBACK, decision.source());
        assertEquals(RouteFallbackReason.TIMEOUT, decision.fallbackReason());
        assertEquals(2, chatMemory.get(conversationId).size());
        assertEquals(List.of("想学 Java", "推荐课程 A"),
                chatMemory.get(conversationId).stream().map(Message::getText).toList());
    }

    @Test
    void redisCleanupPopsExactlyTwoTemporaryMessages() {
        RedisChatMemoryRepository repository = new RedisChatMemoryRepository();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        BoundListOperations<String, String> operations = mock(BoundListOperations.class);
        ReflectionTestUtils.setField(repository, "stringRedisTemplate", redis);
        when(redis.boundListOps("CHAT:" + conversationId)).thenReturn(operations);

        repository.optimization(conversationId);

        verify(operations).rightPop(2);
    }

    @Test
    void advisorCallsRepositoryOnlyForRecognizedAgentResult() {
        MyChatMemoryRepository repository = mock(MyChatMemoryRepository.class);
        RecordOptimizationAdvisor advisor = new RecordOptimizationAdvisor(repository);
        ChatClientResponse routed = chatResponse("CONSULT");

        assertSame(routed, advisor.after(routed, null));
        advisor.after(chatResponse("普通回答"), null);

        verify(repository).optimization(conversationId);
    }

    private JevSystemOneResponse consultResponse() {
        JevChoiceAnswer answer = new JevChoiceAnswer(
                "choice", "CONSULT", 0.93, Map.of("CONSULT", 1.0));
        return new JevSystemOneResponse("jev-1.13.0", Map.of("route_agent", answer), null);
    }

    private ChatClientResponse chatResponse(String text) {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return new ChatClientResponse(response, Map.of(ChatMemory.CONVERSATION_ID, conversationId));
    }
}
