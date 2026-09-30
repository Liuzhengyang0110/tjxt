# tjxt Jev 第一阶段改造手册

> 编写日期：2026-09-23  
> 适用模块：`tj-aigc`  
> 适用版本：Java 17、Spring Boot 3.3.5、Spring AI 1.0.0  
> 配套计划：`docs/Jev改造/Jev改造计划.md`  
> 使用方式：按章节顺序手敲代码，每完成一节先编译或测试，再进入下一节

**路径约定：**下文每段需要手敲的代码都标有“新建文件”或“修改文件”。路径以仓库根目录 `D:\Project\Java\tjxt` 为起点，例如 `tj-aigc/src/main/java/com/tianji/aigc/decision/config/JevProperties.java` 对应 `D:\Project\Java\tjxt\tj-aigc\src\main\java\com\tianji\aigc\decision\config\JevProperties.java`。标为“现有代码”或“协议示例”的片段用于阅读，不需要原样新增文件。PowerShell 命令均在仓库根目录执行。

## 1. 这份手册要帮助你完成什么

本手册只实现改造计划中的第一阶段，不提前加入多意图、知识源选择、回答校验和模型档位选择。

完成后，`ROUTE` 模式应具备以下能力：

- 使用 Jev 的一个 `Choice` 问题，在 `RECOMMEND`、`CONSULT`、`BUY`、`KNOWLEDGE` 中选择一个 Agent。
- 保存 Jev 返回的 `choice`、`confidence`、`probabilities` 和实际模型版本。
- Jev 判断足够可信时使用 Jev 路由。
- Jev 低置信度、超时、限流、鉴权失败或响应异常时回退原 `RouteAgent`。
- Jev 正常路由不经过 Spring AI `ChatClient`，因此不向 ChatMemory 写入中间问答。
- 支持 Shadow 模式：线上仍采用原 RouteAgent，Jev 只做异步对照。
- 支持结构化日志、基础指标、单元测试和离线评测。
- 可以通过配置立即切回原路由，不删除旧代码。

第一阶段明确不做：

- 不让 Jev 生成回复、课程 ID、订单 ID 或工具参数。
- 不让 Jev 直接执行下单、退款等写操作。
- 不删除 `RouteAgent`、`RecordOptimizationAdvisor` 或 Redis 记录优化逻辑。
- 不改变 `RecommendAgent`、`ConsultAgent`、`BuyAgent`、`KnowledgeAgent` 的 RAG 和工具配置。
- 不新增没有下游用途的 `needRag`、`modelTier`、`highRisk` 等字段。

## 2. 先理解当前代码，再开始修改

### 2.1 当前 ROUTE 链路

现有代码：`tj-aigc/src/main/java/com/tianji/aigc/service/impl/AgentServiceImpl.java`，用于理解当前入口：

```java
var result = this.findAgentByType(AgentTypeEnum.ROUTE).process(question, sessionId);
var agentTypeEnum = AgentTypeEnum.agentNameOf(result);
var agent = this.findAgentByType(agentTypeEnum);
return agent.processStream(question, sessionId);
```

这里包含两次模型调用：

1. `RouteAgent.process()` 使用普通聊天模型输出 Agent 名称。
2. 专项 Agent 使用同一个 `ChatClient` 生成最终回答。

`RouteAgent` 继承 `AbstractAgent`，所以会自动使用默认的记忆 Advisor。路由问题和 `RECOMMEND` 等路由结果先进入 ChatMemory，随后 `RecordOptimizationAdvisor` 识别到 Agent 名称，再调用 Redis `rightPop(2)` 删除这两条中间消息。

这说明第一阶段真正要替换的是“获得 `AgentTypeEnum` 的方式”，不是整个 Agent 体系。

### 2.2 为什么 Jev 不能继承 AbstractAgent

`AbstractAgent` 负责的是聊天模型能力：

- 系统提示词。
- ChatMemory。
- Advisor。
- Tool Calling。
- 流式响应。
- SSE 数据封装。

Jev 只做一次结构化判断，不生成面向用户的文本。如果让 Jev 继承 `AbstractAgent`，会重新引入记忆污染和不必要的聊天抽象。

因此本次采用独立的 `decision` 包，并通过普通 HTTP 调用 Jev。

### 2.3 当前配置的一个前置条件

现有配置：`tj-aigc/src/main/resources/application.yml`，当前内容是：

```yaml
tj:
  ai:
    chat-type: ENHANCE
```

`AgentServiceImpl` 只有在 `tj.ai.chat-type=ROUTE` 时才会成为 `ChatService`。开发第一阶段前，先在本地环境覆盖这个配置，不建议为了个人调试直接修改所有环境共用的默认值。

PowerShell 临时覆盖示例：

```powershell
$env:TJ_AI_CHAT_TYPE = "ROUTE"
```

## 3. 改造完成后的代码结构

以下均为新建文件，目录根路径是 `tj-aigc/src/main/java/com/tianji/aigc/decision/`。先不要一次性创建全部空类，按后续章节逐个创建：

```text
com.tianji.aigc.decision
├── client
│   ├── JevDecisionClient.java
│   └── dto
│       ├── JevChoiceAnswer.java
│       ├── JevChoiceQuestion.java
│       ├── JevSystemOneRequest.java
│       ├── JevSystemOneResponse.java
│       └── JevUsage.java
├── config
│   ├── JevClientConfig.java
│   ├── JevProperties.java
│   ├── RouteProperties.java
│   └── ShadowExecutorConfig.java
├── context
│   ├── RoutingContextBuilder.java
│   ├── RoutingMessage.java
│   ├── RoutingState.java
│   └── RoutingTextSanitizer.java
├── exception
│   └── JevClientException.java
├── model
│   ├── RouteDecision.java
│   ├── RouteDecisionSource.java
│   ├── RouteFallbackReason.java
│   └── RouteProvider.java
└── service
    ├── RouteDecisionService.java
    └── impl
        ├── FallbackRouteDecisionService.java
        ├── JevRouteDecisionService.java
        ├── LlmRouteDecisionService.java
        └── ShadowRouteComparisonService.java
```

分层含义：

- `client`：只理解 TypeSafe HTTP 协议。
- `context`：只负责从项目状态构造最小化、脱敏的 Jev 输入。
- `model`：项目自己的领域对象，不依赖外部 API DTO。
- `service`：只放对外暴露的路由接口；`service.impl` 放 LLM、Jev、回退及 Shadow 的具体实现，与项目现有 `ChatService` / `service.impl.ChatServiceImpl` 风格一致。
- `AgentServiceImpl`：只消费最终 `RouteDecision`，不关心它来自 Jev 还是 LLM。

这层隔离是面试时值得重点讲的设计：外部 API 变化只影响 `client`，路由策略变化主要影响 `service`，Agent 执行流程保持稳定。

## 4. 里程碑 0：建立可复现基线

不要先写 Jev 代码。先确认原 ROUTE 流程可用，否则改造后无法判断问题来自旧链路还是新链路。

### 4.1 验证四类路由

至少准备以下测试输入，每条使用不同 `sessionId`，避免上一条上下文干扰下一条：

| 输入 | 期望 Agent |
| --- | --- |
| 给我推荐适合零基础的 Java 课程 | `RECOMMEND` |
| 天机学堂的 Java 课程多少钱 | `CONSULT` |
| 我想购买刚才选中的课程 | `BUY` |
| 给我讲讲 Java 多态 | `KNOWLEDGE` |

现有测试文件：`tj-aigc/src/test/java/com/tianji/aigc/agent/RouteAgentTest.java`。它调用真实模型，更接近集成测试。建议把基线结果记到 `docs/Jev改造/Jev路由评测报告.md`，记录：

