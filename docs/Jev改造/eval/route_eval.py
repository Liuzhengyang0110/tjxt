"""离线路由评测。仅用 Python 标准库；不会启动 tjxt 或写入线上会话。"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

AGENTS = ("RECOMMEND", "CONSULT", "BUY", "KNOWLEDGE")
THRESHOLDS = (0.70, 0.75, 0.80, 0.85, 0.90, 0.95)
AVAILABLE_AGENTS = {
    "RECOMMEND": "推荐平台中的具体课程",
    "CONSULT": "咨询平台具体课程的信息",
    "BUY": "购买或预下单已选课程",
    "KNOWLEDGE": "解释通用知识和编程问题",
}
CRITERIA = {
    "RECOMMEND": "Course recommendation / 根据用户目标、基础、预算或时间，推荐平台中的具体课程",
    "CONSULT": "Course consultation / 咨询平台具体课程的价格、内容、老师、适合人群或状态",
    "BUY": "Course purchase / 用户明确要购买、结算或预下单已经选定的课程；仅问价格不属于购买",
    "KNOWLEDGE": "Knowledge explanation / 通用知识、编程概念、学习路线或问题讲解，不要求推荐平台具体课程",
}
DATASET = Path(__file__).with_name("route-eval-v1.jsonl")
DEFAULT_REPORT = Path(__file__).resolve().parents[3] / "tj-aigc" / "target" / "route-eval" / "report.json"
MASKS = (
    (re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"), "<PHONE>"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "<EMAIL>"),
    (re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)"), "<ID_CARD>"),
    (re.compile(r"(?<!\d)\d{8,}(?!\d)"), "<LONG_ID>"),
)


def sanitize(text: str) -> str:
    for pattern, replacement in MASKS:
        text = pattern.sub(replacement, text)
    return text


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as source:
        for number, raw in enumerate(source, 1):
            if not raw.strip():
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{number}: JSON 格式错误: {error.msg}") from error
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{number}: 每行必须是 JSON 对象")
            rows.append(row)
    return rows


def load_samples(path: Path) -> list[dict]:
    samples = load_jsonl(path)
    seen = set()
    for index, row in enumerate(samples, 1):
        sample_id = row.get("id")
        if not isinstance(sample_id, str) or not sample_id or sample_id in seen:
            raise ValueError(f"第 {index} 行 id 缺失或重复")
        seen.add(sample_id)
        if row.get("expectedAgent") not in AGENTS:
            raise ValueError(f"{sample_id}: expectedAgent 必须为四个专项 Agent 之一")
        if not isinstance(row.get("question"), str) or not row["question"].strip():
            raise ValueError(f"{sample_id}: question 不能为空")
        if not isinstance(row.get("history"), list):
            raise ValueError(f"{sample_id}: history 必须为数组")
        for message in row["history"]:
            if not isinstance(message, dict) or message.get("role") not in ("user", "assistant") \
                    or not isinstance(message.get("content"), str):
                raise ValueError(f"{sample_id}: history 消息需包含 role=user/assistant 和 content")
        for field in ("ambiguous", "multiIntent"):
            if not isinstance(row.get(field), bool):
                raise ValueError(f"{sample_id}: {field} 必须为布尔值")
        if not isinstance(row.get("tags", []), list) or any(
                not isinstance(tag, str) for tag in row.get("tags", [])):
            raise ValueError(f"{sample_id}: tags 必须为字符串数组")
    if not samples:
        raise ValueError("标注集为空")
    return samples


def split(sample_id: str) -> str:
    # 同一场景的 a/b/c/d 改写必须同组，避免近重复样本跨开发集与保留集。
    group = sample_id.rsplit("-", 1)[0] if re.fullmatch(r"route-\d{4}-[a-d]", sample_id) else sample_id
    digest = hashlib.sha256(group.encode()).digest()
    return "holdout" if int.from_bytes(digest[:4], "big") % 5 == 0 else "development"


def keyword_route(question: str) -> str:
    """刻意简单的规则基线，不代表线上业务规则。"""
    q = question.lower()
    if any(term in q for term in ("下单", "付款", "结算", "购买", "支付", "买这门", "买这套")):
        return "BUY"
    if any(term in q for term in ("推荐", "选哪", "哪门课", "课程怎么选", "找个课", "报什么课")):
        return "RECOMMEND"
    if any(term in q for term in ("这门课", "这套课", "课程价格", "课程内容", "讲师", "课时", "开课", "多少钱", "能退", "有效期")):
        return "CONSULT"
    return "KNOWLEDGE"


def load_llm_predictions(path: Path | None, sample_ids: set[str]) -> dict[str, dict]:
    if path is None:
        return {}
    predictions = {}
    for row in load_jsonl(path):
        sample_id = row.get("id")
        if sample_id not in sample_ids or sample_id in predictions:
            raise ValueError(f"RouteAgent 预测 id 未标注或重复: {sample_id}")
        agent = row.get("agent")
        if agent not in AGENTS and not row.get("error"):
            raise ValueError(f"{sample_id}: agent 无效且没有 error")
        predictions[sample_id] = {
            "agent": agent if agent in AGENTS else None,
            "latencyMs": row.get("latencyMs"),
            "costUsd": row.get("costUsd"),
            "error": row.get("error"),
        }
    return predictions


def load_jev_report(path: Path, sample_ids: set[str]) -> dict[str, dict]:
    report = json.loads(path.read_text(encoding="utf-8"))
    predictions = {}
    for row in report.get("predictions", []):
        sample_id = row.get("id")
        if sample_id in sample_ids and isinstance(row.get("jev"), dict):
            predictions[sample_id] = row["jev"]
    if not predictions:
        raise ValueError("Jev 报告中没有与当前标注集匹配的预测")
    return predictions


def jev_state(sample: dict) -> dict:
    latest = sanitize(sample["question"])
    if len(latest) > 4000:
        latest = latest[:1999] + "…" + latest[-2000:]
    remaining = 4000 - len(latest)
    recent = []
    for message in reversed(sample["history"][-8:]):
        content = sanitize(message["content"])
        if content and remaining:
            content = content[-remaining:]
            recent.append({"role": message["role"], "content": content})
            remaining -= len(content)
    return {
        "latest_user_message": latest,
        "recent_context": list(reversed(recent)),
        "available_agents": AVAILABLE_AGENTS,
    }


def jev_call(sample: dict, key: str, base_url: str, model: str, timeout: float,
             retries: int, input_price: float | None, output_price: float | None) -> dict:
    body = {
        "state": jev_state(sample), "model": model,
        "questions": {"route_agent": {
            "type": "choice",
            "instructions": "根据最新用户消息和近期上下文，选择最适合处理当前请求的一个 Agent。只判断主要意图。",
            "criteria": CRITERIA,
        }},
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/systemone",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
        method="POST",
    )
    started = time.monotonic()
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                result = json.load(response)
            answer = result.get("answers", {}).get("route_agent", {})
            agent, confidence, probabilities = answer.get("choice"), answer.get("confidence"), answer.get("probabilities")
            if not isinstance(result.get("model"), str) or not result["model"].strip() \
                    or answer.get("type") != "choice" or agent not in AGENTS \
                    or type(confidence) not in (int, float) or not math.isfinite(confidence) \
                    or not 0 <= confidence <= 1 or not isinstance(probabilities, dict) \
                    or not probabilities or not set(probabilities).issubset(AGENTS) \
                    or any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1
                           for v in probabilities.values()) \
                    or abs(sum(probabilities.values()) - 1) > 0.001:
                raise ValueError("INVALID_RESPONSE")
            usage = result.get("usage") or {}
            input_tokens, output_tokens = usage.get("input_tokens"), usage.get("output_tokens")
            cost = None
            if input_price is not None and output_price is not None \
                    and isinstance(input_tokens, int) and isinstance(output_tokens, int):
                cost = round((input_tokens * input_price + output_tokens * output_price) / 1_000_000, 8)
            return {"agent": agent, "confidence": confidence, "probabilities": probabilities,
                    "model": result.get("model"), "usage": usage, "latencyMs": elapsed_ms(started),
                    "costUsd": cost, "attempts": attempt + 1, "error": None}
        except urllib.error.HTTPError as error:
            code = error.code
            if code in (429, 529) and attempt < retries:
                time.sleep(min(2 ** attempt, 4))
                continue
            reason = {401: "AUTH_FAILURE", 403: "AUTH_FAILURE", 422: "INVALID_REQUEST",
                      429: "RATE_LIMITED", 529: "OVERLOADED"}.get(code, "SERVER_ERROR" if code >= 500 else "HTTP_ERROR")
        except (urllib.error.URLError, TimeoutError) as error:
            reason = "TIMEOUT" if isinstance(error, TimeoutError) else "NETWORK_ERROR"
        except (ValueError, KeyError, TypeError, AttributeError):
            reason = "INVALID_RESPONSE"
        return {"agent": None, "confidence": None, "probabilities": None, "model": None,
                "usage": None, "latencyMs": elapsed_ms(started), "costUsd": None,
                "attempts": attempt + 1, "error": reason}
    raise AssertionError("unreachable")


def elapsed_ms(started: float) -> int:
    return round((time.monotonic() - started) * 1000)


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[math.ceil(p * len(ordered)) - 1], 3)


def ratio(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def metrics(rows: list[dict], samples: dict[str, dict]) -> dict:
    evaluated = [row for row in rows if row.get("agent") in AGENTS]
    correct = sum(row["agent"] == samples[row["id"]]["expectedAgent"] for row in evaluated)
    matrix = {expected: {actual: 0 for actual in AGENTS} for expected in AGENTS}
    for row in evaluated:
        matrix[samples[row["id"]]["expectedAgent"]][row["agent"]] += 1
    per_agent = {}
    for agent in AGENTS:
        tp = matrix[agent][agent]
        fp = sum(matrix[other][agent] for other in AGENTS if other != agent)
        support = sum(samples[row["id"]]["expectedAgent"] == agent for row in rows)
        precision, recall = ratio(tp, tp + fp), ratio(tp, support)
        f1 = round(2 * precision * recall / (precision + recall), 4) if precision and recall else 0.0
        per_agent[agent] = {"precision": precision, "recall": recall, "f1": f1, "support": support}
    slices = {}
    for name, predicate in {
        "ambiguous": lambda s: s["ambiguous"],
        "multiIntent": lambda s: s["multiIntent"],
        "reference": lambda s: "reference" in s.get("tags", []),
        "injection": lambda s: "injection" in s.get("tags", []),
        "colloquialOrTypo": lambda s: "colloquial" in s.get("tags", []) or "typo" in s.get("tags", []),
    }.items():
        selected = [row for row in rows if predicate(samples[row["id"]])]
        slices[name] = {"count": len(selected), "accuracy": ratio(sum(
            row.get("agent") == samples[row["id"]]["expectedAgent"] for row in selected), len(selected))}
    latencies = [row["latencyMs"] for row in rows if isinstance(row.get("latencyMs"), (int, float))]
    costs = [row["costUsd"] for row in rows if isinstance(row.get("costUsd"), (int, float))]
    return {"total": len(rows), "evaluated": len(evaluated), "accuracy": ratio(correct, len(rows)),
            "accuracyWhenAvailable": ratio(correct, len(evaluated)),
            "coverage": ratio(len(evaluated), len(rows)), "perAgent": per_agent, "confusionMatrix": matrix,
            "slices": slices, "errorRate": ratio(len(rows) - len(evaluated), len(rows)),
            "latencyMs": {"p50": percentile(latencies, .5), "p95": percentile(latencies, .95),
                          "p99": percentile(latencies, .99)},
            "costUsdPerRequest": round(statistics.mean(costs), 8) if len(costs) == len(rows) and rows else None}


def evaluate(samples: list[dict], llm: dict[str, dict], jev: dict[str, dict],
             threshold: float, buy_threshold: float) -> dict:
    by_id = {sample["id"]: sample for sample in samples}
    keyword_rows = []
    for sample in samples:
        started = time.perf_counter_ns()
        agent = keyword_route(sample["question"])
        keyword_rows.append({"id": sample["id"], "agent": agent,
                             "latencyMs": (time.perf_counter_ns() - started) / 1_000_000,
                             "costUsd": 0.0})
    report = {"sampleCount": len(samples), "splitCounts": dict(Counter(split(s["id"]) for s in samples)),
              "keyword": metrics(keyword_rows, by_id), "routeAgent": None, "jev": None, "gated": None,
              "jevLlmDisagreementRate": None, "thresholdSweep": [], "calibration": {}}
    if llm:
        report["routeAgent"] = metrics([{"id": s["id"], **llm.get(s["id"], {"agent": None})} for s in samples], by_id)
    if jev:
        report["jev"] = metrics([{"id": s["id"], **jev.get(s["id"], {"agent": None})} for s in samples], by_id)
        for agent in AGENTS:
            for lower in (0.0, 0.5, 0.7, 0.8, 0.9):
                upper = {0.0: 0.5, 0.5: 0.7, 0.7: 0.8, 0.8: 0.9, 0.9: 1.000001}[lower]
                selected = [s for s in samples if s["id"] in jev and jev[s["id"]].get("agent") == agent
                            and isinstance(jev[s["id"]].get("confidence"), (int, float))
                            and lower <= jev[s["id"]]["confidence"] < upper]
                report["calibration"][f"{agent}:{lower:g}-{min(upper, 1):g}"] = {
                    "count": len(selected), "accuracy": ratio(sum(
                        jev[s["id"]]["agent"] == s["expectedAgent"] for s in selected), len(selected))}
        if llm:
            overlap = [s for s in samples if llm.get(s["id"], {}).get("agent") in AGENTS
                       and jev.get(s["id"], {}).get("agent") in AGENTS]
            report["jevLlmDisagreementRate"] = ratio(sum(
                llm[s["id"]]["agent"] != jev[s["id"]]["agent"] for s in overlap), len(overlap))
            for candidate in THRESHOLDS:
                selected = [s for s in samples if split(s["id"]) == "development"
                            and jev.get(s["id"], {}).get("agent") in AGENTS
                            and jev[s["id"]]["confidence"] >= max(candidate, buy_threshold if jev[s["id"]]["agent"] == "BUY" else 0)]
                dev_count = sum(split(s["id"]) == "development" for s in samples)
                report["thresholdSweep"].append({"threshold": candidate,
                    "developmentCoverage": ratio(len(selected), dev_count),
                    "developmentAccuracy": ratio(sum(
                        jev[s["id"]]["agent"] == s["expectedAgent"] for s in selected), len(selected))})
            gated_rows, fallback_count = [], 0
            for sample in samples:
                sample_id = sample["id"]
                j, l = jev.get(sample_id, {}), llm.get(sample_id, {})
                accepted = j.get("agent") in AGENTS and j.get("confidence", -1) >= max(
                    threshold, buy_threshold if j.get("agent") == "BUY" else 0)
                fallback_count += not accepted
                chosen = j if accepted else l
                gated_rows.append({"id": sample_id, "agent": chosen.get("agent"),
                    "latencyMs": (j.get("latencyMs") or 0) + (0 if accepted else (l.get("latencyMs") or 0)),
                    "costUsd": ((j.get("costUsd") or 0) + (0 if accepted else (l.get("costUsd") or 0)))
                               if j.get("costUsd") is not None and (accepted or l.get("costUsd") is not None) else None})
            report["gated"] = metrics(gated_rows, by_id)
            report["gated"]["fallbackRate"] = ratio(fallback_count, len(samples))
            report["gated"]["threshold"] = threshold
            report["gated"]["buyThreshold"] = buy_threshold
            holdout_rows = [row for row in gated_rows if split(row["id"]) == "holdout"]
            report["gated"]["holdout"] = metrics(holdout_rows, by_id)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "run"))
    parser.add_argument("--dataset", type=Path, default=DATASET)
    parser.add_argument("--llm-predictions", type=Path, help="原 RouteAgent 的独立 JSONL 预测文件")
    parser.add_argument("--jev-live", action="store_true", help="显式调用真实 Jev API")
    parser.add_argument("--jev-report", type=Path, help="复用上次报告的 Jev 预测，不再调用 API")
    parser.add_argument("--output", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--max-requests", type=int, help="联调时只跑前 N 条")
    parser.add_argument("--min-interval-ms", type=int, default=250)
    parser.add_argument("--timeout-seconds", type=float, default=5)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--threshold", type=float, default=.85)
    parser.add_argument("--buy-threshold", type=float, default=.95)
    parser.add_argument("--jev-input-usd-per-million", type=float)
    parser.add_argument("--jev-output-usd-per-million", type=float)
    args = parser.parse_args()
    if args.jev_live and args.jev_report:
        parser.error("--jev-live 与 --jev-report 只能选择一个")
    protected = [args.dataset, args.llm_predictions, args.jev_report]
    if any(path is not None and args.output.resolve() == path.resolve() for path in protected):
        parser.error("报告路径不能覆盖原始标注集或输入预测文件")
    samples = load_samples(args.dataset)
    counts = Counter(s["expectedAgent"] for s in samples)
    print(f"标注集: {len(samples)} 条；类别: {dict(counts)}；开发/保留: {dict(Counter(split(s['id']) for s in samples))}")
    if args.command == "validate":
        return 0
    all_sample_ids = {s["id"] for s in samples}
    if args.max_requests is not None:
        if args.max_requests <= 0:
            parser.error("--max-requests 必须大于 0")
        samples = samples[:args.max_requests]
    if any(value < 0 for value in (args.min_interval_ms, args.retries)) \
            or args.timeout_seconds <= 0 or not 0 <= args.threshold <= 1 or not 0 <= args.buy_threshold <= 1:
        parser.error("间隔、重试、超时或阈值参数无效")
    llm = {sample_id: row for sample_id, row in
           load_llm_predictions(args.llm_predictions, all_sample_ids).items()
           if sample_id in {s["id"] for s in samples}}
    jev = load_jev_report(args.jev_report, {s["id"] for s in samples}) if args.jev_report else {}
    key = os.getenv("JEV_API_KEY", "").strip()
    if args.jev_live and not key:
        print("未设置 JEV_API_KEY，跳过真实 Jev 调用。", file=sys.stderr)
    if args.jev_live and key:
        base_url = os.getenv("JEV_BASE_URL", "https://api.typesafe.ai")
        model = os.getenv("JEV_MODEL", "jev-1.13.0")
        for index, sample in enumerate(samples, 1):
            if index > 1:
                time.sleep(args.min_interval_ms / 1000)
            jev[sample["id"]] = jev_call(sample, key, base_url, model, args.timeout_seconds,
                args.retries, args.jev_input_usd_per_million, args.jev_output_usd_per_million)
            print(f"Jev {index}/{len(samples)}: {sample['id']} {jev[sample['id']].get('error') or 'OK'}")
    summary = evaluate(samples, llm, jev, args.threshold, args.buy_threshold)
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "dataset": str(args.dataset),
              "llmSource": str(args.llm_predictions) if args.llm_predictions else None,
              "jevMode": "live" if args.jev_live and jev else "replay" if args.jev_report else "skipped",
              "modelRequested": os.getenv("JEV_MODEL", "jev-1.13.0") if args.jev_live and jev else None,
              "summary": summary, "predictions": [{"id": s["id"], "split": split(s["id"]),
                  "expectedAgent": s["expectedAgent"], "keyword": keyword_route(s["question"]),
                  "routeAgent": llm.get(s["id"]), "jev": jev.get(s["id"])} for s in samples]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"报告: {args.output}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as exc:
        print(f"评测失败: {exc}", file=sys.stderr)
        sys.exit(2)
