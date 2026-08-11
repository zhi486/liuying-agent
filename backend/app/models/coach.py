"""AI 教练 API 请求/响应数据模型"""

from pydantic import BaseModel, Field


class SplitData(BaseModel):
    """单公里分段数据"""
    split_index: int = Field(..., description="第几公里（1-based）")
    distance_km: float = Field(..., description="累计公里数")
    duration_sec: int = Field(..., description="该公里用时（秒）")
    avg_pace_sec_per_km: float = Field(..., description="平均配速（秒/公里）")
    avg_heart_rate: int | None = Field(None, description="平均心率")
    avg_cadence: int | None = Field(None, description="平均步频")


class HrZoneDistribution(BaseModel):
    """心率区间分布（各区间秒数）"""
    Z1: int = 0
    Z2: int = 0
    Z3: int = 0
    Z4: int = 0
    Z5: int = 0


class SessionSummary(BaseModel):
    """当前跑步会话摘要"""
    total_distance_km: float = Field(..., description="累计距离（公里）")
    total_duration_sec: int = Field(..., description="累计时长（秒）")
    avg_pace_sec_per_km: float = Field(..., description="全程平均配速")
    avg_heart_rate: float | None = Field(None, description="全程平均心率")
    avg_cadence: float | None = Field(None, description="全程平均步频")
    hr_zone_distribution: HrZoneDistribution = Field(
        default_factory=HrZoneDistribution, description="心率区间分布"
    )


class RunConfigRequest(BaseModel):
    """跑步配置（来自客户端）"""
    target_pace_sec_per_km: float | None = Field(None, description="目标配速")
    target_distance_km: float | None = Field(None, description="目标距离")


class AnalyzeRequest(BaseModel):
    """分析请求"""
    session_id: str = Field(..., description="会话 ID")
    split_index: int = Field(..., description="当前分段索引")
    split_data: SplitData = Field(..., description="当前分段数据")
    session_summary: SessionSummary = Field(..., description="累计摘要")
    recent_triggers: list[str] = Field(default_factory=list, description="最近触发的规则描述")
    run_config: RunConfigRequest | None = Field(None, description="跑步配置")


class AnalyzeResponse(BaseModel):
    """分析响应"""
    speak_text: str = Field(..., description="可语音播报的中文建议")
    focus_area: str | None = Field(None, description="关注领域：pace/hr/cadence/efficiency")