- 每条路由结果。
- 平均耗时和大致 P95。
- 无法精确匹配枚举的情况。
- Redis ChatMemory 中路由消息是否被正常清理。

### 4.2 先明确四类边界

Jev 的 `Choice` 选项描述必须互斥。建议第一版边界如下：

- `RECOMMEND`：用户希望平台根据目标、基础、预算或时间推荐一门或多门具体课程。
- `CONSULT`：用户询问平台某门课程的价格、内容、老师、适合人群、开课信息或状态。
- `BUY`：用户明确要购买、结算或为已选课程创建预下单；仅询问价格不属于购买。
- `KNOWLEDGE`：通用知识解释、编程问题、概念讲解和学习路线；没有要求平台推荐具体课程。

第一阶段没有 `GENERAL` 和 `OUT_OF_SCOPE`，所以天气、新闻、闲聊等请求一定会被四选一。这是已知边界，应放进评测集观察，不要偷偷增加 Java 枚举之外的选项。

### 本节自检

- 为什么基线样本不能都使用同一个 `sessionId`？
- “Java 学习路线”与“推荐一门 Java 课程”的边界是什么？
- 当前路由失败时，为什么可能把路由字符串直接返回给前端？

## 5. 里程碑 1：增加配置对象

### 5.1 新增 JevProperties

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/config/JevProperties.java`

```java
package com.tianji.aigc.decision.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "tj.ai.jev")
public class JevProperties {
    private boolean enabled = false;
    private String baseUrl = "https://api.typesafe.ai";
    private String apiKey;
    private String model = "jev-1.13.0";
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofMillis(1200);
    private int maxContextChars = 4000;
    private int recentMessageLimit = 8;
}
```

这里故意不使用 Lombok `@Data`，减少 API Key 被对象 `toString()` 意外打印的机会。

截至 2026-09-23，官方文档列出的固定模型版本是 `jev-1.13.0`，`jev-latest` 当前指向该版本。第一阶段建议固定版本，因为置信度阈值需要针对具体版本校准；别名升级后可能出现路由漂移。

### 5.2 新增 RouteProperties

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/config/RouteProperties.java`

```java
package com.tianji.aigc.decision.config;

import com.tianji.aigc.decision.model.RouteProvider;
import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "tj.ai.route")
public class RouteProperties {
    private RouteProvider provider = RouteProvider.LLM;
    private boolean shadowEnabled = false;
    private boolean fallbackEnabled = true;
    private double defaultConfidenceThreshold = 0.85;
    private AgentTypeEnum safeDefaultAgent = AgentTypeEnum.KNOWLEDGE;
    private Map<AgentTypeEnum, Double> confidenceThresholds =
            new EnumMap<>(AgentTypeEnum.class);

    public double thresholdFor(AgentTypeEnum agentType) {
        return confidenceThresholds.getOrDefault(agentType, defaultConfidenceThreshold);
    }
}
```

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/model/RouteProvider.java`，输入以下配套枚举（文件开头补上 `package com.tianji.aigc.decision.model;`）：

```java
public enum RouteProvider {
    LLM,
    JEV
}
```

之所以预留按 Agent 设置阈值，是因为 `BUY` 的误路由风险高于 `KNOWLEDGE`。初期没有数据时仍使用全局默认值，Shadow 后再填写各 Agent 阈值。

### 5.3 注册配置并构造 RestClient

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/config/JevClientConfig.java`

```java
package com.tianji.aigc.decision.config;

import cn.hutool.core.util.StrUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties({JevProperties.class, RouteProperties.class})
public class JevClientConfig {

    @Bean
    public RestClient jevRestClient(JevProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();

        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        var builder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json");

        if (StrUtil.isNotBlank(properties.getApiKey())) {
            builder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + properties.getApiKey()
            );
        }
        return builder.build();
    }
}
```

这里使用 Spring Framework 自带 `RestClient`，不把 Jev 伪装成 Spring AI `ChatModel`。没有额外 SDK 依赖，也便于使用本地 HTTP Server 做契约测试。

### 5.4 增加 YAML

修改文件：`tj-aigc/src/main/resources/application.yml`，在现有 `tj.ai` 节点下增加以下 `route` 和 `jev` 配置。只在本地试验的 `chat-type: ROUTE` 覆盖值可放到 `tj-aigc/src/main/resources/application-local.yml`：

```yaml
route:
  provider: LLM
  shadow-enabled: false
  fallback-enabled: true
  default-confidence-threshold: 0.85
  safe-default-agent: KNOWLEDGE
  confidence-thresholds: { }
jev:
  enabled: false
  base-url: https://api.typesafe.ai
  api-key: ${JEV_API_KEY:}
  model: ${JEV_MODEL:jev-1.13.0}
  connect-timeout: 500ms
  read-timeout: 1200ms
  max-context-chars: 4000
  recent-message-limit: 8
```

不要把真实 Key 写入仓库。启动前使用：

```powershell
$env:JEV_API_KEY = "你的本地Key"
```

### 5.5 第一次编译

```powershell
mvn -pl tj-aigc -am -DskipTests compile
```

### 本节自检

- 为什么配置了 `enabled=false`，仍然可以创建无鉴权头的 `RestClient`？
- 为什么固定模型版本比使用 `jev-latest` 更适合阈值校准？
- 为什么 API Key 不能作为日志字段，也不适合放进 `@Data` 对象的字符串表示？

## 6. 建立 Jev HTTP 契约 DTO

Jev 当前接口是 `POST /v1/systemone`。请求顶层包含：

- `state`：字符串、对象或数组。
- `model`：模型名称。
- `questions`：问题 ID 到问题对象的 Map。

第一阶段只发送一个 ID 为 `route_agent` 的 Choice 问题。

### 6.1 请求 DTO

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/client/dto/JevSystemOneRequest.java`。代码块是类主体，文件开头补上 `package com.tianji.aigc.decision.client.dto;` 和 `java.util.Map` 的 import。

```java
public record JevSystemOneRequest(
        Object state,
        String model,
        Map<String, JevChoiceQuestion> questions
) {
}
```

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/client/dto/JevChoiceQuestion.java`。文件开头补上相同包声明，以及 `java.util.LinkedHashMap`、`java.util.Map` 的 import。

```java
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
```

`LinkedHashMap` 不是 API 的硬要求，但可以使序列化顺序、测试快照和排查结果更稳定。

### 6.2 响应 DTO

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/client/dto/JevSystemOneResponse.java`。文件开头补上 `package com.tianji.aigc.decision.client.dto;` 和 `java.util.Map` 的 import。

```java
public record JevSystemOneResponse(
        String model,
        Map<String, JevChoiceAnswer> answers,
        JevUsage usage
) {
}
```

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/client/dto/JevChoiceAnswer.java`。文件开头补上相同包声明和 `java.util.Map` 的 import。

```java
public record JevChoiceAnswer(
        String type,
        String choice,
        Double confidence,
        Map<String, Double> probabilities
) {
}
```

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/client/dto/JevUsage.java`。文件开头补上相同包声明和 `com.fasterxml.jackson.annotation.JsonProperty` 的 import。

```java
public record JevUsage(
        @JsonProperty("input_tokens") Long inputTokens,
        @JsonProperty("output_tokens") Long outputTokens
) {
}
```

协议示例：以下是 Jev 服务返回的 JSON，供你编写 `tj-aigc/src/test/java/com/tianji/aigc/decision/client/JevDecisionClientTest.java` 中的模拟响应；不需要新建 `.json` 文件。

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "route_agent": {
      "type": "choice",
      "choice": "CONSULT",
      "confidence": 0.81,
      "probabilities": {
        "RECOMMEND": 0.05,
        "CONSULT": 0.88,
        "BUY": 0.02,
        "KNOWLEDGE": 0.05
      }
    }
  },
  "usage": {
    "input_tokens": 318,
    "output_tokens": 34
  }
}
```

