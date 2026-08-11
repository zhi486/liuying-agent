"""应用配置，从环境变量加载敏感信息"""

import os
from dataclasses import dataclass, field


@dataclass
class AppConfig:
    """集中管理所有配置项，默认值适合本地开发"""

    # 服务
    host: str = "0.0.0.0"
    port: int = 8000
    debug: bool = False

    # LLM API（支持多种后端）
    llm_provider: str = "anthropic"  # anthropic | openai | ollama
    llm_api_key: str = field(default_factory=lambda: os.getenv("LLM_API_KEY", ""))
    llm_model: str = "claude-sonnet-5"
    llm_base_url: str = field(
        default_factory=lambda: os.getenv("LLM_BASE_URL", "https://api.anthropic.com")
    )

    # TTS — Fish Audio
    fish_audio_api_key: str = field(
        default_factory=lambda: os.getenv("FISH_AUDIO_API_KEY", "")
    )
    fish_audio_reference_id: str = field(
        default_factory=lambda: os.getenv("FISH_AUDIO_REFERENCE_ID", "")
    )

    # 缓存
    cache_ttl_seconds: int = 300

    @classmethod
    def from_env(cls) -> "AppConfig":
        """从环境变量构建配置（Docker 模式下使用）"""
        return cls(
            host=os.getenv("HOST", "0.0.0.0"),
            port=int(os.getenv("PORT", "8000")),
            debug=os.getenv("DEBUG", "false").lower() == "true",
            llm_provider=os.getenv("LLM_PROVIDER", "anthropic"),
            llm_model=os.getenv("LLM_MODEL", "claude-sonnet-5"),
            llm_base_url=os.getenv("LLM_BASE_URL", "https://api.anthropic.com"),
        )
