package com.tianji.aigc.benchmark;

import com.fasterxml.jackson.databind.*;
import com.tianji.aigc.decision.model.*;
import com.tianji.aigc.service.ChatService;
import com.tianji.common.utils.UserContext;
import org.springframework.ai.chat.messages.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit test-scope collector; never bootstraps Spring, Redis, DB or Nacos. */
public final class RouteBenchmark {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SCHEMA = "route-benchmark-v1", A = "LLM_PRIMARY", B = "JEV_WITH_FALLBACK";
    static final String LEVEL = "ISOLATED_JAVA_ROUTE";
    static final Path DOC = Path.of("docs/Jev与LLM路由对比");
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> m = new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) m.put((String)pairs[i], pairs[i+1]);
        return m;
    }
    static String hash(Path p) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }
    static String hashText(String s) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
    static String now() { return Instant.now().toString(); }
    static String git(Path root,String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of("git","-C",root.toString()));command.addAll(Arrays.asList(args));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        if(process.waitFor()!=0)throw new IllegalStateException("Git identity unavailable");return output;
    }
    static List<JsonNode> lines(Path file) throws IOException {
        List<JsonNode> list = new ArrayList<>();
        for(String line: Files.readAllLines(file, StandardCharsets.UTF_8)) if(!line.isBlank()) list.add(JSON.readTree(line));
        return list;
    }
    static void write(Path p, Object value) throws IOException { Files.writeString(p, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n", StandardCharsets.UTF_8); }
    static void append(BufferedWriter w, Object value) throws IOException { w.write(JSON.writeValueAsString(value)); w.newLine(); w.flush(); }
    static String group(String id) { return id.replaceFirst("-[a-d]$", ""); }
    static String split(String id) throws Exception {
        byte[] h=MessageDigest.getInstance("SHA-256").digest(group(id).getBytes(StandardCharsets.UTF_8));
        long first=0; for(int i=0;i<4;i++) first=(first<<8)|(h[i]&255);
        return first%5==0?"holdout":"development";
    }
    static final class Config {
        final Path root; final JsonNode node; final String mode, runId, stage;
        Config(Path root, JsonNode node) {
            this.root=root.toAbsolutePath().normalize(); this.node=node;
            mode=node.path("mode").asText("DRY_RUN"); runId=node.path("runId").asText(); stage=node.path("stage").asText("smoke");
            if(!Set.of("DRY_RUN","SYNTHETIC","LIVE").contains(mode)) throw new IllegalArgumentException("Invalid mode");
            if(!runId.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("runId required: letters/numbers/_/- only");
            if(!Set.of("smoke","formal").contains(stage)) throw new IllegalArgumentException("stage must be smoke/formal");
            if(rounds()<1 || rounds()>100 || warmups()<0 || warmups()>100 || delay()<0 || timeout()<1) throw new IllegalArgumentException("Invalid schedule/timeouts");
        }
        int rounds(){return node.path("rounds").asInt(stage.equals("formal")?3:1);}
        int warmups(){return node.path("warmupsPerVariantPerRound").asInt(10);}
        long seed(){return node.path("seed").asLong(20261026);}
        long delay(){return node.path("interCallDelayMs").asLong(250);}
        long timeout(){return node.path("routeTimeoutMs").asLong(30000);}
        Path local(String name){return root.resolve(node.path(name).asText()).normalize();}
        long cap(String name){return node.path("limits").path(name).asLong(0);}
        void validateLive(int planned) throws Exception {
            if(!mode.equals("LIVE")) return;
            if(!Boolean.getBoolean("benchmark.live") || !node.path("authorization").path("approved").asBoolean()
                || node.path("authorization").path("reference").asText().isBlank()) throw new IllegalArgumentException("LIVE requires explicit CLI and approved authorization reference");
            for(String name:List.of("routeCalls","jevLogicalCalls","llmLogicalCalls","httpAttempts"))
                if(cap(name)<=0) throw new IllegalArgumentException("Missing positive cap: "+name);
            if(planned>cap("routeCalls")) throw new IllegalArgumentException("Plan exceeds approved route cap");
            if(node.path("llm").path("model").asText().isBlank() || node.path("llm").path("model").asText().contains("UNCONFIGURED")) throw new IllegalArgumentException("Actual baseline LLM model required");
            if(node.path("routePromptPath").asText().isBlank() || !Files.isRegularFile(local("routePromptPath")) || Files.readString(local("routePromptPath")).isBlank()) throw new IllegalArgumentException("Reviewed dynamic prompt snapshot required");
            if(!node.path("promptReviewed").asBoolean()) throw new IllegalArgumentException("Prompt review required");
            for(String env: List.of("JEV_API_KEY","DASHSCOPE_API_KEY")) if(System.getenv(env)==null || System.getenv(env).isBlank()) throw new IllegalArgumentException("Missing credential environment: "+env);
            if(!node.path("baselineConfigConfirmed").asBoolean()) throw new IllegalArgumentException("Baseline parameters/network/retries must be confirmed");
            if(node.path("llm").path("retryMaxAttempts").asInt(0)<1 || node.path("llm").path("connectTimeoutMs").asInt(0)<1 || node.path("llm").path("readTimeoutMs").asInt(0)<1) throw new IllegalArgumentException("Explicit LLM retries/timeouts required");
            if(node.path("budget").path("policy").asText().isBlank()) throw new IllegalArgumentException("Explicit budget policy required (amount hard cap only via vendor account)");
        }
    }
    static List<Map<String,Object>> schedule(Config c, List<JsonNode> selected, List<JsonNode> smoke) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();
        for(int round=1;round<=c.rounds();round++) {
            for(int i=0;i<c.warmups();i++) for(String variant:List.of(A,B)) {
                JsonNode sample=smoke.get(i%smoke.size());
                result.add(entry(c,round,sample,variant,true,"warmup-r"+round+"-"+variant+"-"+String.format(Locale.ROOT,"%02d",i+1),"AB",result.size()));
            }
            List<JsonNode> shuffled=new ArrayList<>(selected); Collections.shuffle(shuffled,new Random(c.seed()+round));
            for(int i=0;i<shuffled.size();i++) {
                String order=(i+round)%2==0?"AB":"BA";
                for(String variant:order.equals("AB")?List.of(A,B):List.of(B,A)) {
                    JsonNode sample=shuffled.get(i); result.add(entry(c,round,sample,variant,false,sample.path("id").asText(),order,result.size()));
                }
            }
        }
        Set<String> keys=new HashSet<>(); for(Map<String,Object> e:result) if(!keys.add(key(e))) throw new IllegalArgumentException("Duplicate schedule key");
        return result;
    }
    static Map<String,Object> entry(Config c,int round,JsonNode s,String v,boolean warmup,String id,String order,int index) throws Exception {
        return map("schemaVersion",SCHEMA,"runId",c.runId,"configId",v.equals(A)?"llm-primary-v1":"jev-fallback-085-v1","environmentLevel",LEVEL,
            "mode",c.mode,"round",round,"sampleId",id,"inputSampleId",s.path("id").asText(),"variant",v,"orderIndex",index,"pairOrder",order,"warmup",warmup,
            "expectedAgent",s.path("expectedAgent").asText(),"scenarioGroupId",group(s.path("id").asText()),"split",split(s.path("id").asText()),
            "tags",s.has("tags")?JSON.convertValue(s.get("tags"),List.class):List.of(),"ambiguous",s.path("ambiguous").asBoolean(),"multiIntent",s.path("multiIntent").asBoolean(),"hasHistory",s.path("history").size()>0);
    }
    static String key(Map<String,Object> e) { return List.of("runId","configId","round","sampleId","variant","warmup").stream().map(k->String.valueOf(e.get(k))).reduce((a,b)->a+"|"+b).orElseThrow(); }
    static final class Counts {
        long jev,llm,http,totalJev,totalLlm,totalHttp; volatile String stopReason; volatile String jevError,llmError,responseModel,jevModel,routeStartedAt; volatile Double jevConfidence; volatile Object usage;
        Runnable syntheticBeforeJev=()->{};
        synchronized void reset(){jev=llm=http=0;jevError=llmError=responseModel=jevModel=routeStartedAt=null;jevConfidence=null;usage=null;}
        synchronized void call(Config c, String vendor) {
            if(Files.exists(c.root.resolve("tj-aigc/target/route-benchmark").resolve(c.runId).resolve("STOP")))stop("STOP_FILE");
            if(stopReason!=null)throw new IllegalStateException("Benchmark stopped");
            long total=vendor.equals("jev")?totalJev:totalLlm;
            if(c.mode.equals("LIVE") && total>=c.cap(vendor+"LogicalCalls")) {stopReason="LOGICAL_CALL_CAP";throw new IllegalStateException(stopReason);}
            if(vendor.equals("jev")){jev++;totalJev++;} else {llm++;totalLlm++;}
        }
        synchronized void http(Config c) {
            if(Files.exists(c.root.resolve("tj-aigc/target/route-benchmark").resolve(c.runId).resolve("STOP")))stop("STOP_FILE");
            if(stopReason!=null)throw new IllegalStateException("Benchmark stopped");
            if(c.mode.equals("LIVE") && totalHttp>=c.cap("httpAttempts")){stopReason="HTTP_ATTEMPT_CAP";throw new IllegalStateException(stopReason);}
            http++;totalHttp++;
        }
        synchronized void stop(String reason){if(stopReason==null)stopReason=reason;}
    }
    static Map<String,Object> collect(Config c,Map<String,Object> e,JsonNode sample,BenchmarkRuntime runtime,Counts counts) throws Exception {
        return collect(c,e,sample,runtime,counts,System::nanoTime);
    }
    static Map<String,Object> collect(Config c,Map<String,Object> e,JsonNode sample,BenchmarkRuntime runtime,Counts counts,java.util.function.LongSupplier clock) throws Exception {
        Map<String,Object> r=new LinkedHashMap<>(e);
        for(String field:List.of("actualAgent","source","fallbackReason","confidence","latencyMs","errorType","jevModel","llmModel","startedAt","finishedAt","jevMs","llmMs","httpAttemptCount","retryCount","jevErrorType","llmErrorType","llmResponseModel","jevConfidence"))r.put(field,null);
        r.put("jevCallCount",0);r.put("llmCallCount",0);
        String session=c.runId+"-"+e.get("orderIndex"); UserContext.setUser(900000001L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        String conversation=ChatService.getConversationId(session);
        try {
            for(JsonNode h:sample.path("history")) runtime.memory.add(conversation,h.path("role").asText().equals("assistant")?new AssistantMessage(h.path("content").asText()):new UserMessage(h.path("content").asText()));
            counts.reset(); runtime.fixture((Integer)e.get("orderIndex")); counts.routeStartedAt=now();r.put("startedAt",counts.routeStartedAt);
            long start=clock.getAsLong(); RouteDecision d=null; RuntimeException failure=null;
            try { d=runtime.service.decide(sample.path("question").asText(),session); }
            catch(RuntimeException ex){failure=ex;}
            long end=clock.getAsLong(); r.put("latencyMs",(end-start)/1_000_000.0);r.put("finishedAt",now());
            if(failure!=null){r.put("status","ERROR");r.put("errorType",failure.getClass().getSimpleName());}
            else if(d==null){r.put("status","ERROR");r.put("errorType","NullDecision");}
            else {
                r.put("actualAgent",d.agentType().name());r.put("source",d.source().name());r.put("fallbackReason",d.fallbackReason().name());r.put("confidence",d.confidence());
                r.put("status",d.source()==RouteDecisionSource.SAFE_DEFAULT?"SAFE_DEFAULT":"OK");
            }
            synchronized(counts) {
                r.put("jevCallCount",counts.jev);r.put("llmCallCount",counts.llm);r.put("httpAttemptCount",c.mode.equals("LIVE")?counts.http:null);
                r.put("retryCount",null); // HTTP admission differences are not a reliable SDK retry count.
                r.put("jevModel",counts.jevModel);r.put("jevConfidence",counts.jevConfidence);r.put("llmModel",counts.llm>0?(c.mode.equals("LIVE")?c.node.path("llm").path("model").asText():"synthetic-fixed-llm"):null);
                r.put("llmResponseModel",counts.responseModel);r.put("jevErrorType",counts.jevError);r.put("llmErrorType",counts.llmError);
                r.put("llmUsage",counts.usage);
            }
            return r;
        } finally {runtime.memory.clear(conversation);RequestContextHolder.resetRequestAttributes();UserContext.removeUser();}
    }
    public static Path run(Path root,Path configPath) throws Exception {
        root=root.toAbsolutePath().normalize();
        final Path runRoot=root;
        Config c=new Config(root,JSON.readTree(configPath.toFile()));
        String branch=git(root,"branch","--show-current"),head=git(root,"rev-parse","HEAD");
        Path protocol=root.resolve(DOC).resolve("protocol.json"), dataset=root.resolve("docs/Jev改造/eval/route-eval-v1.jsonl"),smokeFile=root.resolve(DOC).resolve("smoke-ids.json");
        JsonNode p=JSON.readTree(protocol.toFile()), smokeIds=JSON.readTree(smokeFile.toFile()).path("sampleIds");
        if(c.mode.equals("LIVE")&&(!head.equals(p.path("git").path("head").asText())||!branch.equals(p.path("git").path("branch").asText())))throw new IllegalArgumentException("Git protocol identity drift");
        if(!hash(dataset).equals(p.path("dataset").path("sha256").asText()))throw new IllegalArgumentException("Dataset hash drift");
        Map<String,JsonNode> byId=new LinkedHashMap<>();for(JsonNode s:lines(dataset))if(byId.put(s.path("id").asText(),s)!=null)throw new IllegalArgumentException("Duplicate dataset ID");
        List<JsonNode> smoke=new ArrayList<>();for(JsonNode id:smokeIds){JsonNode s=byId.get(id.asText());if(s==null)throw new IllegalArgumentException("Missing smoke ID");smoke.add(s);}
        List<Map<String,Object>> plan=schedule(c,c.stage.equals("smoke")?smoke:new ArrayList<>(byId.values()),smoke);
        c.validateLive(plan.size());
        Path out=root.resolve("tj-aigc/target/route-benchmark").resolve(c.runId); Files.createDirectories(out.getParent()); Files.createDirectory(out);
        Map<String,Object> source=new LinkedHashMap<>();for(Iterator<String> it=p.path("sourceSha256").fieldNames();it.hasNext();){String f=it.next();String current=hash(root.resolve(f));source.put(f,current);if(c.mode.equals("LIVE")&&!current.equals(p.path("sourceSha256").path(f).asText()))throw new IllegalArgumentException("Protocol source hash drift: "+f);}
        Map<String,Object> collectorHashes=new LinkedHashMap<>();
        for(Path dir:List.of(root.resolve("tj-aigc/src/test/java/com/tianji/aigc/benchmark"),root.resolve("tj-aigc/target/test-classes/com/tianji/aigc/benchmark"),root.resolve("tj-aigc/target/classes/com/tianji/aigc"))) {
            if(Files.isDirectory(dir))try(var paths=Files.walk(dir)){for(Path f:paths.filter(Files::isRegularFile).sorted().toList())collectorHashes.put(root.relativize(f).toString(),hash(f));}
        }
        String prompt=c.mode.equals("SYNTHETIC")?BenchmarkRuntime.SYNTHETIC_PROMPT:(c.node.path("routePromptPath").asText().isBlank()?null:Files.readString(c.local("routePromptPath")));
        Map<String,Object> manifest=map("schemaVersion",SCHEMA,"protocolId",p.path("protocolId").asText(),"protocolSha256",hash(protocol),"runId",c.runId,"mode",c.mode,"environmentLevel",LEVEL,
            "createdAt",now(),"createdAtShanghai",ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString(),"stage",c.stage,"status",c.mode.equals("DRY_RUN")?"PLANNED":"RUNNING",
            "git",map("branch",branch,"head",head,"dirty",!git(root,"status","--porcelain").isBlank(),"baselineIncludesWorkingTree",true),
            "dataset",map("path",root.relativize(dataset).toString(),"sha256",hash(dataset),"sampleCount",byId.size()),"sourceSha256",source,"collectorAndDecisionClassSha256",collectorHashes,"configFileSha256",hash(configPath),
            "configIds",List.of("llm-primary-v1","jev-fallback-085-v1"),"variants",JSON.convertValue(p.path("variants"),Object.class),"plannedRecordCount",plan.size(),
            "schedule",map("seed",c.seed(),"rounds",c.rounds(),"warmupsPerVariantPerRound",c.warmups(),"interCallDelayMs",c.delay(),"concurrency",1,"algorithm","Java Collections.shuffle/Random(seed+round); (pairIndex+round)%2==0 AB else BA","language",System.getProperty("java.version")),
            "routePromptSha256",prompt==null?null:hashText(prompt),"routeFinalPromptSha256",prompt==null?null:hashText(BenchmarkRuntime.finalPrompt(prompt)),
            "runtime",map("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"springAI","1.0.0","alibaba","1.0.0.2","dashscopeSDK","2.22.27","logging","production route INFO; SimpleLoggerAdvisor DEBUG suppressed by benchmark logback; no bodies"),
            "limits",JSON.convertValue(c.node.path("limits"),Object.class),"authorization",JSON.convertValue(c.node.path("authorization"),Object.class),"budget",JSON.convertValue(c.node.path("budget"),Object.class),
            "effectiveConfig",BenchmarkRuntime.safeConfig(c),"knownDifferences",List.of("ChatSessionService.update is no-op; run-local memory with real optimization advisor","No SpringBoot/Nacos/DB/Redis; reviewed local dynamic prompt snapshot","Different current Jev/LLM context policies retained","APPLICATION_ROUTE not implemented or authorized"),
            "limitations",List.of("SYNTHETIC fixed responses are not measured model performance","No automatic resume; never reuse runId; started-without-result is unknown and must not be retried silently","Currency/token spending is not an application hard cap; external account cap required","HTTP cap applies to synchronous RestClient execute attempts, includes Spring AI retries; transport-internal TCP retransmission unobservable","LLM server response model retained when present; configured model is not response evidence","Outer timing includes service metrics/log calls; not pure inference"));
        write(out.resolve("manifest.json"),manifest);
        try(BufferedWriter w=Files.newBufferedWriter(out.resolve("schedule.jsonl"),StandardCharsets.UTF_8)){for(Map<String,Object> e:plan)append(w,e);}
        write(out.resolve("schedule-sha256.json"),map("sha256",hash(out.resolve("schedule.jsonl"))));
        manifest.put("scheduleSha256",hash(out.resolve("schedule.jsonl")));write(out.resolve("manifest.json"),manifest);
        Files.writeString(out.resolve("execution.md"),"# Route benchmark execution\n\nmode="+c.mode+"; stage="+c.stage+"; live "+(c.mode.equals("LIVE")?"explicitly enabled":"NOT_RUN")+".\n\nNo automatic recovery; STOP file requests stop before the next request. Forced process termination may leave STARTED progress with no final record; never reissue it under this runId.\n",StandardCharsets.UTF_8);
        if(c.mode.equals("DRY_RUN")){Files.writeString(out.resolve("requests.jsonl"),"");return out;}
        Counts counts=new Counts();Map<String,BenchmarkRuntime> runtimes=new HashMap<>();
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"route-benchmark-serial");t.setDaemon(true);return t;});
        int completed=0;String stop=null;
        String firstResponseModel=null,firstJevModel=null;
        try(BufferedWriter records=Files.newBufferedWriter(out.resolve("requests.jsonl"),StandardCharsets.UTF_8);BufferedWriter progress=Files.newBufferedWriter(out.resolve("progress.jsonl"),StandardCharsets.UTF_8)) {
            runtimes.put(A,BenchmarkRuntime.create(c,A,counts));runtimes.put(B,BenchmarkRuntime.create(c,B,counts));
            manifest.put("runtimeClientOptions",map(A,runtimes.get(A).clientOptions,B,runtimes.get(B).clientOptions));write(out.resolve("manifest.json"),manifest);
            Set<String> seen=new HashSet<>();
            for(Map<String,Object> e:plan) {
                if(Files.exists(out.resolve("STOP"))){stop="STOP_FILE";break;}
                if(Thread.currentThread().isInterrupted()){stop="INTERRUPTED";break;}
                if(c.mode.equals("LIVE") && completed>=c.cap("routeCalls")){stop="ROUTE_CAP";break;}
                if(!seen.add(key(e)))throw new IllegalStateException("Duplicate logical key");
                boolean drift=!hash(dataset).equals(p.path("dataset").path("sha256").asText()) || !hash(protocol).equals(manifest.get("protocolSha256")) || !hash(configPath).equals(manifest.get("configFileSha256")) || (prompt!=null&&!c.mode.equals("SYNTHETIC")&&!hashText(Files.readString(c.local("routePromptPath"))).equals(manifest.get("routePromptSha256")));
                for(var identity:source.entrySet())if(!hash(runRoot.resolve(identity.getKey())).equals(identity.getValue()))drift=true;
                for(var identity:collectorHashes.entrySet())if(!hash(runRoot.resolve(identity.getKey())).equals(identity.getValue()))drift=true;
                if(drift){stop="CONFIG_DRIFT";counts.stop(stop);break;}
                String attemptStarted=now();append(progress,map("event","STARTED","orderIndex",e.get("orderIndex"),"at",attemptStarted));
                counts.reset();
                BenchmarkRuntime runtime=runtimes.get(e.get("variant"));
                Future<Map<String,Object>> future=worker.submit(()->collect(c,e,byId.get(e.get("inputSampleId")),runtime,counts));
                try {
                    Map<String,Object> r=future.get(c.timeout(),TimeUnit.MILLISECONDS);append(records,r);completed++;
                    append(progress,map("event","FINISHED","orderIndex",e.get("orderIndex"),"at",now()));
                    System.out.println("benchmark "+c.mode+" "+completed+"/"+plan.size()+" status="+r.get("status"));
                    if(counts.stopReason!=null){stop=counts.stopReason;break;}
                    boolean modelDrift=(firstResponseModel!=null&&counts.responseModel!=null&&!firstResponseModel.equals(counts.responseModel))||(firstJevModel!=null&&counts.jevModel!=null&&!firstJevModel.equals(counts.jevModel));
                    if(firstResponseModel==null&&counts.responseModel!=null)firstResponseModel=counts.responseModel;
                    if(firstJevModel==null&&counts.jevModel!=null)firstJevModel=counts.jevModel;
                    if(c.mode.equals("LIVE") && systemicFailure(r,counts,modelDrift)){stop="SYSTEM_FAILURE_OR_MODEL_DRIFT";counts.stop(stop);break;}
                } catch(TimeoutException|InterruptedException ex) {
                    counts.stop("REQUEST_UNKNOWN_STOPPED_NO_NEW_CALLS");future.cancel(true);Map<String,Object> r=new LinkedHashMap<>(e);
                    for(String field:List.of("actualAgent","source","fallbackReason","confidence","latencyMs","jevModel","llmModel","finishedAt","jevMs","llmMs","httpAttemptCount","retryCount"))r.put(field,null);
                    synchronized(counts){r.putAll(map("status","INTERRUPTED","errorType",ex.getClass().getSimpleName(),"startedAt",counts.routeStartedAt==null?attemptStarted:counts.routeStartedAt,"submittedAt",attemptStarted,"actualRouteStartedAt",counts.routeStartedAt,"startedAtSemantics",counts.routeStartedAt==null?"SUBMISSION_ONLY_ACTUAL_START_UNKNOWN":"DECIDE_BOUNDARY","jevCallCount",counts.jev,"llmCallCount",counts.llm,"httpAttemptCountObservedAtStop",counts.http,"completionUnknown",true));}append(records,r);stop="REQUEST_UNKNOWN_STOPPED_NO_NEW_CALLS";break;
                } catch(ExecutionException ex) {
                    counts.stop("WORKER_SETUP_OR_CLEANUP_ERROR");Map<String,Object> r=new LinkedHashMap<>(e);
                    for(String field:List.of("actualAgent","source","fallbackReason","confidence","latencyMs","jevModel","llmModel","jevMs","llmMs","httpAttemptCount","retryCount"))r.put(field,null);
                    r.putAll(map("status","ERROR","errorType",ex.getCause().getClass().getSimpleName(),"startedAt",attemptStarted,"finishedAt",now(),"jevCallCount",counts.jev,"llmCallCount",counts.llm));append(records,r);stop="WORKER_SETUP_OR_CLEANUP_ERROR";break;
                }
                if(c.delay()>0)try{Thread.sleep(c.delay());}catch(InterruptedException ex){counts.stop("INTERRUPTED_BETWEEN_REQUESTS");stop="INTERRUPTED_BETWEEN_REQUESTS";Thread.currentThread().interrupt();break;}
            }
        } finally {
            counts.stop("RUN_ENDED");
            worker.shutdownNow();
            manifest.put("firstObservedLlmResponseModel",firstResponseModel);
            manifest.put("firstObservedJevResponseModel",firstJevModel);
            manifest.put("finishedAt",now());manifest.put("completedRecordCount",completed);manifest.put("status",completed==plan.size()?"COMPLETE":"INCOMPLETE");manifest.put("stopReason",stop);
            manifest.put("actualLogicalCalls",map("jev",counts.totalJev,"llm",counts.totalLlm,"httpAttempts",c.mode.equals("LIVE")?counts.totalHttp:null));write(out.resolve("manifest.json"),manifest);
            Files.writeString(out.resolve("execution.md"),"\nCompleted="+completed+"/"+plan.size()+"; status="+manifest.get("status")+"; stopReason="+stop+"; finished="+now()+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        }
        return out;
    }
    static boolean systemicFailure(Map<String,Object> record, Counts counts, boolean modelDrift) {
        // Count an ended response outside the route enum as an experimental failure, not an outage.
        return "AUTH_FAILURE".equals(record.get("fallbackReason"))
                || "REGION_UNAVAILABLE".equals(record.get("fallbackReason"))
                || ("SAFE_DEFAULT".equals(record.get("status"))
                    && !"INVALID_LLM_ROUTE_RESULT".equals(counts.llmError))
                || modelDrift;
    }
}
