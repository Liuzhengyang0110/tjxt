# 天机学堂 AI 对话项目面试复习

本文档根据 `tj-aigc` 模块的实际代码整理，适合 Java 后端项目面试前快速复习。回答采用“结论—原理—项目落地—改进方向”的结构，面试时可根据时间适当缩短。

## 一、项目核心介绍

本项目在天机学堂中提供 AI 对话能力。前端先创建会话，再携带 `sessionId` 提交问题；后端通过 Spring AI 的 `ChatClient` 调用大模型，并使用 `Flux` 和 SSE 向前端流式返回结果。MySQL 保存会话基本信息，Redis 保存具体聊天消息，`MessageChatMemoryAdvisor` 负责在模型调用前后自动加载和更新会话记忆。

主要技术点：

- Spring Boot、Spring MVC/WebFlux
- Spring AI `ChatClient`
- Reactor `Flux` 与 SSE 流式响应
- MyBatis-Plus 与 MySQL
- Redis 会话记忆
- Nacos 动态系统提示词
- Advisor 增强机制

## 二、项目面试问题

### Q1：`ChatSession` 实体类有什么作用？

**难度：基础**

**简要回答：**

`ChatSession` 是 MyBatis-Plus 实体类，对应数据库中的 `chat_session` 表。`@TableName` 指定表名，Java 驼峰属性会按照配置映射到数据库下划线字段。实体类主要负责描述表结构和承载一行数据，真正的增删改查由 Mapper 和 Service 完成。

**可能追问：**

- Entity、DTO、VO 有什么区别？
- MyBatis-Plus 如何识别主键和完成字段映射？

### Q2：为什么同时设计 `id` 和 `sessionId`？

**难度：中等**

**简要回答：**

`id` 是数据库内部主键，使用 `IdType.ASSIGN_ID` 由 MyBatis-Plus 自动生成，适合索引、关联和通用 CRUD。`sessionId` 是业务会话编号，通过 UUID 生成并暴露给前端，用于聊天、停止生成和查询历史记录。二者分离后，业务编号规则变化不会影响数据库主键设计。

**补充要点：**

- `sessionId` 业务上应保证唯一，可以考虑增加唯一索引。
- 不建议把数据库内部主键直接作为所有外部接口的业务标识。

**可能追问：**

- 为什么不直接用 `sessionId` 做主键？
- `ASSIGN_ID` 与数据库自增 ID 有什么区别？

### Q3：为什么接口返回 `SessionVO`，不直接返回 `ChatSession`？

**难度：基础**

**简要回答：**

实体类面向数据库，VO 面向前端展示。`SessionVO` 只返回前端需要的会话 ID、助手标题、描述和示例问题，不暴露数据库主键、用户 ID、创建人等内部字段。使用 VO 还能组合数据库、配置文件和计算结果，避免接口结构与数据库表强耦合。

**可能追问：**

- DTO 和 VO 有什么区别？
- 直接返回实体类有哪些风险？

### Q4：`@RequestMapping`、`@GetMapping` 和 `@PostMapping` 有什么区别？

**难度：基础**

**简要回答：**

`@RequestMapping` 是通用映射注解，可以放在类上定义公共路径，也可以指定具体 HTTP 方法。`@GetMapping` 专门处理 GET 请求，通常用于查询；`@PostMapping` 专门处理 POST 请求，通常用于新增数据或提交操作。类路径和方法路径会拼接成最终接口地址。

**项目示例：**

```text
POST /session                 创建会话
GET  /session/hot?n=3         获取热门问题
GET  /session/{sessionId}     查询聊天历史
POST /chat                    提交聊天问题
POST /chat/stop               停止生成
```

**可能追问：**

- GET 和 POST 的幂等性有什么区别？
- `@RequestMapping(method = GET)` 与 `@GetMapping` 有什么关系？

### Q5：`@RequestParam` 和 `@RequestBody` 有什么区别？

**难度：基础**

**简要回答：**

`@RequestParam` 通常从 URL 查询字符串中读取简单参数，例如 `/session/hot?n=3`。`@RequestBody` 从 HTTP 请求体读取 JSON，并反序列化成 DTO。聊天问题可能较长，还可能包含换行、代码和特殊字符，所以项目使用 JSON Body 接收 `question` 和 `sessionId`。

**项目示例：**

