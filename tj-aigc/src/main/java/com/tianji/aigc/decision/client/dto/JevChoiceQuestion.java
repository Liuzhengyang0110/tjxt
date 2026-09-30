package com.tianji.aigc.decision.client.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public record JevChoiceQuestion(
        String type,
        Object instructions,
        Map<String, Object> criteria
) {
    public static JevChoiceQuestion routeQuestion() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("RECOMMEND",
                "Course recommendation / 根据用户目标、基础、预算或时间，推荐平台中的具体课程");
        criteria.put("CONSULT",
                "Course consultation / 咨询平台具体课程的价格、内容、老师、适合人群或状态");
        criteria.put("BUY",
                "Course purchase / 用户明确要购买、结算或预下单已经选定的课程；仅问价格不属于购买");
        criteria.put("KNOWLEDGE",
                "Knowledge explanation / 通用知识、编程概念、学习路线或问题讲解，不要求推荐平台具体课程");

        return new JevChoiceQuestion(
                "choice",
                "根据最新用户消息和近期上下文，选择最适合处理当前请求的一个 Agent。只判断主要意图。",
                criteria
        );
    }
}
