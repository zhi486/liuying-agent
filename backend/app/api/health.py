"""健康检查接口"""

from fastapi import APIRouter

router = APIRouter()


@router.get("/health")
async def health_check():
    """Kubernetes / Docker Compose 探活"""
    return {"status": "ok", "service": "healthy-agent-backend"}