注意：`confidence` 是根据整个概率分布形状计算的集中程度，不等于“这个答案有 81% 概率正确”。真正的正确率必须通过 tjxt 自己的标注集校准。

### 本节自检

- `choice`、最高选项概率和 `confidence` 分别表达什么？
- 为什么响应中已经有 `confidence` 时，不应自行发明另一套计算公式？
- 为什么 `answers` 应建模成 Map，而不是固定字段？

## 7. 实现最小化路由状态

### 7.1 定义状态对象

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingMessage.java`。文件开头补上 `package com.tianji.aigc.decision.context;`。

```java
public record RoutingMessage(String role, String content) {
}
```

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingState.java`。文件开头补上相同包声明，以及 `JsonProperty`、`List`、`Map` 的 import。

```java
public record RoutingState(
        @JsonProperty("latest_user_message") String latestUserMessage,
        @JsonProperty("recent_context") List<RoutingMessage> recentContext,
        @JsonProperty("available_agents") Map<String, String> availableAgents
) {
}
```

使用对象而不是拼接一大段提示词的好处是字段边界清楚，后续增加或删除状态字段时不必重写字符串协议。

### 7.2 增加脱敏器

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingTextSanitizer.java`

建议第一版至少处理：

- 中国大陆手机号。
- 邮箱。
- 身份证号。
- 8 位及以上连续数字，用于遮蔽长订单号、支付流水号等。

在 `tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingTextSanitizer.java` 中输入以下示意实现，并补上包声明、`org.springframework.stereotype.Component` 和 `java.util.regex.Pattern` 的 import：

```java
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
```

脱敏不是一次完成的万能正则。后续发现新的敏感字段时补规则，并用测试锁定行为。不要为了脱敏把所有数字都删掉，因为“课程 1234”这类信息仍可能有助于理解意图。

### 7.3 从 ChatMemory 只读构造上下文

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingContextBuilder.java`

在 `tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingContextBuilder.java` 中先写核心依赖；文件开头补上包声明和依赖类型的 import：

```java
@Component
@RequiredArgsConstructor
public class RoutingContextBuilder {
    private final ChatMemory chatMemory;
    private final JevProperties properties;
    private final RoutingTextSanitizer sanitizer;
}
```

构造步骤：

1. 使用 `ChatService.getConversationId(sessionId)` 得到当前会话 ID。
2. 调用 `chatMemory.get(conversationId)`，只读取历史，不调用 `add()`。
3. 仅保留 `USER` 和 `ASSISTANT` 消息。
4. 从最新消息向前取最多 `recentMessageLimit` 条。
5. 对每条内容脱敏。
6. 按 `maxContextChars` 从新到旧分配字符预算。
7. 最后恢复成时间正序。
8. 当前问题单独放在 `latest_user_message`，不要追加进 ChatMemory。

修改同一个文件 `tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingContextBuilder.java`，把 Agent 描述常量放在类内：

```java
private static final Map<String, String> AVAILABLE_AGENTS = Map.of(
        "RECOMMEND", "推荐平台中的具体课程",
        "CONSULT", "咨询平台具体课程的信息",
        "BUY", "购买或预下单已选课程",
        "KNOWLEDGE", "解释通用知识和编程问题"
);
```

截取历史时不要直接使用 `stream().limit(8)`，因为它会保留最旧的 8 条。路由更需要最近上下文，应从列表尾部向前选择，再 `Collections.reverse()`。

修改同一个文件 `tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingContextBuilder.java`，把以下 `build` 方法放在类内：

```java
public RoutingState build(String question, String sessionId) {
    String conversationId = ChatService.getConversationId(sessionId);
    List<Message> history = chatMemory.get(conversationId);

    List<Message> candidates = history == null ? List.of() : history.stream()
            .filter(message -> message.getMessageType() == MessageType.USER
                    || message.getMessageType() == MessageType.ASSISTANT)
            .toList();

    List<RoutingMessage> recent = takeRecentMessages(candidates);
    return new RoutingState(
            sanitizer.sanitize(question),
            recent,
            AVAILABLE_AGENTS
    );
}
```

`takeRecentMessages` 留给你手敲，必须同时满足“条数上限”和“总字符上限”。建议给单条超长消息也设置截断，优先保留靠后的内容或前后各一部分，并通过测试说明你的选择。

### 7.4 一个重要的异步陷阱

`ChatService.getConversationId()` 会读取 `UserContext`。项目的 `UserContext` 基于 `ThreadLocal`，不会自动传播到 `@Async` 线程。

以下调用最终写在 `tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionService.java` 的 `decide` 方法中，确保在请求线程先构造状态：

```java
RoutingState state = routingContextBuilder.build(question, sessionId);
```

然后把已经构造完成的 `RoutingState` 交给异步线程。不要在异步线程里重新调用 `build(question, sessionId)`，否则可能得到 `null_sessionId` 对应的错误历史。

### 本节测试

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/decision/context/RoutingContextBuilderTest.java`，至少覆盖：

- 无历史时 `recentContext` 为空。
- 历史超过 8 条时只保留最新消息。
- 超过字符预算时仍优先保留最近上下文。
- “这个多少钱”能同时携带上一轮课程信息。
- 手机、邮箱、身份证、长订单号被替换。
- 构造上下文后 ChatMemory 条数没有变化。

## 8. 实现 JevDecisionClient

### 8.1 定义固定错误原因

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/model/RouteFallbackReason.java`。文件开头补上 `package com.tianji.aigc.decision.model;`。

```java
public enum RouteFallbackReason {
    NONE,
    JEV_DISABLED,
    LOW_CONFIDENCE,
    TIMEOUT,
    NETWORK_ERROR,
    RATE_LIMITED,
    OVERLOADED,
    AUTH_FAILURE,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    SERVER_ERROR,
    LLM_ERROR,
    UNKNOWN_ERROR
}
```

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/exception/JevClientException.java`。文件开头补上 `package com.tianji.aigc.decision.exception;`，并导入 `RouteFallbackReason` 和 `lombok.Getter`。

```java
@Getter
public class JevClientException extends RuntimeException {
    private final RouteFallbackReason reason;

