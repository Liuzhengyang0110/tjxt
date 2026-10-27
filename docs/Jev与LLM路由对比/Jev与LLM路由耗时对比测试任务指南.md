# Jev 与 LLM 路由耗时对比测试任务指南

版本：v1.0；编写日期：2026-10-26（Asia/Shanghai）。状态：DOC_COMPLETE；实施任务均为 PLAN，真实测试均为 NOT_RUN。

工作目录：`D:\Project\Java\tjxt`。本文是测试实施指南，只生成指南不代表已运行测试、已授权付费 API 调用或已取得性能收益。

本文档绝对路径：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\Jev与LLM路由耗时对比测试任务指南.md`。

本目录同名 HTML 是本文的排版阅读版，使用黑体标题、宋体正文和全黑文字；任务指令以本 Markdown 为准。修改后应同步重新生成阅读版。

<a id="start"></a>
## 1. 目标与使用方式

要回答的问题是：**在相同样本、固定配置和清楚的运行环境下，Jev 主路由加 LLM 回退，相比原普通 LLM 主路由，能否降低整体路由耗时，同时保持可接受的正确率和可用性？**

主要交付物是一套可复现的 Java 路由采集程序、一套本地统计程序，以及包含原始记录和环境说明的实测报告。最终结论允许“有提升”“无明显提升”或“更慢”，不得预设必须证明优化成功。

建议执行顺序：JLC-01 → JLC-02 → JLC-03 → JLC-04 → JLC-05。每项下方有可独立交给新会话的完整指令。完成一项后先核验产物，再开始下一项。本轮不创建执行会话、不实施这些任务。

首批最小验收为：冻结实验协议；用假响应验证采集和统计；在明确的 API 预算与目标环境下完成 20 条冒烟样本的两组实测。通过后再跑正式集。

本次不开展业务 Agent 回答质量、RAG、工具执行、下单、并发容量或聊天首字延迟测试。它们需要单独实验；路由降低多少毫秒不能直接写成整体回答提速多少。

<a id="evidence"></a>
## 2. 当前仓库证据与差距

生成时 HEAD 为 `90a3f044bc586066dd1fc6796184d5ef8432fd82`，但工作区含已有修改和未跟踪文件，包括 Jev 客户端、RouteAgent、回退服务、配置、Python 评测脚本及测试。**实际基线是“该 HEAD 加当时工作区内容”，不能只用 HEAD 声称可复现。** 后续执行须重新检查并记录相关文件哈希，不覆盖、暂存或提交这些已有改动。

| 证据分类 | 已核实内容 | 当前源码依据 |
| --- | --- | --- |
| 源码已实现 | 统一入口从进入 `decide` 到返回决策计时；成功决策记录总耗时，回退时包含 Jev 与 LLM 的串行成本 | [FallbackRouteDecisionService.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionService.java:41) |
| 源码已实现 | LLM、JEV、Shadow 分支；低置信度和异常回退；LLM 失败可返回 SAFE_DEFAULT | [FallbackRouteDecisionService.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/FallbackRouteDecisionService.java:67) |
| 源码已实现 | LLM 基线调用真实 RouteAgent；RouteAgent 在动态系统提示词后追加固定分类输出协议 | [LlmRouteDecisionService.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/LlmRouteDecisionService.java:23)、[RouteAgent.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/agent/RouteAgent.java:30) |
| 源码已实现 | 默认 ChatClient 绑定 DashScope ChatModel，并经过日志、记忆和记录优化 Advisor；实际部署模型名仍须运行时核实 | [SpringAIConfig.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/config/SpringAIConfig.java:38) |
| 源码已实现 | RouteAgent 的父类会调用会话更新时间；记忆仓库可能写入 Redis；不能假定调用路由完全无外部副作用 | [AbstractAgent.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/agent/AbstractAgent.java:44)、[RedisChatMemoryRepository.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/memory/RedisChatMemoryRepository.java:91) |
| 源码已实现 | Jev 读取当前问题与有限历史，默认上限 4000 字符、8 条历史消息；LLM 记忆窗口另有配置 | [RoutingContextBuilder.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/decision/context/RoutingContextBuilder.java:31)、[JevProperties.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/decision/config/JevProperties.java:19) |
| 源码已实现 | 已有 Jev 延迟与总路由延迟计时器；Shadow 异步记录 Jev 结果，不能作为完整新方案的耗时 | [RouteMetrics.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/decision/metrics/RouteMetrics.java:37)、[ShadowRouteComparisonService.java](D:/Project/Java/tjxt/tj-aigc/src/main/java/com/tianji/aigc/decision/service/impl/ShadowRouteComparisonService.java:23) |
| 配置已声明 | 基础配置 LLM、local 覆盖 JEV；默认门槛 0.85，各类覆盖表为空；连接/读取超时 500ms/1200ms；local Jev 代理默认开启 | [application.yml](D:/Project/Java/tjxt/tj-aigc/src/main/resources/application.yml:34)、[application-local.yml](D:/Project/Java/tjxt/tj-aigc/src/main/resources/application-local.yml:41) |
| 数据与工具已有 | 413 行人工构造样本；按场景组切开发/保留集；Python 有准确率和 nearest-rank 分位数，但 LLM 预测需导入 | [评测 README](D:/Project/Java/tjxt/docs/Jev改造/eval/README.md:7)、[route_eval.py](D:/Project/Java/tjxt/docs/Jev改造/eval/route_eval.py:95) |
| 已有工具限制 | Python 门控耗时由两个结果相加估算；缺耗时使用 0，且现有按样本 ID 导入方式不能直接表达多轮重复采样 | [route_eval.py](D:/Project/Java/tjxt/docs/Jev改造/eval/route_eval.py:114)、[route_eval.py](D:/Project/Java/tjxt/docs/Jev改造/eval/route_eval.py:323) |
| 历史运行线索，未独立复核 | 用户提供的 16:27:31 日志显示 source=JEV、fallbackReason=NONE、model=jev-1.13.0、latencyMs=999；未提供完整日期、对应进程和版本 | 仅证明所述单次路由成功；不证明平均值、P95、准确率或对比收益 |
| 本指南建议新增 | Java 成对批量采集入口、逐请求记录、实验清单、汇总器、正式对比报告 | 具体见 JLC-01～JLC-05；生成时未实施 |

当前调查未发现适用于此目录的磁盘 AGENTS.md 或独立 ADR；遵循本次会话给出的 AGENTS.md 偏好。开始实施时仍需重查。

拆分依据是本次对话约定的“批量入口 → 两组对比 → 冒烟与正式集 → 指标与简历”。既有 [Jev 改造计划](D:/Project/Java/tjxt/docs/Jev改造/Jev改造计划.md) 第 8 节、[第一阶段改造手册](D:/Project/Java/tjxt/docs/Jev改造/Jev第一阶段改造手册.md) 第 15 节和评测 README 只作为背景；本次不重复拆分全部 Jev 改造，也不修改旧方案。

<a id="protocol"></a>
## 3. 必须冻结的实验口径

### 3.1 实验组、计时与测试环境层次

| 组 ID | 行为 | 固定要求 |
| --- | --- | --- |
| LLM_PRIMARY | 原普通 LLM 负责选择四类 Agent | provider=LLM，shadow-enabled=false；保留真实 RouteAgent 提示词、模型与 Advisor 行为 |
| JEV_WITH_FALLBACK | Jev 高置信度直接采用，否则调用同一套普通 LLM | provider=JEV，Jev enabled=true，fallback-enabled=true，shadow-enabled=false；阈值按冻结清单 |

两组统一从调用真实 `FallbackRouteDecisionService.decide(question, sessionId)` 前开始，用 `System.nanoTime()` 计时，返回或抛出后结束；结果写盘、样本历史装载、组间等待和预热均不计入这段耗时。外层计时必须在异常路径也有记录，避免日志仅有成功结果造成幸存者偏差。外层与服务内部计时边界略有不同，在清单中说明，不混算。

Jev 组总耗时必须实测完整门控链路，不能用成功 Jev 子集代替，不能从两组分位数推算回退耗时。可另记 `jevMs`、`llmMs` 帮助解释，无法可靠采集时留 null。

推荐先建立 **ISOLATED_JAVA_ROUTE**：复用真实路由服务、RouteAgent、模型客户端、提示词和记忆 Advisor，仅把会话持久化替换为测试适配器，把记忆持久化替换为支持相同记录优化契约的内存仓库。真实模型请求可用该模式测量，报告须写“隔离 Java 路由服务测试”，不能冒称线上完整链路。

若还要形成 **APPLICATION_ROUTE** 证据，需在已明确授权的隔离部署中保留实际 Redis、会话更新、Advisor、配置加载等应用行为，并通过受限测试入口只调用统一路由入口。不得调用整个 `/chat` 取得路由耗时，也不新增默认开放的生产测试端点。这一层缺环境可暂缓，不阻断前一层交付，两层结果分别成报告。

两个模式都不得替换真实决策结果、关闭记录优化来人为加快基线，或用另写提示词的裸 HTTP LLM 请求冒充当前 RouteAgent。测试适配器差异应完整列出；不具备相同契约时不能标为该模式通过。

### 3.2 样本、历史、顺序和配置

- 先从现有集选取 20 条冒烟样本，覆盖四类 Agent、上下文指代、边界、多意图和注入场景；由固定 ID 清单指定，不按当次测试结果挑题。
- 正式集默认采用现有 413 条，经人工复核后冻结文件与 SHA-256。相似 a/b/c/d 改写按同一场景组处理，说明它们不是 413 个独立业务场景。
- 每个 `(round, sampleId, variant)` 创建独立测试会话，预置同一原始 history，使用一致测试用户，调用完成后清理线程 UserContext 和该测试会话的内存。不得把当前 question 预先加入 history 后又发送一次。
- 两组原始问题、原始历史相同；保留各自当前上下文截断和脱敏策略并记录配置，所以主实验比较的是两套实际路由方案。不要宣称完全相同 token 输入或纯模型推理速度。若另做相同有效上下文实验，使用单独配置与报告。
- 建议并发 1，正式测试 3 轮，每轮每组预热 10 次；预热只记录为 warmup，不进入正式统计。预热不能保证供应商端没有缓存或冷启动，应记录可获得的缓存信息。
- 每轮用固定随机种子打乱样本顺序，并为样本交替/随机采用 AB、BA 顺序，记录 orderIndex、pairOrder 和 seed；组间保持相同固定间隔。正式轮次开始前冻结 seed，不能因结果不理想换种子。
- 两组在独立、不变的配置实例或进程执行；不在共享单例 RouteProperties 上并发切 provider。应用进程模式可由协调程序交替访问两组受限入口。
- 固定模型、Jev 版本、实际 LLM 模型名、模型参数、动态提示词快照哈希、上下文窗口、代理、超时、日志级别、SDK 重试策略和源码文件哈希。Nacos 动态提示词在轮次中变化时标记轮次无效，不静默混合。
- 基线采用当前真实 LLM 模型，不能默认为某个型号；`RouteDecision.model` 在 LLM 路径为 null，需由配置和模型响应补充元数据，不能编造。
- 当前 Java 默认阈值 0.85，BUY 没有独立覆盖；Python 评测另有 BUY 阈值参数，不能直接当 Java 生效配置。首次对比保留核实后的现状；如调优，仅在开发集选阈值，通过 Java `confidence-thresholds` 显式设置并记录新配置 ID，再在保留集验收。
- 3 轮 × 413 条 × 2 组是 2478 次正式路由决策；最坏有 1239 次 Jev 调用和 2478 次 LLM 路由调用，不含预热、冒烟、SDK 重试与调参。执行前需给出真实调用数上限和预算；单价缺失时不估造金额。

### 3.3 逐请求记录与实验清单

建议新增产物统一写到 `D:\Project\Java\tjxt\tj-aigc\target\route-benchmark\<runId>\`，先核实其 Git 忽略规则。不要覆盖既有 `route-eval/report.json`。`runId` 可用上海日期时间加随机后缀，清单同时存 UTC 与 Asia/Shanghai 时间。

| 文件 | 作用 |
| --- | --- |
| manifest.json | schemaVersion、环境层次、源码/数据/提示词哈希、配置 ID、模型、运行环境、限额、测试时间、调度种子、计划记录数和已知差异 |
| schedule.jsonl | 执行前生成的样本、轮次、组别和顺序，作为缺失记录检查依据 |
| requests.jsonl | 每次计划路由的一条完整结果，保留失败记录，支持中断恢复和追加 |
| llm-predictions-round-N.jsonl | 可选兼容导出；单轮每个 ID 一条，禁止重复 ID 静默覆盖 |
| summary.json / summary.md | 逐轮和总体指标、数据完整性、分组切片、对比差值与限制 |
| execution.md | 实际命令、开始/结束时间、停止原因和覆盖范围；不含密钥 |

原始记录至少包括：`schemaVersion`、`runId`、`configId`、`environmentLevel`、`round`、`sampleId`、`variant`、`orderIndex`、`pairOrder`、`warmup`、`expectedAgent`、`actualAgent`、`source`、`fallbackReason`、`confidence`、`latencyMs`、`status`、`errorType`、`jevModel`、`llmModel`、`jevCallCount`、`llmCallCount`、`startedAt`、`finishedAt`。推荐追加 `scenarioGroupId`、`split`、`tags`、可测的子阶段耗时与重试次数。

唯一逻辑键为 `(runId, configId, round, sampleId, variant, warmup)`；预热样本必须用独立 ID，或在键中增加预热序号，避免冲突。模型计数须定义层次：逻辑服务调用与 SDK 内 HTTP 尝试分别记录；无法观测真实 HTTP 尝试时为 null。

`status` 使用 `OK / SAFE_DEFAULT / ERROR / INTERRUPTED`。合法枚举返回也可能是 SAFE_DEFAULT，不能算正常模型决策。外层异常、超时、限流、无效输出、两个服务都失败、测试程序中断，都应在可确定范围内保留证据。中断时不知道完成时长应为 null，不能写 0 或以截止时间伪装精确值。

一般报告只引用样本 ID，不复制问题、历史或凭据。动态提示词如需保存，仅在本地受限产物中保存已审查的快照；公开报告用哈希与版本标识，禁止把内部完整配置、认证头或 URL 凭据写入清单。

### 3.4 统计定义与结论边界

| 指标 | 定义及注意点 |
| --- | --- |
| 完整性 | 计划、已开始、已完成、失败、中断、缺失、重复分别计数；按组/轮次核对。重复键或缺失配置不允许静默跳过 |
| 路由 P50/P95/P99 | 对该组全部已完成且有精确耗时的请求计算，包括回退、安全默认和已结束错误；nearest-rank：排序后取第 ceil(p×n) 项；报告 n 和耗时缺失数 |
| 正常决策延迟 | 另列 status=OK 子集分位数用于解释；不能替换上一行。存在大量快速错误时，不得靠全请求延迟下降宣称成功 |
| 最终准确率（主指标） | status=OK 且 actualAgent=expectedAgent 的数量 ÷ 计划正式请求数；失败、安全默认和缺失均计错。另列仅按最终标签匹配的准确率，用于解释兜底影响 |
| 各类 Recall | 每个真实类别中，正常正确决策数 ÷ 该类计划样本数；分别列四类，特别检查 BUY 的误路由和漏路由 |
| 回退尝试率 | Jev 组中实际尝试 LLM 回退的决策数 ÷ Jev 组计划数；包含回退后 SAFE_DEFAULT；不要只统计 source=LLM_FALLBACK |
| Jev 直接采用率 | Jev 组中 source=JEV 且 status=OK 的数量 ÷ Jev 组计划数；同时列来源和原因分布 |
| 异常与安全默认 | 分开报告最终 ERROR、SAFE_DEFAULT、Jev 子调用错误、LLM 子调用错误；Jev 出错后成功回退属于成功最终决策，但仍有 Jev 错误 |
| LLM 逻辑调用减少比例 | 1 −（Jev 组 llmCallCount 合计 / LLM 基线组 llmCallCount 合计），在同一完整计划下比较；不能当作成本减少比例 |
| 延迟改善 | (LLM 分位数 − Jev 方案同分位数) / LLM 分位数 × 100%；基线为 0 时为 null。分别算 P50 与 P95，保留负数 |
| 配对差值 | 对相同轮次与样本的两组完整记录算毫秒差，报告可配对/不可配对数量，不能仅保留两边都成功的样本冒充总体 |

逐轮展示结果，再报告总体聚合；不要平均三个 P95 作为总体 P95。P99 仅作探索性数据，需说明样本规模与尾部稳定性。重复轮次用于考察波动，不能把同一 413 条重复三轮宣称为 1239 条独立标注样本。

沿用场景分组的开发/保留切分，报告开发集、保留集、四类 Agent、上下文/多意图等切片。模型一致率只衡量一致性，不能代替人工标签的准确率。若计算置信区间，按场景组重采样并保留两组及轮次关联，说明方法；不是首版强制交付。

是否接受优化必须在 JLC-01 冻结判据。建议起点：同一环境下 P50、P95 都降低；主准确率相对基线下降不超过 1 个百分点；BUY Recall 不下降；最终错误与安全默认比例不升高。**这些是待用户按场景确认的工程建议，不是统计显著性结论，也不是已批准指标。** 未确定判据仍可采集客观数据，但结论为“待裁决”；不调阈值迁就保留集。

<a id="tasks"></a>
## 4. 任务总览、依赖与覆盖

本次新建稳定任务 ID，无旧任务编号需要替换。以下表格承接上一轮建议，不承接旧改造计划中的全部能力。

| ID / 类型 | 目标与模块 | 强依赖及产物 | 交付物 | 验证与资源 | 完成标准 |
| --- | --- | --- | --- | --- | --- |
| [JLC-01](#jlc-01) / 只读调查、协议 | 冻结数据、环境、计时和预算；docs | 无 | 实验协议.md、复核后的样本清单、环境与参数登记 | 静态核对；待用户确认运行目标/预算/成功判据 | 协议无歧义；未确认项有明确影响范围 |
| [JLC-02](#jlc-02) / 代码与本地验证 | Java 采集程序及受控运行入口；tj-aigc 测试支撑 | JLC-01 的协议与字段定义 | 采集代码、假响应验证、使用说明、CODE_COMPLETE 记录 | 本地单元/组件验证；不需要真实模型 Key | 真实服务复用、默认不 live、失败不丢失、无业务 Agent 调用 |
| [JLC-03](#jlc-03) / 代码与本地验证 | 严格统计与报告；测试支撑脚本 | JLC-01 协议、JLC-02 记录契约及 fixture | 汇总器、边界验证、示例报告 | 只读本地产物；无外部资源 | 指标与缺失/重复语义正确；结果可追溯 |
| [JLC-04](#jlc-04) / 真实隔离验证 | 冒烟、正式集、必要时开发集调参后重测 | JLC-02/03 本地 PASS；冻结协议和数据 | 原始逐请求数据、清单、真实汇总、执行记录 | 条件依赖：指定环境、真实 API 配额/预算；应用层另需隔离存储 | 全计划可对账；正常/回退/错误都保留；范围准确 |
| [JLC-05](#jlc-05) / 分析与文档 | 决策、瓶颈和简历材料；docs | JLC-04 实测产物；结论判据已确认或标待裁决 | 测试报告.md、证据索引、简历表述.md | 只读复算与证据审阅 | 结论与原始结果一致，限制充分，不伪造优化 |

共享源码与记录契约必须串行确认。JLC-03 可提前准备统计方案，但最终依赖 JLC-02 的实际契约；JLC-04 不可绕过两项本地验证。没有环境不阻断 JLC-02/03；缺 live 授权时 JLC-04 的执行部分为 BLOCKED。任何“可提前准备”均不授权自动创建聊天或子代理。

| 要求/场景 | 覆盖任务 | 预期证据 |
| --- | --- | --- |
| 当前配置、模型、提示词和工作区版本 | 01、02、04 | 协议、哈希清单、启动后有效配置摘要 |
| 同题同历史、独立会话、AB/BA 调度、预热隔离 | 01、02、04 | schedule、会话隔离验证、warmup 标志 |
| Jev 直接成功、低置信回退、超时、限流、非法结果、LLM 失败安全默认 | 02、03 | 本地故障 fixture；真实采集只记录自然出现故障，不要求向供应商制造故障 |
| 计时包含真实串行回退、失败也有记录 | 02、03、04 | 单调时钟边界、请求记录、总计与子阶段说明 |
| 重复、缺失、中断、恢复、缺耗时 | 02、03 | 唯一键和计划对账、解析错误、不可计算标志 |
| 数据标签、分组切分、阈值防泄漏 | 01、03、04 | 复核说明、数据哈希、开发集调参记录与冻结配置 |
| 速度、质量、可用性和 LLM 调用数 | 03、05 | 逐轮/总体/保留集指标和分母 |
| 实际应用层证据 | 04 条件执行 | 单独环境报告；无环境则明确未验证 |
| 首字延迟、成本、并发容量 | 本轮延期 | 不影响路由对比；分别需客户端流式计时、实际账单价、负载协议，不作未测承诺 |

<a id="parameters"></a>
## 5. 资源、待决参数与停止条件

| 参数/资源 | 建议或现状 | 决定人及执行影响 |
| --- | --- | --- |
| 测试环境层次 | 先 ISOLATED_JAVA_ROUTE；APPLICATION_ROUTE 为条件扩展 | 用户确认真实调用目标；01 可先准备，04 必须有明确范围 |
| 模型、提示词 | Jev 默认 jev-1.13.0；LLM 实际模型和动态提示词未核实 | 执行者采集，用户确认基线；未核实不能出正式对比 |
| 并发/轮次/预热 | 建议 1 / 3 / 每轮每组 10 次 | 01 冻结；配额不足可缩小但需报告覆盖下降 |
| 门控阈值 | 当前默认 0.85；BUY 覆盖为空 | 先保留现状，开发集调优形成新配置 ID |
| 请求间隔与重试 | 间隔按实际配额设定；保留当前真实 SDK 策略 | 不默认扩大重试；记录 HTTP 尝试未知项 |
| 配额与金额上限 | 未提供 Key、价格和预算 | 用户明确允许的供应商、请求上限和预算；凭据仅通过环境或安全配置读取 |
| 存储与配置中心 | 主实验建议内存测试适配；应用层需要明确隔离 Redis/DB/配置目标 | 未授权不得沿用 local 默认连接真实共享环境 |
| 标签复核及质量容差 | 用户或业务复核标签；建议容差见 3.4 | 可先客观测量，正式“达标”结论需已冻结判据 |

遇到持续认证/地区不可用/配额耗尽、预算触顶、配置漂移、数据写入风险或无法中止的超时请求，应停止对应 live 运行并保留部分数据。不可为获得成功结果跳过异常样本。不要通过更换网络出口规避供应商地区限制；先核实可用性与实际目标。

恢复仅针对同一 runId、不可变清单和唯一逻辑键中尚未执行的计划项；已开始但结果不明的请求不能直接重复计入旧逻辑键，应标中断并以新 attempt 标识重试，报告是否可能重复计费。无法证明同配置时创建新 runId。

<a id="jlc-01"></a>
## 6. JLC-01：冻结实验协议与样本

```text
只执行本任务，完成后停止，不自动实施后续任务或创建其他执行会话。

