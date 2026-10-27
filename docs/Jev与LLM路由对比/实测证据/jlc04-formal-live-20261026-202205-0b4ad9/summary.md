# Jev 与 LLM 路由对比统计报告

运行：`jlc04-formal-live-20261026-202205-0b4ad9`；模式：`LIVE`；环境：`ISOLATED_JAVA_ROUTE`；数据状态：`INCOMPLETE`。

接受结论：`NOT_EVALUATED`；原因：`USER_REPORT_ONLY`。

## 数据完整性

| 项目 | 数量 |
| --- | --- |
| planned | 2538 |
| recorded | 25 |
| started | 25 |
| progressFinished | 25 |
| ended | 25 |
| missing | 2513 |
| notExecuted | 0 |
| duplicates | 0 |
| unknownCompletionOrLatency | 0 |
| formalPlanned | 2478 |
| warmupPlanned | 60 |
| manifestStatus | INCOMPLETE |
| uniqueFormalSampleCount | 413 |
| uniqueScenarioGroupCount | 104 |

## 正式请求总体结果

主准确率以计划正式请求为分母，只有正常且标签正确的决策计对；安全默认、错误、缺失均计错。耗时单位 ms，nearest-rank 分位数。

| 指标 | LLM 主路由 | Jev 加回退 |
| --- | --- | --- |
| planned | 1239 | 1239 |
| recorded | 2 | 3 |
| primaryAccuracy | 0.0016 | 0.0016 |
| labelMatchAccuracy | 0.0016 | 0.0024 |
| normalCoverage | 0.0016 | 0.0016 |
| finalErrorRate | 0.0000 | 0.0000 |
| safeDefaultRate | 0.0000 | 0.0008 |
| fallbackAttemptRate | 未知 / 不适用 | 0.0008 |
| directJevRate | 未知 / 不适用 | 0.0016 |
| llmLogicalCalls | 2 | 1 |
| jevLogicalCalls | 0 | 3 |
| latencyMissing | 1237 | 1236 |
| latencyMs.n | 2 | 3 |
| latencyMs.p50 | 371.5572 | 262.0502 |
| latencyMs.p95 | 517.8835 | 1269.0955 |
| latencyMs.p99 | 517.8835 | 1269.0955 |
| normalLatencyMs.n | 2 | 2 |
| normalLatencyMs.p50 | 371.5572 | 251.1591 |
| normalLatencyMs.p95 | 517.8835 | 262.0502 |
| normalLatencyMs.p99 | 517.8835 | 262.0502 |

## 逐轮结果

| 轮次 | 组 | 计划数 | 主准确率 | P50 | P95 | 安全默认数 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | LLM_PRIMARY | 413 | 0.0048 | 371.5572 | 517.8835 | 0 |
| 1 | JEV_WITH_FALLBACK | 413 | 0.0048 | 262.0502 | 1269.0955 | 1 |
| 2 | LLM_PRIMARY | 413 | 0.0000 | 未知 / 不适用 | 未知 / 不适用 | 0 |
| 2 | JEV_WITH_FALLBACK | 413 | 0.0000 | 未知 / 不适用 | 未知 / 不适用 | 0 |
| 3 | LLM_PRIMARY | 413 | 0.0000 | 未知 / 不适用 | 未知 / 不适用 | 0 |
| 3 | JEV_WITH_FALLBACK | 413 | 0.0000 | 未知 / 不适用 | 未知 / 不适用 | 0 |

## 改善与配对

百分比允许为负；缺少完整请求时仅保留已观测比较，不能据此宣称正式收益。

| 项目 | 值 |
| --- | --- |
| p50ImprovementPercent | 29.4724 |
| p95ImprovementPercent | -145.0542 |
| p99ImprovementPercent | -145.0542 |
| llmLogicalReductionPercent | 未知 / 不适用 |
| observedLlmLogicalReductionPercent | 50.0000 |
| accuracyDeltaPercentagePoints | 0.0000 |
| observationsComplete | False |
| plannedPairs | 1239 |
| precisePairs | 2 |
| notPreciselyPaired | 1237 |

