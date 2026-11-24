"""本地官方参考计数，不联网、不替代供应商 usage 或生产计数器。"""

import argparse
import copy
import hashlib
import json
import struct
import sys
import types
from functools import lru_cache
from importlib.metadata import version
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LOCK = json.loads((ROOT / "assets.lock.json").read_text(encoding="utf-8"))


def _require(condition):
    if not condition:
        raise ValueError("不支持的请求或资源")


def _object(value, keys):
    _require(type(value) is dict and set(value) == set(keys))


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result)
        result[key] = value
    return result


def _json(text):
    def invalid_constant(_):
        raise ValueError("非法 JSON 常量")
    value = json.loads(text, object_pairs_hook=_unique_pairs, parse_constant=invalid_constant)
    # 同时拒绝无限数值和无法表示成 UTF-8 的孤立代理字符。
    json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
    return value


def parse_request(payload):
    _require(type(payload) is bytes and len(payload) <= 65536)
    value = _json(payload.decode("utf-8"))
    _require(type(value) is dict)
    return value


def _text(value):
    _require(type(value) is str)
    _require("<｜deepseek_image｜>" not in value and "<｜image｜>" not in value)


def _messages(request):
    streaming = request.get("stream") is True
    _object(request, ("model", "thinking", "stream", "max_tokens", "messages", "tools") +
            (("stream_options",) if streaming else ()))
    _require(request["model"] == LOCK["model"])
    _require(request["thinking"] == {"type": "disabled"} and (streaming or request["stream"] is False))
    if streaming:
        _object(request["stream_options"], ("include_usage",))
        _require(request["stream_options"]["include_usage"] is True)
    _require(type(request["max_tokens"]) is int and 1 <= request["max_tokens"] <= 1600)
    _require(type(request["tools"]) is list and len(request["tools"]) <= 6)
    names = set()
    facts_allowed = False
    for tool in request["tools"]:
        _object(tool, ("type", "function"))
        _require(tool["type"] == "function")
        fn = tool["function"]
        _object(fn, ("name", "description", "parameters"))
        _require(fn["name"] in ("runs_list", "runs_summary", "memory_search", "memory_upsert", "mcp_call", "knowledge_search") and fn["name"] not in names)
        names.add(fn["name"])
        _text(fn["description"])
        _require(type(fn["parameters"]) is dict)
        if fn["name"] == "memory_upsert":
            properties = fn["parameters"].get("properties")
            category = properties.get("category") if type(properties) is dict else None
            facts_allowed = type(category) is dict and category.get("enum") == ["preference", "goal", "fact"]
    messages = copy.deepcopy(request["messages"])
    _require(type(messages) is list and len(messages) >= 2)
    _require(messages[0].get("role") == "system" and messages[1].get("role") == "user")
    pending = set()
    seen = set()
    for index, message in enumerate(messages):
        _require(type(message) is dict)
        role = message.get("role")
        if role in ("system", "user"):
            _object(message, ("role", "content"))
            _require(not pending and (role != "system" or index == 0))
            _text(message["content"])
        elif role == "assistant":
            if type(message.get("content")) is str:
                _object(message, ("role", "content"))
                _require(not pending)
                _text(message["content"])
                continue
            _object(message, ("role", "content", "tool_calls"))
            _require(not pending and message["content"] is None)
            calls = message["tool_calls"]
            _require(type(calls) is list and 1 <= len(calls) <= 6)
            for call in calls:
                _object(call, ("id", "type", "function"))
                _require(type(call["id"]) is str and bool(call["id"]) and call["id"] not in seen)
                _require(call["type"] == "function")
                fn = call["function"]
                _object(fn, ("name", "arguments"))
                _require(fn["name"] in names and type(fn["arguments"]) is str)
                args = _json(fn["arguments"])
                if fn["name"] in ("memory_search", "knowledge_search"):
                    _object(args, ("query",))
                    _text(args["query"])
                elif fn["name"] == "memory_upsert":
                    _object(args, ("category", "evidence", "target"))
                    _require(args["category"] in ("preference", "goal") or (args["category"] == "fact" and facts_allowed))
                    _text(args["evidence"])
                    if args["target"] is not None:
                        _object(args["target"], ("id", "version"))
                        _text(args["target"]["id"])
                        _require(type(args["target"]["version"]) is int and 1 <= args["target"]["version"] < 2147483647)
                elif fn["name"] == "mcp_call":
                    _object(args, ("modelName", "argumentsJson"))
                    _text(args["modelName"])
                    _text(args["argumentsJson"])
                    _require(type(_json(args["argumentsJson"])) is dict)
                elif fn["name"] == "runs_summary":
                    _object(args, ("runId",))
                    _text(args["runId"])
                else:
                    _object(args, ())
                pending.add(call["id"])
                seen.add(call["id"])
        elif role == "tool":
            _object(message, ("role", "tool_call_id", "content"))
            _require(type(message["tool_call_id"]) is str and message["tool_call_id"] in pending)
            pending.remove(message["tool_call_id"])
            _text(message["content"])
        else:
            raise ValueError("不支持的消息角色")
    _require(not pending and messages[-1]["role"] in ("user", "tool"))
    messages[0]["tools"] = copy.deepcopy(request["tools"])
    return messages


