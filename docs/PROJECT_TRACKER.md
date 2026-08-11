# AI 跑步伴侣 — 项目全生命周期追踪

> 最后更新：2026-08-11 | 当前阶段：Phase 1 完成 + 基础设施就绪

---

## 项目概述

- **目标**：Android 原生 AI 跑步伴侣，实时数据 → 语音指导
- **技术栈**：Kotlin + Jetpack Compose + Koin + Room + TTS
- **AI 架构**：本地规则引擎（<50ms）+ LLM API 异步（3-10s）
- **详细方案**：见 `C:\Users\fangr\.claude\plans\indexed-cuddling-lobster.md`

---

## Phase 进度

| Phase | 内容 | 状态 | 日期 |
|-------|------|------|------|
| Phase 1 | 脚手架 + 模拟数据 + 管道 | ✅ 完成 | 2026-08-11 |
| Phase 2 | 规则引擎 + TTS 语音 | ⏳ 下次继续 | - |
| Phase 3 | 会话 + 持久化 | ⏳ 待开始 | - |
| Phase 4 | LLM 集成 | ⏳ 待开始 | - |
| Phase 5 | Keep B5 真机接入 | ⏳ 待开始 | - |
| Phase 6 | 打磨完善 | ⏳ 待开始 | - |

---

## 会话记录

### 2026-08-11 会话 1（完整）

**完成内容：**

1. **需求调研**
   - 确认 Keep B5 使用 Protobuf 私有 BLE 协议，无公开 SDK/API
   - 建议先用模拟数据原型验证，同时用 nRF Connect 扫描运动模式下是否暴露标准 Heart Rate Service
   
2. **架构设计**（详见 plan 文件）
   - 确定本地规则引擎 + LLM API 异步互补的混合 AI 架构
   - 数据源可插拔设计（`SensorDataSource` 接口）
   - `core/` 包零 Android 依赖，纯 Kotlin JVM 单测
   
3. **Phase 1 完整交付**（31 个文件）
   - Gradle 项目脚手架（AGP 8.5.2 + Kotlin 2.0.20 + Compose BOM 2024.12）
   - 8 个核心数据模型：SensorSample → MetricsFrame → RuleTrigger → RunSession
   - 2 个数学计算器（纯函数）：HrZoneCalculator + MetricsCalculator + 单测覆盖
   - 数据处理管道：RunningDataPipeline（1Hz 帧生成，5s 心率平滑，10s 配速滚动）
   - 4 套模拟场景脚本：轻松跑 / 渐加速 / 心率飙升 / 步频骤降
   - 基础 UI：RunningScreen（深色仪表盘风格，心率大字 + 配速/步频/距离）
   - 前台服务 RunningService、Koin DI、基础 Theme

4. **Chatbot 项目语音经验调研**
   - 用户已有 Fish Audio API 声音克隆配置（`LIUYING_REFERENCE_ID`）
   - TTS 双层架构设计确定：本地 Android TTS（URGENT）+ Fish Audio API（COACH）
   - 可复用模式：文本清洗、音频缓存、中断机制、指数退避重试

**关键决策：**
1. 数据源采用可插拔设计（`SensorDataSource` 接口），先用 Mock 后接 BLE
2. `core/` 包零 Android 依赖，纯 Kotlin JVM 单测
3. LLM 失败绝不阻塞实时规则链路（SpeechBus 仲裁 + URGENT 优先）
4. 跑步姿态用步频-步幅-心率耦合作为代理指标（受限于 Keep B5 协议封闭）
5. TTS 双层架构：LocalTTS（<50ms 离线应急） + FishAudioTts（1-3s 云端自定义音色）
6. 建立 PROJECT_TRACKER.md 全生命周期追踪机制

**下次建议：**
- 启动 Phase 2：RuleEngine + 11 条规则 + SpeechBus + TTS 双层实现
- 创建 `core/engine/rules/` 下每条规则的独立类 + 单元测试
- 实现 AndroidTtsEngine 和 FishAudioTtsEngine（复用用户已有 API Key）
- 如能获取 Keep B5 实机，用 nRF Connect 扫描运动模式下的 BLE 服务

---

### 2026-08-11 会话 2 — 基础设施：Git / Docker / Anaconda

**完成内容：**

1. **Git 初始化**
   - `git init` + 创建 `.gitignore`（Android + Python + Docker + Secrets）
   - 创建 `.gitattributes` 跨平台换行符标准化（LF for Kotlin/Python, CRLF for PS1）
   - 2 个提交：Phase 1 初始提交 → Docker/Conda 配置提交

2. **Docker 化**
   - `Dockerfile`：多阶段构建，Python 3.12-slim，非 root 用户运行，HEALTHCHECK
   - `docker-compose.yml`：backend 服务 + 可选 Redis 缓存（profiles: cache）
   - `.dockerignore` + `.env.example`：安全忽略 .env，提供配置模板
   - 所有 LLM / Fish Audio 密钥通过环境变量注入

3. **Anaconda Python 环境**
   - 创建 `environment.yml`：Python 3.12 + FastAPI + NumPy/Pandas/Scipy + Pytest/Black/Ruff
   - `conda env create` 成功，环境名 `healthy-agent`，位于 `D:\Anaconda_envs\healthy-agent\`
   - 已验证核心包：FastAPI 0.135.1 / NumPy 2.2.6 / Pandas 2.3.3 / Fish Audio SDK 1.3.0

4. **PyCharm 解释器配置修复**
   - 问题：PyCharm 2025.2 缺少 `bin/info`，Conda 自动检测失败
   - 解决：直接编辑 `jdk.table.xml` 注册 `Python 3.12 (healthy-agent)` SDK（CondaSdkFlavor）
   - 项目 `misc.xml` 指向该 SDK
   - 需重启 PyCharm 生效

5. **Python 后端骨架**
   - `backend/app/main.py`：FastAPI 入口 + CORS + lifespan
   - `backend/app/api/health.py`：`/health` 探活接口
   - `backend/app/api/coach.py`：`/api/v1/analyze` 占位（Phase 4 实现）
   - `backend/app/core/config.py`：环境变量驱动配置（LLM Provider / Fish Audio）

**关键决策：**
1. 选择 Anaconda 而非 pip venv 管理 Python 环境（数据科学包依赖 C 扩展，conda 解析更好）
2. Docker 镜像以 Python 后端为主，Android 构建用 Gradle wrapper 在宿主机运行
3. PyCharm 解释器通过直接编辑 `jdk.table.xml` 注册，绕过 UI bug

**下次建议：**
- 重启 PyCharm 验证解释器切换成功
- 可继续 Phase 2（规则引擎）或先完善 Python 后端（Phase 4 的 LLM API 接入）
- 如需本地跑后端：`conda activate healthy-agent && cd backend && uvicorn app.main:app --reload`
