import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import route_eval as evaluate


class RouteEvalTest(unittest.TestCase):
    def test_dataset_is_valid_and_scenario_variants_stay_in_one_split(self):
        samples = evaluate.load_samples(evaluate.DATASET)
        self.assertGreaterEqual(len(samples), 300)
        self.assertLessEqual(len(samples), 500)
        for agent in evaluate.AGENTS:
            self.assertGreaterEqual(sum(s["expectedAgent"] == agent and "clear" in s.get("tags", [])
                                        for s in samples), 50)
        self.assertGreaterEqual(sum("boundary" in s.get("tags", []) for s in samples), 50)
        self.assertGreaterEqual(sum("reference" in s.get("tags", []) for s in samples), 40)
        self.assertGreaterEqual(sum("colloquial" in s.get("tags", []) or "typo" in s.get("tags", [])
                                    for s in samples), 40)
        self.assertGreaterEqual(sum(any(tag in s.get("tags", []) for tag in
                                        ("multiIntent", "insufficient", "outOfScope", "injection"))
                                    for s in samples), 70)
        self.assertEqual(evaluate.split("route-0002-a"), evaluate.split("route-0002-d"))

    def test_metrics_count_missing_predictions_as_errors(self):
        samples = {
            "a": {"expectedAgent": "RECOMMEND", "ambiguous": False, "multiIntent": False},
            "b": {"expectedAgent": "BUY", "ambiguous": True, "multiIntent": False},
        }
        result = evaluate.metrics([
            {"id": "a", "agent": "RECOMMEND", "latencyMs": 10, "costUsd": .01},
            {"id": "b", "agent": None, "latencyMs": 20, "costUsd": .01},
        ], samples)
        self.assertEqual(result["accuracy"], .5)
        self.assertEqual(result["errorRate"], .5)
        self.assertEqual(result["perAgent"]["BUY"]["recall"], 0.0)
        self.assertEqual(result["slices"]["ambiguous"]["accuracy"], 0.0)
        self.assertEqual(result["latencyMs"]["p95"], 20)

    def test_gated_route_falls_back_and_uses_buy_threshold(self):
        samples = [
            {"id": "a", "question": "购买课程", "expectedAgent": "BUY", "ambiguous": False,
             "multiIntent": False, "tags": []},
            {"id": "b", "question": "推荐课程", "expectedAgent": "RECOMMEND", "ambiguous": False,
             "multiIntent": False, "tags": []},
        ]
        llm = {"a": {"agent": "BUY", "latencyMs": 30, "costUsd": .02},
               "b": {"agent": "CONSULT", "latencyMs": 30, "costUsd": .02}}
        jev = {"a": {"agent": "BUY", "confidence": .90, "latencyMs": 10, "costUsd": .01},
               "b": {"agent": "RECOMMEND", "confidence": .90, "latencyMs": 10, "costUsd": .01}}
        report = evaluate.evaluate(samples, llm, jev, .85, .95)
        self.assertEqual(report["gated"]["fallbackRate"], .5)
        self.assertEqual(report["gated"]["accuracy"], 1.0)
        self.assertEqual(report["jevLlmDisagreementRate"], .5)

    def test_llm_prediction_file_rejects_unknown_id(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "llm.jsonl"
            path.write_text(json.dumps({"id": "missing", "agent": "BUY"}), encoding="utf-8")
            with self.assertRaises(ValueError):
                evaluate.load_llm_predictions(path, {"known"})

    def test_live_mode_without_key_skips_api_and_writes_separate_report(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            with patch.dict(os.environ, {"JEV_API_KEY": ""}), patch.object(sys, "argv", [
                    "route_eval.py", "run", "--jev-live", "--max-requests", "2",
                    "--output", str(report)]):
                self.assertEqual(evaluate.main(), 0)
            data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(data["jevMode"], "skipped")
            self.assertIsNone(data["summary"]["jev"])
            self.assertEqual(data["summary"]["sampleCount"], 2)

    def test_jev_retries_429_without_exposing_key(self):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                requests.append((self.headers.get("Authorization"), body))
                if len(requests) == 1:
                    self.send_response(429)
                    self.end_headers()
                    return
                payload = json.dumps({"model": "jev-test", "answers": {"route_agent": {
                    "type": "choice", "choice": "RECOMMEND", "confidence": .9,
                    "probabilities": {"RECOMMEND": .9, "CONSULT": .05, "BUY": .03, "KNOWLEDGE": .02}}},
                    "usage": {"input_tokens": 10, "output_tokens": 2}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = evaluate.jev_call(
                {"question": "推荐 Java 课", "history": []}, "test-secret",
                f"http://127.0.0.1:{server.server_port}", "jev-test", 2, 1, 1.0, 1.0)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
        self.assertEqual(result["agent"], "RECOMMEND")
        self.assertEqual(result["attempts"], 2)
        self.assertEqual(result["costUsd"], .000012)
        self.assertEqual(requests[0][0], "Bearer test-secret")
        self.assertEqual(requests[0][1]["state"]["latest_user_message"], "推荐 Java 课")
        self.assertNotIn("test-secret", json.dumps(result))

    def test_jev_region_unavailable_does_not_retry_or_expose_key(self):
        error = urllib.error.HTTPError("https://jev.invalid/v1/systemone", 451,
                                       "Unavailable For Legal Reasons", {}, None)
        with patch.object(evaluate.urllib.request, "urlopen", side_effect=error) as urlopen:
            result = evaluate.jev_call(
                {"question": "推荐 Java 课", "history": []}, "test-secret",
                "https://jev.invalid", "jev-test", 2, 2, 1.0, 1.0)

        self.assertEqual(result["error"], "REGION_UNAVAILABLE")
        self.assertEqual(result["attempts"], 1)
        urlopen.assert_called_once()
        self.assertNotIn("test-secret", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