一、身份与目标
任务 JLC-01，类型：只读调查与实验协议编写；承接上一轮“先统一测量口径”的建议。
工作目录 D:\Project\Java\tjxt。
任务源 D:\Project\Java\tjxt\docs\Jev与LLM路由对比\Jev与LLM路由耗时对比测试任务指南.md，v1.0，锚点 jlc-01。
目标是给采集、统计和真实运行提供唯一协议，不开发代码、不启动服务。

二、必须先读与核实
阅读适用 AGENTS、任务源第 2/3/5 节及 docs\Jev改造\eval\README.md。
重新定位 tj-aigc\src\main\java\com\tianji\aigc 下的 decision/service/impl、decision/context、decision/config、agent/RouteAgent.java、agent/AbstractAgent.java、config/SpringAIConfig.java 和两份 application 配置。
核实 Git HEAD、分支、既有修改和未跟踪文件；保护这些内容。源码存在不等于本轮运行通过。

三、前置交付物与门禁
无前置任务。真实环境和预算未提供时，完成静态协议，登记运行待决项，不自行连接核实。

四、允许和禁止范围
仅写 D:\Project\Java\tjxt\docs\Jev与LLM路由对比 内的实验协议.md、样本选择清单和必要说明。
允许只读核对代码、配置与人工构造数据；不改旧数据集、源码、部署配置，不输出任何凭据，不清理工作区。