```java
@RequestParam(value = "n", defaultValue = "3") Integer num
```

```java
@RequestBody ChatDTO chatDTO
```

对应的聊天请求体：

```json
{
  "question": "什么是 Spring Boot？",
  "sessionId": "abc123"
}
```

**可能追问：**

- POST 请求能不能使用 `@RequestParam`？
- Body 是否天然比 URL 参数安全？

### Q6：`ChatClient` 如何实现流式输出？

**难度：中等**

**简要回答：**

项目使用 `ChatClient.stream()` 获取大模型的流式响应，再使用 `Flux<ChatEventVO>` 持续向 Controller 传递响应分片。Controller 声明 `produces = MediaType.TEXT_EVENT_STREAM_VALUE`，通过 SSE 把每个分片发送给前端，从而实现打字机效果。链路中不能调用 `block()` 或先 `collectList()`，否则会等待全部内容生成后再返回。

**核心代码：**

```java
chatClient.prompt()
        .user(question)
        .stream()
        .chatResponse();
```

**可能追问：**

- `Flux` 和 `Mono` 有什么区别？
- SSE 和 WebSocket 有什么区别？
- 什么操作会破坏端到端流式响应？

### Q7：`SpringAIConfig` 的作用是什么？

**难度：中等**

**简要回答：**

`SpringAIConfig` 负责组装 Spring AI 的核心组件。它创建 `ChatClient`、日志 Advisor、`ChatMemory` 和自定义 Redis Repository，并把 `MessageChatMemoryAdvisor` 注册为 `ChatClient` 的默认 Advisor。这样业务代码调用 `ChatClient` 时，就能自动记录日志、加载历史消息并保存本轮消息。

**Bean 依赖关系：**

1. 创建 `RedisChatMemoryRepository`。
2. 将 Repository 注入 `MessageWindowChatMemory`。
3. 将 `ChatMemory` 注入 `MessageChatMemoryAdvisor`。
4. 将日志 Advisor 和记忆 Advisor 注入 `ChatClient`。

**可能追问：**

- `@Configuration` 和 `@Bean` 分别有什么作用？
- Spring 如何确定这些 Bean 的创建顺序？
- 多个相同类型的 Advisor 如何区分？

### Q8：`ChatMemory` 对 `RedisChatMemoryRepository` 的依赖在哪里体现？

**难度：基础**

**简要回答：**

代码层面，`ChatMemory` 依赖的是 `ChatMemoryRepository` 接口；运行时，Spring 注入的实际对象是 `RedisChatMemoryRepository`。依赖首先体现在 `chatMemory()` 方法的参数中，然后通过 Builder 的 `.chatMemoryRepository(chatMemoryRepository)` 设置到 `MessageWindowChatMemory` 内部。

```java
@Bean
public ChatMemory chatMemory(ChatMemoryRepository repository) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(maxMessages)
            .build();
}
```

这是面向接口编程，后续可以替换为 JDBC 等其他存储实现，而不需要修改上层 `ChatMemory` 的使用方式。

### Q9：`ChatMemory`、`ChatMemoryRepository` 和 Advisor 分别负责什么？

**难度：中等**

**简要回答：**

`ChatMemoryRepository` 负责消息的底层持久化，例如读写 Redis；`ChatMemory` 负责消息窗口的添加、读取、删除和数量限制；`MessageChatMemoryAdvisor` 负责在大模型调用前读取历史消息，在调用后保存本轮问题和回答。可以概括为：Advisor 决定什么时候读写，ChatMemory 决定如何管理，Repository 决定存在哪里。

**注意：**

`maxMessages=100` 表示每个会话的消息窗口最多管理 100 条消息，不是最多保存 100 个会话。

### Q10：`.advisors(...CONVERSATION_ID...)` 有什么作用？

**难度：中等**

**简要回答：**

这一步给本次模型请求的 Advisor 上下文设置 `conversationId`，让 `MessageChatMemoryAdvisor` 知道应该读取和保存哪一组历史消息。项目使用“用户 ID + sessionId”组成内部对话 ID，例如 `100_abc123`，从而隔离不同用户和不同会话。它本身不直接访问 Redis，也不会直接把这个 ID 发给大模型。

```java
.advisors(spec -> spec.param(
        ChatMemory.CONVERSATION_ID,
        conversationId
))
```

