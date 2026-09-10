# 流萤跑步伴侣：新对话交接文档

> 交接时间：2026-09-05（Asia/Singapore）  
> 项目目录：`D:\fangr\PycharmProjects\healthy agent`  
> 当前分支：`master`  
> 当前 HEAD：`0432ea9 docs: 固化 B5 会话与语音可靠性设计`  
> 当前停留点：规格已经确认，实施计划已经封板；里程碑 A 的生产代码尚未开始实施。

## 1. 新对话先读这一段

用户已经确认 2026-09-05 的 B5 会话与语音可靠性修复规格，不需要再次进行需求拷问，也不需要再次请求“规格确认”。下一对话应先核对本文件、已确认规格和实施计划的哈希，然后按计划执行前门禁，从 Task 1 开始实施。

必须同时记住三个彼此独立的问题：

1. **B5 长暂停断连、恢复不重连**：属于里程碑 A，根因明确，尚未修复。
2. **流萤完全没有播报**：属于里程碑 A，根因明确，尚未修复。
3. **B5 屏幕的距离、配速、千卡为空**：属于里程碑 B，协议字段仍未知，明确延期；没有动态抓包证据前禁止猜测编码。

规格文件首部若仍显示“等待书面规格复核”，那是陈旧元数据。原始会话记录显示用户已于 2026-09-05 03:04 回复“规格确认”，随后实施计划已经完成并通过两轮独立审查。以较晚的用户确认为准。

## 2. 用户已经冻结的产品方向

- 产品名与人格：个人专属跑步伴侣“流萤”，称呼用户为“开拓者”。
- 仅做 Android，个人使用、本地优先、无需账号体系。
- 视觉参考 Keep 的清晰运动信息层级，但不复制 Keep；整体是萤火虫感、晨萤浅色主题和夜萤深色主题。
- 动效要求是“炫技但低调”的真正 React/ReactBits 组件效果；数据和操作始终优先于装饰。
- 常规界面由 React 负责，Kotlin 保留 BLE、GPS、TTS、Room、规则引擎、Health Connect、前台服务等原生核心；Compose 只保留安全兜底页。
- React 通过 APK 内离线 WebView 与受限、版本化 Bridge 消费 Kotlin 快照。
- 手机 GPS 是距离、配速和路线主数据源；Keep B5 是心率来源。无手环时也允许开跑，B5 断连不能中断手机跑步记录。
- 跑步状态必须跨锁屏、后台、WebView 重载和进程恢复保持。
- Room 是主数据源；Health Connect 在跑步结束后自动写入。
- 实时安全提醒由本地规则负责；LLM 失败不得阻塞实时链路。
- 个人 AI Key 使用 Android Keystore 保护。

## 3. 已完成的主要阶段

| 日期 | 已完成结果 | 证据或边界 |
| --- | --- | --- |
| 2026-08-26 | 完成 Compose 结构重构和模拟器展示；确定萤火虫视觉、双主题、ReactBits 动效与 React/Kotlin 混合架构 | 架构规格提交 `1832bc3` |
| 2026-08-27 | 形成首轮 22 项混合前端实施计划 | 提交 `f6a7fc3` |
| 2026-08-31 至 09-01 | 建立 React 19/Vite 工具链、离线 bundle、Bridge v1、React 三屏壳、原生设置与可恢复状态 | 提交链 `d7b23ab`、`e864fa2`、`0b4bec4`、`7297074`、`3eb4a35`、`fecf1e7`、`3f4e394` |
| 2026-09-01 | 第一阶段原生运行核心闭环：`AppCoordinator`、前台 `RunningService`、版本化 Bridge、5 秒检查点、暂停冻结、进程恢复 | 当时报告 React 37/37、Android JVM 123/123、模拟器端到端通过 |
| 2026-09-01 | 第二阶段真实数据闭环：GPS/路线、标准 BLE 尝试、真实历史与设置 Bridge、Room v2、Health Connect、双主题/无障碍、WebView 冷启动修复 | 当时报告 React 77/77、Android JVM 201/201、设备测试 3/3、Lint 0 |
| 2026-09-03 至 09-04 | 通过官方 Keep HCI 抓包和 APK 静态分析确认 B5 私有协议；实现 `0x190A`、B3/Protobuf、START、UPDATE_WORKOUT、HEART_BEAT、WORK_PUSH 心率和 CRC16/XMODEM | vivo 真机 + `KeepB5-21` 观察到真实心率 75→80 bpm；边界仅限当前设备/固件组合 |
| 2026-09-04 | 完成一次户外实测，确认手机 GPS、跑步计时、记录落库和 B5 心率主体链路可工作 | 同时暴露长暂停断连、无播报、B5 三项显示为空 |
| 2026-09-05 | 完成并确认 B5 会话与语音可靠性设计；完成 Task 1–12 实施计划 | 规格提交 `0432ea9`；计划仍未跟踪，尚未执行 |