五、具体步骤
1. 明确两组都调用真实 FallbackRouteDecisionService.decide，固定计时、失败、SAFE_DEFAULT 和回退尝试率的定义。
2. 记录 ISOLATED_JAVA_ROUTE 与 APPLICATION_ROUTE 的差异和选定范围；明确原始历史相同、各自有效上下文策略保留。
3. 检查现有 413 条数据的 ID、类别、场景组和开发/保留切分；选固定 20 条冒烟 ID，覆盖四类及复杂场景。
4. 复核标签；不能自行确定的条目列出争议和建议，请用户或业务确认。若改标签，生成带版本的新副本，不覆盖旧集。
5. 冻结数据哈希、三轮/并发一/预热与 AB/BA 调度建议，以及 schema、计划数、预算上限登记格式。
6. 明确当前真实 LLM 模型/提示词的获取办法，区分静态声明和运行生效值；核实 Java 与 Python 的 BUY 门槛差异。
7. 冻结质量与性能验收判据；用户尚未决定的条目保持 PENDING，不当作已批准。形成实验协议.md。

六、配置与保护
本任务不改运行配置、不迁移数据。阈值、重试、代理和环境资源只登记，不执行。

七、验证与证据
只做文档/JSONL 静态核对、路径/哈希核对；不启动应用、不跑工程测试或 live API。
记录样本行数、去重结果、分组切分和争议项，证明冒烟清单引用的 ID 都存在。

