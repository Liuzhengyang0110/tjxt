package com.tianji.aigc.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianji.aigc.config.DashScopeAudioProperties;
import com.tianji.aigc.vo.AsrVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashScopeAudioServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldUploadBrowserWebmAudioAndParseResult() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/asr", exchange -> {
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("disable", exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));

            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            assertEquals("qwen-audio-3.0-asr-flash", request.path("model").asText());
            assertEquals("webm", request.path("parameters").path("format").asText());
            assertEquals("zh", request.path("parameters").path("language_hints").path(0).asText());
            String dataUri = request.path("input").path("messages").path(0)
                    .path("content").path(0).path("input_audio").path("data").asText();
            assertTrue(dataUri.startsWith("data:audio/webm;base64,"));

            byte[] response = """
                    {"output":{"text":"歡迎來到天機學堂。"},"usage":{"duration":2},"request_id":"req-1"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DashScopeAudioProperties properties = new DashScopeAudioProperties();
        properties.setKey("test-key");
        properties.getAsr().setUrl("http://localhost:" + server.getAddress().getPort() + "/asr");
        DashScopeAudioServiceImpl service = new DashScopeAudioServiceImpl(properties, objectMapper);
        MockMultipartFile file = new MockMultipartFile(
                "file", "recording.webm", "audio/webm", new byte[]{1, 2, 3, 4});

        AsrVO result = service.asr(file);

        assertEquals("欢迎来到天机学堂。", result.getText());
        assertEquals("req-1", result.getRequestId());
        assertEquals(2L, result.getDurationSeconds());
    }
}