如果不正确设置会话 ID，可能无法读取当前会话的历史记录，或者使多个请求错误地共用默认记忆。

**可能追问：**

- 为什么还要拼接用户 ID？
- 如何防止用户查询其他人的聊天历史？

### Q11：`RedisChatMemoryRepository` 如何保存聊天消息？

**难度：中等**

**简要回答：**

项目为每个 `conversationId` 创建一个带有 `CHAT:` 前缀的 Redis Key，例如 `CHAT:100_abc123`，并使用 Redis List 按顺序保存消息 JSON。保存时将 `Message` 转成 JSON 后执行 `rightPush`；读取时通过 `range(0, -1)` 获取全部元素，再恢复为 Spring AI 的消息对象。

**方法职责：**

| 方法 | 作用 |
| --- | --- |
| `findConversationIds()` | 查询已有对话 ID |
| `findByConversationId()` | 读取指定会话的全部消息 |
| `saveAll()` | 保存指定会话的消息列表 |
| `deleteByConversationId()` | 删除指定会话的聊天记录 |

**可能追问：**

- 为什么使用 Redis List？
- 为什么不直接把消息存在 MySQL？
- 聊天记录如何设置过期时间？

### Q12：为什么需要 `MessageUtil`？

**难度：中等**

**简要回答：**

Spring AI 的 `Message` 是接口，实际包含 `SystemMessage`、`UserMessage`、`AssistantMessage` 和 `ToolResponseMessage` 等实现。直接把 JSON 反序列化成 `Message` 时，程序不知道应该创建哪个具体类型。因此项目先把消息转换成统一的 `MyMessage`，显式保存 `messageType`、文本、媒体和工具调用等字段；读取时再根据消息类型创建相应的具体消息对象。

**转换过程：**

```text
保存：具体 Message → MyMessage → JSON → Redis
读取：Redis JSON → MyMessage → 具体 Message
```

**可能追问：**

- 什么是多态反序列化？
- 为什么 `AssistantMessage` 要单独保存 `toolCalls`？
- 新增消息类型时如何扩展这段代码？

### Q13：项目如何实现停止生成？为什么使用 Redis？

**难度：困难**

**简要回答：**

开始生成时，项目把 `sessionId` 写入 Redis Hash；每收到一个模型响应分片，就通过 `takeWhile` 判断该标记是否存在。停止接口删除标记，下一次判断发现标记不存在后，Flux 结束。使用 Redis 而不是普通本地数组或集合，是为了让多个服务实例共享状态，避免聊天请求和停止请求落到不同实例后停止失效。

**当前方案的问题：**

- 每个响应分片都查询一次 Redis，网络请求频繁。
- `StringRedisTemplate` 是阻塞式客户端，可能阻塞响应式处理线程。
- 客户端断开或服务异常退出时，状态可能清理不完整。
- 只判断存在性时，Set 比固定值为 `true` 的 Hash 更符合语义。

**优化思路：**

- 单实例使用本地线程安全集合或响应式取消信号。
- 分布式环境使用 Redis Pub/Sub 发送停止事件。
- 保存 `Disposable`、`Sinks` 等取消句柄，直接取消流。
- 使用 `doFinally` 统一清理状态。

### Q14：MySQL 和 Redis 在项目中如何分工？

**难度：中等**

**简要回答：**

MySQL 的 `chat_session` 表保存会话元数据，例如会话 ID、用户 ID、标题和创建时间，负责回答“有哪些会话”。Redis 保存具体的用户问题和 AI 回答，负责回答“每个会话说了什么”。这样可以利用 MySQL 的持久化和查询能力，同时利用 Redis 支撑聊天上下文的高频读写；如果需要永久保留消息，可以再异步归档到数据库。

### Q15：项目如何从 Nacos 读取和动态更新系统提示词？

**难度：中等**

**简要回答：**

`AIProperties` 从本地配置中读取系统提示词的 `dataId`、`group` 和超时时间。`SystemPromptConfig` 在 `@PostConstruct` 方法中调用 Nacos `ConfigService.getConfig()` 获取提示词，并保存在 `AtomicReference<String>` 中；随后注册 Listener，Nacos 配置发布后会更新这个引用。聊天时每次调用 `get()` 读取最新提示词，因此不需要重启服务。

**可能追问：**