八、完成标准与交付
实验协议可直接驱动 JLC-02；参数有来源，未决项有影响范围；状态 DOC_COMPLETE，运行 NOT_RUN，用户确认 PENDING 或据实际回复登记。
给出绝对路径和后续任务必须读取的协议位置。

九、缺资源处理
没有模型有效配置、预算或环境授权也完成静态内容；标明这些事项阻断 JLC-04，不伪造资源就绪。

十、停用与恢复
不涉及上线或数据回退；后续修改协议要升级版本，并保留旧实验使用的协议身份。

十一、最终回复
报告任务 ID、文件、静态核对、待决参数和未运行范围；到此停止。
```

<a id="jlc-02"></a>
## 7. JLC-02：实现 Java 路由采集程序

```text
只执行本任务，完成后停止，不自动实施后续任务或创建其他执行会话。

一、身份与目标
任务 JLC-02，类型：测试支撑代码及本地验证；承接“补 Java 批量入口”。
工作目录 D:\Project\Java\tjxt。
任务源 D:\Project\Java\tjxt\docs\Jev与LLM路由对比\Jev与LLM路由耗时对比测试任务指南.md，v1.0，锚点 jlc-02。
目标是从同一套真实 Java 路由服务采集两组可对账数据，路由返回后结束。

二、必须先读与核实
阅读适用 AGENTS、任务源第 2/3 节、实验协议.md、现有 eval README。
在 tj-aigc\src\main\java\com\tianji\aigc 内阅读 FallbackRouteDecisionService、LlmRouteDecisionService、JevRouteDecisionService、RouteAgent、AbstractAgent、RoutingContextBuilder、SpringAIConfig、RecordOptimizationAdvisor 和 memory 仓库接口。
参考 src\test\java\com\tianji\aigc\decision\service\impl 的既有测试，特别是 JevRouteChatMemoryIntegrationTest；该测试含 mock，不是 live 延迟证据。
核对实际 HEAD/分支/既有修改和未跟踪内容，不覆盖或提交它们。

