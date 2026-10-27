# 第一阶段离线路由评测

这里的程序与普通 Maven 单测分开运行；不会启动 tjxt，也不会修改 Redis、数据库或线上路由配置。`route-eval-v1.jsonl` 是原始标注集，报告默认写到被 Git 忽略的 `tj-aigc/target/route-eval/report.json`。

## 数据与标签

标注集共有 413 行，包含四类明确意图、相邻边界、上下文指代、口语/错别字、多意图、信息不足、越界及提示注入。`history` 中的消息使用 `{ "role": "user|assistant", "content": "..." }`。`tags` 用于切片统计。同一场景的 `a/b/c/d` 改写会进入同一个开发集或保留集，避免近重复文本跨组。

这些是人工构造的起始样本，不是线上真实分布；四种改写仍有较高相似性。正式选择阈值前，应由业务方逐条复核意图标签，加入更多独立场景及脱敏真实流量，特别检查多意图、上下文不完整和越界请求的标注约定。不要把此数据集上的高分直接当作生产效果。

## 运行

需要 Python 3.10 或更新版本，无第三方依赖。在仓库根目录执行：

```powershell
python 'docs/Jev改造/eval/route_eval.py' validate
python -m unittest discover -s 'docs/Jev改造/eval' -p 'test_*.py' -v
python 'docs/Jev改造/eval/route_eval.py' run
```

不提供外部预测时，只计算关键词规则基线；报告中的 RouteAgent、Jev 和门控方案为 `null`，**不会伪造它们的成绩**。

取得 TypeSafe Key 后，可显式运行真实 Jev。建议先用 `--max-requests 5` 检查请求契约，再运行完整标注集。程序串行调用、默认至少间隔 250 毫秒，对 429/529 最多指数退避重试两次。Key 仅从环境变量读取，不写入报告或日志。

```powershell
$env:JEV_API_KEY = '你的 Key'
python 'docs/Jev改造/eval/route_eval.py' run --jev-live --max-requests 5 --output 'tj-aigc/target/route-eval/smoke.json'
python 'docs/Jev改造/eval/route_eval.py' run --jev-live
```

可用 `JEV_MODEL` 和 `JEV_BASE_URL` 覆盖模型和地址。默认模型为项目当前配置的 `jev-1.13.0`。如需计算美元成本，请按实际账单价传入 `--jev-input-usd-per-million` 和 `--jev-output-usd-per-million`；未提供单价时成本保持 `null`，不猜测价格。

### Java 与 Python 的网络出口

Python 评测使用 `urllib`，可能读取操作系统或环境变量中的代理；Java Jev 客户端不应被假定使用相同代理。Java 支持独立的 `tj.ai.jev.proxy.enabled/host/port` 配置，对应 `JEV_PROXY_ENABLED`、`JEV_PROXY_HOST`、`JEV_PROXY_PORT`。它只作用于 Jev，不修改 Nacos、Redis、Feign 等组件的 JVM 全局代理。使用的是 **HTTP 代理端口**（支持 HTTPS CONNECT），不是 SOCKS 端口；当前不支持代理账号密码认证。

基础配置默认关闭代理；`local` 配置为当前联调默认开启 `127.0.0.1:7890`，需先启动本机代理，修改后重启 AIGC 服务。无需代理时设置 `JEV_PROXY_ENABLED=false`。部署到容器或远程服务器时，`127.0.0.1` 指向容器或服务器本身，不能直接复用开发机地址。

HTTP 451 在 Java 和 Python 中均记录为 `REGION_UNAVAILABLE`；这是服务商的可用地区限制，不应通过更换提示词、降低置信度或反复重试解决。应确认账号、实际服务地区与网络出口符合服务商条款；代理配置本身不保证服务可用。重启后发送新问题，确认路由日志 `source=JEV`；`source=LLM_FALLBACK` 表示仍然回退，需结合 `fallbackReason` 排查。Jev 只选择后续业务 Agent，最终回答仍由业务模型生成。

原 RouteAgent 的结果从独立 JSONL 文件导入，每行格式如下。`id` 必须与标注集一致，`agent` 为四种 Agent 之一；失败时可以用 `error` 代替。`latencyMs` 和 `costUsd` 可选，缺失则对应指标为 `null`。

```json
{"id":"route-0001","agent":"RECOMMEND","latencyMs":821,"costUsd":0.0003}
```

导出时必须调用项目原 `RouteAgent`，使用与线上一致的系统提示词和模型，在**隔离的测试环境**逐条记录结果；不要把规则或另一个提示词的结果冒充 RouteAgent。原 Agent 的会话更新与 ChatMemory 可能写入外部系统，因此本工具不自动启动它，也不连接生产服务。拿到其预测文件后运行：

```powershell
python 'docs/Jev改造/eval/route_eval.py' run --llm-predictions 'D:/safe/route-agent-predictions.jsonl' --jev-live
```

以后调阈值应复用保存的 Jev 结果，避免重复付费调用 API：

```powershell
python 'docs/Jev改造/eval/route_eval.py' run --llm-predictions 'D:/safe/route-agent-predictions.jsonl' --jev-report 'tj-aigc/target/route-eval/report.json' --threshold 0.90 --buy-threshold 0.95 --output 'tj-aigc/target/route-eval/threshold-090.json'
```

注意首次完整报告和小样本联调报告使用不同的 `--output` 文件名，避免覆盖。报告只记录样本 ID、标签与预测，不复制原问题/历史内容。若原始 JSONL 含真实用户数据，请在评测前脱敏，并控制报告文件的访问权限。

## 指标口径

- `accuracy` 以全部样本为分母，失败或缺失预测计错；`accuracyWhenAvailable` 只看成功预测，必须与 `coverage` 一起解读。
- 混淆矩阵固定为四乘四，仅统计有效预测；错误数由 `errorRate` 反映。每类 Recall 的分母包含该类失败样本。
- `thresholdSweep` 只在开发集上列出自动路由覆盖率和正确率。先根据开发集选阈值，再看 `gated.holdout`，不要反复用保留集调参。
- `gated` 在 Jev 错误或置信度不足时采用导入的 RouteAgent 预测；BUY 使用单独、更高的门槛。`fallbackRate` 是进入 RouteAgent 的比例。
- P50/P95/P99 使用 nearest-rank 口径；门控延迟按 Jev 与回退 LLM 耗时相加。成本同理。真实请求的计费价格可能变化，需自行填入当前价格。
