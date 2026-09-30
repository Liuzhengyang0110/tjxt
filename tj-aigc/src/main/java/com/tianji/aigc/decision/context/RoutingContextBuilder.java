package com.tianji.aigc.decision.context;

import com.tianji.aigc.decision.config.JevProperties;
import com.tianji.aigc.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RoutingContextBuilder {
    private static final Map<String, String> AVAILABLE_AGENTS = Map.of(
            "RECOMMEND", "推荐平台中的具体课程",
            "CONSULT", "咨询平台具体课程的信息",
            "BUY", "购买或预下单已选课程",
            "KNOWLEDGE", "解释通用知识和编程问题"
    );

    private final ChatMemory chatMemory;
    private final JevProperties properties;
    private final RoutingTextSanitizer sanitizer;

    public RoutingState build(String question, String sessionId) {
        String conversationId = ChatService.getConversationId(sessionId);
        List<Message> history = chatMemory.get(conversationId);

        List<Message> candidates = history == null ? List.of() : history.stream()
                .filter(message -> message != null
                        && (message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT))
                .toList();

        int maxChars = Math.max(0, properties.getMaxContextChars());
        String latest = truncateLatest(sanitizer.sanitize(question), maxChars);
        List<RoutingMessage> recent = takeRecentMessages(candidates, maxChars - latest.length());

        return new RoutingState(latest, recent, AVAILABLE_AGENTS);
    }

    private List<RoutingMessage> takeRecentMessages(List<Message> candidates, int remainingChars) {
        int maxMessages = Math.max(0, properties.getRecentMessageLimit());
        List<RoutingMessage> recent = new ArrayList<>();

        for (int i = candidates.size() - 1;
             i >= 0 && recent.size() < maxMessages && remainingChars > 0;
             i--) {
            Message message = candidates.get(i);
            String content = sanitizer.sanitize(message.getText());
            if (content.isBlank()) {
                continue;
            }
            if (content.length() > remainingChars) {
                content = content.substring(content.length() - remainingChars);
            }

            recent.add(new RoutingMessage(
                    message.getMessageType().name().toLowerCase(Locale.ROOT),
                    content
            ));
            remainingChars -= content.length();
        }

        Collections.reverse(recent);
        return recent;
    }

    private String truncateLatest(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars == 0) {
            return "";
        }
        if (maxChars <= 3) {
            return text.substring(text.length() - maxChars);
        }

        int headChars = (maxChars - 1) / 2;
        int tailChars = maxChars - headChars - 1;
        return text.substring(0, headChars)
                + "…"
                + text.substring(text.length() - tailChars);
    }
}