三、前置交付物
JLC-01 的实验协议、样本选择与字段契约必须可读取且一致；运行参数待确认不阻断安全代码实现。

四、允许和禁止范围
优先新增 tj-aigc\src\test 下的 benchmark 支撑及测试资源，并在目标 docs 目录写运行说明。
只有证明必要时最小调整 tj-aigc\pom.xml 的显式测试执行入口，保护现有修改；不引入默认启动的 Runner 或公开生产 Controller。
不改业务 Agent、RAG、订单工具、默认生产路由，不连接真实 Redis/DB/Nacos/API，不下载依赖或启动容器来绕过资源缺失。

五、具体步骤与预期行为
1. 使用受控测试入口读取样本、协议与有效配置，生成 manifest 和 schedule；默认 dry-run，只有显式 live 开关与请求上限齐备才允许真实客户端。
2. 复用真实 FallbackRouteDecisionService、两种子服务、RouteAgent 和模型客户端；只在本地验证替换模型响应。正式隔离模式保留真实模型，测试适配持久化。
3. 用支持 MyChatMemoryRepository 优化契约的内存实现接入实际记忆 Advisor；会话更新时间用测试适配器。列明与真实应用差异，验证优化不能删除预置历史。
4. 从已确认的安全配置/快照加载同一基线提示词，保留 RouteAgent 追加的输出协议；保留模型参数与 SDK 重试，并处理当前 Spring 配置需要的请求上下文。不可用新裸 HTTP 提示词替代 RouteAgent。
5. 每组使用固定、独立的配置实例；每条恢复相同 history，设置一致测试 UserContext；调用真实统一入口并在 finally 清理线程上下文。
6. 以单调时钟包围 decide，异常也生成完整记录。采集最终来源、回退原因和实际逻辑调用计数；SAFE_DEFAULT 不当正常模型成功。子阶段未知耗时/HTTP 尝试次数为 null。
7. 依据协议生成 warmup、AB/BA、轮次和固定种子，逐条写 JSONL；拒绝重复逻辑键和配置漂移。读取失败时给脱敏错误，禁止输出 Key、认证头和完整响应体。
8. 加请求上限、可控超时、停止标记和中断落盘；不能可靠取消的已开始请求标未知，不继续堆积线程。dry-run 与 live 用不同 runId，mock 结果永不混入实测。
9. 为每种模式提供真实可复制的执行命令、参数、环境变量名和输出路径。先检查实际 Maven 测试配置，保证普通 test 不自动付费；不用猜测的入口命令冒充可运行命令。
10. 需要 APPLICATION_ROUTE 时仅交付默认关闭、限测试环境的受控方案与资源清单；没有授权不运行应用层，也不影响隔离采集代码完成。

