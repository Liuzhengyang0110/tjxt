package com.tianji.aigc.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianji.aigc.service.ChatService;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteBenchmarkTest {
    @TempDir Path temp;
    Path root(){return Path.of("").toAbsolutePath().getParent();}
    RouteBenchmark.Config config(String mode) throws Exception {
        return new RouteBenchmark.Config(root(),RouteBenchmark.JSON.readTree("{\"runId\":\"unit-test\",\"mode\":\""+mode+"\",\"warmupsPerVariantPerRound\":0,\"interCallDelayMs\":0}"));
    }
    JsonNode sample() throws Exception {return RouteBenchmark.JSON.readTree("{\"id\":\"route-0067-a\",\"question\":\"这个多少钱\",\"expectedAgent\":\"CONSULT\",\"history\":[{\"role\":\"user\",\"content\":\"原始用户\"},{\"role\":\"assistant\",\"content\":\"原始助手\"}]}");}
    Map<String,Object> collect(String variant,int index) throws Exception {
        RouteBenchmark.Config c=config("SYNTHETIC");RouteBenchmark.Counts counts=new RouteBenchmark.Counts();BenchmarkRuntime runtime=BenchmarkRuntime.create(c,variant,counts);
        Map<String,Object> e=RouteBenchmark.entry(c,1,sample(),variant,false,"route-0067-a","AB",index);
        Map<String,Object> result=RouteBenchmark.collect(c,e,sample(),runtime,counts);
        assertTrue(runtime.repository.findConversationIds().isEmpty()); assertNull(UserContext.getUser());
        assertNotNull(result.get("latencyMs"));assertTrue((Double)result.get("latencyMs")>=0);
        return result;
    }
    @Test void llmPrimaryUsesRealAgentAndAdvisor() throws Exception {Map<String,Object> r=collect(RouteBenchmark.A,0);assertEquals("LLM_PRIMARY",r.get("source"));assertEquals(1L,r.get("llmCallCount"));assertEquals(0L,r.get("jevCallCount"));}
    @Test void jevAcceptedWithoutLlm() throws Exception {Map<String,Object> r=collect(RouteBenchmark.B,0);assertEquals("JEV",r.get("source"));assertEquals(0L,r.get("llmCallCount"));assertEquals(1L,r.get("jevCallCount"));}
    @Test void lowConfidenceFallbackCountsBoth() throws Exception {Map<String,Object> r=collect(RouteBenchmark.B,1);assertEquals("LOW_CONFIDENCE",r.get("fallbackReason"));assertEquals(1L,r.get("llmCallCount"));assertEquals(1L,r.get("jevCallCount"));}
    @Test void timeoutFallback() throws Exception {assertEquals("TIMEOUT",collect(RouteBenchmark.B,2).get("fallbackReason"));}
    @Test void rateLimitFallback() throws Exception {assertEquals("RATE_LIMITED",collect(RouteBenchmark.B,3).get("fallbackReason"));}
    @Test void invalidOutputFallback() throws Exception {assertEquals("INVALID_RESPONSE",collect(RouteBenchmark.B,4).get("fallbackReason"));}
    @Test void failedFallbackIsSafeDefault() throws Exception {Map<String,Object> r=collect(RouteBenchmark.B,6);assertEquals("SAFE_DEFAULT",r.get("status"));assertEquals(1L,r.get("llmCallCount"));assertEquals(1L,r.get("jevCallCount"));}
    @Test void primaryFailureIsSafeDefault() throws Exception {assertEquals("SAFE_DEFAULT",collect(RouteBenchmark.A,6).get("status"));}
    @Test void scheduleStableIndependentWarmupKeys() throws Exception {
        RouteBenchmark.Config c=new RouteBenchmark.Config(root(),RouteBenchmark.JSON.readTree("{\"runId\":\"schedule\",\"mode\":\"DRY_RUN\",\"rounds\":3,\"warmupsPerVariantPerRound\":10}"));
        List<JsonNode> samples=RouteBenchmark.lines(root().resolve("docs/Jev改造/eval/route-eval-v1.jsonl"));
        List<Map<String,Object>> first=RouteBenchmark.schedule(c,samples,samples.subList(0,20));
        assertEquals(2538,first.size());assertEquals(first,RouteBenchmark.schedule(c,samples,samples.subList(0,20)));
        assertEquals(first.size(),first.stream().map(RouteBenchmark::key).distinct().count());
    }
    @Test void liveFailsClosedBeforeCreatingRuntime() throws Exception {assertThrows(IllegalArgumentException.class,()->config("LIVE").validateLive(40));}
    @Test void logicalAndHttpCapsAreHard() throws Exception {
        RouteBenchmark.Config c=new RouteBenchmark.Config(root(),RouteBenchmark.JSON.readTree("{\"runId\":\"cap\",\"mode\":\"LIVE\",\"limits\":{\"jevLogicalCalls\":1,\"llmLogicalCalls\":1,\"httpAttempts\":1}}"));
        RouteBenchmark.Counts counts=new RouteBenchmark.Counts();counts.call(c,"jev");counts.http(c);
        assertThrows(IllegalStateException.class,()->counts.call(c,"jev"));assertThrows(IllegalStateException.class,()->counts.http(c));assertEquals(1,counts.totalHttp);
    }
    @Test void optimizationPreservesHistory() {
        BenchmarkMemory m=new BenchmarkMemory();List<Message> history=List.of(new UserMessage("历史"),new AssistantMessage("历史答复"));
        List<Message> all=new ArrayList<>(history);all.add(new UserMessage("路由问题"));all.add(new AssistantMessage("CONSULT"));m.saveAll("only",all);m.optimization("only");assertEquals(history,m.findByConversationId("only"));
    }
    @Test void routeContractAppendsReviewedDynamicPrompt() {String finalPrompt=BenchmarkRuntime.finalPrompt("真实快照");assertTrue(finalPrompt.startsWith("真实快照\n\n"));assertTrue(finalPrompt.contains("路由接口输出协议"));}
    @Test void rejectsRunIdPathTraversal() {assertThrows(IllegalArgumentException.class,()->new RouteBenchmark.Config(root(),RouteBenchmark.JSON.readTree("{\"runId\":\"../escape\"}")));}
    @Test void endpointRejectsCredentialsAndPlainHttp() {assertThrows(IllegalArgumentException.class,()->BenchmarkRuntime.endpoint("https://user:secret@example.com"));assertThrows(IllegalArgumentException.class,()->BenchmarkRuntime.endpoint("http://example.com"));}
    @Test void emptyHistoryAndClockEncloseBothSerialStages() throws Exception {
        RouteBenchmark.Config c=config("SYNTHETIC");RouteBenchmark.Counts counts=new RouteBenchmark.Counts();BenchmarkRuntime runtime=BenchmarkRuntime.create(c,RouteBenchmark.B,counts);
        JsonNode s=RouteBenchmark.JSON.readTree("{\"id\":\"route-0001\",\"question\":\"问题\",\"expectedAgent\":\"CONSULT\",\"history\":[]}");
        int[] calls={0};java.util.function.LongSupplier clock=()->{if(calls[0]++==0){assertEquals(0,counts.totalJev);assertEquals(0,counts.totalLlm);return 0;}assertEquals(1,counts.totalJev);assertEquals(1,counts.totalLlm);return 123400000;};
        Map<String,Object> r=RouteBenchmark.collect(c,RouteBenchmark.entry(c,1,s,RouteBenchmark.B,false,"route-0001","AB",1),s,runtime,counts,clock);
        assertEquals(123.4,r.get("latencyMs"));assertTrue(runtime.repository.findConversationIds().isEmpty());assertEquals(2,calls[0]);
    }
    @Test void stoppingBeforeCancellationPreventsInterruptedJevFromStartingFallback() throws Exception {
        RouteBenchmark.Config c=config("SYNTHETIC");RouteBenchmark.Counts counts=new RouteBenchmark.Counts();BenchmarkRuntime runtime=BenchmarkRuntime.create(c,RouteBenchmark.B,counts);
        CountDownLatch entered=new CountDownLatch(1);counts.syntheticBeforeJev=()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new com.tianji.aigc.decision.exception.JevClientException(com.tianji.aigc.decision.model.RouteFallbackReason.TIMEOUT,"Synthetic interrupted transport");}};
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            JsonNode s=sample();Future<?> f=worker.submit(()->{try{return RouteBenchmark.collect(c,RouteBenchmark.entry(c,1,s,RouteBenchmark.B,false,"route-0067-a","AB",0),s,runtime,counts);}catch(Exception ex){throw new RuntimeException(ex);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));counts.stop("REQUEST_UNKNOWN_STOPPED_NO_NEW_CALLS");f.cancel(true);worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));
            assertEquals(1,counts.totalJev);assertEquals(0,counts.totalLlm);assertTrue(runtime.repository.findConversationIds().isEmpty());
            counts.reset();assertThrows(IllegalStateException.class,()->counts.call(c,"llm"));assertThrows(IllegalStateException.class,()->counts.http(c));
        } finally {worker.shutdownNow();}
    }
    @Test void dryRunNeverConstructsModelAndRejectsSameRunDirectory() throws Exception {
        Path cfg=temp.resolve("dry.json");String id="jlc02-unit-dry-"+UUID.randomUUID();
        RouteBenchmark.write(cfg,RouteBenchmark.map("mode","DRY_RUN","runId",id,"warmupsPerVariantPerRound",0,"llm",RouteBenchmark.map("model","UNCONFIGURED")));
        Path out=RouteBenchmark.run(root(),cfg);assertTrue(Files.exists(out.resolve("manifest.json")));assertEquals(40,RouteBenchmark.lines(out.resolve("schedule.jsonl")).size());assertEquals(0,Files.size(out.resolve("requests.jsonl")));
        assertThrows(FileAlreadyExistsException.class,()->RouteBenchmark.run(root(),cfg));
    }
    @Test void actualMockHttpAttemptsShareCapAcrossVendorsAndSdkRetries() throws Exception {
        RouteBenchmark.Config c=new RouteBenchmark.Config(root(),RouteBenchmark.JSON.readTree("{\"runId\":\"http-cap\",\"mode\":\"LIVE\",\"limits\":{\"httpAttempts\":2}}"));
        RouteBenchmark.Counts counts=new RouteBenchmark.Counts();SpringAiRetryAutoConfiguration retryFactory=new SpringAiRetryAutoConfiguration();SpringAiRetryProperties props=new SpringAiRetryProperties();props.setMaxAttempts(3);props.getBackoff().setInitialInterval(java.time.Duration.ofMillis(1));
        RestClient.Builder first=RestClient.builder().defaultStatusHandler(retryFactory.responseErrorHandler(props)).requestInterceptor((r,b,e)->{counts.http(c);return e.execute(r,b);});
        MockRestServiceServer jevMock=MockRestServiceServer.bindTo(first).build();jevMock.expect(requestTo("https://fixture.invalid/jev")).andRespond(withSuccess("jev",MediaType.TEXT_PLAIN));
        RestClient firstClient=first.build();assertEquals("jev",firstClient.get().uri("https://fixture.invalid/jev").retrieve().body(String.class));
        RestClient.Builder second=RestClient.builder().defaultStatusHandler(retryFactory.responseErrorHandler(props)).requestInterceptor((r,b,e)->{counts.http(c);return e.execute(r,b);});
        MockRestServiceServer llmMock=MockRestServiceServer.bindTo(second).build();llmMock.expect(requestTo("https://fixture.invalid/llm")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("synthetic"));RestClient secondClient=second.build();
        assertThrows(RuntimeException.class,()->retryFactory.retryTemplate(props).execute(context->secondClient.get().uri("https://fixture.invalid/llm").retrieve().body(String.class)));
        assertEquals(2,counts.totalHttp);assertEquals("HTTP_ATTEMPT_CAP",counts.stopReason);jevMock.verify();llmMock.verify();
    }
    @Test void ordinaryInvalidModelRouteIsRecordedWithoutAbortingTheExperiment() throws Exception {
        RouteBenchmark.Config c=config("SYNTHETIC");RouteBenchmark.Counts counts=new RouteBenchmark.Counts();
        BenchmarkRuntime runtime=BenchmarkRuntime.create(c,RouteBenchmark.A,counts);
        Map<String,Object> entry=RouteBenchmark.entry(c,1,sample(),RouteBenchmark.A,false,"route-0067-a","AB",5);
        Map<String,Object> record=RouteBenchmark.collect(c,entry,sample(),runtime,counts);
        assertEquals("SAFE_DEFAULT",record.get("status"));
        assertEquals("INVALID_LLM_ROUTE_RESULT",counts.llmError);
        assertFalse(RouteBenchmark.systemicFailure(record,counts,false));
        counts.llmError="ResourceAccessException";
        assertTrue(RouteBenchmark.systemicFailure(record,counts,false));
        assertTrue(RouteBenchmark.systemicFailure(record,counts,true));
    }
    @Test void officialRetryDoesNotRetryNonTransientFailure() {
        SpringAiRetryProperties props=new SpringAiRetryProperties();props.setMaxAttempts(3);int[] attempts={0};
        assertThrows(IllegalStateException.class,()->new SpringAiRetryAutoConfiguration().retryTemplate(props).execute(context->{attempts[0]++;throw new IllegalStateException("synthetic budget failure");}));assertEquals(1,attempts[0]);
    }
}