## 4. 2026-09-04 户外实测原始反馈

用户报告：

- 暂停约 30 秒以内恢复，B5 通常不会断开。
- 暂停超过约 30 秒，B5 会断开；恢复跑步后不会自动重连。
- 正常跑步时，App 内心率可以持续显示。
- B5 屏幕只有心率和时间，距离、配速、千卡为空。
- 流萤没有任何播报。

只读诊断得到的辅助证据：

- 手机数据库存在两次户外记录，约为 `1.22 km / 640 秒` 和 `2.06 km / 844 秒`，分别形成 1 个和 2 个公里分段。
- 两段暂停采样间隔约 27 秒和 32 秒，与用户描述吻合。
- TTS 设置实际开启，最小间隔 4 秒；Android TTS 系统服务连接成功，所以“完全无播报”不是用户设置关闭造成的。
- 实测结束后出现过一次 WebView 渲染进程 OOM，应用发生重启并丢失当时的内存日志，但跑步记录正常落库。

## 5. 三类问题的已确认根因

| 现象 | 已确认根因 | 已确认处理方式 |
| --- | --- | --- |
| 长暂停后 B5 断开且恢复不重连 | `KeepB5BleDataSource.onRunPaused()` 停止私有 UPDATE_WORKOUT 和 HEART_BEAT，并取消/禁止暂停态重连；恢复还可能被旧连接状态误判为健康 | 分离 BLE 物理链路与跑步生命周期；暂停继续发送冻结时长状态和心跳，允许暂停态重连；恢复校验私有协议活动，假连接必须重建 |
| 流萤完全无播报 | Koin 中 11 个无 qualifier 的 `single<Rule>` 使用同一索引键，前 10 条被覆盖，运行时只剩 `GoalAchievedRule`；用户又未设置目标距离。开始、暂停、恢复、结束也未进入 SpeechBus | 用 `RuleCatalog.defaultRules()` 显式构造 11 条唯一规则；接入四种会话播报与权威 Split 公里播报；修正 TTS 完成语义、焦点和回退 |
| B5 距离、配速、千卡为空 | 当前 B3 UPDATE_WORKOUT 只可靠编码运动状态与时长；`DisplayData.dataType`、单位、缩放没有可信动态证据；App 也没有可使用的真实体重/千卡模型 | 里程碑 A 不写猜测值；里程碑 B 用官方 Keep 做 2–3 分钟动态 HCI 对照并形成 fixture 后再实现 |

## 6. 2026-09-05 已确认的里程碑 A

用户选择了方案 A：暂停时 B5 保持连接和心率，手机只冻结时间、距离和配速；恢复后立即续跑。实现方向选择“双状态监督器”。

里程碑 A 必须交付：

