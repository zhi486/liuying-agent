"""一次性差分输入：仅合成数据，由已锁定 Python 参考给出完整 prompt 与 ID 哈希。"""

import copy
import hashlib
import json
import random
import struct
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "token-count"))
import count_tokens as reference

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
golden = json.loads((reference.ROOT / "golden.json").read_text(encoding="utf-8"))
reference.verify_cases(golden)
cases = [{"name": case["name"], "request": case["request"]} for case in golden]
loop_cases = subprocess.run(
    ["node", "--experimental-strip-types", str(Path(__file__).with_name("loop-cases.mjs"))],
    check=True, capture_output=True, encoding="utf-8", timeout=10,
)
cases.extend(json.loads(loop_cases.stdout))
base = golden[0]["request"]


def add(name, text):
    request = copy.deepcopy(base)
    request["messages"][-1]["content"] = text
    cases.append({"name": name, "request": request})


add("unicode-spaces", "a\t\n\r\n\v\f\u0085\u00a0\u1680\u2007\u2028\u2029\u202f\u205f\u3000z")
add("unicode-numbers", "0123456789 ١٢٣٤٥٦ १२३४५ １２３４５ 𝟙𝟚𝟛")
add("unicode-scripts", "𠀀一龥龦かなカナé e\u0301 Ω אבג हिन्दी ไทย 👩🏽‍💻🏃‍♀️")
add("special-markers", "<｜begin▁of▁sentence｜><｜end▁of▁sentence｜><｜User｜><｜Assistant｜></think>")
add("long-ascii", "abc123XYZ_" * 6500)
add("long-cjk", "流萤跑步" * 5300)
add("long-single-piece", "a" * 65000)
add("near-token-limit", "x " * 19500)

rng = random.Random(20260915)
alphabet = list("abcXYZ流萤跑步一龥𠀀012３٤!?,.\\_\"'`\t\r\néΩ🏃") + ["\u0085", "\u00a0", "\u2007", "\u2028", "\u202f", "\u3000", "\u0301"]
for index in range(128):
    add(f"seeded-{index:03}", "".join(rng.choices(alphabet, k=rng.randrange(1, 250))))

results = []
for case in cases:
    request = case["request"]
    payload = json.dumps(request, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    assert len(payload) <= 65536, (case["name"], len(payload))
    prompt = reference.render_request(request)
    ids = reference._tokenizer().encode(prompt, add_special_tokens=False).ids
    results.append({
        "name": case["name"],
        "requestBytes": len(payload),
        "prompt": prompt,
        "inputTokens": len(ids),
        "tokenIdsSha256": hashlib.sha256(struct.pack(f"<{len(ids)}I", *ids)).hexdigest(),
    })
    # 默认探针输出保持冻结；正式模板测试显式要求原始请求。
    if "--include-requests" in sys.argv:
        results[-1]["request"] = request

print(json.dumps(results, ensure_ascii=False))
