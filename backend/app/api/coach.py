"""AI 教练接口 — 接收跑步数据，返回 AI 分析结果"""

import logging

from fastapi import APIRouter, Request

from app.models.coach import AnalyzeRequest, AnalyzeResponse
from app.services.llm_service import analyze

logger = logging.getLogger(__name__)
router = APIRouter()


@router.post("/analyze", response_model=AnalyzeResponse)
async def analyze_run(request: Request, body: AnalyzeRequest):
    """接收跑步分段数据，调用 LLM 生成语音教练建议

    每次跨过整公里边界时客户端调用此接口。
    超时 15 秒，LLM 调用失败返回 500。
    """
    config = request.app.state.config
    try:
        result = await analyze(body, config)
        logger.info(
            "LLM analysis: session=%s split=%d focus=%s",
            body.session_id,
            body.split_index,
            result.focus_area,
        )
        return result
    except Exception as e:
        logger.error("LLM analysis failed: %s", e)
        # 降级：返回通用鼓励
        return AnalyzeResponse(
            speak_text=f"第{body.split_index}公里完成，继续保持！",
            focus_area="general",
        )
