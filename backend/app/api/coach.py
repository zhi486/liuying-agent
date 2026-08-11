"""AI 教练接口 — 接收跑步数据，返回语音建议"""

from fastapi import APIRouter

router = APIRouter()


@router.post("/analyze")
async def analyze_run():
    """接收 MetricsFrame，返回 AI 分析结果"""
    return {"message": "AI 教练分析接口 — Phase 4 实现"}
