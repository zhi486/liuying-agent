# CLAUDE.md — AI 跑步伴侣

> 项目级指令，与全局 `~/.claude/CLAUDE.md` 合并生效。

## 项目概述

AI 跑步伴侣：Android 原生应用（Kotlin + Jetpack Compose），在跑步时根据实时数据给出语音调整建议。

- **平台**：Android 原生 Kotlin
- **架构**：MVVM + StateFlow 单向数据流
- **核心约束**：`core/` 包零 Android 依赖，纯 Kotlin 可 JVM 单测
- **AI 引擎**：本地规则引擎（<50ms 实时）+ LLM API（异步深度分析）

## 项目结构

```
app/src/main/java/com/healthyagent/running/
├── core/          # ★ 纯 Kotlin，零 Android 依赖
│   ├── model/     # 数据模型
│   ├── math/      # 纯函数指标计算
│   ├── engine/    # 规则引擎 + 规则实现
│   ├── pipeline/  # 数据处理管道
│   └── session/   # 跑步会话状态机
├── data/          # 数据层（sensor/repository/local）
├── ai/            # LLM 异步分析
├── speech/        # TTS 语音播报
├── ui/            # Compose 界面
└── di/            # Koin 依赖注入
```

## 关键技术选型

- DI: Koin（运行时按名切换 Mock/BLE 数据源）
- 网络: Retrofit + OkHttp + kotlinx-serialization
- 持久化: Room + DataStore Preferences
- TTS: android.speech.tts.TextToSpeech
- 日志: Timber

## 开发约定

- 所有用户可见文字使用中文
- 代码标识符使用英文
- 规则引擎的每条规则必须有单元测试
- LLM API 调用失败不得阻塞实时规则链路

## 会话管理

### 每次会话结束前

在用户表示要结束会话时（如"退出""结束""下次继续"等），必须执行以下操作：

1. **更新全生命周期追踪文档**：读取并更新 `docs/PROJECT_TRACKER.md`，记录本会话的：
   - 完成了什么（具体到文件/功能）
   - 做了哪些关键决策及原因
   - 遇到的问题和解决方案
   - 当前项目状态（各 Phase 进度）
   - 下次会话建议从哪里继续
   
2. **保存记忆**：将重要发现写入 `~/.claude/projects/D--fangr-PycharmProjects-healthy-agent/memory/`

3. **格式要求**：追踪文档按日期倒序追加会话记录，保持简洁但信息完整

### 会话开始前

1. 读取 `docs/PROJECT_TRACKER.md` 了解项目进度
2. 读取 `MEMORY.md` 了解关键上下文
