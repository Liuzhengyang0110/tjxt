# Jev 与 LLM 路由对比统计报告

运行：`jlc04-smoke-live-20261026-201850-be37aa`；模式：`LIVE`；环境：`ISOLATED_JAVA_ROUTE`；数据状态：`COMPLETE`。

接受结论：`NOT_EVALUATED`；原因：`USER_REPORT_ONLY`。

## 数据完整性

| 项目 | 数量 |
| --- | --- |
| planned | 60 |
| recorded | 60 |
| started | 60 |
| progressFinished | 60 |
| ended | 60 |
| missing | 0 |
| notExecuted | 0 |
| duplicates | 0 |
| unknownCompletionOrLatency | 0 |
| formalPlanned | 40 |
| warmupPlanned | 20 |
| manifestStatus | COMPLETE |
| uniqueFormalSampleCount | 20 |
| uniqueScenarioGroupCount | 20 |

## 正式请求总体结果

主准确率以计划正式请求为分母，只有正常且标签正确的决策计对；安全默认、错误、缺失均计错。耗时单位 ms，nearest-rank 分位数。

| 指标 | LLM 主路由 | Jev 加回退 |
| --- | --- | --- |
| planned | 20 | 20 |
| recorded | 20 | 20 |
| primaryAccuracy | 0.9500 | 1.0000 |
| labelMatchAccuracy | 0.9500 | 1.0000 |
| normalCoverage | 1.0000 | 1.0000 |
| finalErrorRate | 0.0000 | 0.0000 |
| safeDefaultRate | 0.0000 | 0.0000 |
| fallbackAttemptRate | 未知 / 不适用 | 0.0500 |
| directJevRate | 未知 / 不适用 | 0.9500 |
| llmLogicalCalls | 20 | 1 |
| jevLogicalCalls | 0 | 20 |
| latencyMissing | 0 | 0 |
| latencyMs.n | 20 | 20 |
| latencyMs.p50 | 407.7588 | 251.0031 |
| latencyMs.p95 | 557.5028 | 295.8909 |
| latencyMs.p99 | 980.9495 | 733.7760 |
| normalLatencyMs.n | 20 | 20 |
| normalLatencyMs.p50 | 407.7588 | 251.0031 |
| normalLatencyMs.p95 | 557.5028 | 295.8909 |
| normalLatencyMs.p99 | 980.9495 | 733.7760 |

## 逐轮结果

| 轮次 | 组 | 计划数 | 主准确率 | P50 | P95 | 安全默认数 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | LLM_PRIMARY | 20 | 0.9500 | 407.7588 | 557.5028 | 0 |
| 1 | JEV_WITH_FALLBACK | 20 | 1.0000 | 251.0031 | 295.8909 | 0 |

## 改善与配对

百分比允许为负；缺少完整请求时仅保留已观测比较，不能据此宣称正式收益。

| 项目 | 值 |
| --- | --- |
| p50ImprovementPercent | 38.4432 |
| p95ImprovementPercent | 46.9257 |
| p99ImprovementPercent | 25.1974 |
| llmLogicalReductionPercent | 95.0000 |
| observedLlmLogicalReductionPercent | 95.0000 |
| accuracyDeltaPercentagePoints | 5.0000 |
| observationsComplete | True |
| plannedPairs | 20 |
| precisePairs | 20 |
| notPreciselyPaired | 0 |

## 保留集与类别

| 切片 | 组 | 计划数 | 主准确率 | P95 |
| --- | --- | --- | --- | --- |
| split:development | LLM_PRIMARY | 19 | 0.9474 | 980.9495 |
| split:development | JEV_WITH_FALLBACK | 19 | 1.0000 | 733.7760 |
| split:holdout | LLM_PRIMARY | 1 | 1.0000 | 555.4559 |
| split:holdout | JEV_WITH_FALLBACK | 1 | 1.0000 | 251.0031 |
| expectedAgent:RECOMMEND | LLM_PRIMARY | 6 | 0.8333 | 555.4559 |
| expectedAgent:RECOMMEND | JEV_WITH_FALLBACK | 6 | 1.0000 | 733.7760 |
| expectedAgent:CONSULT | LLM_PRIMARY | 4 | 1.0000 | 503.6614 |
| expectedAgent:CONSULT | JEV_WITH_FALLBACK | 4 | 1.0000 | 289.6913 |
| expectedAgent:BUY | LLM_PRIMARY | 5 | 1.0000 | 980.9495 |
| expectedAgent:BUY | JEV_WITH_FALLBACK | 5 | 1.0000 | 272.9808 |
| expectedAgent:KNOWLEDGE | LLM_PRIMARY | 5 | 1.0000 | 557.5028 |
| expectedAgent:KNOWLEDGE | JEV_WITH_FALLBACK | 5 | 1.0000 | 295.8909 |
| policyPending:False | LLM_PRIMARY | 19 | 0.9474 | 980.9495 |
| policyPending:False | JEV_WITH_FALLBACK | 19 | 1.0000 | 733.7760 |
| policyPending:True | LLM_PRIMARY | 1 | 1.0000 | 555.4559 |
| policyPending:True | JEV_WITH_FALLBACK | 1 | 1.0000 | 251.0031 |

四类 Recall、正常决策混淆矩阵、来源/回退原因、错误切片、逐对差值和完整配置身份见同前缀 JSON。总体分位数从原始记录重算，不平均各轮分位数。

## 证据与限制

独立样本 20 条，场景组 20 个；重复轮次不扩大独立标注规模。

- manifest.json：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-smoke-live-20261026-201850-be37aa\manifest.json`；SHA-256 `9e2463c2478abd0964876f9cb98fc1a33f724f265f30b9483fb248c7651565fa`。
- schedule.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-smoke-live-20261026-201850-be37aa\schedule.jsonl`；SHA-256 `0151b1122b2552dcd20a99b29c11cb47d717e720acdef3886d24b87d6bf14f80`。
- requests.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-smoke-live-20261026-201850-be37aa\requests.jsonl`；SHA-256 `002ee834b8d4b19d5cbcc5f8654c3c9a35b7d2ece8f2fed11fa72b8925c067d1`。
- progress.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-smoke-live-20261026-201850-be37aa\progress.jsonl`；SHA-256 `457dd04153f36829b1670ee52b9b2855a4aa05d03cabbef0b03fd34b500e1f8f`。

- All latency quantiles include ended errors and safe defaults; also inspect quality and normal-only latency.
- P99 is exploratory; repeated rounds are not independent labeled scenarios.
- Artificial labels and disputed scenario groups do not establish production quality.
- HTTP counters mean RestClient execution attempts, not vendor billing or transport retransmissions.
- Money cost is unknown without reliable pricing and usage; no currency hard cap is claimed.
- Past source hashes describe that run; they are not silently replaced by today's files.
- An accepted result applies only to the recorded environment and approved experimental criteria.

## 复现命令

```powershell
python "D:\Project\Java\tjxt\docs\Jev与LLM路由对比\benchmark_report.py" "D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-smoke-live-20261026-201850-be37aa"
```