六、配置与保护
新增 benchmark 配置默认关闭 live，凭据只读环境或批准的安全配置；不迁移、不改全局代理，不修改共享 Nacos。
详细请求日志默认关闭或脱敏；记录日志级别，两组一致。检查 target\route-benchmark 是否被 Git 忽略。

七、本地验证与证据
运行资源具备的相关本地单元/组件测试；不执行真实网络。
覆盖直接 LLM、Jev 通过、低置信回退、Jev 超时/429/非法输出、LLM 失败安全默认、无历史/有历史、重复键/中断。
验证只有回退时发生额外 LLM 逻辑调用，总耗时包围完整串行链路；验证 fixture 结束后原始历史和线程用户未被污染。
验证普通测试无 live 调用、无业务 Agent、无工具执行；同 fixture 固定种子生成相同 schedule。
时钟边界可用可控时钟或明确延迟 fixture 验证，不做依赖公网毫秒值的脆弱断言。

八、完成标准与交付
提交代码产物路径、实际执行命令、测试结果和示例 JSONL；标 CODE_COMPLETE，本地 PASS/FAIL/NOT_RUN，真实环境 NOT_RUN。
示例显著标 synthetic，不声称性能收益；文档说明字段/错误/恢复语义。

九、缺资源与失败处理
依赖缺失则交付可完成部分并标受影响验证 BLOCKED，不自动下载。基线配置无法取得时，完成支持加载的代码，真实基线留待运行确认。

十、停用与恢复
采集器默认关闭，去掉显式入口即停用；不得影响正常应用启动。只清理由当前 runId 创建的测试内存/文件，不清空 Redis 或删除未知产物。

十一、最终回复
报告 JLC-02、修改文件、命令与结果、契约版本、剩余限制；不启动冒烟或后续任务。
```

<a id="jlc-03"></a>
## 8. JLC-03：实现统计与报告生成

```text
只执行本任务，完成后停止，不自动实施后续任务或创建其他执行会话。

一、身份与目标
任务 JLC-03，类型：本地统计代码与验证；承接“统一指标与报告”。
工作目录 D:\Project\Java\tjxt。
任务源 D:\Project\Java\tjxt\docs\Jev与LLM路由对比\Jev与LLM路由耗时对比测试任务指南.md，v1.0，锚点 jlc-03。
目标是从计划和逐请求记录生成诚实、可复算的两组对比报告。

二、必须先读与核实
阅读适用 AGENTS、任务源第 3 节、实验协议.md、JLC-02 实际使用说明与 schema/fixture。
只读参考 D:\Project\Java\tjxt\docs\Jev改造\eval\route_eval.py 的 split、percentile、metrics、evaluate 和 test_route_eval.py。
核对工作区已有修改，不覆盖既有 eval 脚本；识别旧格式重复 ID、缺耗时按 0 和估算门控耗时的局限。

三、前置交付物
依赖 JLC-01 的统计口径与 JLC-02 已冻结记录契约及本地 fixture；没有真实数据也应完成本任务。

四、允许和禁止范围
可在 D:\Project\Java\tjxt\docs\Jev与LLM路由对比 新增 benchmark 统计脚本、测试和说明；不改业务代码或旧评测工具语义。
只读输入产物，报告写新目标；不访问网络、不填补虚构耗时、不覆盖原始数据。

五、具体步骤
1. 校验 schemaVersion、manifest、数据/配置身份和唯一键，对照 schedule 找重复、缺失、中断和非法字段；结构错误返回非零状态并保留诊断。
2. 严格分离环境层次、配置 ID、warmup、dry-run/live 和轮次；不把不同配置的最好成绩拼接成一组。
3. 按任务源 3.4 计算全请求与正常子集分位数、主准确率、标签匹配率、各类 Recall、混淆矩阵、回退尝试率、直接采用率、错误/SAFE_DEFAULT 和 LLM 逻辑调用数。
4. 逐轮、总体、开发/保留、类别与复杂场景切片；总体 P95 从总体样本重算。保留分母、耗时样本 n、未知项和不可配对数。
5. 未完成/缺失记录不填 0；为部分运行输出 INCOMPLETE 标记并拒绝生成“正式达标”结论。无耗时样本指标为 null。
6. 对成对记录给出毫秒差，按各组完整样本各算分位数后计算改善比例；不平均百分位，不剔除不利异常，不把一致率当准确率。
7. 输出 summary.json 和黑白 summary.md；报告附环境、时间、哈希、限制与精确复现命令。可选旧格式导出按单轮分文件，禁止静默覆盖重复 ID。
8. 需要图表时必须使用 diagram-design 技能；首版表格足够，不因绘图扩展依赖。成本缺价格/用量则 null，不推导“节省费用”。

六、配置与保护
只采用明确传入的文件路径，不读取 Key 或应用配置秘密；依赖优先标准库。不迁移，不改部署参数。

