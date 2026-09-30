package com.tianji.aigc.decision.context;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class RoutingTextSanitizer {
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern LONG_NUMBER =
            Pattern.compile("(?<!\\d)\\d{8,}(?!\\d)");

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String result = PHONE.matcher(text).replaceAll("<PHONE>");
        result = EMAIL.matcher(result).replaceAll("<EMAIL>");
        result = ID_CARD.matcher(result).replaceAll("<ID_CARD>");
        return LONG_NUMBER.matcher(result).replaceAll("<LONG_ID>");
    }
}
