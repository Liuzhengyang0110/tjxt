# Jev 与 LLM 路由对比统计报告

运行：`jlc04-formal-live-20261026-203132-2b882a`；模式：`LIVE`；环境：`ISOLATED_JAVA_ROUTE`；数据状态：`COMPLETE`。

接受结论：`NOT_EVALUATED`；原因：`USER_REPORT_ONLY`。

## 数据完整性

| 项目 | 数量 |
| --- | --- |
| planned | 2538 |
| recorded | 2538 |
| started | 2538 |
| progressFinished | 2538 |
| ended | 2538 |
| missing | 0 |
| notExecuted | 0 |
| duplicates | 0 |
| unknownCompletionOrLatency | 0 |
| formalPlanned | 2478 |
| warmupPlanned | 60 |
| manifestStatus | COMPLETE |
| uniqueFormalSampleCount | 413 |
| uniqueScenarioGroupCount | 104 |

## 正式请求总体结果

主准确率以计划正式请求为分母，只有正常且标签正确的决策计对；安全默认、错误、缺失均计错。耗时单位 ms，nearest-rank 分位数。

| 指标 | LLM 主路由 | Jev 加回退 |
| --- | --- | --- |
| planned | 1239 | 1239 |
| recorded | 1239 | 1239 |
| primaryAccuracy | 0.9475 | 0.9847 |
| labelMatchAccuracy | 0.9596 | 0.9903 |
| normalCoverage | 0.9879 | 0.9944 |
| finalErrorRate | 0.0000 | 0.0000 |
| safeDefaultRate | 0.0121 | 0.0056 |
| fallbackAttemptRate | 未知 / 不适用 | 0.0395 |
| directJevRate | 未知 / 不适用 | 0.9605 |
| llmLogicalCalls | 1239 | 49 |
| jevLogicalCalls | 0 | 1239 |
| latencyMissing | 0 | 0 |
| latencyMs.n | 1239 | 1239 |
| latencyMs.p50 | 405.9356 | 211.5878 |
| latencyMs.p95 | 722.1893 | 536.6248 |
| latencyMs.p99 | 1030.3331 | 735.5674 |
| normalLatencyMs.n | 1224 | 1232 |
| normalLatencyMs.p50 | 405.1139 | 211.4512 |
| normalLatencyMs.p95 | 719.1043 | 505.6862 |
| normalLatencyMs.p99 | 1030.3331 | 701.0642 |

## 逐轮结果

| 轮次 | 组 | 计划数 | 主准确率 | P50 | P95 | 安全默认数 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | LLM_PRIMARY | 413 | 0.9419 | 411.7832 | 749.1837 | 7 |
| 1 | JEV_WITH_FALLBACK | 413 | 0.9831 | 211.7713 | 487.9622 | 3 |
| 2 | LLM_PRIMARY | 413 | 0.9516 | 410.6882 | 729.7314 | 5 |
| 2 | JEV_WITH_FALLBACK | 413 | 0.9879 | 214.7995 | 589.7878 | 1 |
| 3 | LLM_PRIMARY | 413 | 0.9492 | 397.9855 | 642.2688 | 3 |
| 3 | JEV_WITH_FALLBACK | 413 | 0.9831 | 209.4031 | 529.3435 | 3 |

## 改善与配对

百分比允许为负；缺少完整请求时仅保留已观测比较，不能据此宣称正式收益。

| 项目 | 值 |
| --- | --- |
| p50ImprovementPercent | 47.8765 |
| p95ImprovementPercent | 25.6947 |
| p99ImprovementPercent | 28.6088 |
| llmLogicalReductionPercent | 96.0452 |
| observedLlmLogicalReductionPercent | 96.0452 |
| accuracyDeltaPercentagePoints | 3.7127 |
| observationsComplete | True |
| plannedPairs | 1239 |
| precisePairs | 1239 |
| notPreciselyPaired | 0 |

## 保留集与类别

| 切片 | 组 | 计划数 | 主准确率 | P95 |
| --- | --- | --- | --- | --- |
| split:development | LLM_PRIMARY | 1059 | 0.9415 | 710.1573 |
| split:development | JEV_WITH_FALLBACK | 1059 | 0.9821 | 589.7878 |
| split:holdout | LLM_PRIMARY | 180 | 0.9833 | 781.7422 |
| split:holdout | JEV_WITH_FALLBACK | 180 | 1.0000 | 321.8860 |
| expectedAgent:RECOMMEND | LLM_PRIMARY | 303 | 0.9175 | 642.2688 |
| expectedAgent:RECOMMEND | JEV_WITH_FALLBACK | 303 | 1.0000 | 529.3435 |
| expectedAgent:CONSULT | LLM_PRIMARY | 348 | 0.9655 | 803.3800 |
| expectedAgent:CONSULT | JEV_WITH_FALLBACK | 348 | 0.9655 | 630.5143 |
| expectedAgent:BUY | LLM_PRIMARY | 252 | 0.9603 | 580.5587 |
| expectedAgent:BUY | JEV_WITH_FALLBACK | 252 | 1.0000 | 306.4636 |
| expectedAgent:KNOWLEDGE | LLM_PRIMARY | 336 | 0.9464 | 788.4872 |
| expectedAgent:KNOWLEDGE | JEV_WITH_FALLBACK | 336 | 0.9792 | 517.9446 |
| policyPending:False | LLM_PRIMARY | 1191 | 0.9647 | 719.3144 |
| policyPending:False | JEV_WITH_FALLBACK | 1191 | 0.9941 | 491.6320 |
| policyPending:True | LLM_PRIMARY | 48 | 0.5208 | 766.0678 |
| policyPending:True | JEV_WITH_FALLBACK | 48 | 0.7500 | 748.9597 |

四类 Recall、正常决策混淆矩阵、来源/回退原因、错误切片、逐对差值和完整配置身份见同前缀 JSON。总体分位数从原始记录重算，不平均各轮分位数。

## 证据与限制

独立样本 413 条，场景组 104 个；重复轮次不扩大独立标注规模。

- manifest.json：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-203132-2b882a\manifest.json`；SHA-256 `8928d35200011358f1d6fc73b9e3a455b9e14be20ba362ff5478da8d08cde26d`。
- schedule.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-203132-2b882a\schedule.jsonl`；SHA-256 `5633b526742c2ad5d567604b5886e5e473a5202dea1717eacbf325bc1b8a9e59`。
- requests.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-203132-2b882a\requests.jsonl`；SHA-256 `7a4d84117fdfcf1c1d452981fdabfd34fb1cad55d7bcf3be6fbe8cb4a66d53d8`。
- progress.jsonl：`D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-203132-2b882a\progress.jsonl`；SHA-256 `bc0dc91504a4ede24005525b77b8e93d95dc288289b422a447ee6e44556f12cb`。

- All latency quantiles include ended errors and safe defaults; also inspect quality and normal-only latency.
- P99 is exploratory; repeated rounds are not independent labeled scenarios.
- Artificial labels and disputed scenario groups do not establish production quality.
- HTTP counters mean RestClient execution attempts, not vendor billing or transport retransmissions.
- Money cost is unknown without reliable pricing and usage; no currency hard cap is claimed.
- Past source hashes describe that run; they are not silently replaced by today's files.
- An accepted result applies only to the recorded environment and approved experimental criteria.

## 复现命令

```powershell
python "D:\Project\Java\tjxt\docs\Jev与LLM路由对比\benchmark_report.py" "D:\Project\Java\tjxt\docs\Jev与LLM路由对比\实测证据\jlc04-formal-live-20261026-203132-2b882a"
```
