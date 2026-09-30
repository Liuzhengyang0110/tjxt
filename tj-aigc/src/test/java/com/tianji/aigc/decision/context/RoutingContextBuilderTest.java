package com.tianji.aigc.decision.context;

import com.tianji.aigc.decision.config.JevProperties;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RoutingContextBuilderTest {
    private ChatMemory chatMemory;
    private JevProperties properties;
    private RoutingContextBuilder builder;

    @BeforeEach
    void setUp() {
        UserContext.setUser(7L);
        chatMemory = mock(ChatMemory.class);
        properties = new JevProperties();
        builder = new RoutingContextBuilder(chatMemory, properties, new RoutingTextSanitizer());
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void buildsStateWithoutHistoryOrMemoryWrites() {
        when(chatMemory.get("7_s1")).thenReturn(List.of());

        RoutingState state = builder.build("推荐 Java 课程", "s1");

        assertEquals("推荐 Java 课程", state.latestUserMessage());
        assertTrue(state.recentContext().isEmpty());
        assertEquals(4, state.availableAgents().size());
        verify(chatMemory).get("7_s1");
        verifyNoMoreInteractions(chatMemory);
    }

    @Test
    void keepsEightNewestMessagesInChronologicalOrder() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(i % 2 == 0 ? new UserMessage("u" + i) : new AssistantMessage("a" + i));
        }
        when(chatMemory.get("7_s1")).thenReturn(history);

        RoutingState state = builder.build("继续", "s1");

        assertEquals(List.of("u2", "a3", "u4", "a5", "u6", "a7", "u8", "a9"),
                state.recentContext().stream().map(RoutingMessage::content).toList());
        assertEquals("user", state.recentContext().get(0).role());
        assertEquals("assistant", state.recentContext().get(7).role());
        verify(chatMemory).get("7_s1");
        verifyNoMoreInteractions(chatMemory);
    }

    @Test
    void spendsCharacterBudgetOnLatestHistoryFirst() {
        properties.setMaxContextChars(12);
        when(chatMemory.get("7_s1")).thenReturn(List.of(
                new UserMessage("旧内容"),
                new AssistantMessage("abcdefghijklmno")
        ));

        RoutingState state = builder.build("价格", "s1");

        assertEquals(List.of(new RoutingMessage("assistant", "fghijklmno")), state.recentContext());
        assertEquals(12, state.latestUserMessage().length()
                + state.recentContext().stream().mapToInt(message -> message.content().length()).sum());
        verify(chatMemory).get("7_s1");
        verifyNoMoreInteractions(chatMemory);
    }

    @Test
    void carriesCourseContextForFollowUpQuestionAndFiltersSystemMessages() {
        when(chatMemory.get("7_s1")).thenReturn(List.of(
                new SystemMessage("内部指令"),
                new UserMessage("推荐一门 Java 课程"),
                new AssistantMessage("我推荐课程 A")
        ));

        RoutingState state = builder.build("这个多少钱？", "s1");

        assertEquals("这个多少钱？", state.latestUserMessage());
        assertEquals(List.of("推荐一门 Java 课程", "我推荐课程 A"),
                state.recentContext().stream().map(RoutingMessage::content).toList());
        verify(chatMemory).get("7_s1");
        verifyNoMoreInteractions(chatMemory);
    }

    @Test
    void sanitizesBothLatestQuestionAndHistory() {
        when(chatMemory.get("7_s1")).thenReturn(List.of(new UserMessage("订单 123456789012，手机 13800138000")));

        RoutingState state = builder.build("邮箱 student@example.com，身份证 11010519491231002X", "s1");

        assertEquals("邮箱 <EMAIL>，身份证 <ID_CARD>", state.latestUserMessage());
        assertEquals("订单 <LONG_ID>，手机 <PHONE>", state.recentContext().get(0).content());
        verify(chatMemory).get("7_s1");
        verifyNoMoreInteractions(chatMemory);
    }
}