    public JevClientException(RouteFallbackReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public JevClientException(RouteFallbackReason reason, String message) {
        this(reason, message, null);
    }
}
```

固定枚举便于指标聚合。不要把异常消息直接作为 `reason` 标签，否则会产生高基数指标。

### 8.2 客户端职责

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/client/JevDecisionClient.java`

客户端只负责：

- 检查启用状态和 API Key。
- 构造官方请求 DTO。
- 调用 `/v1/systemone`。
- 把 HTTP、网络和超时异常转换成项目异常。
- 返回响应 DTO。

客户端不负责：

- 选择置信度阈值。
- 回退 RouteAgent。
- 修改 ChatMemory。
- 选择最终 Agent。

在 `tj-aigc/src/main/java/com/tianji/aigc/decision/client/JevDecisionClient.java` 中实现 `evaluate` 方法。以下为方法骨架，需放入类内，并补齐字段、import 和 `mapStatusException`、`mapResourceException` 辅助方法：

```java
public JevSystemOneResponse evaluate(RoutingState state) {
    if (!properties.isEnabled()) {
        throw new JevClientException(JEV_DISABLED, "Jev is disabled");
    }
    if (StrUtil.isBlank(properties.getApiKey())) {
        throw new JevClientException(AUTH_FAILURE, "Jev API key is missing");
    }

    var request = new JevSystemOneRequest(
            state,
            properties.getModel(),
            Map.of("route_agent", JevChoiceQuestion.routeQuestion())
    );

    try {
        return restClient.post()
                .uri("/v1/systemone")
                .body(request)
                .retrieve()
                .body(JevSystemOneResponse.class);
    } catch (RestClientResponseException e) {
        throw mapStatusException(e);
    } catch (ResourceAccessException e) {
        throw mapResourceException(e);
    } catch (RestClientException e) {
        throw new JevClientException(NETWORK_ERROR, "Jev request failed", e);
    }
}
```

`mapStatusException` 建议映射：

| HTTP 状态 | 原因 |
| --- | --- |
| 401、403 | `AUTH_FAILURE` |
| 422 | `INVALID_REQUEST` |
| 429 | `RATE_LIMITED` |
| 529 | `OVERLOADED` |
| 其他 5xx | `SERVER_ERROR` |
| 其他状态 | `UNKNOWN_ERROR` |

`ResourceAccessException` 的 cause 链中如果出现 `SocketTimeoutException` 或 `HttpTimeoutException`，映射为 `TIMEOUT`，否则映射为 `NETWORK_ERROR`。

不要在异常中保存 Authorization Header，也不要记录完整请求体。

### 8.3 在线路由暂不主动重试

TypeSafe 官方建议 429 和 529 使用指数退避重试。但 tjxt 的在线路由已经有原 RouteAgent 作为回退，而且用户正在等待首字节。第一阶段建议：

- 在线主链路遇到 429/529 立即回退，不在 1200ms 预算内反复等待。
- Shadow 或离线评测可以增加有限重试，并遵守 `Retry-After`。
- 如果后续真实数据证明短重试收益更高，再单独引入重试策略。

这体现“外部 API 建议”和“本项目延迟目标”之间的权衡。

### 8.4 客户端契约测试

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/decision/client/JevDecisionClientTest.java`。沿用现有 `tj-aigc/src/test/java/com/tianji/aigc/service/impl/DashScopeAudioServiceImplTest.java` 的风格，使用 JDK `HttpServer`：

- 断言请求路径是 `/v1/systemone`。
- 断言 Authorization 是 `Bearer test-key`。
- 解析请求 JSON，断言 `model`、`state` 和四个 `criteria`。
- 返回固定 Jev JSON，断言 DTO 正确解析。
- 分别模拟 401、422、429、529、500。
- 通过延迟响应模拟 read timeout。

测试不能依赖真实 Jev 服务，否则普通 Maven 测试会受到网络、额度和模型漂移影响。

## 9. 定义项目自己的 RouteDecision

### 9.1 决策来源

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/model/RouteDecisionSource.java`。文件开头补上 `package com.tianji.aigc.decision.model;`。

```java
public enum RouteDecisionSource {
    JEV,
    LLM_PRIMARY,
    LLM_FALLBACK,
    SAFE_DEFAULT
}
```

相比只使用 `JEV` 和 `LLM_FALLBACK`，这里额外区分：

- 配置本来就是 LLM 时的正常主路由。
- Jev 失败后发生的 LLM 回退。
- Jev 和 LLM 都失败后的无工具安全默认路由。

### 9.2 最终决策对象

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/model/RouteDecision.java`。文件开头补上相同包声明，并导入 `AgentTypeEnum` 和 `java.util.Map`。

```java
public record RouteDecision(
        AgentTypeEnum agentType,
        Double confidence,
        Map<String, Double> probabilities,
        RouteDecisionSource source,
        RouteFallbackReason fallbackReason,
        String model
) {
    public RouteDecision {
        probabilities = probabilities == null
                ? Map.of()
                : Map.copyOf(probabilities);
        fallbackReason = fallbackReason == null
                ? RouteFallbackReason.NONE
                : fallbackReason;
    }
}
```

这里使用 `Double` 而不是 `double`，因为原 RouteAgent 没有经过校准的置信度。LLM 路由时用 `null` 表达“没有该数据”，不要伪造为 `1.0` 或 `0.0`。

Jev DTO 与 `RouteDecision` 不应合并：前者是外部协议，后者是项目领域模型。即使 Jev 将来改字段，AgentService 也不应被迫感知。

## 10. 实现三层路由服务

遵循项目已有的 `service` / `service.impl` 分层：`RouteDecisionService` 是唯一对外接口，放在 `decision.service`；LLM、Jev、回退三个实现类放在 `decision.service.impl`。`AgentServiceImpl` 只依赖接口，由 `@Primary` 选中回退编排实现；三个实现之间可以明确注入具体类型，避免注入同一个接口时产生歧义。第 12 节的 Shadow 辅助服务也放入 `impl`，但它不实现 `RouteDecisionService`，因为它不承担用户请求的路由入口。

### 10.1 统一入口接口

新建接口文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/service/RouteDecisionService.java`。这个目录只放接口，不放下面三个实现类。

```java
package com.tianji.aigc.decision.service;

import com.tianji.aigc.decision.model.RouteDecision;

public interface RouteDecisionService {
    RouteDecision decide(String question, String sessionId);
}
```

最终注入 `AgentServiceImpl` 的是编排服务，而不是直接注入 Jev 客户端。

### 10.2 LlmRouteDecisionService

新建实现类文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/LlmRouteDecisionService.java`。职责是把现有 `RouteAgent.process()` 封装成一个策略；包名必须是 `com.tianji.aigc.decision.service.impl`，并导入上面的 `RouteDecisionService` 接口以及代码中使用的其他类型。

```java
package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.decision.service.RouteDecisionService;
// 其余 import 按使用补齐

@Service("llmRouteDecisionService")
@RequiredArgsConstructor
public class LlmRouteDecisionService implements RouteDecisionService {
    private final RouteAgent routeAgent;

