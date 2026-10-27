# JLC-03 验收记录

日期：2026-10-26（Asia/Shanghai）。状态：CODE_COMPLETE；本地 PASS；真实 API NOT_RUN。工作目录 `D:\Project\Java\tjxt`，分支 `jev-routing`。

## 1. 前置核验与范围

已核验 JLC-01 的协议、413 条数据及 JLC-02 的记录契约、模拟 fixture 和 DRY_RUN 产物。采集器源与编译产物 114 项哈希在进入本任务前一致；21 个已有改动文件受起始快照保护。

统计子任务曾因执行资源容量中断，未留下统计代码；随后在当前工作区继续实现，无重复执行已完成采集任务。只新增本目录统计脚本、测试、说明和缓存忽略规则，并在上游运行目录新增 summary；未改动原始 manifest、schedule、requests 或 Java 实现。

## 2. 代码产物

- `benchmark_report.py`：标准库离线严格汇总器，支持结构校验、计划对账、分位数、质量与回退指标、切片、配对和明确的接受门槛。
- `test_benchmark_report.py`：29 项本地合同测试，使用工作区内的受控临时目录。
- `统计工具使用说明.md`：命令、返回码、统计口径与限制。
- `.gitignore`：忽略本目录 Python 缓存。

## 3. 实际验证

```powershell
python -m unittest discover -s 'docs/Jev与LLM路由对比' -p 'test_benchmark_report.py' -v
python 'docs/Jev与LLM路由对比/benchmark_report.py' 'tj-aigc/target/route-benchmark/jlc02-synthetic-fixture-v1'
python 'docs/Jev与LLM路由对比/benchmark_report.py' 'tj-aigc/target/route-benchmark/jlc02-dry-run-v1'
```

29 项单元测试全部 PASS；日志 `tj-aigc/target/route-benchmark/jlc03-unit-tests.log`。初次验证因系统临时目录权限失败，随后将临时目录明确限定到工作区 `target/route-benchmark/python-tests` 并在清理前核验归属；未修改系统权限，最终验证通过。

| 验证范围 | 结果 |
| --- | --- |
| 分位数、空/单样本、总体不平均 P95、负改善 | PASS |
| 正常 KNOWLEDGE 与同标签安全默认、失败回退计数、计划分母 | PASS |
| 缺失/中断/未知耗时保持 null，不以 0 填补 | PASS |
| 重复/额外记录、schema/mode/config 混合、计划哈希、非法数字及身份 | PASS |
| 逐轮、配对、开发/保留及类别切片、预热排除 | PASS |
| DRY_RUN 未执行，SYNTHETIC/冒烟不判正式通过 | PASS |
| 明确批准后才评估真实正式完整数据，质量退化可拒绝 | PASS；使用临时构造记录，没有实际 API |

模拟 fixture CLI 返回 0，数据 COMPLETE、接受 NOT_EVALUATED；DRY_RUN CLI 返回 0，状态 PLANNED、60 项未执行、准确率 null。两份汇总均位于对应 run 目录的 `summary.json`、`summary.md`。

父级手算参考与模拟汇总一致：LLM 组主正确 4/20、标签匹配 5/20；Jev 组主正确 4/20、实际回退尝试 14/20。模拟数据不支持真实提速、质量或简历收益结论。

## 4. 门槛与后续

JLC-03 已具备交付条件，可进入 JLC-04 的配置/预算集中确认。真实调用仍为 0；本记录不批准任何 API 调用。JLC-04 未运行，JLC-05 的最终实测报告与简历表述尚不能生成性能数字。

HTTP 仅观测 RestClient 执行尝试；金额成本未知。APPLICATION_ROUTE 尚未验证。真实证据位于 target，正式完成后应按用户批准的本地归档方案保留，不自动上传。
