# JLC-02 采集器验收

状态：CODE_COMPLETE；本地 PASS；LIVE NOT_RUN。日期：2026-10-26（Asia/Shanghai）。协议 `jlc-protocol-v1`，schema `route-benchmark-v1`。本任务不启动 JLC-03 或真实测试，由根会话核验后串行继续。

## 1. 实施产物

新增 Java 测试支撑目录：`D:/Project/Java/tjxt/tj-aigc/src/test/java/com/tianji/aigc/benchmark/`。

| 文件 | 用途 |
| --- | --- |
| RouteBenchmark.java | 计划、门禁、哈希、计时、逐条记录、停止与中断 |
| BenchmarkRuntime.java | 两组真实路由组件与模型、Advisor 的隔离组合 |
| BenchmarkMemory.java | 同时实现两类记忆仓库接口的内存持久化适配 |
| RouteBenchmarkTest.java | 20 项本地组件/边界测试 |
| RouteBenchmarkLauncherTest.java | 显式操作入口，普通 test 默认跳过 |

本目录新增：`采集器使用说明.md`、`Invoke-RouteBenchmark.ps1`、`logback-benchmark.xml`、`benchmark-synthetic.json`、`benchmark-live.example.json`、本验收文档。未修改原指南、协议、业务源码、线上配置、旧 eval 工具或 POM；未提交、未推送。

## 2. 实际执行与结果

首次只用 `-pl tj-aigc` 的离线命令缺少本地未安装的 tj-api/tj-auth-resource-sdk。改为 `-am` 离线 reactor 编译同仓库依赖，未安装依赖、未联网下载。

最终完整验证命令（已实际执行）：

```powershell
Set-Location 'D:/Project/Java/tjxt'
& 'D:/Tools/maven/apache-maven-3.9.11/bin/mvn.cmd' -o -pl tj-aigc -am '-Dmaven.repo.local=D:/Tools/maven/maven-repository' '-Dtest=com.tianji.aigc.benchmark.RouteBenchmarkTest,com.tianji.aigc.benchmark.RouteBenchmarkLauncherTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dbenchmark.run=true' '-Dbenchmark.root=D:/Project/Java/tjxt' '-Dbenchmark.config=D:/Project/Java/tjxt/docs/Jev与LLM路由对比/benchmark-synthetic.json' '-Dlogback.configurationFile=D:/Project/Java/tjxt/docs/Jev与LLM路由对比/logback-benchmark.xml' test
```

结果：BUILD SUCCESS；20 组件测试 + 1 显式 synthetic launcher，21 run / 0 failure / 0 error / 0 skipped。结束时间 17:26:56 +08:00。日志：`D:/Project/Java/tjxt/tj-aigc/target/route-benchmark/jlc02-final-test-and-fixture.log`。此前不带 benchmark.run 的普通验证为 20 项 PASS、操作入口 1 项 SKIP，证明默认不执行采集。

实际 PowerShell 脚本验证命令：

```powershell
& 'D:/Project/Java/tjxt/docs/Jev与LLM路由对比/Invoke-RouteBenchmark.ps1' -Config 'D:/Project/Java/tjxt/tj-aigc/target/route-benchmark/jlc02-dry-config.json'
```

结果：BUILD SUCCESS；1 launcher PASS，结束 17:27:53 +08:00；`jlc02-dry-run-v1` 为 DRY_RUN / PLANNED，60 项计划（40 正式 + 20 预热），requests.jsonl 为 0 字节，未构造或调用真实模型。日志：`D:/Project/Java/tjxt/tj-aigc/target/route-benchmark/launcher-20261026-172724-0d10ff.log`。未重复运行同 runId。

## 3. 交付 fixture 与复核

目录：`D:/Project/Java/tjxt/tj-aigc/target/route-benchmark/jlc02-synthetic-fixture-v1/`。

该运行明确为 SYNTHETIC / COMPLETE，40 计划 = 40 记录，warmup=0 是 fixture 显式覆盖。35 条 OK、5 条 SAFE_DEFAULT；来源为 JEV 6、LLM_FALLBACK 12、LLM_PRIMARY 17、SAFE_DEFAULT 5。固定假响应不读取 expectedAgent；上述数据不是供应商性能或准确率证据。

| 文件 | SHA-256 |
| --- | --- |
| manifest.json | 379399134badc26a6ff4cb97ecc62af0ac63cbd7100957bce689d8f00db36595 |
| schedule.jsonl | b6a55635a7ba3089ddfcdd802ac79c42299c73fee609eacb605b209f87485024 |
| requests.jsonl | 0d263d9f9b139194375c759bcf149749f29c232c6048a8413c4db5c26afa02e8 |

收尾复核：fixture 清单内 114 项采集器源码/测试编译文件/业务编译文件哈希与当前磁盘完全一致，差异 0。根会话基线 workspace-before.json 的 21 个受保护已有文件全部哈希不变。根会话也独立核验了 fixture 的 24 个必填字段和唯一键。

## 4. 合同与验证范围

必填 schema 未变。附加字段见《采集器使用说明》；中断可能附加 submittedAt/actualRouteStartedAt/startedAtSemantics/httpAttemptCountObservedAtStop/completionUnknown。不可测的 jevMs/llmMs/retryCount 为 null；SYNTHETIC 的 HTTP 计数为 null。Jev 子调用型号/置信度与 LLM 响应型号、usage 仅在可得时保留。

本地覆盖：真实 Fallback/Llm/Jev service 与 RouteAgent/Advisor 组合；LLM 直接、Jev 直接、低置信、超时、429、非法输出、失败回退 SAFE_DEFAULT；原历史、空历史、记录优化与会话清理；计时用可控时钟证明包围完整两阶段；固定 seed 和独立 warmup 键；dry-run 无模型、重复 run 拒绝；逻辑硬上限；两供应商共用 HTTP 拦截器上限覆盖官方 retry；停止后所有 logical/HTTP 入口拒绝；Jev 被取消后不能继续发起 LLM。MockRestServiceServer 拦截全部测试 HTTP，未连接 fixture.invalid。

## 5. 剩余条件与局限

LIVE 必须由根会话统一确认并提供真实型号、动态提示词快照、有效参数/出口/超时/重试、环境凭据、调用上限和预算。当前未取得真实性能证据。

官方 Spring AI 默认 retry 已复用；显式 override 与原默认的差异记入 manifest。外层 routeTimeoutMs 与默认长退避需要在实测前协调批准，不能把超时中断结果当完整性能测试。

HTTP 上限限制的是同步 RestClient 拦截器放行的执行尝试，包含应用层重试；供应商到账数、JDK 内部重传、金额硬上限不能可靠观测或保证。没有可靠价格/用量或账号限额时不作成本结论。响应别名与配置型号分别保存，首次非空响应型号后续变化才停。

只验证 ISOLATED_JAVA_ROUTE 的实现就绪；APPLICATION_ROUTE 未实现、未授权。不会自动恢复未知已开始的请求；需新 runId、审阅原始证据并重新核对预算。target 产物有被 clean 删除的风险，正式运行归档另行确认。