- 为什么使用 `AtomicReference`？
- `spring.config.import` 与主动调用 `getConfig()` 有什么区别？
- Nacos 配置读取失败后应该如何降级？

### Q16：当前 Redis 会话记忆实现有哪些优化空间？

**难度：困难**

**简要回答：**

第一，`findConversationIds()` 使用 `KEYS CHAT:*`，Redis 数据量较大时会遍历全部 Key 并造成阻塞，生产环境更适合使用 `SCAN` 或单独维护会话 ID 集合。第二，`saveAll()` 每次先删除旧 List，再重写全部历史消息，会产生写放大，并且删除和重写不是原子操作。后续可以评估增量追加、Lua 脚本、事务或临时 Key 切换，同时增加合理的过期和清理策略。

**可能追问：**

- `KEYS` 和 `SCAN` 有什么区别？
- 如何保证聊天记录更新的原子性？
- 增量写入如何避免重复消息？

## 三、项目完整口述版本

面试官要求整体介绍 AI 对话模块时，可以参考以下表达：

> 这个模块基于 Spring AI 实现流式多轮对话。前端创建会话后获得业务 `sessionId`，聊天接口使用 POST Body 接收问题和会话 ID。后端通过 `ChatClient.stream()` 调用模型，使用 `Flux` 和 SSE 把结果分片返回前端。会话元数据保存在 MySQL，具体消息通过 `MessageWindowChatMemory` 和自定义 `RedisChatMemoryRepository` 存入 Redis。`MessageChatMemoryAdvisor` 根据“用户 ID + sessionId”生成的 `conversationId` 自动加载和保存上下文。系统提示词通过 Nacos 动态读取和监听更新。项目当前可以支持多轮对话、会话隔离和停止生成，但 Redis 高频轮询、`KEYS` 查询和历史消息全量重写还有优化空间。

## 四、答题方法

回答项目题时建议使用以下顺序：

1. 先用一句话说明结论和组件职责。
2. 解释关键调用链或数据流转过程。
3. 说明为什么选择当前技术方案。
4. 主动补充方案的局限和适用范围。
5. 最后给出能够落地的优化方案。

示例：

> 项目使用 Redis List 保存聊天消息，因为消息天然有顺序，而且读取上下文时通常需要按时间顺序获取。Spring AI 通过 `ChatMemoryRepository` 接口与 Redis 解耦，上层只依赖接口。不过当前 `saveAll()` 会全量重写消息，历史较长时存在写放大，后续可以评估增量追加和原子更新。

## 五、容易答错的细节

- `maxMessages=100` 是每个会话窗口的消息数量，不是会话数量。
- `ChatMemory.CONVERSATION_ID` 不会直接发送给大模型，它是 Advisor 的上下文参数。
- `ChatMemoryRepository` 只负责底层存储，不能单独完成多轮对话。
- `stream()` 决定从模型端流式接收，`Flux` 承载多个结果，SSE 负责通过 HTTP 推送给前端。
- Body 和 Param 是参数位置；GET 和 POST 才是 HTTP 请求方法。
- Redis 中保存具体消息，MySQL `chat_session` 保存会话元数据。
- 本地数组无法解决线程安全、动态扩容和多实例共享问题。
- `Message` 是接口，反序列化时必须恢复成正确的具体消息类型。

## 六、相关代码

- `tj-aigc/src/main/java/com/tianji/aigc/config/SpringAIConfig.java`
- `tj-aigc/src/main/java/com/tianji/aigc/config/SystemPromptConfig.java`
- `tj-aigc/src/main/java/com/tianji/aigc/controller/ChatController.java`
- `tj-aigc/src/main/java/com/tianji/aigc/controller/SessionController.java`
- `tj-aigc/src/main/java/com/tianji/aigc/service/impl/ChatServiceImpl.java`
- `tj-aigc/src/main/java/com/tianji/aigc/service/impl/ChatSessionServiceImpl.java`
- `tj-aigc/src/main/java/com/tianji/aigc/memory/RedisChatMemoryRepository.java`
- `tj-aigc/src/main/java/com/tianji/aigc/memory/MessageUtil.java`
- `tj-aigc/src/main/java/com/tianji/aigc/memory/MyMessage.java`
- `tj-aigc/src/main/java/com/tianji/aigc/entity/ChatSession.java`

技术债和后续优化事项统一记录在项目根目录的 `待改进.md` 中。
