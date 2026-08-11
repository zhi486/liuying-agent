# AI 跑步伴侣 — 项目全生命周期追踪

> 最后更新：2026-08-11 | 当前阶段：Phase 1 完成 → Phase 2 准备中

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
