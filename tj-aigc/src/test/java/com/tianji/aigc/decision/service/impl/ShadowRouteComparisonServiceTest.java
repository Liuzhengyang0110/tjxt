package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.decision.config.ShadowExecutorConfig;
import com.tianji.aigc.decision.context.RoutingState;
import com.tianji.aigc.decision.exception.JevClientException;
import com.tianji.aigc.decision.metrics.RouteMetrics;
import com.tianji.aigc.decision.metrics.RouteMetrics.Mode;
import com.tianji.aigc.decision.model.RouteDecision;
import com.tianji.aigc.decision.model.RouteDecisionSource;
import com.tianji.aigc.decision.model.RouteFallbackReason;
import com.tianji.aigc.enums.AgentTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShadowRouteComparisonServiceTest {
    private final RoutingState state = new RoutingState("问题", List.of(), Map.of());
    private final RouteDecision actual = decision(AgentTypeEnum.CONSULT, RouteDecisionSource.LLM_PRIMARY);

    @Test
    void executesJevComparisonOnDedicatedAsyncPool() throws InterruptedException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ShadowExecutorConfig.class, AsyncTestConfig.class);
            context.refresh();
            JevRouteDecisionService jevService = context.getBean(JevRouteDecisionService.class);
            ShadowRouteComparisonService shadowService = context.getBean(ShadowRouteComparisonService.class);
            RouteMetrics routeMetrics = context.getBean(RouteMetrics.class);
            CountDownLatch called = new CountDownLatch(1);
            AtomicReference<String> workerName = new AtomicReference<>();
            when(jevService.decide(state)).thenAnswer(invocation -> {
                workerName.set(Thread.currentThread().getName());
                called.countDown();
                return decision(AgentTypeEnum.RECOMMEND, RouteDecisionSource.JEV);
            });

            shadowService.compareAsync("request-id", "session-hash", state, actual);

            assertTrue(called.await(3, TimeUnit.SECONDS));
            assertTrue(workerName.get().startsWith("jev-shadow-"));
            verify(jevService).decide(state);
            verify(routeMetrics).recordRequest(Mode.SHADOW);
            verify(routeMetrics).recordDisagreement(AgentTypeEnum.RECOMMEND, AgentTypeEnum.CONSULT);
        }
    }

    @Test
    void swallowsJevFailureInsideShadowWorker() {
        JevRouteDecisionService jevService = mock(JevRouteDecisionService.class);
        RouteMetrics routeMetrics = mock(RouteMetrics.class);
        when(jevService.decide(state)).thenThrow(
                new JevClientException(RouteFallbackReason.TIMEOUT, "Jev timed out"));

        new ShadowRouteComparisonService(jevService, routeMetrics)
                .compareAsync("request-id", "session-hash", state, actual);

        verify(jevService).decide(state);
        verify(routeMetrics).recordError(RouteFallbackReason.TIMEOUT);
    }

    @Test
    void executorUsesBoundedQueueAndRejectsOverflow() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new ShadowExecutorConfig().jevShadowExecutor();
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(4, executor.getMaxPoolSize());
            assertEquals(200, executor.getQueueCapacity());
            assertEquals("jev-shadow-", executor.getThreadNamePrefix());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,
                    executor.getThreadPoolExecutor().getRejectedExecutionHandler());
        } finally {
            executor.shutdown();
        }
    }

    private RouteDecision decision(AgentTypeEnum agent, RouteDecisionSource source) {
        return new RouteDecision(agent, source == RouteDecisionSource.JEV ? 0.9 : null,
                Map.of(), source, RouteFallbackReason.NONE, null);
    }

    @Configuration
    @EnableAsync
    static class AsyncTestConfig {
        @Bean
        JevRouteDecisionService jevRouteDecisionService() {
            return mock(JevRouteDecisionService.class);
        }

        @Bean
        ShadowRouteComparisonService shadowRouteComparisonService(JevRouteDecisionService jevService) {
            return new ShadowRouteComparisonService(jevService, routeMetrics());
        }

        @Bean
        RouteMetrics routeMetrics() {
            return mock(RouteMetrics.class);
        }
    }
}