七、本地验证与证据
用小型人工可计算 fixture 验证 nearest-rank、空集、单样本、不同轮次汇总、负改善、阈值分组和保留集。
覆盖 Jev 失败后 LLM 成功、回退失败 SAFE_DEFAULT、正常 KNOWLEDGE 与同标签 SAFE_DEFAULT 的准确率区分、缺耗时、重复、缺记录和中断。
断言全请求数与计划一致，未知值不当 0；本地单测不请求真实 API。输出示例标 synthetic，不是性能证据。

八、完成标准与交付
给出脚本、相关本地测试 PASS 和实际生成命令；fixture 可手工复核。状态 CODE_COMPLETE，实测 NOT_RUN。

九、缺资源与失败处理
输入 schema 漂移先定位并更新契约版本；不猜字段含义。无真实数据不阻断脚本完成，拒绝编造报告结论。

十、停用与恢复
统计程序可重复只读重算；每次输出新文件或明确版本，保留原始 requests/schedule/manifest。回退脚本版本不改原始证据。

十一、最终回复
报告 JLC-03、代码/测试/示例位置、命令和边界；到此停止，不运行真实测试。
```

<a id="jlc-04"></a>
## 9. JLC-04：运行冒烟与正式对比测试

```text
只执行本任务，完成后停止，不自动实施后续任务或创建其他执行会话。

一、身份与目标
任务 JLC-04，类型：指定环境真实模型验证；承接“先小样本再正式集”。
工作目录 D:\Project\Java\tjxt。
任务源 D:\Project\Java\tjxt\docs\Jev与LLM路由对比\Jev与LLM路由耗时对比测试任务指南.md，v1.0，锚点 jlc-04。
目标是取得可对账的两组真实耗时与质量数据，不预设优化成功。

二、必须先读与核实
阅读适用 AGENTS、实验协议.md、JLC-02/03 的实际命令、测试结果和 schema；重查 HEAD/分支/工作区并记录相关文件哈希。
核实模型、提示词、路由服务、当前配置、环境层次、代理、超时/SDK 重试、日志级别。所有参数以启动后的生效摘要为准，不能只看 YAML。

三、前置产物与真实操作门禁
JLC-02 和 JLC-03 必须本地 PASS；mock/dry-run 不能当 live 通过。
必须已有用户对具体环境层次、模型供应商、样本/轮次、最大请求数/预算及持久化资源范围的授权。已有明确授权不重复询问；本指南本身不自动授权付费或外部写入。
ISOLATED_JAVA_ROUTE 允许真实模型、测试内存持久化；APPLICATION_ROUTE 另需指定隔离 Redis/DB/配置中心资源，不能沿用未知 local 默认连接。

四、允许和禁止范围
仅执行已验证入口，在 tj-aigc\target\route-benchmark\<runId> 生成数据；在目标 docs 目录补执行说明。
不改业务逻辑，不调用下游 Agent/工具，不压测、不向供应商故意制造限流，不清库、不批量删除共享缓存、不发布或切换线上流量。

五、具体步骤
1. 建新 runId，冻结 manifest、schedule、数据与提示词哈希，记录调用上限及停止条件；确认所有产物无凭据。
2. 先 dry-run 验证本次配置的组别、数量、顺序、会话隔离和权限开关，dry-run 单独 runId。
3. 真实跑固定 20 条冒烟清单的两组；检查 source 分布、实际模型、每条耗时和实际调用数，出现回退也照实记录。
4. 运行汇总器对账，确认无业务 Agent、无会话串扰、无配置漂移和缺记录。系统性鉴权/地区/预算故障停止，不通过跳过样本使冒烟通过。
5. 冒烟满足运行条件后按授权跑冻结正式集，建议 3 轮、并发 1、每轮每组预热 10 次、固定 seed 的 AB/BA 调度；所有预热/SDK 重试也受预算限制。
6. 结束时验证计划数、记录数、错误/回退原因、模型及配置身份，生成逐轮/总体/保留集 summary。部分运行保留 INCOMPLETE 状态，不补假记录。
7. 若首次方案慢或准确率不足，先保留首次结果。需要调阈值时仅用开发集，形成新 configId 和预算计划；在冻结后测试保留集，不反复用保留集选参数。
8. APPLICATION_ROUTE 仅在明确授权且环境具备时单独运行，保留真实应用存储/Advisor/配置路径；报告独立。没有资源则该层 BLOCKED，不能把隔离模式升级成应用层证据。

六、配置与保护
不改共享默认配置或 Nacos；测试进程的 provider/threshold 保持不变。Key 只从安全环境读取，不写入命令示例、日志、报告或提交。
按已批准策略处理重试；未知 HTTP 重试次数留 null，不把逻辑调用数宣称为账单调用数。

七、验证与证据
保存 manifest、schedule、requests、summary 和 execution.md，包含实际命令、时间、软件版本、可用模型版本与哈希。
区分自然出现的真实故障和本地模拟故障。真实运行没有覆盖所有故障分支时，引用本地验证，不声称现场全覆盖。

八、完成标准与交付
两组正式计划可完整对账，统计可重复生成；报告指定环境 VERIFIED 或 INCOMPLETE/BLOCKED，负责人状态按实际授权登记。
性能是否达标由冻结判据和真实结果决定；数据完整的“没有提升”也能算实验完成。

九、缺资源与失败处理
缺目标授权、有效模型/提示词、Key、配额或隔离存储时完成 dry-run 就绪检查，实际操作 BLOCKED，列具体缺项。
超预算、配置漂移或请求不可控时停止继续发起，保留已有记录；恢复按 manifest 和唯一键规则，身份无法一致则新 runId。

十、停用与恢复
结束后停测试进程/入口并清线程上下文；只清本次命名空间内明确归属的测试对象。不改变线上路由开关，不删除原始记录。

十一、最终回复
报告 JLC-04、真实运行范围、原始产物绝对路径、执行命令、结果与未覆盖项；到此停止。
```

<a id="jlc-05"></a>
## 10. JLC-05：整理结论、后续优化与简历证据

```text
只执行本任务，完成后停止，不自动实施后续任务或创建其他执行会话。

