package com.tianji.aigc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaCompatibilityTest {

    @Test
    void generatesSchemasForRealAgentToolsWithoutCallingRemoteServices() throws Exception {
        // 与 ChatClient.tools(...) 一样注册真实工具，仅解析方法签名，不执行工具或连接远程服务。
        var callbacks = ToolCallbacks.from(new CourseTools(null), new OrderTools(null));
        var definitions = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition())
                .collect(Collectors.toMap(definition -> definition.name(), Function.identity()));

        assertEquals(2, definitions.size());
        ObjectMapper mapper = new ObjectMapper();
        for (var entry : Map.of("queryCourseById", "courseId", "prePlaceOrder", "ids").entrySet()) {
            var definition = definitions.get(entry.getKey());
            var schema = mapper.readTree(definition.inputSchema());
            assertEquals("object", schema.path("type").asText());
            assertTrue(schema.path("properties").has(entry.getValue()));
            assertFalse(schema.path("properties").has("toolContext"));
        }
    }
}
