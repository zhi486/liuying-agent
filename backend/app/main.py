"""FastAPI 应用入口"""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import coach, health
from app.core.config import AppConfig


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：启动时加载配置，关闭时清理资源"""
    app.state.config = AppConfig.from_env()
    yield


def create_app() -> FastAPI:
    app = FastAPI(
        title="AI 跑步伴侣 API",
        description="跑步实时数据分析和 AI 教练建议",
        version="0.1.0",
        lifespan=lifespan,
    )

    # CORS — 允许 Android 客户端访问
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # 注册路由
    app.include_router(health.router, tags=["健康检查"])
    app.include_router(coach.router, prefix="/api/v1", tags=["AI 教练"])

    return app


app = create_app()
