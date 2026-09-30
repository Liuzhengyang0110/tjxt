package com.tianji.aigc.decision.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingTextSanitizerTest {
    private final RoutingTextSanitizer sanitizer = new RoutingTextSanitizer();

    @Test
    void keepsOrdinaryRoutingText() {
        assertEquals("推荐课程 1234，适合 Java 初学者", sanitizer.sanitize("推荐课程 1234，适合 Java 初学者"));
    }

    @Test
    void masksSensitiveTextButKeepsShortCourseNumbers() {
        String text = "手机 13800138000，邮箱 student@example.com，身份证 11010519491231002X，订单 123456789012，课程 1234";

        assertEquals("手机 <PHONE>，邮箱 <EMAIL>，身份证 <ID_CARD>，订单 <LONG_ID>，课程 1234",
                sanitizer.sanitize(text));
    }

    @Test
    void handlesMissingText() {
        assertEquals("", sanitizer.sanitize(null));
        assertEquals("", sanitizer.sanitize("  "));
    }
}