## 保留集与类别

| 切片 | 组 | 计划数 | 主准确率 | P95 |
| --- | --- | --- | --- | --- |
| split:development | LLM_PRIMARY | 1059 | 0.0019 | 517.8835 |
| split:development | JEV_WITH_FALLBACK | 1059 | 0.0019 | 1269.0955 |
| split:holdout | LLM_PRIMARY | 180 | 0.0000 | 未知 / 不适用 |
| split:holdout | JEV_WITH_FALLBACK | 180 | 0.0000 | 未知 / 不适用 |
| expectedAgent:RECOMMEND | LLM_PRIMARY | 303 | 0.0033 | 371.5572 |
| expectedAgent:RECOMMEND | JEV_WITH_FALLBACK | 303 | 0.0033 | 262.0502 |
| expectedAgent:CONSULT | LLM_PRIMARY | 348 | 0.0000 | 未知 / 不适用 |
| expectedAgent:CONSULT | JEV_WITH_FALLBACK | 348 | 0.0000 | 未知 / 不适用 |
| expectedAgent:BUY | LLM_PRIMARY | 252 | 0.0000 | 未知 / 不适用 |
| expectedAgent:BUY | JEV_WITH_FALLBACK | 252 | 0.0000 | 未知 / 不适用 |
| expectedAgent:KNOWLEDGE | LLM_PRIMARY | 336 | 0.0030 | 517.8835 |
| expectedAgent:KNOWLEDGE | JEV_WITH_FALLBACK | 336 | 0.0030 | 1269.0955 |
| policyPending:False | LLM_PRIMARY | 1191 | 0.0017 | 517.8835 |
| policyPending:False | JEV_WITH_FALLBACK | 1191 | 0.0017 | 1269.0955 |
| policyPending:True | LLM_PRIMARY | 48 | 0.0000 | 未知 / 不适用 |
| policyPending:True | JEV_WITH_FALLBACK | 48 | 0.0000 | 未知 / 不适用 |

四类 Recall、正常决策混淆矩阵、来源/回退原因、错误切片、逐对差值和完整配置身份见同前缀 JSON。总体分位数从原始记录重算，不平均各轮分位数。

## 证据与限制

独立样本 413 条，场景组 104 个；重复轮次不扩大独立标注规模。

- manifest.json：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-202205-0b4ad9\manifest.json`；SHA-256 `61fbda3833b3ac3968a4aaed9ebdfd83deb0f5b368c04c35cc96e36e3a6d0d97`。
- schedule.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-202205-0b4ad9\schedule.jsonl`；SHA-256 `3cfa51c11bbe5619f80e58d956415aa73ea10da610066dc017402f17815befb4`。
- requests.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-202205-0b4ad9\requests.jsonl`；SHA-256 `66701f022220dfdcac4adb587fa869d5c9638bcba032f0c1c2d7bb8d8ab6e70c`。
- progress.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-202205-0b4ad9\progress.jsonl`；SHA-256 `8d372bd1919a636ab810d988e20cb6615e4aad1afd13693179cf2a7f54afdeaa`。

- All latency quantiles include ended errors and safe defaults; also inspect quality and normal-only latency.
- P99 is exploratory; repeated rounds are not independent labeled scenarios.
- Artificial labels and disputed scenario groups do not establish production quality.
- HTTP counters mean RestClient execution attempts, not vendor billing or transport retransmissions.
- Money cost is unknown without reliable pricing and usage; no currency hard cap is claimed.
- Past source hashes describe that run; they are not silently replaced by today's files.
- An accepted result applies only to the recorded environment and approved experimental criteria.

## 复现命令

```powershell
python "D:\Project\Java\tjxt\docs\Jev与LLM路由对比\benchmark_report.py" "D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-202205-0b4ad9"
```
