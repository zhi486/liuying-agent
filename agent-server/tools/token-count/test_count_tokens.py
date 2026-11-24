"""独立工具的真实协议及命令行回归，不请求供应商。"""

import copy
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from count_tokens import _asset, count_request, parse_request, render_request, verify_cases


def request():
    return {
        "model": "deepseek-flash",
        "thinking": {"type": "disabled"},
        "stream": False,
        "max_tokens": 600,
        "messages": [
            {"role": "system", "content": "只读助手"},
            {"role": "user", "content": "你好"},
        ],
        "tools": [],
    }


def tool_request():
    value = request()
    value["tools"] = [{"type": "function", "function": {
        "name": "runs_list", "description": "读取摘要", "parameters": {
            "type": "object", "properties": {}, "required": [],
            "additionalProperties": False,
        },
    }}]
    return value


class TokenCountTests(unittest.TestCase):
    def test_same_size_tampered_assets_fail_before_loading(self):
        for name in ("encoding.py", "tokenizer.json"):
            original = _asset(name)
            corrupted = bytes([original[0] ^ 1]) + original[1:]
            with self.subTest(name=name), patch.object(Path, "read_bytes", return_value=corrupted):
                with self.assertRaises(ValueError):
                    _asset(name)

    def test_all_golden_outputs_are_checked(self):
        cases = json.loads(Path(__file__).with_name("golden.json").read_text(encoding="utf-8"))
        for key in ("inputTokens", "promptSha256", "tokenIdsSha256"):
            changed = copy.deepcopy(cases[:1])
            changed[0]["expected"][key] = 999999 if key == "inputTokens" else "0" * 64
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_cases(changed)

    def test_warm_cache_does_not_leak_tools_between_requests(self):
        first = tool_request()
        before = count_request(first)
        second = copy.deepcopy(first)
        second["tools"][0]["function"]["description"] = "不同的虚构说明，不应污染前一次请求"
        self.assertNotEqual(count_request(second)["promptSha256"], before["promptSha256"])
        self.assertNotEqual(count_request(request())["promptSha256"], before["promptSha256"])
        self.assertEqual(count_request(first), before)

    def test_golden_validation_rejects_empty_or_missing_expectations(self):
        for cases in ([], [{"name": "empty", "request": request(), "expected": {}}]):
            with self.subTest(cases=cases), self.assertRaises(ValueError):
                verify_cases(cases)

    def test_request_over_token_limit_is_counted_not_truncated(self):
        value = request()
        value["messages"][1]["content"] = "x " * 20100
        result = count_request(value)
        self.assertGreater(result["inputTokens"], 20000)
        self.assertFalse(result["withinInputLimit"])

    def test_production_protocol_matches_frozen_reference_counts(self):
        result = subprocess.run(
            ["node", "--experimental-strip-types", str(Path(__file__).with_name("production-fixtures.mjs"))],
            capture_output=True, check=True,
        )
        requests = json.loads(result.stdout)
        golden = json.loads(Path(__file__).with_name("golden.json").read_text(encoding="utf-8"))
        self.assertEqual(len(requests), 3)
        for case in requests:
            fixed = next(row for row in golden if row["name"] == case["name"])
            actual = count_request(case["request"])
            for key, value in fixed["expected"].items():
                self.assertEqual(actual[key], value, f'{case["name"]}: {key}')

    def test_plain_prompt_has_role_and_generation_tokens(self):
        self.assertEqual(render_request(request()),
            "<｜begin▁of▁sentence｜><｜System｜>只读助手<｜User｜>你好<｜Assistant｜></think>")

    def test_tools_are_in_prompt_and_input_is_not_mutated(self):
        value = tool_request()
        original = copy.deepcopy(value)
        prompt = render_request(value)
        self.assertIn('### Available Tool Schemas\n\n{"name": "runs_list",', prompt)
        self.assertIn('"additionalProperties": false', prompt)
        self.assertTrue(prompt.endswith("<｜User｜>你好<｜Assistant｜></think>"))
        self.assertEqual(value, original)

    def test_tool_results_follow_call_order(self):
        value = tool_request()
        value["messages"] += [
            {"role": "assistant", "content": None, "tool_calls": [
                {"id": name, "type": "function", "function": {
                    "name": "runs_list", "arguments": "{}",
                }} for name in ("a", "b")
            ]},
            {"role": "tool", "tool_call_id": "b", "content": '{"second":2}'},
            {"role": "tool", "tool_call_id": "a", "content": '{"first":1}'},
        ]
        prompt = render_request(value)
        self.assertIn('<｜DSML｜ invoke name="runs_list">', prompt)
        self.assertIn('<tool_result>{"first":1}</tool_result>\n\n<tool_result>{"second":2}</tool_result>', prompt)

    def test_unicode_escaping_and_no_double_special_tokens(self):
        value = request()
        value["messages"][1]["content"] = '跑步🏃\n"配速"\\中文 <｜User｜>'
        parsed = parse_request(json.dumps(value, ensure_ascii=True).encode())
        self.assertIn(value["messages"][1]["content"], render_request(parsed))
        result = count_request(parsed)
        self.assertGreater(result["inputTokens"], 0)
        self.assertEqual(result["assurance"], "local_reference_only")
        self.assertNotIn("prompt", result)
        self.assertNotIn("tokenIds", result)

    def test_transport_output_limit_does_not_become_prompt(self):
        first = request()
        second = request()
        second["max_tokens"] = 1
        self.assertEqual(count_request(first)["inputTokens"], count_request(second)["inputTokens"])
        second["max_tokens"] = 1600
        self.assertEqual(count_request(first), count_request(second))

    def test_streaming_envelope_preserves_tokens_and_requires_usage(self):
        first = request()
        streaming = {**first, "stream": True, "stream_options": {"include_usage": True}}
        self.assertEqual(count_request(first), count_request(streaming))
        for changes in (
            {"stream": False}, {"stream": 1}, {"stream": "true"},
            {"stream_options": {"include_usage": 1}},
            {"stream_options": {"include_usage": False}},
            {"stream_options": {"include_usage": True, "other": True}},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                render_request({**streaming, **changes})

    def test_rejects_unimplemented_request_features(self):
        for key, value in (
            ("model", "deepseek-v4-pro"), ("thinking", {"type": "enabled"}),
            ("stream", True), ("max_tokens", True), ("max_tokens", 1601),
            ("temperature", 0.5), ("response_format", {"type": "json_object"}),
        ):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                render_request({**request(), key: value})

    def test_rejects_images_special_image_markers_and_unknown_message_fields(self):
        for content in ([{"type": "image_url", "image_url": {"url": "https://example.com"}}],
                        "<｜deepseek_image｜>", "<｜image｜>"):
            value = request()
            value["messages"][1]["content"] = content
            with self.subTest(content=content), self.assertRaises(ValueError):
                render_request(value)
        value = request()
        value["messages"][1]["name"] = "ignored"
        with self.assertRaises(ValueError):
            render_request(value)

    def test_rejects_incomplete_tool_history(self):
        value = tool_request()
        value["messages"].append({"role": "tool", "tool_call_id": "missing", "content": "{}"})
        with self.assertRaises(ValueError):
            render_request(value)

    def test_strict_json_rejects_duplicate_keys_nonfinite_and_oversize(self):
        for payload in (b'{"model":1,"model":2}', b'{"value":NaN}', b" " * 65537,
                        b'{"value":"\\ud800"}'):
            with self.subTest(payload=payload[:30]), self.assertRaises(ValueError):
                parse_request(payload)

    def test_cli_invalid_input_does_not_echo_sensitive_content(self):
        result = subprocess.run(
            [sys.executable, "-B", str(Path(__file__).with_name("count_tokens.py")), "--stdin"],
            input=b'{"SECRET-TEST-MARKER":NaN}', capture_output=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(b"SECRET-TEST-MARKER", result.stdout + result.stderr)
        self.assertNotIn(b"Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