- 用纯 Kotlin `KeepB5SessionSupervisor` 分开管理物理链路和跑步生命周期。
- 暂停时每秒发送冻结在暂停秒数的 UPDATE_WORKOUT，并继续每 6 秒 HEART_BEAT。
- 暂停期间若断链，继续指数退避重连；重连后仍保持 Paused，不错误推进计时。
- 恢复时检查最近有效私有协议活动；失活超过规定窗口时关闭假连接并重新扫描、握手、恢复会话。
- 暂停期间 App 继续展示新鲜心率；心率断流 5 秒主动清空，但时间、距离、配速、分段和规则保持冻结。
- 显式构造并实际评估全部 11 条规则，规则 ID 唯一。
- 开始、暂停、恢复、结束和每公里事件进入真实 TTS 链路。
- Fish Audio 未配置时零无效网络请求，直接使用 Android 本地 TTS。
- Android/Fish TTS 都以真实完成、失败或取消为结果，带 watchdog；取消不得触发本地迟到回退。
- 播报申请可 duck 的独立音频焦点，结束后归还，沿用当前耳机或扬声器路由。
- BLE、规则和 TTS 写入 typed、有限大小、可轮转、跨重启保留的本地诊断日志；严禁写 API Key、蓝牙地址、原始 HCI、完整响应体或堆栈。

里程碑 A 明确不做：

- 不实现或猜测 B5 距离、配速、千卡 `DisplayData`。
- 不更改 GPS 距离、配速、分段和 Room 算法。
- 不重做 React 页面、主题或 ReactBits 动效。
- 不新增账号、远程日志平台或默认体重。

## 7. 已确认规格与实施计划

### 规格

- 文件：`D:\fangr\PycharmProjects\healthy agent\docs\superpowers\specs\2026-09-05-b5-session-speech-reliability-design.md`
- 行数：406
- Git 状态：已提交
- 提交：`0432ea9`
- SHA-256：`00614D786B64EBF0242CBE7F97D90CB7964BAD82707A2EE77E2B3E506C8D3E27`
- 用户确认时间：2026-09-05 03:04

### 实施计划

- 文件：`D:\fangr\PycharmProjects\healthy agent\docs\superpowers\plans\2026-09-05-b5-session-speech-reliability.md`
- 行数：2870
- Git 状态：未跟踪，未暂存
- SHA-256：`4243E5586CC86310B71A35A5981C98BE0D31FA6F338C61A189E2736A97907F94`
- 计划审查：两轮独立总审通过，记录为 P0=0、P1=0
- 机械检查：38 个 PowerShell 代码块零解析错误；零已知陈旧模式；计划完成时暂存区为空
- 实施状态：86 个复选框未勾选；计划要求的新核心符号尚不存在；执行 state JSON 尚未建立

计划中的任务顺序：

1. 建立 typed 持久诊断日志。
2. 区分合法 B3 活动与可选心率。
3. 实现纯 Kotlin B5 同步调度器。
4. 实现双状态 `KeepB5SessionSupervisor`。
5. 新增暂停态心率观察器。
6. 恢复 11 条规则并让 Split 成为公里唯一事实。
7. 实现会话播报工厂和 SpeechBus 仲裁。
8. 让 Android TTS 等待真实完成并管理独立音频焦点。
9. 收窄 Fish 合成/播放器端口并实现可靠本地回退。
10. 把 Android GATT 收窄为 `BleTransport` 并接入监督器。
11. 统一接线 `AppCoordinator` 与 Koin。
12. 全量回归、APK、安装与真机证据。

## 8. 当前仓库状态与安全边界

交接时的只读检查：

- 分支：`master`
- HEAD：`0432ea9`
- 已修改文件：47 个
- 未跟踪文件：61 个，其中 1 个是本交接文档；创建交接文档前为 60 个
- 暂存路径：0 个
- 当前 tracked diff 约为 5070 行新增、1174 行删除；还不包括未跟踪文件。
- 这些大量改动主要来自 2026-09-01 至 09-04 已完成但尚未整体提交的 React、Bridge、GPS、Room、Health Connect、B5 私有协议和测试工作。
- 当前没有配置 Git remote，因此这些工作树成果没有远端备份。

因此下一对话必须遵守：

- 禁止 `git reset --hard`、`git checkout --`、批量覆盖或清理未跟踪文件。
- 禁止把当前脏工作树误认为本轮可靠性修复已经实施。
- 按计划先建立包含 tracked 与 untracked 的固定基线和 SHA 清单。
- 本批次计划明确禁止自动 `git add` 或 `git commit`；暂存区应持续为空。
- 每个任务只审查自己的增量，不顺手格式化或重构相邻代码。
- `AppCoordinator.kt`、`AppCoordinatorTest.kt` 和 `AppModule.kt` 只在 Task 11 统一接线；此前任务不得提前编辑。
- 严格执行测试先行：先出现预期红灯，再做最小实现，再跑绿灯和任务范围回归。