一、身份与目标
任务 JLC-05，类型：只读分析和结果文档；承接“形成可用于简历的可信结果”。
工作目录 D:\Project\Java\tjxt。
任务源 D:\Project\Java\tjxt\docs\Jev与LLM路由对比\Jev与LLM路由耗时对比测试任务指南.md，v1.0，锚点 jlc-05。
目标是解释结果、作出有边界的接受判断，并给出与证据一致的简历表述。

二、必须先读与核实
阅读适用 AGENTS、实验协议及 JLC-04 的 manifest/schedule/requests/summary/execution。
核对 runId、schema/configId、环境层次、样本版本、模型与提示词、当前代码身份；保护工作区已有内容。

三、前置交付物
需要 JLC-04 真实数据；未完整完成时可以分析部分数据，但不能宣称正式达标。判据未确认则写“待裁决”。

四、允许和禁止范围
仅写 D:\Project\Java\tjxt\docs\Jev与LLM路由对比 内的测试报告.md、简历表述.md 和证据索引；允许只读本地复算。
不重跑模型 API、不改参数/业务实现、不覆盖原始数据、不发布文档或对外发送消息。

五、具体步骤
1. 复算抽查记录与统计，检查分母、缺失、错误、SAFE_DEFAULT、重复、预热和环境是否正确区分。
2. 报告逐轮和总体 P50/P95、主准确率、各类 Recall、回退尝试率、直接采用率、最终失败/安全默认和 LLM 逻辑调用数。
3. 对照冻结判据给出有提升/无明显提升/更慢/证据不足；保留负结果，不挑最好轮次当整体。
4. 区分网络/代理耗时、Jev 处理、上下文读取、回退和 LLM 调用等可能来源；没有子阶段证据只能标推测，不把客户端总耗时当纯推理时间。
5. 若回退多，优先查低置信、超时、限流比例和分组正确率；若 Jev 直接成功也慢，先检查连接/代理/服务区域及上下文开销。仅写后续实验建议，不本轮修改实现。
6. 写出环境限制、人工构造数据与相似改写、样本规模、尾延迟稳定性、模型/提示词漂移和应用层是否验证。
7. 建证据索引，链接真实本地产物并列哈希。target 产物可能被 clean 删除，应在用户指定的本地归档目录保留必要脱敏证据；没有归档授权则记录未归档风险，不擅自上传或大量复制。
8. 简历模板写明测试层次、独立样本数、实际模型、质量指标与整体路由改善；数字只取报告。无收益时描述结构化决策、门控、回退、可观测性和评测建设，不填虚构提速。

六、配置、排版与保护
不迁移、不切配置。Word 或 HTML 的标题/类似标题标签用黑体，正文/列表/表格/页眉页脚用宋体，全部文字 #000000。
不输出 Key、真实用户隐私或内部连接信息。表格足够；若新增解释性图表必须使用 diagram-design。

七、验证与证据
只运行本地只读统计、核对链接/哈希/公式；不把复算等同于新增现场验证。检查报告和简历中的每个数字都有来源。

八、完成标准与交付
DOC_COMPLETE；注明本地复算结果、指定环境证据范围和负责人状态。读者可从结论追溯配置、数据和原始请求。

九、缺资源与失败处理
缺原始记录、哈希或有效配置时标证据不足，列补充项；不以截图/单条 999ms 日志替代完整实验。

十、停用与恢复
本任务无上线变更；修订结论应保留旧报告身份与原因，不删除负结果。简历引用更新时对应更新证据索引。

十一、最终回复
报告 JLC-05、文档绝对路径、是否支持性能优化结论、实测范围和建议下一实验；到此停止。
```

<a id="delivery"></a>
## 11. 最终报告与简历的写法

最终报告至少包含以下内容：实验问题和结论；源码/工作区/配置/数据身份；两组实际行为和适配差异；采样顺序与预算执行情况；数据完整性；逐轮与总体指标；保留集质量；回退和失败分析；可复现命令；适用范围与待补证据。

下面只是模板，没有任何占位数字被当作测试结果：

> 将多 Agent 意图路由改造为 Jev 结构化决策与置信度门控，保留普通 LLM 异常回退并建设对比评测。在【环境层次】的【N 条人工标注样本、R 轮】测试中，相比【实际 LLM 模型】基线，整体路由 P50 从【A】ms 降至【B】ms，P95 降低【C】%，主准确率【D】%，路由阶段 LLM 逻辑调用减少【E】%。

只有真实数据支持时才使用“降低”。若仅完成隔离测试，必须在项目说明或面试回答中说清；不得写“线上”“生产流量”“首字加速”“成本下降”或“高并发性能提升”等未验证内容。当前单次 Jev=999ms 只能作为接入成功的历史线索。

<a id="status"></a>
## 12. 状态记录与交接

| 任务 | 文档/代码 | 本地验证 | 指定环境 | 用户/负责人 |
| --- | --- | --- | --- | --- |
| 指南本身 | DOC_COMPLETE v1.0 | 文档静态检查；不代表工程测试 | NOT_RUN | 本轮已授权生成 |
| JLC-01 | PLAN | NOT_RUN | NOT_RUN | PENDING |
| JLC-02 | PLAN | NOT_RUN | NOT_RUN | PENDING |
| JLC-03 | PLAN | NOT_RUN | NOT_RUN | PENDING |
| JLC-04 | PLAN | NOT_RUN | NOT_RUN | PENDING |
| JLC-05 | PLAN | NOT_RUN | NOT_RUN | PENDING |

开始执行时按实际证据更新任务状态。`CODE_COMPLETE` 不等于 live `VERIFIED`；本地 mock `PASS` 不等于供应商调用成功；报告完整不等于优化达标。

若用户后续明确要求新会话执行，可向编排工具提供：本指南绝对路径、v1.0、任务 ID 与锚点、工作目录、已批准运行范围，以及前置产物路径。默认先执行 JLC-01；每项完成后停止。仅交付本文不触发任何编排、测试或付费调用。

变更记录：2026-10-26，v1.0，依据当前工作区和本次对话生成 5 项任务；所有运行结论保持未确认。
