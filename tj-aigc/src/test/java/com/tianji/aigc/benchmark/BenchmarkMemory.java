package com.tianji.aigc.benchmark;

import com.tianji.aigc.memory.MyChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import java.util.*;

/** Run-local persistence adapter. The production optimization contract removes two route messages. */
final class BenchmarkMemory implements ChatMemoryRepository, MyChatMemoryRepository {
    private final Map<String, List<Message>> conversations = new HashMap<>();
    @Override public synchronized List<String> findConversationIds() { return new ArrayList<>(conversations.keySet()); }
    @Override public synchronized List<Message> findByConversationId(String id) { return new ArrayList<>(conversations.getOrDefault(id, List.of())); }
    @Override public synchronized void saveAll(String id, List<Message> messages) { conversations.put(id, new ArrayList<>(messages)); }
    @Override public synchronized void deleteByConversationId(String id) { conversations.remove(id); }
    @Override public synchronized void optimization(String id) {
        List<Message> messages = conversations.get(id);
        if (messages == null || messages.size() < 2) throw new IllegalStateException("Invalid route memory optimization");
        conversations.put(id, new ArrayList<>(messages.subList(0, messages.size() - 2)));
    }
}