## 9. 最近可复核的构建与测试证据

以下是交接时从磁盘读取到的最近结果，不代表 2026-09-05 的修复已完成：

- Android JVM 测试结果目录包含 29 个 suite、223 个测试、0 failure、0 error、0 skipped。
- 最近测试结果时间：2026-09-04 22:37。
- 当前 debug APK：`D:\fangr\PycharmProjects\healthy agent\app\build\outputs\apk\debug\app-debug.apk`
- APK 时间：2026-09-04 22:37。
- APK 大小：22,057,330 bytes。
- APK SHA-256：`11C110F2F6A707D366BADEAA25544248C5A60766C841A8CD8D80A2C0149AC132`
- 该 APK 早于 2026-09-05 的修复规格和计划，不包含里程碑 A 修复。
- 当前磁盘上的 Lint 报告是 0 error、37 warning；至少包含 WebView 特性检查和 targetSdk 偏旧等警告，不能写成“Lint 无问题”。
- 当前磁盘上的 instrumentation 报告显示 0 tests，无法独立证明项目跟踪文档曾记录的 3/3。
- 没有持久化的前端测试报告或 coverage；历史第二阶段曾报告 React 77/77、Android JVM 201/201、设备测试 3/3、Lint 0，但重新实施时必须 fresh run，不能只引用旧文档。
- 真机截图可证明当前设备曾持续显示真实 B5 心率和短暂停连接，不能证明暂停 60 秒后仍连接或自动恢复：`build/reports/liuying-b5-first.png`、`liuying-b5-sustained.png`、`liuying-b5-paused-final.png`。

## 10. 里程碑 A 完成定义

只有以下条件全部满足才能称为完成：

1. 新增测试与相关既有 JVM 测试通过，debug APK 可构建并安装。
2. 自动化证明 11 条规则全部存在且 ID 唯一。
3. 自动化证明 Paused 仍产生冻结 UPDATE_WORKOUT、HEART_BEAT 与重连动作；旧 attempt/GATT/lifecycle token 不越界；Stop → Disconnect 只关闭一次。
4. 自动化证明暂停心率持续更新，断流 5 秒置空，其他跑步指标和规则保持冻结。
5. 自动化证明四种会话播报和每公里播报进入 TTS；空 Fish 配置零网络；取消不回退；watchdog 与焦点 lease 在所有终态收口。
6. BLE、规则、TTS 接线测试通过；flush 后重启仍可读取轮转日志。
7. 至少完成一次真机 60 秒暂停/恢复验收。若用户暂时不实测，只能写“自动化完成、真机验收待用户执行”。
8. 代码中没有任何未经动态验证的 B5 距离、配速或千卡值。

真机最终验收设备与场景：

- vivo Android 手机、Keep B5、Beats Studio Buds。
- 开始跑步，确认心率与开始播报。
- 暂停至少 60 秒，确认 B5 与 App 心率持续，时间、距离、配速不增长。
- 暂停时让 B5 短暂离开范围，再回到范围，确认自动重连后仍处于 Paused，随后可恢复。
- QQ 音乐播放时触发暂停、恢复和公里播报，确认音乐 duck、播报走当前耳机、随后音乐恢复。
- 结束后强制停止并重启 App，通过 adb 读取上一进程 BLE、规则与 TTS 诊断记录。

安装前必须重新执行 `adb devices -l` 发现真实 serial；禁止复用历史 serial。若设备不在线，不得宣称真机验收完成。

## 11. 里程碑 B：不要混入当前实施

里程碑 A 稳定后，另开计划完成 B5 屏幕指标：

