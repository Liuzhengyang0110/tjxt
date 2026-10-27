package com.tianji.aigc.controller;

import com.tianji.aigc.decision.service.RouteDecisionService;
import com.tianji.aigc.service.impl.AgentServiceImpl;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerSseTest {

    @Test
    void routeFailureReturnsDataAndStopInSseInsteadOfEmptyHttp500() throws Exception {
        RouteDecisionService routing = mock(RouteDecisionService.class);
        when(routing.decide("你好", "s1")).thenThrow(new IllegalStateException("Invalid LLM route result"));
        var mvc = MockMvcBuilders.standaloneSetup(
                        new ChatController(new AgentServiceImpl(routing, List.of())))
                .setControllerAdvice(new CommonExceptionAdvice())
                .build();

        var result = mvc.perform(post("/chat")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"你好\",\"sessionId\":\"s1\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("\"eventType\":1001")))
                .andExpect(content().string(containsString("\"eventType\":1002")));
    }
}
