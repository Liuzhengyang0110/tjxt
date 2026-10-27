"""Offline contract tests; all temporary model records are synthetic."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import benchmark_report as report


class ReportTests(unittest.TestCase):
    def setUp(self):
        temp_parent = Path(__file__).resolve().parents[2] / "tj-aigc/target/route-benchmark/python-tests"
        temp_parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=temp_parent)
        # Verify the resolved cleanup target stays in the benchmark workspace.
        assert Path(self.temp.name).resolve().is_relative_to(temp_parent.resolve())
        self.addCleanup(self.temp.cleanup)
        self.run = Path(self.temp.name)
        self.protocol = self.run / "protocol.json"
        self.protocol.write_text(json.dumps({"schemaVersion": report.SCHEMA, "protocolId": "test-protocol",
                                             "dataset": {"policyPendingGroupIds": ["route-0030"]},
                                             "acceptanceProposal": {"maxAccuracyDropPercentagePoints": 1}}), encoding="utf-8")

    def fixture(self, labels=("KNOWLEDGE", "BUY"), rounds=1, mode="SYNTHETIC", warmups=False):
        plan, rows = [], []
        for r in range(1, rounds + 1):
            for index, label in enumerate(labels):
                original = f"route-{index + 2:04d}-a"
                for v, config, source in ((report.A, "a-config", "LLM_PRIMARY"), (report.B, "b-config", "JEV")):
                    item = {"schemaVersion": report.SCHEMA, "runId": "unit-fixture", "configId": config,
                            "environmentLevel": "ISOLATED_JAVA_ROUTE", "mode": mode, "round": r,
                            "sampleId": f"warmup-{r}-{v}-{index}" if warmups else original,
                            "inputSampleId": original, "variant": v, "orderIndex": len(plan),
                            "pairOrder": "AB", "warmup": warmups, "expectedAgent": label,
                            "scenarioGroupId": report.scenario(original), "split": report.split(original),
                            "tags": ["clear"], "hasHistory": False, "multiIntent": False}
                    plan.append(item)
                    rows.append(item | {"actualAgent": label, "source": source, "fallbackReason": "NONE",
                                       "confidence": .95 if v == report.B else None,
                                       "latencyMs": 100.0 if v == report.A else 50.0,
                                       "status": "OK", "errorType": None, "jevModel": "synthetic-jev",
                                       "llmModel": "synthetic-llm", "jevCallCount": int(v == report.B),
                                       "llmCallCount": int(v == report.A), "startedAt": "2026-10-26T00:00:00Z",
                                       "finishedAt": "2026-10-26T00:00:01Z", "httpAttemptCount": None})
        manifest = {"schemaVersion": report.SCHEMA, "protocolId": "test-protocol", "runId": "unit-fixture",
                    "mode": mode, "environmentLevel": "ISOLATED_JAVA_ROUTE", "status": "COMPLETE", "stage": "formal",
                    "variants": [{"variant": report.A, "configId": "a-config"}, {"variant": report.B, "configId": "b-config"}],
                    "configIds": ["a-config", "b-config"], "plannedRecordCount": len(plan), "completedRecordCount": len(rows)}
        self.persist(manifest, plan, rows)
        return manifest, plan, rows

    def persist(self, manifest, plan, rows, progress=None):
        for name, value in (("schedule.jsonl", plan), ("requests.jsonl", rows)):
            (self.run / name).write_text("".join(json.dumps(row) + "\n" for row in value), encoding="utf-8")
        manifest["scheduleSha256"] = hashlib.sha256((self.run / "schedule.jsonl").read_bytes()).hexdigest()
        (self.run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        if progress is not None:
            (self.run / "progress.jsonl").write_text("".join(json.dumps(row) + "\n" for row in progress), encoding="utf-8")

    def summary(self, **kwargs):
        return report.summarize(self.run, self.protocol, **kwargs)

    def test_nearest_rank_empty_single_and_tail(self):
        self.assertIsNone(report.percentile([], .95))
        self.assertEqual(3, report.percentile([3], .95))
        self.assertEqual(2, report.percentile([4, 1, 3, 2], .5))
        self.assertEqual(4, report.percentile([4, 1, 3, 2], .95))

    def test_two_configs_same_run_are_valid(self):
        self.fixture()
        s = self.summary()
        self.assertEqual("COMPLETE", s["status"])
        self.assertEqual(1, s["overall"][report.A]["primaryAccuracy"])
        self.assertEqual(50, s["comparison"]["p95ImprovementPercent"])
        self.assertEqual(100, s["comparison"]["llmLogicalReductionPercent"])
        self.assertEqual("NON_LIVE_DATA", s["acceptance"]["reason"])

    def test_safe_default_does_not_inflate_primary_accuracy(self):
        m, p, r = self.fixture(labels=("KNOWLEDGE",))
        r[1].update(status="SAFE_DEFAULT", source="SAFE_DEFAULT", llmCallCount=1,
                    confidence=None, fallbackReason="LLM_ERROR", jevErrorType="TIMEOUT", llmErrorType="SyntheticError")
        self.persist(m, p, r)
        b = self.summary()["overall"][report.B]
        self.assertEqual(0, b["primaryAccuracy"])
        self.assertEqual(1, b["labelMatchAccuracy"])
        self.assertEqual(1, b["fallbackAttemptRate"])
        self.assertEqual(1, b["latencyMs"]["n"])
        self.assertEqual(0, b["normalLatencyMs"]["n"])
        self.assertEqual(1, b["jevSubcallErrors"]["TIMEOUT"])

    def test_failed_primary_is_also_safe_default(self):
        m, p, r = self.fixture(labels=("KNOWLEDGE",))
        r[0].update(status="SAFE_DEFAULT", source="SAFE_DEFAULT", confidence=None)
        self.persist(m, p, r)
        a = self.summary()["overall"][report.A]
        self.assertEqual(0, a["primaryAccuracy"])
        self.assertEqual(1, a["safeDefaultRate"])

    def test_successful_fallback_counts_actual_attempt(self):
        m, p, r = self.fixture(labels=("BUY",))
        r[1].update(source="LLM_FALLBACK", llmCallCount=1, confidence=None, fallbackReason="TIMEOUT")
        self.persist(m, p, r)
        b = self.summary()["overall"][report.B]
        self.assertEqual(1, b["primaryAccuracy"])
        self.assertEqual(1, b["fallbackAttemptRate"])
        self.assertEqual(0, b["directJevRate"])

    def test_overall_p95_recomputed_not_average(self):
        m, p, r = self.fixture(labels=("BUY",), rounds=2)
        r[0]["latencyMs"], r[2]["latencyMs"] = 10, 100
        self.persist(m, p, r)
        s = self.summary()
        self.assertEqual(100, s["overall"][report.A]["latencyMs"]["p95"])
        self.assertEqual(10, s["byRound"]["1"][report.A]["latencyMs"]["p95"])
        self.assertEqual(1, s["integrity"]["uniqueFormalSampleCount"])
        self.assertEqual(1, s["integrity"]["uniqueScenarioGroupCount"])

    def test_negative_improvement_is_preserved(self):
        m, p, r = self.fixture(labels=("BUY",))
        r[1]["latencyMs"] = 200
        self.persist(m, p, r)
        s = self.summary()
        self.assertEqual(-100, s["comparison"]["p95ImprovementPercent"])
        self.assertEqual(-100, s["paired"]["differenceMs"]["p50"])

    def test_missing_record_uses_planned_denominator(self):
        m, p, r = self.fixture(labels=("BUY",))
        m["status"], m["completedRecordCount"] = "INCOMPLETE", 1
        self.persist(m, p, r[:1])
        s = self.summary()
        self.assertEqual("INCOMPLETE", s["status"])
        self.assertEqual(0, s["overall"][report.B]["primaryAccuracy"])
        self.assertIsNone(s["overall"][report.B]["latencyMs"]["p95"])
        self.assertIsNone(s["comparison"]["llmLogicalReductionPercent"])
        self.assertEqual(1, s["paired"]["notPreciselyPaired"])

    def test_missing_latency_is_null_not_zero(self):
        m, p, r = self.fixture(labels=("BUY",))
        r[1]["latencyMs"] = None
        self.persist(m, p, r)
        s = self.summary()
        self.assertEqual("INCOMPLETE", s["status"])
        self.assertIsNone(s["overall"][report.B]["latencyMs"]["p50"])
        self.assertEqual(1, s["overall"][report.B]["latencyMissing"])

    def test_interrupted_record_keeps_unknown_completion(self):
        m, p, r = self.fixture(labels=("BUY",))
        m["status"], m["completedRecordCount"] = "INCOMPLETE", 1
        r[1].update(status="INTERRUPTED", actualAgent=None, source=None, confidence=None,
                    latencyMs=None, finishedAt=None, completionUnknown=True, errorType="TimeoutException")
        self.persist(m, p, r)
        s = self.summary()
        self.assertEqual("INCOMPLETE", s["status"])
        self.assertEqual(1, s["overall"][report.B]["statuses"]["INTERRUPTED"])

    def test_dry_run_is_not_executed_not_zero_accuracy(self):
        m, p, _ = self.fixture(mode="DRY_RUN")
        m["status"], m["completedRecordCount"] = "PLANNED", 0
        self.persist(m, p, [])
        s = self.summary()
        self.assertEqual("PLANNED", s["status"])
        self.assertEqual(4, s["integrity"]["notExecuted"])
        self.assertEqual(0, s["integrity"]["missing"])
        self.assertIsNone(s["overall"][report.A]["primaryAccuracy"])

    def test_empty_plan_cannot_be_accepted(self):
        self.fixture(labels=(), mode="LIVE")
        s = self.summary(acceptance_approved=True)
        self.assertEqual("INCOMPLETE", s["status"])
        self.assertEqual("NOT_EVALUATED", s["acceptance"]["status"])

    def test_warmup_is_not_formal_observation(self):
        self.fixture(labels=("BUY",), warmups=True)
        s = self.summary()
        self.assertEqual(0, s["overall"][report.A]["planned"])
        self.assertEqual(1, s["warmup"][report.A]["planned"])

    def test_approved_live_criteria_can_accept(self):
        self.fixture(mode="LIVE")
        self.assertEqual("CRITERIA_PENDING", self.summary()["acceptance"]["reason"])
        self.assertEqual("ACCEPTED", self.summary(acceptance_approved=True)["acceptance"]["status"])

    def test_quality_regression_rejects_even_when_faster(self):
        m, p, r = self.fixture(mode="LIVE")
        r[3]["actualAgent"] = "CONSULT"
        self.persist(m, p, r)
        s = self.summary(acceptance_approved=True)
        self.assertEqual("NOT_ACCEPTED", s["acceptance"]["status"])
        self.assertFalse(s["acceptance"]["checks"]["buyRecallNotLower"])

    def test_synthetic_never_accepted_even_with_flag(self):
        self.fixture()
        self.assertEqual("NON_LIVE_DATA", self.summary(acceptance_approved=True)["acceptance"]["reason"])

    def test_explicit_report_only_policy_never_becomes_acceptance(self):
        m, p, r = self.fixture(mode="LIVE")
        m["authorization"] = {"approved": True, "acceptancePolicy": "REPORT_ONLY"}
        self.persist(m, p, r)
        s = self.summary(acceptance_approved=True)
        self.assertEqual("NOT_EVALUATED", s["acceptance"]["status"])
        self.assertEqual("USER_REPORT_ONLY", s["acceptance"]["reason"])

    def test_smoke_never_certifies_formal_experiment(self):
        m, p, r = self.fixture(mode="LIVE")
        m["stage"] = "smoke"
        self.persist(m, p, r)
        self.assertEqual("NON_FORMAL_STAGE", self.summary(acceptance_approved=True)["acceptance"]["reason"])

    def test_boolean_cannot_impersonate_integer_identity(self):
        m, p, r = self.fixture()
        r[0]["round"] = True
        self.persist(m, p, r)
        with self.assertRaises(report.ReportError):
            self.summary()

    def test_manifest_logical_total_must_match_records(self):
        m, p, r = self.fixture()
        m["actualLogicalCalls"] = {"jev": 500, "llm": 2, "httpAttempts": None}
        self.persist(m, p, r)
        with self.assertRaisesRegex(report.ReportError, "logical totals"):
            self.summary()

    def test_duplicate_request_rejected(self):
        m, p, r = self.fixture()
        r.append(copy.deepcopy(r[0]))
        m["completedRecordCount"] = len(r)
        self.persist(m, p, r)
        with self.assertRaisesRegex(report.ReportError, "Duplicate request"):
            self.summary()

    def test_extra_request_rejected(self):
        m, p, r = self.fixture()
        r[0]["sampleId"] = "not-in-plan"
        self.persist(m, p, r)
        with self.assertRaisesRegex(report.ReportError, "Unexpected request"):
            self.summary()

    def test_mixed_mode_or_schema_or_config_rejected(self):
        for field, bad in (("mode", "LIVE"), ("schemaVersion", "v-other"), ("configId", "other")):
            with self.subTest(field=field):
                m, p, r = self.fixture()
                r[0][field] = bad
                self.persist(m, p, r)
                with self.assertRaises(report.ReportError):
                    self.summary()

    def test_invalid_latency_negative_boolean_and_nan_rejected(self):
        for bad in (-1, True, "20", float("nan")):
            with self.subTest(value=bad):
                m, p, r = self.fixture()
                r[0]["latencyMs"] = bad
                self.persist(m, p, r)
                with self.assertRaises(report.ReportError):
                    self.summary()

    def test_source_status_inconsistency_rejected(self):
        m, p, r = self.fixture()
        r[0]["source"] = "SAFE_DEFAULT"
        self.persist(m, p, r)
        with self.assertRaises(report.ReportError):
            self.summary()

    def test_progress_requires_unique_start_and_consistent_finish(self):
        m, p, r = self.fixture()
        progress = [{"event": event, "orderIndex": row["orderIndex"]} for row in r for event in ("STARTED", "FINISHED")]
        self.persist(m, p, r, progress)
        self.assertEqual(4, self.summary()["integrity"]["started"])
        progress.append({"event": "STARTED", "orderIndex": 0})
        self.persist(m, p, r, progress)
        with self.assertRaisesRegex(report.ReportError, "Duplicate progress"):
            self.summary()

    def test_schedule_tampering_rejected_by_hash(self):
        self.fixture()
        path = self.run / "schedule.jsonl"
        path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        with self.assertRaisesRegex(report.ReportError, "Schedule hash"):
            self.summary()

    def test_split_and_scenario_slices_partition_plan(self):
        self.fixture(labels=("BUY", "CONSULT", "RECOMMEND", "KNOWLEDGE"))
        s = self.summary()
        for variant in report.VARIANTS:
            self.assertEqual(4, sum(v[variant]["planned"] for v in s["slices"]["split"].values()))
            self.assertEqual(4, sum(v[variant]["planned"] for v in s["slices"]["expectedAgent"].values()))

    def test_cli_outputs_and_incomplete_exit_code(self):
        m, p, r = self.fixture(labels=("BUY",))
        self.assertEqual(0, report.main([str(self.run), "--protocol", str(self.protocol)]))
        self.assertIn("模拟响应", (self.run / "summary.md").read_text(encoding="utf-8"))
        m["status"], m["completedRecordCount"] = "INCOMPLETE", 1
        self.persist(m, p, r[:1])
        self.assertEqual(3, report.main([str(self.run), "--protocol", str(self.protocol)]))

    def test_cli_invalid_diagnostic_and_input_protection(self):
        m, p, r = self.fixture()
        r.append(r[0])
        self.persist(m, p, r)
        self.assertEqual(2, report.main([str(self.run), "--protocol", str(self.protocol)]))
        self.assertTrue((self.run / "summary-diagnostic.json").exists())
        with self.assertRaises(report.ReportError):
            report.main([str(self.run), "--protocol", str(self.protocol), "--prefix", "manifest"])


if __name__ == "__main__":
    unittest.main()
