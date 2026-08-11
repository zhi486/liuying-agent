# === Python 后端服务 ===
# 使用多阶段构建以减小镜像体积
# docker build -t healthy-agent-backend .

# ===== 构建阶段 =====
FROM python:3.12-slim AS builder

WORKDIR /build
COPY backend/ .

# 安装依赖到虚拟环境
RUN python -m venv /opt/venv && \
    /opt/venv/bin/pip install --no-cache-dir --upgrade pip && \
    /opt/venv/bin/pip install --no-cache-dir .

# ===== 运行阶段 =====
FROM python:3.12-slim AS runtime

# 安全：不以 root 运行
RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app

# 从构建阶段复制虚拟环境
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# 复制应用代码
COPY backend/ .

# 切换到非 root 用户
USER appuser

EXPOSE 8000

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
