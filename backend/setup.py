"""后端包安装配置"""
from setuptools import find_packages, setup

setup(
    name="healthy-agent-backend",
    version="0.1.0",
    description="AI 跑步伴侣 — Python 后端服务",
    packages=find_packages(),
    python_requires=">=3.12",
    install_requires=[
        "fastapi>=0.115",
        "uvicorn[standard]>=0.34",
        "httpx>=0.28",
        "pydantic>=2.10",
        "numpy>=2.2",
        "pandas>=2.2",
    ],
)
