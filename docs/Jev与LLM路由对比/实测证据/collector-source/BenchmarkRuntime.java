package com.tianji.aigc.benchmark;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.tianji.aigc.agent.RouteAgent;
import com.tianji.aigc.config.*;
import com.tianji.aigc.decision.client.JevDecisionClient;
import com.tianji.aigc.decision.client.dto.*;
import com.tianji.aigc.decision.config.*;
import com.tianji.aigc.decision.context.*;
import com.tianji.aigc.decision.exception.JevClientException;
import com.tianji.aigc.decision.metrics.RouteMetrics;
import com.tianji.aigc.decision.model.*;
import com.tianji.aigc.decision.service.impl.*;
import com.tianji.aigc.service.ChatSessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.*;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import java.lang.reflect.Proxy;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

/** Production routing composition with persistence-only adapters; fake transport only in SYNTHETIC. */
final class BenchmarkRuntime {
    static final String SYNTHETIC_PROMPT="SYNTHETIC fixture prompt. This is not the deployed baseline.";
    final ChatMemory memory;
    final BenchmarkMemory repository;
    final FallbackRouteDecisionService service;
    final Object clientOptions;
    private final int[] fixtureIndex;
    private BenchmarkRuntime(ChatMemory m,BenchmarkMemory r,FallbackRouteDecisionService s,int[] i,Object options){memory=m;repository=r;service=s;fixtureIndex=i;clientOptions=options;}
    void fixture(int ordinal){fixtureIndex[0]=ordinal;}
    static String finalPrompt(String prompt) {
        SystemPromptConfig config=new SystemPromptConfig(null,null);config.getRouteAgentSystemMessage().set(prompt);
        return new RouteAgent(config).systemMessage();
    }
    static Object safeConfig(RouteBenchmark.Config c) {
        JsonNode n=c.node;
        return RouteBenchmark.map("llmModel",c.mode.equals("SYNTHETIC")?"synthetic-fixed-llm":n.path("llm").path("model").asText(null),
            "llm",whitelist(n.path("llm"),"model","temperature","topP","topK","maxTokens","enableThinking","enableSearch","seed","retryMaxAttempts","retryBackoffMs","connectTimeoutMs","readTimeoutMs","proxyEnabled","proxyHost","proxyPort"),
            "jev",whitelist(n.path("jev"),"model","connectTimeoutMs","readTimeoutMs","proxyEnabled","proxyHost","proxyPort"),
            "jevEndpointHost",host(n.path("jev").path("baseUrl").asText("https://api.typesafe.ai")),"llmEndpointHost",host(n.path("llm").path("baseUrl").asText("https://dashscope.aliyuncs.com")),
            "threshold",0.85,"thresholdOverrides",Map.of(),"memoryMaxMessages",100,"jevContextMaxChars",4000,"jevRecentMessageLimit",8,
            "routeTimeoutMs",c.timeout(),"retryPolicy","SpringAiRetryAutoConfiguration retryTemplate/responseErrorHandler; TransientAiException only; Jev no application retry",
            "retryEffective",retrySummary(n.path("llm")),
            "llmOptionSemantics","llm contains explicitly configured values; absent/null options are not asserted server defaults. Client defaults serialized in runtimeClientOptions when model constructed; server defaults unknown.",
            "currencyHardCap",false,"tokenHardCap",false,"httpAttemptObservation",c.mode.equals("LIVE")?"synchronous RestClient interceptor":"NOT_APPLICABLE_SYNTHETIC");
    }
    static Object whitelist(JsonNode node,String... keys) {
        Map<String,Object> out=new LinkedHashMap<>();for(String key:keys)if(node.has(key))out.put(key,RouteBenchmark.JSON.convertValue(node.get(key),Object.class));return out;
    }
    static String host(String url) {return URI.create(url).getHost();}
    static SpringAiRetryProperties retryProperties(JsonNode l) {
        SpringAiRetryProperties p=new SpringAiRetryProperties();
        p.setMaxAttempts(l.path("retryMaxAttempts").asInt(10));
        p.getBackoff().setInitialInterval(Duration.ofMillis(l.path("retryInitialIntervalMs").asLong(2000)));
        p.getBackoff().setMultiplier(l.path("retryMultiplier").asInt(5));
        p.getBackoff().setMaxInterval(Duration.ofMillis(l.path("retryMaxIntervalMs").asLong(180000)));
        return p;
    }
    static Object retrySummary(JsonNode l) {
        SpringAiRetryProperties p=retryProperties(l);
        return RouteBenchmark.map("factory","SpringAiRetryAutoConfiguration 1.0.0","maxAttempts",p.getMaxAttempts(),"initialIntervalMs",p.getBackoff().getInitialInterval().toMillis(),"multiplier",p.getBackoff().getMultiplier(),"maxIntervalMs",p.getBackoff().getMaxInterval().toMillis(),"onClientErrors",p.isOnClientErrors(),"onHttpCodes",p.getOnHttpCodes(),"excludeOnHttpCodes",p.getExcludeOnHttpCodes(),"differsFromDefault",p.getMaxAttempts()!=10||p.getBackoff().getInitialInterval().toMillis()!=2000||p.getBackoff().getMultiplier()!=5||p.getBackoff().getMaxInterval().toMillis()!=180000);
    }
    static String endpoint(String url) {
        URI u=URI.create(url);
        if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||u.getHost()==null)throw new IllegalArgumentException("HTTPS endpoint without credentials/query required");return url;
    }
    static JdkClientHttpRequestFactory factory(JsonNode node) {
        HttpClient.Builder b=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(node.path("connectTimeoutMs").asLong(500)));
        if(node.path("proxyEnabled").asBoolean()) b.proxy(ProxySelector.of(new InetSocketAddress(node.path("proxyHost").asText("127.0.0.1"),node.path("proxyPort").asInt(7890))));
        JdkClientHttpRequestFactory f=new JdkClientHttpRequestFactory(b.build());f.setReadTimeout(Duration.ofMillis(node.path("readTimeoutMs").asLong(1200)));return f;
    }
    static BenchmarkRuntime create(RouteBenchmark.Config c,String variant,RouteBenchmark.Counts counts) throws Exception {
        int[] ordinal={0}; SpringAIConfig spring=new SpringAIConfig();ReflectionTestUtils.setField(spring,"maxMessages",100);
        BenchmarkMemory repository=new BenchmarkMemory();ChatMemory memory=spring.chatMemory(repository);
        JsonNode j=c.node.path("jev"),l=c.node.path("llm");JevProperties jp=new JevProperties();jp.setEnabled(true);
        jp.setModel(j.path("model").asText("jev-1.13.0"));jp.setConnectTimeout(Duration.ofMillis(j.path("connectTimeoutMs").asLong(500)));jp.setReadTimeout(Duration.ofMillis(j.path("readTimeoutMs").asLong(1200)));
        jp.getProxy().setEnabled(j.path("proxyEnabled").asBoolean());jp.getProxy().setHost(j.path("proxyHost").asText("127.0.0.1"));jp.getProxy().setPort(j.path("proxyPort").asInt(7890));
        JevDecisionClient realJev=null;ChatModel model;Object clientOptions=null;
        if(c.mode.equals("LIVE")) {
            jp.setApiKey(System.getenv("JEV_API_KEY"));jp.setBaseUrl(endpoint(j.path("baseUrl").asText("https://api.typesafe.ai")));
            RestClient jevHttp=new JevClientConfig().jevRestClient(jp).mutate().requestInterceptor((request,body,execution)->{counts.http(c);return execution.execute(request,body);}).build();
            realJev=new JevDecisionClient(jevHttp,jp);
            RestClient.Builder rest=RestClient.builder().requestFactory(factory(l)).requestInterceptor((request,body,execution)->{counts.http(c);return execution.execute(request,body);});
            SpringAiRetryAutoConfiguration retryFactory=new SpringAiRetryAutoConfiguration();SpringAiRetryProperties retryProperties=retryProperties(l);
            DashScopeApi api=DashScopeApi.builder().apiKey(System.getenv("DASHSCOPE_API_KEY")).baseUrl(endpoint(l.path("baseUrl").asText("https://dashscope.aliyuncs.com"))).restClientBuilder(rest).responseErrorHandler(retryFactory.responseErrorHandler(retryProperties)).build();
            DashScopeChatOptions options=DashScopeChatOptions.builder().withModel(l.path("model").asText())
                .withTemperature(l.has("temperature")?l.get("temperature").asDouble():null).withTopP(l.has("topP")?l.get("topP").asDouble():null)
                .withTopK(l.has("topK")?l.get("topK").asInt():null).withMaxToken(l.has("maxTokens")?l.get("maxTokens").asInt():null)
                .withEnableThinking(l.has("enableThinking")?l.get("enableThinking").asBoolean():null).withEnableSearch(l.has("enableSearch")?l.get("enableSearch").asBoolean():null)
                .withSeed(l.has("seed")?l.get("seed").asInt():null).build();
            RetryTemplate retry=retryFactory.retryTemplate(retryProperties);
            spring.customizeRetryTemplate(retry);
            ChatModel realModel=DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(options).retryTemplate(retry).build();
            DashScopeChatOptions effective=(DashScopeChatOptions)realModel.getDefaultOptions();
            clientOptions=RouteBenchmark.map("model",effective.getModel(),"temperature",effective.getTemperature(),"topP",effective.getTopP(),"topK",effective.getTopK(),"maxTokens",effective.getMaxTokens(),"enableThinking",effective.getEnableThinking(),"enableSearch",effective.getEnableSearch(),"seed",effective.getSeed(),"serverDefaults","UNKNOWN_WHEN_NULL");
            model=new ChatModel(){@Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions(){return realModel.getDefaultOptions();}@Override public ChatResponse call(Prompt prompt){
                ChatResponse response=realModel.call(prompt);
                if(response!=null && response.getMetadata()!=null) {
                    String responseModel=response.getMetadata().getModel();counts.responseModel=responseModel==null||responseModel.isBlank()?null:responseModel;
                    if(response.getMetadata().getUsage()!=null&&!response.getMetadata().getUsage().getClass().getSimpleName().equals("EmptyUsage")) counts.usage=RouteBenchmark.map("promptTokens",response.getMetadata().getUsage().getPromptTokens(),"completionTokens",response.getMetadata().getUsage().getCompletionTokens(),"totalTokens",response.getMetadata().getUsage().getTotalTokens());
                }
                return response;
            }};
        } else {
            model=new ChatModel(){@Override public ChatResponse call(Prompt prompt){
                if(ordinal[0]%7==6) throw new IllegalStateException("Synthetic LLM failure");
                if(ordinal[0]%7==5) return new ChatResponse(List.of(new Generation(new AssistantMessage("SYNTHETIC_INVALID_ROUTE"))));
                return new ChatResponse(List.of(new Generation(new AssistantMessage("CONSULT"))));
            }};
        }
        SystemPromptConfig promptConfig=new SystemPromptConfig(null,null);
        promptConfig.getRouteAgentSystemMessage().set(c.mode.equals("LIVE")?Files.readString(c.local("routePromptPath")):SYNTHETIC_PROMPT);
        RouteAgent routeAgent=new RouteAgent(promptConfig);
        ChatSessionService noOp=(ChatSessionService)Proxy.newProxyInstance(ChatSessionService.class.getClassLoader(),new Class<?>[]{ChatSessionService.class},(proxy,method,args)->{
            if(method.getName().equals("update")&&method.getParameterCount()==3)return null;
            if(method.getName().equals("toString"))return "BenchmarkNoOpSession";
            throw new UnsupportedOperationException("Only route session update is permitted");
        });
        ReflectionTestUtils.setField(routeAgent,"chatSessionService",noOp);ReflectionTestUtils.setField(routeAgent,"chatMemory",memory);
        ReflectionTestUtils.setField(routeAgent,"chatClient",spring.chatClient(model,spring.loggerAdvisor(),spring.messageChatMemoryAdvisor(memory),spring.recordOptimizationAdvisor(repository)));
        LlmRouteDecisionService llm=new LlmRouteDecisionService(routeAgent){@Override public RouteDecision decide(String question,String session){
            counts.call(c,"llm");try{return super.decide(question,session);}catch(RuntimeException e){
                counts.llmError=e instanceof IllegalStateException && "Invalid LLM route result".equals(e.getMessage())
                        ? "INVALID_LLM_ROUTE_RESULT" : e.getClass().getSimpleName();
                throw e;
            }
        }};
        RoutingContextBuilder context=new RoutingContextBuilder(memory,jp,new RoutingTextSanitizer());JevDecisionClient delegate=realJev;
        JevDecisionClient observedJev=new JevDecisionClient(null,jp){@Override public JevSystemOneResponse evaluate(RoutingState state){
            counts.call(c,"jev");
            try {
                JevSystemOneResponse response;
                if(c.mode.equals("LIVE"))response=delegate.evaluate(state);
                else {
                    counts.syntheticBeforeJev.run();
                    int f=ordinal[0]%7;
                    if(f==2)throw new JevClientException(RouteFallbackReason.TIMEOUT,"Synthetic timeout");
                    if(f==3)throw new JevClientException(RouteFallbackReason.RATE_LIMITED,"Synthetic HTTP 429");
                    if(f==6)throw new JevClientException(RouteFallbackReason.TIMEOUT,"Synthetic timeout with failed fallback");
                    response=new JevSystemOneResponse("synthetic-fixed-jev",Map.of("route_agent",new JevChoiceAnswer("choice",f==4?"INVALID":"CONSULT",f==1?0.50:0.95,Map.of("CONSULT",1.0))),null);
                }
                if(response!=null){counts.jevModel=response.model();JevChoiceAnswer answer=response.answers()==null?null:response.answers().get("route_agent");if(answer!=null)counts.jevConfidence=answer.confidence();}
                return response;
            }catch(JevClientException ex){counts.jevError=ex.getReason().name();throw ex;}
            catch(RuntimeException ex){counts.jevError=ex.getClass().getSimpleName();throw ex;}
        }};
        JevRouteDecisionService jev=new JevRouteDecisionService(context,observedJev){@Override public RouteDecision decide(RoutingState state){try{return super.decide(state);}catch(JevClientException ex){counts.jevError=ex.getReason().name();throw ex;}}};
        RouteProperties rp=new RouteProperties();rp.setProvider(variant.equals(RouteBenchmark.A)?RouteProvider.LLM:RouteProvider.JEV);rp.setShadowEnabled(false);rp.setFallbackEnabled(true);rp.setDefaultConfidenceThreshold(0.85);
        RouteMetrics metrics=new RouteMetrics(new SimpleMeterRegistry());
        return new BenchmarkRuntime(memory,repository,new FallbackRouteDecisionService(jev,llm,context,rp,jp,new ShadowRouteComparisonService(jev,metrics),metrics),ordinal,clientOptions);
    }
}
