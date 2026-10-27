package com.tianji.aigc.decision.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tianji.aigc.decision.config.JevClientConfig;
import com.tianji.aigc.decision.config.JevProperties;
import com.tianji.aigc.decision.context.RoutingMessage;
import com.tianji.aigc.decision.context.RoutingState;
import com.tianji.aigc.decision.exception.JevClientException;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevDecisionClientTest {
    private static final String RESPONSE_JSON = """
            {
              "model":"jev-1.13.0",
              "answers":{"route_agent":{
                "type":"choice",
                "choice":"CONSULT",
                "confidence":0.93,
                "probabilities":{"CONSULT":0.93,"RECOMMEND":0.04,"BUY":0.02,"KNOWLEDGE":0.01}
              }},
              "usage":{"input_tokens":42,"output_tokens":6}
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOfficialRequestAndParsesResponse() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendResponse(exchange, 200, RESPONSE_JSON);
        });

        var response = client(properties()).evaluate(state());

        assertEquals("/v1/systemone", path.get());
        assertEquals("Bearer test-key", authorization.get());
        assertTrue(contentType.get().startsWith("application/json"));
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertEquals("jev-1.13.0", request.path("model").asText());
        assertEquals("这个多少钱？", request.path("state").path("latest_user_message").asText());
        assertEquals("assistant", request.path("state").path("recent_context").path(0).path("role").asText());
        assertEquals("我推荐课程 A", request.path("state").path("recent_context").path(0).path("content").asText());
        assertEquals(4, request.path("state").path("available_agents").size());
        JsonNode question = request.path("questions").path("route_agent");
        assertEquals("choice", question.path("type").asText());
        assertEquals(4, question.path("criteria").size());
        for (String agent : List.of("RECOMMEND", "CONSULT", "BUY", "KNOWLEDGE")) {
            assertFalse(question.path("criteria").path(agent).asText().isBlank());
        }
        assertEquals("CONSULT", response.answers().get("route_agent").choice());
        assertEquals(0.93, response.answers().get("route_agent").confidence());
        assertEquals(42L, response.usage().inputTokens());
        assertEquals(6L, response.usage().outputTokens());
    }

    @Test
    void mapsHttpErrorsWithoutRetrying() throws IOException {
        AtomicInteger status = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            sendResponse(exchange, status.get(), "{}");
        });
        JevDecisionClient client = client(properties());
        Map<Integer, RouteFallbackReason> cases = Map.of(
                401, RouteFallbackReason.AUTH_FAILURE,
                403, RouteFallbackReason.AUTH_FAILURE,
                422, RouteFallbackReason.INVALID_REQUEST,
                429, RouteFallbackReason.RATE_LIMITED,
                451, RouteFallbackReason.REGION_UNAVAILABLE,
                529, RouteFallbackReason.OVERLOADED,
                500, RouteFallbackReason.SERVER_ERROR,
                418, RouteFallbackReason.UNKNOWN_ERROR
        );

        for (Map.Entry<Integer, RouteFallbackReason> entry : cases.entrySet()) {
            status.set(entry.getKey());
            JevClientException exception = assertThrows(JevClientException.class,
                    () -> client.evaluate(state()));
            assertEquals(entry.getValue(), exception.getReason());
            assertFalse(exception.getMessage().contains("test-key"));
        }
        assertEquals(cases.size(), requests.get());
    }

    @Test
    void sendsRequestViaConfiguredProxyWithoutChangingJvmDefault() throws IOException {
        AtomicReference<String> target = new AtomicReference<>();
        startServer(exchange -> {
            target.set(exchange.getRequestURI().toString());
            exchange.getRequestBody().readAllBytes();
            sendResponse(exchange, 200, RESPONSE_JSON);
        });
        ProxySelector defaultProxy = ProxySelector.getDefault();
        JevProperties properties = properties();
        // This origin cannot resolve; only the local fake proxy can answer the request.
        properties.setBaseUrl("http://jev-origin.invalid");
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost("127.0.0.1");
        properties.getProxy().setPort(server.getAddress().getPort());

        var response = client(properties).evaluate(state());

        assertEquals("http://jev-origin.invalid/v1/systemone", target.get());
        assertEquals("CONSULT", response.answers().get("route_agent").choice());
        assertSame(defaultProxy, ProxySelector.getDefault());
    }

    @Test
    void rejectsInvalidEnabledProxyBeforeCallingHttp() {
        JevProperties properties = properties();
        assertFalse(properties.getProxy().isEnabled());
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost(" ");
        assertThrows(IllegalArgumentException.class, () -> client(properties));

        properties.getProxy().setHost("127.0.0.1");
        for (int port : List.of(-1, 0, 65536)) {
            properties.getProxy().setPort(port);
            assertThrows(IllegalArgumentException.class, () -> client(properties));
        }
    }

    @Test
    void mapsReadTimeout() throws IOException {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(500);
                sendResponse(exchange, 200, RESPONSE_JSON);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client has already timed out and closed the connection.
            }
        });
        JevProperties properties = properties();
        properties.setReadTimeout(Duration.ofMillis(100));

        JevClientException exception = assertThrows(JevClientException.class,
                () -> client(properties).evaluate(state()));

        assertEquals(RouteFallbackReason.TIMEOUT, exception.getReason());
    }

    @Test
    void rejectsDisabledOrUnconfiguredClientBeforeCallingHttp() {
        JevProperties properties = properties();
        properties.setEnabled(false);
        assertEquals(RouteFallbackReason.JEV_DISABLED,
                assertThrows(JevClientException.class, () -> client(properties).evaluate(state())).getReason());

        properties.setEnabled(true);
        properties.setApiKey(" ");
        assertEquals(RouteFallbackReason.AUTH_FAILURE,
                assertThrows(JevClientException.class, () -> client(properties).evaluate(state())).getReason());
    }

    @Test
    void rejectsEmptyResponseBody() throws IOException {
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        JevClientException exception = assertThrows(JevClientException.class,
                () -> client(properties()).evaluate(state()));

        assertEquals(RouteFallbackReason.INVALID_RESPONSE, exception.getReason());
    }

    private JevProperties properties() {
        JevProperties properties = new JevProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        if (server != null) {
            properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        }
        return properties;
    }

    private JevDecisionClient client(JevProperties properties) {
        return new JevDecisionClient(new JevClientConfig().jevRestClient(properties), properties);
    }

    private RoutingState state() {
        return new RoutingState("这个多少钱？",
                List.of(new RoutingMessage("assistant", "我推荐课程 A")),
                Map.of("RECOMMEND", "推荐课程", "CONSULT", "咨询课程",
                        "BUY", "购买课程", "KNOWLEDGE", "知识讲解"));
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler);
        server.start();
        assertNotNull(server.getAddress());
    }

    private void sendResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