    @Override
    public RouteDecision decide(String question, String sessionId) {
        String raw = routeAgent.process(question, sessionId);
        String normalized = StrUtil.trim(raw).toUpperCase(Locale.ROOT);
        AgentTypeEnum agentType = AgentTypeEnum.agentNameOf(normalized);

        if (agentType == null || agentType == AgentTypeEnum.ROUTE) {
            throw new IllegalStateException("Invalid LLM route result");
        }

        return new RouteDecision(
                agentType,
                null,
                Map.of(),
                RouteDecisionSource.LLM_PRIMARY,
                RouteFallbackReason.NONE,
                null
        );
    }
}
```

这里顺便增强了原实现的空白和大小写容错，但仍不接受附加解释。真正的自由文本解析问题由 Jev 主路由解决，LLM 只作为旧能力回退。

### 10.3 JevRouteDecisionService

新建实现类文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/JevRouteDecisionService.java`。包名为 `com.tianji.aigc.decision.service.impl`，导入 `com.tianji.aigc.decision.service.RouteDecisionService` 及其他依赖，在该类中提供以下两个方法：

```java
package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.decision.service.RouteDecisionService;
// 其余 import 按使用补齐

@Service("jevRouteDecisionService")
@RequiredArgsConstructor
public class JevRouteDecisionService implements RouteDecisionService {
    private static final String ROUTE_QUESTION_ID = "route_agent";
    private static final Set<AgentTypeEnum> ALLOWED_AGENTS = Set.of(
            AgentTypeEnum.RECOMMEND,
            AgentTypeEnum.CONSULT,
            AgentTypeEnum.BUY,
            AgentTypeEnum.KNOWLEDGE
    );

    private final RoutingContextBuilder contextBuilder;
    private final JevDecisionClient client;

    @Override
    public RouteDecision decide(String question, String sessionId) {
        return decide(contextBuilder.build(question, sessionId));
    }

    public RouteDecision decide(RoutingState state) {
        JevSystemOneResponse response = client.evaluate(state);
        // 在这里完成严格响应校验和领域对象转换
    }
}
```

重载 `decide(RoutingState)` 是为 Shadow 模式准备的。请求线程先构建状态，异步线程直接消费状态，不再读取 ThreadLocal。

响应校验至少包括：

- response、model、answers 不为空。
- `answers.route_agent` 存在。
- `type` 必须是 `choice`。
- `choice` 能转换为允许的四类 Agent，禁止 `ROUTE`。
- `confidence` 必须是有限数字且位于 `[0, 1]`。
- probabilities 不为空；键不能包含未知 Agent。
- 每个概率必须是有限数字且位于 `[0, 1]`。
- 概率总和允许小范围浮点误差，例如 `abs(sum - 1) <= 0.001`。

在 `tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/JevRouteDecisionService.java` 的响应校验方法中，非法响应统一抛出：

```java
new JevClientException(RouteFallbackReason.INVALID_RESPONSE, "...")
```

日志中可以记录“缺少 route_agent”这类结构原因，但不要记录完整用户状态。

### 10.4 FallbackRouteDecisionService

新建实现类文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionService.java`。包名为 `com.tianji.aigc.decision.service.impl`，导入 `RouteDecisionService` 接口及其他依赖。它是最终注入 `AgentServiceImpl` 的实现，使用 `@Primary`；代码块是类骨架：

```java
package com.tianji.aigc.decision.service.impl;

import com.tianji.aigc.decision.service.RouteDecisionService;
// 其余 import 按使用补齐

@Primary
@Service
public class FallbackRouteDecisionService implements RouteDecisionService {
    // 注入同一 impl 包下的 JevRouteDecisionService、LlmRouteDecisionService，
    // 以及 RoutingContextBuilder、RouteProperties、JevProperties。
    // ShadowRouteComparisonService 在第 12 节创建后再接入。
}
```

在 `tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionService.java` 的 `decide` 方法中实现以下顺序。这是最终完整流程的逻辑说明，不是需要粘贴的 Java 代码；第 10 节先完成非 Shadow 分支，第 12 节创建 Shadow 服务后再补第 2 步：

```text
1. 在当前请求线程构造 RoutingState
2. 如果开启 Shadow：
   - 同步调用 LLM，作为实际结果
   - 异步提交 Jev 对照
   - 返回 LLM 结果
3. 如果 provider=LLM：直接返回 LLM
4. 如果 provider=JEV 但 Jev disabled：按 JEV_DISABLED 回退
5. 调用 Jev
6. 若 confidence >= 当前 Agent 的阈值：返回 Jev
7. 若 confidence 不足：按 LOW_CONFIDENCE 回退
8. Jev 异常：按异常 reason 回退
9. LLM 回退也失败：返回 safeDefaultAgent
```

修改 `tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionService.java`，在类内增加以下回退辅助方法，避免形成递归：

```java
private RouteDecision fallbackToLlm(
        String question,
        String sessionId,
        RouteFallbackReason reason
) {
    if (!routeProperties.isFallbackEnabled()) {
        return safeDefault(reason);
    }

    try {
        RouteDecision llm = llmService.decide(question, sessionId);
        return new RouteDecision(
                llm.agentType(),
                null,
                Map.of(),
                RouteDecisionSource.LLM_FALLBACK,
                reason,
                null
        );
    } catch (Exception e) {
        return safeDefault(RouteFallbackReason.LLM_ERROR);
    }
}
```

安全默认建议使用 `KNOWLEDGE`，因为它当前没有工具和写操作。它不保证回答一定正确，但比错误进入 `BUY` 更安全。最终仍应把这个策略写入产品说明。

### 10.5 为什么不直接使用概率最大值门控

官方响应同时给出最高选项概率与 `confidence`。第一阶段按计划使用官方 `confidence` 门控，并完整保存 `probabilities` 用于后续分析。

不要把下面两个判断混为一谈：

- `CONSULT=0.55`：在四个类别中它的相对概率最高。
- `confidence=0.20`：整个分布较平，模型对类别区分并不确定。

如果 Shadow 数据显示官方 confidence 不适合某类边界，再基于概率间隔、top1 概率等设计项目自己的门控，但必须通过标注集验证。

### 本节自检

- 为什么回退只能发生一次？
- 为什么安全默认 Agent 不应选择 `BUY`？
- `LLM_PRIMARY` 和 `LLM_FALLBACK` 对排查问题有什么区别？
- 为什么 `JevRouteDecisionService` 不直接决定是否回退？

## 11. 修改 AgentServiceImpl

### 11.1 最小接入

修改文件：`tj-aigc/src/main/java/com/tianji/aigc/service/impl/AgentServiceImpl.java`。在 `chat` 方法中把原来的两行路由调用替换为：

```java
RouteDecision decision = routeDecisionService.decide(question, sessionId);
Agent agent = findAgentByType(decision.agentType());
```

在 `tj-aigc/src/main/java/com/tianji/aigc/service/impl/AgentServiceImpl.java` 的 `chat` 方法中，查找并校验 Agent 后继续调用：

```java
return agent.processStream(question, sessionId);
```

### 11.2 推荐顺手消除 SpringUtil 查找

仍修改 `tj-aigc/src/main/java/com/tianji/aigc/service/impl/AgentServiceImpl.java`。当前每次路由都通过 `SpringUtil.getBeansOfType(Agent.class)` 遍历 Bean。为了便于单元测试，可以让 Spring 直接注入 `List<Agent>`，在类中添加字段和构造函数，并导入 `List`、`Map`、`Collectors`、`Function`：

```java
private final RouteDecisionService routeDecisionService;
private final Map<AgentTypeEnum, Agent> agentMap;

public AgentServiceImpl(
        RouteDecisionService routeDecisionService,
        List<Agent> agents
) {
    this.routeDecisionService = routeDecisionService;
    this.agentMap = agents.stream().collect(Collectors.toUnmodifiableMap(
            Agent::getAgentType,
            Function.identity()
    ));
}
```

在 `tj-aigc/src/main/java/com/tianji/aigc/service/impl/AgentServiceImpl.java` 中替换现有的 `findAgentByType` 方法：

```java
private Agent findAgentByType(AgentTypeEnum agentType) {
    return agentMap.get(agentType);
}
```

如果出现重复 Agent 类型，`toUnmodifiableMap` 会在启动阶段暴露问题，比运行到某个请求时随机找到一个 Bean 更安全。

### 11.3 防御非法结果

在 `tj-aigc/src/main/java/com/tianji/aigc/service/impl/AgentServiceImpl.java` 的 `chat` 方法中，查找 Agent 后增加防御分支；其中 `friendlyErrorEvent` 需要根据现有 `ChatEventVO` 构造：

```java
if (agent == null || decision.agentType() == AgentTypeEnum.ROUTE) {
    // 记录 routeRequestId、source、fallbackReason，不记录完整问题
    Agent safeAgent = findAgentByType(AgentTypeEnum.KNOWLEDGE);
    if (safeAgent == null) {
        return Flux.just(friendlyErrorEvent, AbstractAgent.STOP_EVENT);
    }
    return safeAgent.processStream(question, sessionId);
}
```

### 11.4 第一阶段不要删除的代码

- `RouteAgent`：Jev 的回退依赖它。
- `RecordOptimizationAdvisor`：LLM 回退仍会写入路由中间消息。
- `RedisChatMemoryRepository.optimization()`：LLM 回退仍需清理记录。
- 各专项 Agent 的 Advisor 和 Tool。

只有 Jev 成为稳定主路由并且决定淘汰 LLM 回退时，才讨论这些代码是否删除。

### 11.5 ChatMemory 验证

Jev 正常路径中：

1. `RoutingContextBuilder` 只调用 `chatMemory.get()`。
2. Jev HTTP 客户端不使用 ChatClient。
3. 只有专项 Agent 的 `processStream()` 把当前用户问题和最终助手回答写入记忆。

因此一轮对话应只增加正常的用户消息和助手消息，不再先增加路由消息后删除。

## 12. 实现 Shadow 模式

### 12.1 建立专用线程池

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/config/ShadowExecutorConfig.java`。文件开头补上 `package com.tianji.aigc.decision.config;` 及 Spring、JDK 线程池相关 import。

```java
@Configuration
public class ShadowExecutorConfig {

    @Bean("jevShadowExecutor")
    public Executor jevShadowExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("jev-shadow-");
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.initialize();
        return executor;
    }
}
```

Shadow 是旁路观测，队列满时应放弃这次对照任务。这里使用 `AbortPolicy` 使提交方能够感知拒绝；在 `tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionService.java` 调用 `compareAsync` 的位置捕获 `TaskRejectedException`，增加 `jev.shadow.rejected` 指标后继续返回原 LLM 结果。不要使用静默丢弃策略，否则难以统计丢失的样本。

### 12.2 单独建立异步 Bean

新建文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/ShadowRouteComparisonService.java`。文件开头补上 `package com.tianji.aigc.decision.service.impl;` 及相关 import。它和 Jev 路由实现同包，不需要跨包导入 `JevRouteDecisionService`。

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowRouteComparisonService {
    private final JevRouteDecisionService jevService;

    @Async("jevShadowExecutor")
    public void compareAsync(
            String routeRequestId,
            String sessionHash,
            RoutingState state,
            RouteDecision actualDecision
    ) {
        try {
            long start = System.nanoTime();
            RouteDecision shadow = jevService.decide(state);
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            boolean disagreed = shadow.agentType() != actualDecision.agentType();
            log.info(
                    "jev shadow result: routeRequestId={}, sessionHash={}, "
                            + "actualAgent={}, jevAgent={}, confidence={}, "
                            + "latencyMs={}, disagreed={}, model={}",
                    routeRequestId,
                    sessionHash,
                    actualDecision.agentType(),
                    shadow.agentType(),
                    shadow.confidence(),
                    latencyMs,
                    disagreed,
                    shadow.model()
            );
        } catch (Exception e) {
            // 记录固定错误类别，不向外抛出，不影响用户请求
        }
    }
}
```

必须放在独立 Bean 中，因为同一个类内部调用自己的 `@Async` 方法属于 self-invocation，不会经过 Spring 代理，也就不会真正异步。

### 12.3 Shadow 主流程

修改文件：`tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionService.java`，把以下逻辑放在 `decide` 方法的前部：

```java
RoutingState state = contextBuilder.build(question, sessionId);

if (routeProperties.isShadowEnabled()) {
    RouteDecision actual = llmService.decide(question, sessionId);
    shadowService.compareAsync(
            routeRequestId,
            hashSession(sessionId),
            state,
            actual
    );
    return actual;
}
```

顺序非常重要：必须先构造 `state`，再调用原 RouteAgent。否则原 RouteAgent 可能先写入并清理 ChatMemory，使 Shadow 捕获到的上下文与路由发生时不一致。

`sessionHash` 可以使用 Hutool `DigestUtil.sha256Hex(sessionId)`，只记录必要长度的前缀。不要记录原始 sessionId、用户 ID 或完整问题。

### 12.4 Shadow 配置

修改文件：`tj-aigc/src/main/resources/application-local.yml`，仅在本地调试时覆盖以下配置；若使用 Nacos 管理环境配置，则将相同键写入对应的 `aigc-service.yaml`：

```yaml
tj:
  ai:
    chat-type: ROUTE
    route:
      provider: LLM
      shadow-enabled: true
    jev:
      enabled: true
```

Shadow 开启时，实际结果始终来自 LLM。即使误把 `provider` 配成 `JEV`，代码也应让 `shadow-enabled` 优先，或者在启动时拒绝这种含义不清的组合。手册建议直接规定：Shadow 阶段必须配置 `provider=LLM`。

### 本节自检

- 为什么 Shadow 不能使用公共无界线程池？
- 为什么异步线程里不能重新读取 `UserContext`？
- 为什么 `@Async` 方法必须放在另一个 Spring Bean？
- Shadow 队列满时选择丢弃，对用户体验和数据完整性分别有什么影响？

## 13. 可观测性

### 13.1 增加依赖

修改文件：`tj-aigc/pom.xml`，如果项目还没有 `MeterRegistry`，在 `<dependencies>` 下增加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

是否暴露 Prometheus 端点是部署问题，不是第一阶段代码的前置条件。不要为了看指标把所有 Actuator 端点公开到公网。

### 13.2 建议指标

Micrometer 代码中建议使用点号命名，导出到 Prometheus 后通常会转换成下划线：

| Micrometer 名称 | 标签 |
| --- | --- |
| `jev.route.requests` | `mode=shadow/main` |
| `jev.route.decisions` | `agent`、`source` |
| `jev.route.fallbacks` | `reason`、`agent` |
| `jev.route.errors` | `reason` |
| `jev.route.disagreements` | `jevAgent`、`llmAgent` |
| `jev.route.latency` | `mode` |
| `jev.route.confidence` | `agent` |
| `jev.shadow.rejected` | 无 |

不要把以下内容作为标签：

- 用户问题。
- sessionId、userId、requestId。
- 异常 message。
- 模型返回的任意自由文本。

这些字段会造成高基数或隐私问题。requestId 只放结构化日志，不放指标标签。

### 13.3 一次决策应能回答的问题

通过日志和指标，至少可以回答：

- Jev 今天被调用多少次？
- 成功、超时、限流、鉴权失败各多少？
- 每类 Agent 的平均 confidence 分布如何？
- Jev 与 LLM 最常在哪两类之间发生分歧？
- 开启主路由后有多少请求回退？
- P50、P95、P99 路由耗时是否优于原 LLM？
- 实际响应模型是不是配置中固定的版本？

## 14. 测试实施顺序

测试不要最后一次性补。按下面顺序，每写完一层立即测试。

### 14.1 RoutingTextSanitizerTest

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/decision/context/RoutingTextSanitizerTest.java`。

纯单元测试，不启动 Spring：

- 正常文本保持不变。
- 手机、邮箱、身份证、长数字分别替换。
- null 和空字符串安全处理。
- 课程短 ID 不被误删。

### 14.2 RoutingContextBuilderTest

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/decision/context/RoutingContextBuilderTest.java`。

使用 Mockito 模拟 `ChatMemory`：

- 设置 `UserContext.setUser(1L)`。
- 每个测试后 `UserContext.removeUser()`，避免 ThreadLocal 污染其他测试。
- 验证最近消息、顺序、截断和只读行为。

### 14.3 JevDecisionClientTest

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/decision/client/JevDecisionClientTest.java`。

使用本地 `HttpServer`，验证：

- 请求契约。
- 正常响应解析。
- 401、422、429、529、500 映射。
- 超时映射。
- 日志和异常不包含 test-key。

### 14.4 JevRouteDecisionServiceTest

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/decision/service/impl/JevRouteDecisionServiceTest.java`。测试包名与实现类一致：`com.tianji.aigc.decision.service.impl`。

Mock 客户端响应，覆盖：

- 四种正常 choice。
- choice 为 `ROUTE`。
- 未知 choice。
- 缺少 `route_agent`。
- `type` 不是 choice。
- confidence 为 null、NaN、负数或大于 1。
- 概率缺失、越界、总和明显不为 1。

### 14.5 FallbackRouteDecisionServiceTest

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionServiceTest.java`。测试包名与实现类一致：`com.tianji.aigc.decision.service.impl`。

这是第一阶段最重要的单元测试：

- `provider=LLM` 时不调用 Jev。
- Shadow 时实际结果来自 LLM，异步提交 Jev 对照。
- Jev 高置信度时直接采用 Jev。
- Jev 低置信度时回退 LLM，reason 为 `LOW_CONFIDENCE`。
- Jev 各类异常均回退一次。
- fallback disabled 时进入安全默认，不调用 LLM。
- Jev 和 LLM 都失败时使用 `KNOWLEDGE`。
- BUY 可以配置更高阈值。

使用 Mockito 的 `verify(..., never())` 和 `times(1)` 明确验证“没有多调用”和“只回退一次”。

### 14.6 AgentServiceImplTest

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/service/impl/AgentServiceImplTest.java`。

如果已改为构造注入 `List<Agent>`，这个测试会很简单：

- 决策为每个枚举时找到正确 Agent。
- `ROUTE` 或未知结果不产生空指针。
- 调用的是专项 Agent `processStream()`。
- 路由服务不负责最终 SSE 内容。
- `stop()` 行为未被破坏。

### 14.7 ChatMemory 集成测试

新建测试文件：`tj-aigc/src/test/java/com/tianji/aigc/decision/service/impl/JevRouteChatMemoryIntegrationTest.java`。测试包名与实现类一致：`com.tianji.aigc.decision.service.impl`。

使用测试 ChatMemory 或内存 Repository：

1. 预置两条历史消息。
2. 执行 Jev 路由。
3. 断言路由完成后仍为两条。
4. 执行专项 Agent 后，只出现正常的新用户问题和助手回答。
5. 执行 LLM 回退，确认旧的记录清理逻辑仍工作。

### 14.8 推荐命令

编译：

```powershell
mvn -pl tj-aigc -am -DskipTests compile
```

运行 aigc 模块测试，并避免聚合模块因找不到指定测试报错：

```powershell
mvn -pl tj-aigc -am test -Dsurefire.failIfNoSpecifiedTests=false
```

运行指定测试：

```powershell
mvn -pl tj-aigc -am `
  -Dtest=JevDecisionClientTest,FallbackRouteDecisionServiceTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 15. 离线路由评测

### 15.1 数据文件

新建数据文件：`docs/Jev改造/eval/route-eval-v1.jsonl`。

```text
docs/Jev改造/eval/route-eval-v1.jsonl
```

把以下 JSON 对象作为上述 `route-eval-v1.jsonl` 的一行，不要在 JSONL 文件中加入 Markdown 代码围栏：

```json
{"id":"route-0001","history":[],"question":"推荐一门适合零基础的Java课程","expectedAgent":"RECOMMEND","ambiguous":false,"multiIntent":false,"notes":"明确推荐"}
```

不要只从路由提示词复制简单样例。第一版 300～500 条建议按以下比例起步，再根据真实流量修正：

- 每个明确单意图至少 50 条。
- 相邻边界至少 50 条。
- 上下文指代至少 40 条。
- 否定、口语、错别字至少 40 条。
- 多意图、信息不足、越界、提示注入合计至少 70 条。

类别数量尽量接近，避免 `KNOWLEDGE` 占绝大多数造成虚高的总体准确率。

### 15.2 真实 API 评测不要混入普通单测

真实 Jev 评测建议使用独立 JUnit Tag 或单独 main 程序，并满足：

- 环境变量没有 `JEV_API_KEY` 时自动跳过。
- 输出结果写入独立报告，不修改原始标注集。
- 保存实际 `model` 字段、每项概率、confidence、耗时和错误原因。
- 限制并发并处理 429/529。
- 评测输入使用人工构造或脱敏数据。

### 15.3 必须计算的指标

对四种方案分别计算：

1. 关键词规则基线。
2. 原 RouteAgent。
3. Jev Choice。
4. Jev 置信度门控 + RouteAgent 回退。

指标：

- 总体 Accuracy。
- 每个 Agent 的 Precision、Recall、F1。
- 四乘四混淆矩阵。
- 模糊、多意图、指代、注入等切片准确率。
- Jev/LLM 分歧率。
- 各阈值下的自动路由覆盖率与正确率。
- 回退率。
- P50、P95、P99 延迟。
- 错误率和单次成本。

### 15.4 阈值选择方法

不要先认定 `0.85` 就是最终阈值。建议：

1. 把样本按 Agent 和 confidence 区间分桶。
2. 统计每个桶的真实正确率。
3. 分别尝试 0.70、0.75、0.80、0.85、0.90、0.95。
4. 观察阈值提高后自动覆盖率下降多少、准确率提升多少。
5. 为 `BUY` 设置更严格门槛。
6. 在未参与调阈值的保留集上验证一次，避免过拟合。

Jev 官方说明英语是主要训练语言，CJK 可以处理但效果不完全相同。因此必须使用 tjxt 的中文、口语、错别字和上下文指代样本评测，不能直接采用英文厂商示例的效果。

## 16. 从 Shadow 切到主路由

### 16.1 Shadow 配置

修改文件：本地试验用 `tj-aigc/src/main/resources/application-local.yml`；部署环境若由 Nacos 管理，则修改对应的 `aigc-service.yaml`。以下配置覆盖 `tj-aigc/src/main/resources/application.yml` 中的默认值：

```yaml
tj:
  ai:
    chat-type: ROUTE
    route:
      provider: LLM
      shadow-enabled: true
    jev:
      enabled: true
```

### 16.2 启用置信度门控

修改同一环境配置文件：本地为 `tj-aigc/src/main/resources/application-local.yml`，Nacos 环境为对应的 `aigc-service.yaml`。Shadow 数据达到要求后，改为：

```yaml
tj:
  ai:
    route:
      provider: JEV
      shadow-enabled: false
      fallback-enabled: true
      default-confidence-threshold: 0.85
      confidence-thresholds:
        BUY: 0.95
    jev:
      enabled: true
      model: jev-1.13.0
```

上面的数字只是配置示例，必须替换为评测结果。

### 16.3 故障演练

上线前手动验证：

- Key 改错时回退并产生 `AUTH_FAILURE` 告警。
- base URL 指向不可达地址时快速超时并回退。
- 模拟 429、529 和 500。
- 返回未知 Choice。
- 返回缺少 confidence 的 JSON。
- 关闭 fallback 后使用安全默认 Agent。
- Jev 故障期间 SSE 最终回答仍可生成。

### 16.4 一键回滚

修改同一环境配置文件：本地为 `tj-aigc/src/main/resources/application-local.yml`，Nacos 环境为对应的 `aigc-service.yaml`：

```yaml
tj:
  ai:
    route:
      provider: LLM
      shadow-enabled: false
    jev:
      enabled: false
```

回滚不应修改数据库、Redis 历史或删除 Jev 代码。

## 17. 常见问题排查

### 17.1 AgentServiceImpl 没有生效

检查：

- `tj.ai.chat-type` 是否真的为 `ROUTE`。
- 环境变量、Nacos 和 profile 是否覆盖了 `application.yml`。
- 是否同时存在多个 `ChatService` Bean。

### 17.2 所有 Jev 请求都回退 AUTH_FAILURE

检查：

- `JEV_API_KEY` 是否注入进当前进程，而不只是设置在另一个终端。
- Authorization 是否是 `Bearer ` 加 Key。
- 日志中不要输出 Key 来排查，测试 Header 是否存在即可。

### 17.3 中文输入 confidence 普遍偏低

不要立即降低阈值。先检查：

- 四个 criteria 是否真正互斥。
- `recent_context` 是否包含无关长文本。
- 多意图是否被强制单选。
- 越界请求是否被四选一。
- 中文口语和错别字是否是主要错误来源。

然后通过标注集决定改 criteria、调整上下文或进入第二阶段，而不是凭感觉降阈值。

### 17.4 Shadow 模式查不到历史

大概率是在异步线程里调用了 `RoutingContextBuilder`。确认状态是在请求线程构造完成后作为参数传入异步方法。

### 17.5 Shadow 打开后用户仍变慢

检查：

- `@Async` 是否发生 self-invocation。
- 是否误用了无界公共线程池并造成资源竞争。
- 是否在返回 LLM 结果前等待 Jev `Future`。
- 是否在主线程执行真实 Jev 重试。

### 17.6 Jev 正常路由仍出现两条中间记忆

检查：

- Jev 服务是否误继承了 `AbstractAgent`。
- 是否调用了 `RouteAgent.process()` 做二次确认。
- `RoutingContextBuilder` 是否只调用 `chatMemory.get()`。

### 17.7 BUY 路由正确但触发了不希望的操作

路由只表示主要意图，不是业务授权。继续检查：

- 工具是否仅执行预下单，而非最终支付。
- 是否存在用户确认步骤。
- Java 服务是否校验登录、课程存在性、价格和状态。
- Tool 入参是否来自真实查询结果，而不是模型虚构。

## 18. 面试复习提纲

完成手敲后，尝试不看文档回答以下问题。

### 18.1 架构类

1. 为什么 Jev 不实现 Spring AI `ChatModel`，也不继承 `AbstractAgent`？
2. 为什么需要区分外部 API DTO 和项目领域对象？
3. `JevDecisionClient`、`JevRouteDecisionService`、`FallbackRouteDecisionService` 各自职责是什么？
4. 为什么保留原 RouteAgent，而不是一次性替换？
5. 为什么 AgentService 只依赖统一 `RouteDecisionService`？

回答关键词：单一职责、依赖倒置、适配器、策略、编排、可回滚。

### 18.2 稳定性类

1. Jev 超时时为什么回退而不是持续重试？
2. 429、529、401、422 的处理有何不同？
3. “fail-open 到旧 LLM”为什么不等于“允许高风险操作继续执行”？
4. 为什么回退只能执行一次？
5. 安全默认为什么选择无工具 Agent？

回答关键词：延迟预算、故障隔离、降级、幂等、风险分级。

### 18.3 并发与上下文类

1. `UserContext` 为什么在 Shadow 异步线程中可能丢失？
2. 为什么要在请求线程构造 RoutingState？
3. 为什么 Shadow 使用有界线程池？
4. `@Async` self-invocation 为什么不会异步？
5. 拒绝策略选择 DiscardPolicy 的代价是什么？如何监控？

回答关键词：ThreadLocal、线程切换、代理、背压、资源隔离。

### 18.4 模型评测类

1. probability 和 confidence 有什么区别？
2. 为什么 confidence 高仍可能判断错误？
3. 为什么不能只看总体 Accuracy？
4. 如何确定 BUY 的阈值？
5. Shadow 分歧样本为什么比一致样本更值得人工复核？

回答关键词：校准、类别不平衡、Precision/Recall/F1、混淆矩阵、覆盖率。

### 18.5 数据安全类

1. 路由为什么不需要真实用户 ID 和完整订单？
2. 为什么 sessionId 应哈希后再记录？
3. 为什么用户问题不能作为指标标签？
4. 正则脱敏有哪些局限？
5. 外部服务声称不训练输入，为什么仍需最小化传输？

回答关键词：最小权限、数据最小化、高基数、跨境处理、纵深防御。

## 19. 建议的手敲顺序

每完成一项打勾，不要跳着写：

- [ ] 运行并记录原 RouteAgent 基线。
- [ ] 明确四类 Agent 的业务边界。
- [ ] 新增 `RouteProvider`、`RouteFallbackReason`、`RouteDecisionSource`。
- [ ] 新增 `JevProperties`、`RouteProperties` 和 YAML。
- [ ] 新增 Jev 请求、响应 DTO。
- [ ] 新增 `JevClientConfig` 和 `RestClient`。
- [ ] 新增 `RoutingTextSanitizer` 及测试。
- [ ] 新增 `RoutingContextBuilder` 及测试。
- [ ] 新增 `JevDecisionClient` 及本地 HTTP 契约测试。
- [ ] 新增 `RouteDecision`。
- [ ] 新增 `LlmRouteDecisionService`。
- [ ] 新增 `JevRouteDecisionService` 及严格响应校验测试。
- [ ] 新增 `FallbackRouteDecisionService` 及门控、回退测试。
- [ ] 修改 `AgentServiceImpl` 使用最终 RouteDecision。
- [ ] 验证 Jev 正常路由不写入 ChatMemory。
- [ ] 新增专用 Shadow 线程池。
- [ ] 新增异步对照服务，验证不影响实际路由。
- [ ] 新增结构化日志和 Micrometer 指标。
- [ ] 建立不少于 300 条的路由评测集。
- [ ] 输出四种方案的评测结果。
- [ ] 根据真实数据设置各 Agent 阈值。
- [ ] 完成故障演练与配置回滚。

## 20. 第一阶段验收清单

只有下面各项全部满足，才算第一阶段完成：

- [ ] Jev 请求只有一个真正被下游消费的 Choice 判断。
- [ ] Jev 只能产生四种允许的专项 Agent。
- [ ] 未知类型、非法概率、缺少字段不会进入 Agent 执行链路。
- [ ] 正常 Jev 路由不调用 RouteAgent，不写入路由中间记忆。
- [ ] 低置信度、超时、网络、429、529、401/403、422、5xx 都有固定回退原因。
- [ ] LLM 回退最多执行一次。
- [ ] Shadow 模式实际路由始终来自原 RouteAgent。
- [ ] Shadow 不依赖异步线程中的 UserContext。
- [ ] API Key、完整问题、原始 sessionId 和订单敏感信息不进入普通日志或指标标签。
- [ ] BUY 的错误和阈值单独统计。
- [ ] 四类 Agent 的 Precision、Recall、F1 和混淆矩阵已生成。
- [ ] P50、P95、P99 延迟和回退率已生成。
- [ ] 通过配置可以在不改代码的情况下切回 LLM。
- [ ] 推荐、咨询、购买、知识讲解、停止生成、ToolResultHolder 和 SSE 均通过回归测试。

## 21. 参考资料

- TypeSafe Quick Start：<https://docs.typesafe.ai/introduction/quickstart>
- TypeSafe API Reference：<https://docs.typesafe.ai/api>
- TypeSafe Choice：<https://docs.typesafe.ai/primitives/choice>
- TypeSafe Confidence：<https://docs.typesafe.ai/confidence>
- TypeSafe Intent Routing：<https://docs.typesafe.ai/patterns/intent-routing>
- TypeSafe Confidence-gated Routing：<https://docs.typesafe.ai/patterns/confidence-routing>
- TypeSafe Models：<https://docs.typesafe.ai/models>
- 项目改造计划：`docs/Jev改造/Jev改造计划.md`
- 当前路由入口：`tj-aigc/src/main/java/com/tianji/aigc/service/impl/AgentServiceImpl.java`
- 当前路由 Agent：`tj-aigc/src/main/java/com/tianji/aigc/agent/RouteAgent.java`
- 当前 Agent 骨架：`tj-aigc/src/main/java/com/tianji/aigc/agent/AbstractAgent.java`
- 当前记忆清理：`RecordOptimizationAdvisor.java`、`RedisChatMemoryRepository.java`
