package com.tianji.aigc.decision.service;

import com.tianji.aigc.decision.model.RouteDecision;

public interface RouteDecisionService {
    RouteDecision decide(String question, String sessionId);
}
