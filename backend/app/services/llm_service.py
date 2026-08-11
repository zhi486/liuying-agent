"""LLM 服务 — 调用 AI API 生成跑步教练建议"""

import json
from typing import Any

import httpx

from app.core.config import AppConfig
from app.models.coach import AnalyzeRequest, AnalyzeResponse

SYSTEM_PROMPT = """你是一位专业的跑步教练。你会根据跑者当前的实时数据，给出简洁、可操作的建议。

规则：
1. 每次给出 1-2 句中肯的建议（适合 TTS 语音播报，20-50 字）
2. 重点关注：配速是否偏离目标、心率是否异常、步频是否合理、效率是否下降
3. 语气鼓励性，但不要空洞（"加油"之类的不需要）
4. 结合上下公里数据判断趋势，而不是只看当前一公里
5. 如果数据正常，简单肯定即可（如"配速稳定，心率可控，保持住"）
6. 回复 JSON 格式：{"speak_text": "...", "focus_area": "pace|hr|cadence|efficiency|general"}

不要回复除 JSON 以外的任何内容。"""  # noqa: E501


async def analyze(
    request: AnalyzeRequest,
    config: AppConfig,
) -> AnalyzeResponse:
    """调用 LLM 分析跑步数据，返回语音建议"""

    user_prompt = _build_prompt(request)

    if config.llm_provider == "ollama":
        return await _call_ollama(user_prompt, config)
    elif config.llm_provider == "openai":
        return await _call_openai(user_prompt, config)
    else:
        return await _call_anthropic(user_prompt, config)


def _build_prompt(req: AnalyzeRequest) -> str:
    """构建 LLM prompt"""
    parts = [
        f"第 {req.split_index} 公里数据：",
        f"  - 用时：{_format_duration(req.split_data.duration_sec)}",
        f"  - 配速：{_format_pace(req.split_data.avg_pace_sec_per_km)}",
    ]
    if req.split_data.avg_heart_rate:
        parts.append(f"  - 心率：{req.split_data.avg_heart_rate} bpm")
    if req.split_data.avg_cadence:
        parts.append(f"  - 步频：{req.split_data.avg_cadence} spm")

    parts.append("")
    parts.append("累计数据：")
    parts.append(f"  - 已跑：{req.session_summary.total_distance_km:.1f} 公里")
    parts.append(f"  - 总时长：{_format_duration(req.session_summary.total_duration_sec)}")
    parts.append(f"  - 全程平均配速：{_format_pace(req.session_summary.avg_pace_sec_per_km)}")
    if req.session_summary.avg_heart_rate:
        parts.append(f"  - 平均心率：{req.session_summary.avg_heart_rate:.0f} bpm")
    if req.session_summary.avg_cadence:
        parts.append(f"  - 平均步频：{req.session_summary.avg_cadence:.0f} spm")

    if req.run_config:
        if req.run_config.target_pace_sec_per_km:
            parts.append(f"  - 目标配速：{_format_pace(req.run_config.target_pace_sec_per_km)}")
        if req.run_config.target_distance_km:
            parts.append(f"  - 目标距离：{req.run_config.target_distance_km:.0f} 公里")

    if req.recent_triggers:
        parts.append("")
        parts.append(f"最近规则触发：{', '.join(req.recent_triggers)}")

    return "\n".join(parts)


async def _call_anthropic(user_prompt: str, config: AppConfig) -> AnalyzeResponse:
    """调用 Anthropic Claude API"""
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(
            f"{config.llm_base_url}/v1/messages",
            headers={
                "x-api-key": config.llm_api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json={
                "model": config.llm_model,
                "max_tokens": 200,
                "system": SYSTEM_PROMPT,
                "messages": [{"role": "user", "content": user_prompt}],
            },
        )
        resp.raise_for_status()
        data: dict[str, Any] = resp.json()
        text = data["content"][0]["text"]
        return _parse_llm_response(text)


async def _call_openai(user_prompt: str, config: AppConfig) -> AnalyzeResponse:
    """调用 OpenAI 兼容 API"""
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post(
            f"{config.llm_base_url}/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {config.llm_api_key}",
                "content-type": "application/json",
            },
            json={
                "model": config.llm_model,
                "max_tokens": 200,
                "messages": [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": user_prompt},
                ],
            },
        )
        resp.raise_for_status()
        data: dict[str, Any] = resp.json()
        text = data["choices"][0]["message"]["content"]
        return _parse_llm_response(text)


async def _call_ollama(user_prompt: str, config: AppConfig) -> AnalyzeResponse:
    """调用本地 Ollama"""
    async with httpx.AsyncClient(timeout=30.0) as client:
        resp = await client.post(
            f"{config.llm_base_url}/api/chat",
            json={
                "model": config.llm_model,
                "stream": False,
                "messages": [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": user_prompt},
                ],
            },
        )
        resp.raise_for_status()
        data: dict[str, Any] = resp.json()
        text = data["message"]["content"]
        return _parse_llm_response(text)


def _parse_llm_response(text: str) -> AnalyzeResponse:
    """解析 LLM 返回的 JSON，失败时返回原文作为 speak_text"""
    try:
        # 尝试提取 JSON（LLM 可能在前后加了 markdown 代码块）
        text = text.strip()
        if text.startswith("```"):
            lines = text.split("\n")
            text = "\n".join(lines[1:]) if len(lines) > 1 else text
        if text.endswith("```"):
            text = text[: text.rfind("```")].strip()
        data = json.loads(text)
        return AnalyzeResponse(
            speak_text=data["speak_text"],
            focus_area=data.get("focus_area"),
        )
    except (json.JSONDecodeError, KeyError):
        # 降级：直接使用 LLM 返回的原始文本
        return AnalyzeResponse(speak_text=text.strip()[:100])


def _format_duration(seconds: int) -> str:
    """秒 → 人类可读时长"""
    m = seconds // 60
    s = seconds % 60
    return f"{m}分{s:02d}秒"


def _format_pace(sec_per_km: float) -> str:
    """秒/公里 → 配速格式"""
    m = int(sec_per_km) // 60
    s = int(sec_per_km) % 60
    return f"{m}'{s:02d}\"/km"