1. 开启 Android Bluetooth HCI snoop。
2. 用官方 Keep 做一次约 2–3 分钟可控户外运动。
3. 同步记录 B5 屏幕上距离、配速、千卡的变化时点。
4. 解析连续 cmd06 的 repeated `DisplayData`，确定 `dataType` 映射、单位、缩放、符号以及暂停/恢复/跨公里行为。
5. 形成可复现 fixture 后，才扩展 `KeepB5B3Protocol`。
6. 如果千卡需要手机计算，先让用户设置真实体重并确认公式。

手机端 GPS 已经能计算距离和配速，不等于 B5 私有屏幕字段已经破解。

## 12. 对话丢失的恢复证据

Codex 界面/任务读取接口出现历史异常：只返回少量回合，部分回合为空，且早期“规格确认”后的一个回合长期保持 `inProgress`。但本机原始 JSONL 存档仍完整保存关键消息。

- 原始存档：`C:\Users\fangr\.codex\sessions\2026\08\26\rollout-2026-08-26T16-38-20-01a03d38-5a04-7c83-a075-d450546b010c.jsonl`
- 交接检查时大小：122,514,932 bytes
- 记录行数：22,703
- 最近更新时间：2026-09-05 18:41
- 已验证其中存在从 2026-08-26 到 09-05 的关键用户消息、里程碑答复和最终确认。

如需进一步取证，只提取 `response_item` 中 role 为 `user` 或 `assistant` 的可见消息；禁止把系统提示、开发者指令、隐藏 reasoning、工具秘密或整份 122 MB 文件复制到新对话或外部服务。

## 13. 新对话的第一步

1. 读取本交接文档。
2. 读取已确认规格与实施计划全文。
3. 重新计算两个文件的 SHA-256，与本文件记录比对。
4. 读取当前 `git status --porcelain=v2 --untracked-files=all`，确认暂存区仍为空。
5. 按实施计划“执行前门禁”建立固定基线 state；不要凭聊天文字恢复 shell 变量。
6. 运行计划要求的定向基线测试。
7. 使用 `superpowers:subagent-driven-development` 按 Task 1–12 实施，从 Task 1 开始。
8. 每个任务完成后做需求符合性与代码质量复审；不自动暂存或提交。

## 14. 可直接粘贴到新对话的提示词

```text
继续开发我的个人 Android 跑步伴侣“流萤”。项目位于 D:\fangr\PycharmProjects\healthy agent。

先完整读取并以此为交接依据：
1. D:\fangr\PycharmProjects\healthy agent\docs\handoffs\2026-09-05-liuying-context-transfer.md
2. D:\fangr\PycharmProjects\healthy agent\docs\superpowers\specs\2026-09-05-b5-session-speech-reliability-design.md
3. D:\fangr\PycharmProjects\healthy agent\docs\superpowers\plans\2026-09-05-b5-session-speech-reliability.md

规格已经由我确认，不要重新拷问或要求再次确认。请先核对两个文档的 SHA-256、当前 HEAD、dirty/untracked 工作树和空暂存区，然后使用 superpowers:subagent-driven-development，严格按实施计划执行前门禁并从 Task 1 开始实施里程碑 A。

必须保护现有 2026-09-01 至 09-04 的未提交改动：禁止 reset、checkout、覆盖还原、清理未跟踪文件、自动 git add/commit。每个任务测试先行，只改计划允许的职责，并做需求与质量复审。AppCoordinator.kt、AppCoordinatorTest.kt、AppModule.kt 只能在 Task 11 统一接线。

本轮只修复：B5 长暂停保活/断链重连、暂停心率、11 条规则、开始/暂停/恢复/结束/每公里播报、TTS/音频焦点/本地回退和持久诊断。B5 屏幕距离、配速、千卡属于里程碑 B；没有官方 Keep 动态 HCI fixture 前绝不猜测 DisplayData。

先完成自动化、构建和安装准备；需要真机时再告诉我连接 vivo 手机、Keep B5 和 Beats Studio Buds。若设备未在线，把状态写成“自动化完成、真机验收待用户执行”，不要宣称全部完成。
```

## 15. 本次交接动作

- 只新增本交接文档及项目记忆指针。
- 未修改任何生产代码、测试代码、规格或实施计划。
- 未暂存、未提交、未清理现有工作树。
