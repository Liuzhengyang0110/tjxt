package com.tianji.aigc.config;

import com.tianji.common.constants.Constant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpringAIConfigTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldSetSpringAiFlagOnlyWhileRetryTemplateIsExecuting() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RetryTemplate retryTemplate = new RetryTemplate();

        new SpringAIConfig().customizeRetryTemplate(retryTemplate);
        retryTemplate.execute(context -> {
            assertEquals(Constant.SPRING_AI_FLAG, request.getAttribute(Constant.SPRING_AI_ATTR));
            return null;
        });

        assertNull(request.getAttribute(Constant.SPRING_AI_ATTR));
    }
}
