"""Strict, offline reporting for route-benchmark-v1. Never calls a model API."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import sys
from typing import Any

BASE = Path(__file__).resolve().parent
SCHEMA = "route-benchmark-v1"
A, B = "LLM_PRIMARY", "JEV_WITH_FALLBACK"
VARIANTS = (A, B)
AGENTS = ("RECOMMEND", "CONSULT", "BUY", "KNOWLEDGE")
STATUSES = ("OK", "SAFE_DEFAULT", "ERROR", "INTERRUPTED")
SOURCES = ("LLM_PRIMARY", "JEV", "LLM_FALLBACK", "SAFE_DEFAULT")
KEY_FIELDS = ("runId", "configId", "round", "sampleId", "variant", "warmup")
PLAN_FIELDS = ("schemaVersion", "runId", "configId", "environmentLevel", "mode", "round",
               "sampleId", "variant", "orderIndex", "pairOrder", "warmup", "expectedAgent",
               "inputSampleId", "scenarioGroupId", "split", "tags")
REQUIRED = (*PLAN_FIELDS[:12], "actualAgent", "source", "fallbackReason", "confidence",
            "latencyMs", "status", "errorType", "jevModel", "llmModel", "jevCallCount",
            "llmCallCount", "startedAt", "finishedAt")


class ReportError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportError(message)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _object(pairs: list[tuple[str, Any]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(ReportError("Nonfinite JSON constant")))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ReportError(f"Cannot read valid JSON: {path.name} ({type(error).__name__})") from error


def read_jsonl(path: Path) -> list[dict]:
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except (OSError, UnicodeError) as error:
        raise ReportError(f"Cannot read JSONL: {path.name} ({type(error).__name__})") from error
    result = []
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line, object_pairs_hook=_object,
                               parse_constant=lambda _: (_ for _ in ()).throw(ReportError("Nonfinite JSON constant")))
        except (ValueError, TypeError) as error:
            raise ReportError(f"Invalid JSONL: {path.name}, line {number} ({type(error).__name__})") from error
        require(isinstance(value, dict), f"JSONL object required: {path.name}, line {number}")
        result.append(value)
    return result


def key(row: dict) -> tuple:
    return tuple(row[name] for name in KEY_FIELDS)


def scenario(sample_id: str) -> str:
    return re.sub(r"-[a-d]$", "", sample_id)


def split(sample_id: str) -> str:
    number = int.from_bytes(hashlib.sha256(scenario(sample_id).encode()).digest()[:4], "big")
    return "holdout" if number % 5 == 0 else "development"


def number(value: Any) -> bool:
    return type(value) in (int, float) and math.isfinite(value)


def percentile(values: list[float], p: float) -> float | None:
    return sorted(values)[max(0, math.ceil(p * len(values)) - 1)] if values else None


def ratio(numerator: float, denominator: float) -> float | None:
    return numerator / denominator if denominator else None


def quantiles(values: list[float]) -> dict:
    return {"n": len(values), "p50": percentile(values, .5),
            "p95": percentile(values, .95), "p99": percentile(values, .99)}


def validate(manifest: dict, plan: list[dict], rows: list[dict], progress: list[dict] | None) -> dict:
    require(isinstance(manifest, dict), "Manifest object required")
    for field in ("schemaVersion", "protocolId", "runId", "mode", "environmentLevel", "variants",
                  "configIds", "plannedRecordCount", "status"):
        require(field in manifest, f"Missing manifest field: {field}")
    require(manifest["schemaVersion"] == SCHEMA, "Unsupported manifest schema")
    require(manifest["mode"] in ("DRY_RUN", "SYNTHETIC", "LIVE"), "Invalid manifest mode")
    require(manifest["environmentLevel"] in ("ISOLATED_JAVA_ROUTE", "APPLICATION_ROUTE"), "Invalid environment")
    require(type(manifest["plannedRecordCount"]) is int and manifest["plannedRecordCount"] == len(plan), "Plan count mismatch")
    require(manifest["status"] in ("PLANNED", "RUNNING", "COMPLETE", "INCOMPLETE"), "Invalid manifest status")
    configs = {}
    for item in manifest["variants"]:
        require(isinstance(item, dict) and item.get("variant") in VARIANTS, "Invalid variant declaration")
        require(item["variant"] not in configs, "Duplicate variant declaration")
        require(isinstance(item.get("configId"), str) and bool(item["configId"]), "Missing config ID")
        configs[item["variant"]] = item["configId"]
    require(set(configs) == set(VARIANTS) and len(set(configs.values())) == 2, "Exactly two independent variant configs required")
    require(set(manifest["configIds"]) == set(configs.values()) and len(manifest["configIds"]) == 2, "Manifest config IDs disagree")
    by_plan, by_row, pairs = {}, {}, defaultdict(set)
    for position, item in enumerate(plan):
        require(set(PLAN_FIELDS) <= item.keys(), f"Missing plan fields at index {position}")
        require(item["schemaVersion"] == SCHEMA, "Plan schema mismatch")
        for field in ("runId", "mode", "environmentLevel"):
            require(item[field] == manifest[field], f"Plan identity mismatch: {field}")
        require(item["variant"] in VARIANTS and item["configId"] == configs[item["variant"]], "Plan variant/config mismatch")
        require(type(item["round"]) is int and item["round"] > 0, "Invalid round")
        require(type(item["orderIndex"]) is int and item["orderIndex"] == position, "Plan order indexes must be contiguous")
        require(type(item["warmup"]) is bool, "warmup must be boolean")
        require(item["pairOrder"] in ("AB", "BA"), "Invalid pair order")
        require(all(isinstance(item[f], str) and item[f] for f in ("sampleId", "inputSampleId")), "Invalid sample ID")
        require(item["expectedAgent"] in AGENTS, "Invalid expected label")
        require(item["scenarioGroupId"] == scenario(item["inputSampleId"]), "Scenario identity mismatch")
        require(item["split"] == split(item["inputSampleId"]), "Dataset split mismatch")
        require(isinstance(item["tags"], list) and all(isinstance(t, str) for t in item["tags"]), "Invalid tags")
        require(key(item) not in by_plan, "Duplicate plan key")
        by_plan[key(item)] = item
        if not item["warmup"]:
            pair = (item["round"], item["sampleId"])
            require(item["variant"] not in pairs[pair], "Duplicate scheduled variant in pair")
            pairs[pair].add(item["variant"])
    require(all(v == set(VARIANTS) for v in pairs.values()), "Scheduled formal sample must contain both variants")
    for position, row in enumerate(rows):
        require(set(REQUIRED) <= row.keys(), f"Missing request fields at row {position}")
        require(key(row) not in by_row, "Duplicate request key")
        require(key(row) in by_plan, "Unexpected request key")
        entry = by_plan[key(row)]
        for field, value in entry.items():
            require(field in row and type(row[field]) is type(value) and row[field] == value,
                    f"Request disagrees with plan: row {position}, {field}")
        require(row["status"] in STATUSES, "Invalid request status")
        require(row["source"] is None or row["source"] in SOURCES, "Invalid request source")
        require(row["actualAgent"] is None or row["actualAgent"] in AGENTS, "Invalid actual label")
        duration = row["latencyMs"]
        require(duration is None or (number(duration) and duration >= 0), "Invalid latency; unknown must be null")
        require(row["confidence"] is None or (number(row["confidence"]) and 0 <= row["confidence"] <= 1), "Invalid confidence")
        for field in ("jevCallCount", "llmCallCount"):
            require(type(row[field]) is int and row[field] >= 0, f"Invalid logical counter: {field}")
        for field in ("httpAttemptCount", "httpAttemptCountObservedAtStop", "retryCount"):
            value = row.get(field)
            require(value is None or (type(value) is int and value >= 0), f"Invalid optional counter: {field}")
        if row["status"] == "OK":
            require(row["actualAgent"] in AGENTS and row["source"] in SOURCES[:3], "OK must be a normal model decision")
            if row["variant"] == A:
                require(row["source"] == "LLM_PRIMARY" and row["llmCallCount"] > 0 and row["jevCallCount"] == 0, "Invalid primary LLM record")
            else:
                require(row["source"] in ("JEV", "LLM_FALLBACK"), "Invalid Jev-variant source")
                if row["source"] == "JEV":
                    require(row["jevCallCount"] > 0 and row["llmCallCount"] == 0, "Jev direct result has inconsistent calls")
                else:
                    require(row["llmCallCount"] > 0, "Fallback result needs a measured LLM attempt")
        elif row["status"] == "SAFE_DEFAULT":
            require(row["source"] == "SAFE_DEFAULT" and row["actualAgent"] in AGENTS and row["actualAgent"] != "BUY", "Invalid safe default")
        else:
            require(row["source"] != "SAFE_DEFAULT", "Safe default must have its own status")
        if row["status"] == "INTERRUPTED":
            require(duration is None and row["finishedAt"] is None, "Interrupted completion time must be unknown")
        by_row[key(row)] = row
    if manifest["mode"] == "DRY_RUN":
        require(not rows and manifest["status"] == "PLANNED", "DRY_RUN cannot contain model decisions")
    if manifest["status"] == "COMPLETE":
        require(manifest.get("completedRecordCount") == len(rows), "Manifest completed count mismatch")
        totals = manifest.get("actualLogicalCalls")
        if isinstance(totals, dict):
            for vendor, field in (("jev", "jevCallCount"), ("llm", "llmCallCount")):
                require(totals.get(vendor) == sum(row[field] for row in rows), "Manifest logical totals disagree with records")
            if totals.get("httpAttempts") is not None:
                require(all(row.get("httpAttemptCount") is not None for row in rows)
                        and totals["httpAttempts"] == sum(row["httpAttemptCount"] for row in rows),
                        "Manifest HTTP totals disagree with records")
    started, finished = set(), set()
    if progress is not None:
        by_index = {item["orderIndex"]: item for item in plan}
        for event in progress:
            index = event.get("orderIndex")
            require(type(index) is int and index in by_index, "Unexpected progress index")
            require(event.get("event") in ("STARTED", "FINISHED"), "Unknown progress event")
            if event["event"] == "STARTED":
                require(index not in started, "Duplicate progress start")
                started.add(index)
            else:
                require(index in started and index not in finished, "Progress finish without unique start")
                finished.add(index)
        for row in rows:
            require(row["orderIndex"] in started, "Request without recorded progress start")
            if row["status"] in ("OK", "SAFE_DEFAULT"):
                require(row["orderIndex"] in finished, "Normal completed request lacks finish event")
        for index in finished:
            item = by_index[index]
            require(key(item) in by_row and by_row[key(item)]["status"] != "INTERRUPTED", "Progress finish disagrees with requests")
    return {"plan": by_plan, "rows": by_row, "started": started if progress is not None else None,
            "finished": finished if progress is not None else None}


def metrics(plan: list[dict], by_row: dict, executed: bool) -> dict:
    rows = [by_row[key(item)] for item in plan if key(item) in by_row]
    total = len(plan)
    ok = [row for row in rows if row["status"] == "OK"]
    precise = [row["latencyMs"] for row in rows if row["status"] != "INTERRUPTED" and row["latencyMs"] is not None]
    statuses = Counter(row["status"] for row in rows)
    correct = sum(row["actualAgent"] == row["expectedAgent"] for row in ok)
    label_match = sum(row["actualAgent"] is not None and row["actualAgent"] == row["expectedAgent"] for row in rows)
    matrix = {a: {b: 0 for b in AGENTS} for a in AGENTS}
    for row in ok:
        matrix[row["expectedAgent"]][row["actualAgent"]] += 1
    agent_metrics = {}
    for agent in AGENTS:
        count = sum(item["expectedAgent"] == agent for item in plan)
        true_positive = sum(row["expectedAgent"] == agent and row["actualAgent"] == agent for row in ok)
        agent_metrics[agent] = {"planned": count, "truePositive": true_positive,
                                "recall": ratio(true_positive, count) if executed else None,
                                "falsePositive": sum(row["actualAgent"] == agent and row["expectedAgent"] != agent for row in ok)}
    http = [row.get("httpAttemptCount") for row in rows]
    return {"planned": total, "recorded": len(rows), "ended": sum(row["status"] != "INTERRUPTED" for row in rows),
            "missing": total - len(rows) if executed else 0, "notExecuted": total if not executed else 0,
            "statuses": {s: statuses[s] for s in STATUSES}, "primaryCorrect": correct,
            "primaryAccuracy": ratio(correct, total) if executed else None,
            "labelMatchCorrect": label_match, "labelMatchAccuracy": ratio(label_match, total) if executed else None,
            "accuracyWhenNormal": ratio(correct, len(ok)) if executed else None,
            "normalCoverage": ratio(len(ok), total) if executed else None,
            "latencyMs": quantiles(precise), "normalLatencyMs": quantiles([row["latencyMs"] for row in ok if row["latencyMs"] is not None]),
            "latencyMissing": total - len(precise) if executed else 0,
            "perAgent": agent_metrics, "normalDecisionConfusionMatrix": matrix,
            "sources": dict(Counter(row["source"] or "UNKNOWN" for row in rows)),
            "fallbackReasons": dict(Counter(row["fallbackReason"] or "UNKNOWN" for row in rows)),
            "fallbackAttempts": sum(row["llmCallCount"] > 0 for row in rows) if plan and plan[0]["variant"] == B else 0,
            "fallbackAttemptRate": ratio(sum(row["llmCallCount"] > 0 for row in rows), total) if executed and plan and plan[0]["variant"] == B else None,
            "directJevRate": ratio(sum(row["status"] == "OK" and row["source"] == "JEV" for row in rows), total) if executed and plan and plan[0]["variant"] == B else None,
            "finalErrorRate": ratio(statuses["ERROR"], total) if executed else None,
            "safeDefaultRate": ratio(statuses["SAFE_DEFAULT"], total) if executed else None,
            "interruptedRate": ratio(statuses["INTERRUPTED"], total) if executed else None,
            "jevSubcallErrors": dict(Counter(row["jevErrorType"] for row in rows if row.get("jevErrorType"))),
            "llmSubcallErrors": dict(Counter(row["llmErrorType"] for row in rows if row.get("llmErrorType"))),
            "jevLogicalCalls": sum(row["jevCallCount"] for row in rows),
            "llmLogicalCalls": sum(row["llmCallCount"] for row in rows),
            "httpExecutionAttempts": sum(http) if rows and all(v is not None for v in http) else None,
            "httpCounterUnknownRows": sum(v is None for v in http), "costUsd": None}


def grouped_metrics(plan: list[dict], rows: dict, executed: bool) -> dict:
    return {variant: metrics([item for item in plan if item["variant"] == variant], rows, executed) for variant in VARIANTS}


def comparison(overall: dict, complete: bool) -> dict:
    left, right = overall[A], overall[B]
    improvement = {}
    for name in ("p50", "p95", "p99"):
        a, b = left["latencyMs"][name], right["latencyMs"][name]
        improvement[name + "ImprovementPercent"] = (a - b) / a * 100 if a not in (None, 0) and b is not None else None
    calls = left["llmLogicalCalls"]
    observed = (1 - right["llmLogicalCalls"] / calls) * 100 if calls else None
    improvement.update({"llmLogicalReductionPercent": observed if complete else None,
                        "observedLlmLogicalReductionPercent": observed,
                        "accuracyDeltaPercentagePoints": (right["primaryAccuracy"] - left["primaryAccuracy"]) * 100 if left["primaryAccuracy"] is not None and right["primaryAccuracy"] is not None else None,
                        "observationsComplete": complete})
    return improvement


def paired(plan: list[dict], rows: dict) -> dict:
    groups = defaultdict(dict)
    for item in plan:
        groups[(item["round"], item["sampleId"])][item["variant"]] = item
    differences, details, states = [], [], Counter()
    both_recorded = both_ok = 0
    for (round_number, sample_id), entries in groups.items():
        left, right = (rows.get(key(entries[v])) for v in VARIANTS)
        both_recorded += left is not None and right is not None
        both_ok += left is not None and right is not None and left["status"] == right["status"] == "OK"
        states[f"{left['status'] if left else 'MISSING'}/{right['status'] if right else 'MISSING'}"] += 1
        difference = None
        if left and right and all(row["status"] != "INTERRUPTED" and row["latencyMs"] is not None for row in (left, right)):
            difference = left["latencyMs"] - right["latencyMs"]
            differences.append(difference)
        details.append({"round": round_number, "sampleId": sample_id,
                        "leftStatus": left["status"] if left else None, "rightStatus": right["status"] if right else None,
                        "llmMinusJevMs": difference})
    return {"plannedPairs": len(groups), "bothRecordedPairs": both_recorded, "bothNormalPairs": both_ok,
            "precisePairs": len(differences), "notPreciselyPaired": len(groups) - len(differences),
            "statusPairs": dict(states), "differenceMs": quantiles(differences), "details": details}


def acceptance_result(manifest: dict, status: str, overall: dict, delta: dict, approved: bool, protocol: dict) -> dict:
    if manifest.get("authorization", {}).get("acceptancePolicy") == "REPORT_ONLY":
        return {"status": "NOT_EVALUATED", "reason": "USER_REPORT_ONLY", "approved": False, "checks": {}}
    reason = "CRITERIA_PENDING"
    if manifest["mode"] != "LIVE":
        reason = "NON_LIVE_DATA"
    elif status != "COMPLETE":
        reason = "INCOMPLETE_EVIDENCE"
    elif manifest.get("stage") != "formal":
        reason = "NON_FORMAL_STAGE"
    if reason != "CRITERIA_PENDING" or not approved:
        return {"status": "NOT_EVALUATED", "reason": reason, "approved": approved, "checks": {}}
    a, b = overall[A], overall[B]
    tolerance = protocol.get("acceptanceProposal", {}).get("maxAccuracyDropPercentagePoints", 1) / 100
    required = [a["primaryAccuracy"], b["primaryAccuracy"], a["perAgent"]["BUY"]["recall"], b["perAgent"]["BUY"]["recall"],
                delta["p50ImprovementPercent"], delta["p95ImprovementPercent"], a["finalErrorRate"], b["finalErrorRate"], a["safeDefaultRate"], b["safeDefaultRate"]]
    if any(value is None for value in required):
        return {"status": "NOT_EVALUATED", "reason": "INSUFFICIENT_SLICE_OR_TIMING", "approved": approved, "checks": {}}
    checks = {"p50Lower": delta["p50ImprovementPercent"] > 0, "p95Lower": delta["p95ImprovementPercent"] > 0,
              "accuracyWithinTolerance": b["primaryAccuracy"] + 1e-12 >= a["primaryAccuracy"] - tolerance,
              "buyRecallNotLower": b["perAgent"]["BUY"]["recall"] >= a["perAgent"]["BUY"]["recall"],
              "finalErrorRateNotHigher": b["finalErrorRate"] <= a["finalErrorRate"],
              "safeDefaultRateNotHigher": b["safeDefaultRate"] <= a["safeDefaultRate"]}
    return {"status": "ACCEPTED" if all(checks.values()) else "NOT_ACCEPTED", "reason": "APPROVED_EXPERIMENTAL_CRITERIA", "approved": approved, "checks": checks}


def summarize(run: Path, protocol_path: Path = BASE / "protocol.json", acceptance_approved: bool = False) -> dict:
    run = run.resolve()
    manifest = read_json(run / "manifest.json")
    protocol = read_json(protocol_path)
    require(protocol.get("schemaVersion") == SCHEMA, "Unsupported reporting protocol")
    require(manifest.get("protocolId") == protocol.get("protocolId"), "Protocol identity mismatch")
    plan, records = read_jsonl(run / "schedule.jsonl"), read_jsonl(run / "requests.jsonl")
    if manifest.get("scheduleSha256"):
        require(sha256(run / "schedule.jsonl") == manifest["scheduleSha256"], "Schedule hash mismatch")
    progress_file = run / "progress.jsonl"
    data = validate(manifest, plan, records, read_jsonl(progress_file) if progress_file.exists() else None)
    executed = manifest["mode"] != "DRY_RUN"
    formal = [item for item in plan if not item["warmup"]]
    warmup = [item for item in plan if item["warmup"]]
    missing = len(plan) - len(records)
    unknown = sum(row["status"] == "INTERRUPTED" or row["latencyMs"] is None for row in records)
    complete = executed and manifest["status"] == "COMPLETE" and missing == 0 and unknown == 0 and bool(formal)
    status = "PLANNED" if not executed else "COMPLETE" if complete else "INCOMPLETE"
    overall = grouped_metrics(formal, data["rows"], executed)
    by_round = {str(r): grouped_metrics([item for item in formal if item["round"] == r], data["rows"], executed)
                for r in sorted({item["round"] for item in formal})}
    dimensions = {"split": ("development", "holdout"), "expectedAgent": AGENTS,
                  "tags": sorted({tag for item in formal for tag in item["tags"]}),
                  "hasHistory": (False, True), "multiIntent": (False, True), "policyPending": (False, True)}
    pending = set(protocol.get("dataset", {}).get("policyPendingGroupIds", []))
    slices = {}
    for dimension, values in dimensions.items():
        def matches(item: dict, value: Any) -> bool:
            if dimension == "tags":
                return value in item["tags"]
            if dimension == "policyPending":
                return (item["scenarioGroupId"] in pending) == value
            return item.get(dimension) == value
        slices[dimension] = {str(value): grouped_metrics([item for item in formal if matches(item, value)], data["rows"], executed) for value in values}
    delta = comparison(overall, complete)
    if not executed:
        delta = {name: None for name in delta} | {"observationsComplete": False}
    files = {name: {"path": str(run / name), "sha256": sha256(run / name)} for name in ("manifest.json", "schedule.jsonl", "requests.jsonl")}
    if progress_file.exists():
        files["progress.jsonl"] = {"path": str(progress_file), "sha256": sha256(progress_file)}
    input_sha = manifest.get("protocolSha256")
    return {"schemaVersion": SCHEMA, "reportSchemaVersion": "route-benchmark-summary-v1", "runId": manifest["runId"],
            "mode": manifest["mode"], "environmentLevel": manifest["environmentLevel"], "status": status,
            "syntheticNotice": "SYNTHETIC DATA: software verification only; not model performance evidence" if manifest["mode"] == "SYNTHETIC" else None,
            "files": files, "reportingProtocol": {"path": str(protocol_path.resolve()), "sha256": sha256(protocol_path),
            "matchesRunProtocolHash": sha256(protocol_path) == input_sha if input_sha else None},
            "identity": {name: manifest.get(name) for name in ("createdAt", "finishedAt", "stage", "git", "dataset", "sourceSha256", "collectorAndDecisionClassSha256", "configIds", "variants", "effectiveConfig", "runtimeClientOptions", "firstObservedLlmResponseModel", "firstObservedJevResponseModel", "authorization", "budget", "limits", "knownDifferences")},
            "integrity": {"planned": len(plan), "recorded": len(records), "started": len(data["started"]) if data["started"] is not None else None,
                          "progressFinished": len(data["finished"]) if data["finished"] is not None else None,
                          "ended": sum(row["status"] != "INTERRUPTED" for row in records), "missing": missing if executed else 0,
                          "notExecuted": len(plan) if not executed else 0, "duplicates": 0, "unknownCompletionOrLatency": unknown,
                          "formalPlanned": len(formal), "warmupPlanned": len(warmup), "manifestStatus": manifest["status"],
                          "uniqueFormalSampleCount": len({item["sampleId"] for item in formal}), "uniqueScenarioGroupCount": len({item["scenarioGroupId"] for item in formal})},
            "overall": overall, "byRound": by_round, "warmup": grouped_metrics(warmup, data["rows"], executed), "slices": slices,
            "paired": paired(formal, data["rows"]), "comparison": delta,
            "acceptance": acceptance_result(manifest, status, overall, delta, acceptance_approved, protocol),
            "costUsd": None,
            "limitations": ["All latency quantiles include ended errors and safe defaults; also inspect quality and normal-only latency.",
                            "P99 is exploratory; repeated rounds are not independent labeled scenarios.",
                            "Artificial labels and disputed scenario groups do not establish production quality.",
                            "HTTP counters mean RestClient execution attempts, not vendor billing or transport retransmissions.",
                            "Money cost is unknown without reliable pricing and usage; no currency hard cap is claimed.",
                            "Past source hashes describe that run; they are not silently replaced by today's files.",
                            "An accepted result applies only to the recorded environment and approved experimental criteria."],
            "reproduceCommand": f'python "{Path(__file__).resolve()}" "{run}"' + (" --acceptance-approved" if acceptance_approved else "")}


def markdown_report(report: dict) -> str:
    def value(item: Any) -> str:
        if item is None:
            return "未知 / 不适用"
        return f"{item:.4f}" if isinstance(item, float) else str(item)
    lines = ["# Jev 与 LLM 路由对比统计报告", "", f"运行：`{report['runId']}`；模式：`{report['mode']}`；环境：`{report['environmentLevel']}`；数据状态：`{report['status']}`。", ""]
    if report["mode"] == "SYNTHETIC":
        lines += ["**本报告使用模拟响应，仅验证软件与统计口径，不能作为模型性能或简历收益证据。**", ""]
    if report["mode"] == "DRY_RUN":
        lines += ["**仅生成计划，未调用模型；准确率和耗时未执行，不是测试失败。**", ""]
    lines += [f"接受结论：`{report['acceptance']['status']}`；原因：`{report['acceptance']['reason']}`。", "", "## 数据完整性", "", "| 项目 | 数量 |", "| --- | --- |"]
    lines += [f"| {name} | {value(item)} |" for name, item in report["integrity"].items()]
    lines += ["", "## 正式请求总体结果", "", "主准确率以计划正式请求为分母，只有正常且标签正确的决策计对；安全默认、错误、缺失均计错。耗时单位 ms，nearest-rank 分位数。", "", "| 指标 | LLM 主路由 | Jev 加回退 |", "| --- | --- | --- |"]
    for name in ("planned", "recorded", "primaryAccuracy", "labelMatchAccuracy", "normalCoverage", "finalErrorRate", "safeDefaultRate", "fallbackAttemptRate", "directJevRate", "llmLogicalCalls", "jevLogicalCalls", "latencyMissing"):
        lines.append(f"| {name} | {value(report['overall'][A][name])} | {value(report['overall'][B][name])} |")
    for prefix in ("latencyMs", "normalLatencyMs"):
        for q in ("n", "p50", "p95", "p99"):
            lines.append(f"| {prefix}.{q} | {value(report['overall'][A][prefix][q])} | {value(report['overall'][B][prefix][q])} |")
    lines += ["", "## 逐轮结果", "", "| 轮次 | 组 | 计划数 | 主准确率 | P50 | P95 | 安全默认数 |", "| --- | --- | --- | --- | --- | --- | --- |"]
    for r, variants in report["byRound"].items():
        for v, m in variants.items():
            lines.append(f"| {r} | {v} | {m['planned']} | {value(m['primaryAccuracy'])} | {value(m['latencyMs']['p50'])} | {value(m['latencyMs']['p95'])} | {m['statuses']['SAFE_DEFAULT']} |")
    lines += ["", "## 改善与配对", "", "百分比允许为负；缺少完整请求时仅保留已观测比较，不能据此宣称正式收益。", "", "| 项目 | 值 |", "| --- | --- |"]
    lines += [f"| {name} | {value(item)} |" for name, item in report["comparison"].items()]
    lines += [f"| plannedPairs | {report['paired']['plannedPairs']} |", f"| precisePairs | {report['paired']['precisePairs']} |", f"| notPreciselyPaired | {report['paired']['notPreciselyPaired']} |", "", "## 保留集与类别", "", "| 切片 | 组 | 计划数 | 主准确率 | P95 |", "| --- | --- | --- | --- | --- |"]
    for dimension in ("split", "expectedAgent", "policyPending"):
        for label, variants in report["slices"][dimension].items():
            for v, m in variants.items():
                lines.append(f"| {dimension}:{label} | {v} | {m['planned']} | {value(m['primaryAccuracy'])} | {value(m['latencyMs']['p95'])} |")
    lines += ["", "四类 Recall、正常决策混淆矩阵、来源/回退原因、错误切片、逐对差值和完整配置身份见同前缀 JSON。总体分位数从原始记录重算，不平均各轮分位数。", "", "## 证据与限制", "", f"独立样本 {report['integrity']['uniqueFormalSampleCount']} 条，场景组 {report['integrity']['uniqueScenarioGroupCount']} 个；重复轮次不扩大独立标注规模。", ""]
    for name, item in report["files"].items():
        lines += [f"- {name}：`{item['path']}`；SHA-256 `{item['sha256']}`。"]
    lines += [""] + [f"- {item}" for item in report["limitations"]]
    lines += ["", "## 复现命令", "", "```powershell", report["reproduceCommand"], "```", ""]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--protocol", type=Path, default=BASE / "protocol.json")
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--prefix", default="summary")
    parser.add_argument("--acceptance-approved", action="store_true", help="Only use after the user approved the protocol criteria and frozen labels")
    args = parser.parse_args(argv)
    require(bool(re.fullmatch(r"[A-Za-z0-9_-]+", args.prefix)), "Output prefix must be a filename stem")
    out = (args.output_dir or args.run_dir).resolve()
    outputs = [out / (args.prefix + suffix) for suffix in (".json", ".md")]
    protected = {(args.run_dir / name).resolve() for name in ("manifest.json", "schedule.jsonl", "requests.jsonl", "progress.jsonl", "execution.md", "schedule-sha256.json")}
    protected.add(args.protocol.resolve())
    require(not any(path in protected for path in outputs), "Output must not replace source evidence")
    out.mkdir(parents=True, exist_ok=True)
    try:
        report = summarize(args.run_dir, args.protocol, args.acceptance_approved)
    except (ReportError, KeyError, TypeError) as error:
        diagnostic = {"status": "INVALID", "errorType": type(error).__name__, "diagnostic": str(error), "runPath": str(args.run_dir.resolve())}
        (out / (args.prefix + "-diagnostic.json")).write_text(json.dumps(diagnostic, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(diagnostic, ensure_ascii=True), file=sys.stderr)
        return 2
    outputs[0].write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    outputs[1].write_text(markdown_report(report), encoding="utf-8")
    print(json.dumps({"runId": report["runId"], "mode": report["mode"], "status": report["status"], "acceptance": report["acceptance"]["status"], "summary": str(outputs[0])}, ensure_ascii=True))
    return 3 if report["status"] == "INCOMPLETE" else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ReportError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)