def _asset(name):
    info = LOCK["assets"][name]
    payload = (ROOT / ".assets" / name).read_bytes()
    _require(len(payload) == info["bytes"] and hashlib.sha256(payload).hexdigest() == info["sha256"])
    return payload


@lru_cache(maxsize=1)
def _reference():
    # 仅执行已经审阅、固定哈希的官方源码；不信任同名包或任意下载代码。
    source = _asset("encoding.py")
    module = types.ModuleType("deepseek_v41_pinned_reference")
    exec(compile(source, "deepseek_v41_pinned_reference", "exec"), module.__dict__)
    return module


def render_request(request):
    payload = json.dumps(request, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    _require(len(payload) <= 65536)
    return _reference().encode_messages(_messages(request), thinking_mode="chat")


@lru_cache(maxsize=1)
def _tokenizer():
    _require(version("tokenizers") == LOCK["tokenizersVersion"])
    from tokenizers import Tokenizer
    return Tokenizer.from_str(_asset("tokenizer.json").decode("utf-8"))


def count_request(request):
    prompt = render_request(request)
    ids = _tokenizer().encode(prompt, add_special_tokens=False).ids
    return {
        "profile": LOCK["profile"],
        "modelRevision": LOCK["modelRevision"],
        "assurance": "local_reference_only",
        "inputTokens": len(ids),
        "withinInputLimit": len(ids) <= 20000,
        "promptSha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
        "tokenIdsSha256": hashlib.sha256(struct.pack(f"<{len(ids)}I", *ids)).hexdigest(),
    }


def verify_cases(cases):
    _require(type(cases) is list and bool(cases))
    results = []
    for case in cases:
        _object(case, ("name", "request", "expected"))
        _require(type(case["name"]) is str and bool(case["name"]))
        _object(case["expected"], ("inputTokens", "promptSha256", "tokenIdsSha256"))
        result = count_request(case["request"])
        _require(all(result[key] == expected for key, expected in case["expected"].items()))
        results.append({"name": case["name"], "inputTokens": result["inputTokens"]})
    return results


def main():
    parser = argparse.ArgumentParser(description="流萤离线官方参考计数，不调用 API")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--request", type=Path)
    source.add_argument("--stdin", action="store_true")
    source.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        if args.self_test:
            cases = _json((ROOT / "golden.json").read_text(encoding="utf-8"))
            results = verify_cases(cases)
            print(json.dumps({"assurance": "local_reference_only", "passed": len(results), "cases": results}, ensure_ascii=False))
        else:
            if args.stdin:
                payload = sys.stdin.buffer.read(65537)
            else:
                with args.request.open("rb") as handle:
                    payload = handle.read(65537)
            print(json.dumps(count_request(parse_request(payload)), ensure_ascii=False))
        return 0
    except Exception:
        # 错误不回显用户内容、路径或第三方堆栈。
        print("计数失败：请检查请求格式、固定资源、对照样本及隔离依赖。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
