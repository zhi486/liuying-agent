# 数据质量与手环真实故障通知实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除定位弱和心率停更造成的手环误报，并为真正 BLE 故障提供可撤销的内置通知。

**Architecture:** Supervisor 只增加旁路观察，不改变任何传输决策；纯 Kotlin 门控器区分连续故障与短暂恢复，Coordinator 的独立定时器驱动通知，SpeechBus 按通知身份取消。数据质量通过现有单一采样流水、暂停心率来源更新，原生快照与 Web 严格契约同批接入。

**Tech Stack:** Kotlin、协程、JUnit、Android、TypeScript、React、Zod、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-11-outdoor-alerts-deepseek-design.md`，本计划只执行第 5 节及相关契约、诊断和验收要求。

## Global Constraints

- 不修改 B5 协议报文、START/STOP、同步频率、重连算法、GPS 距离算法、千卡来源或 Health Connect。
- 不更换或重新生成 14 条已验收音频，不改变公里数字和整句合成方式，不恢复系统 TTS。
- 异常必须连续满 10,000 毫秒；正常开跑、用户在非跑步状态主动断开、结束清理均不触发。
- 就绪稳定 10 秒后才解除该故障的「已通知」标记。短暂恢复再失败不会制造一串新故障；如果此前尚未通知，再次异常仍须重新连续满足 10 秒。
- 每个故障代次最多提交一次。总静音、优先级拒绝或播放失败均不进行迟到补播，屏幕仍显示故障。
- 该连接通知直接使用内置流萤音频，不等待 DeepSeek 或 Fish，保持 APP 优先级，不抢占紧急心率提示。
- 保持 contractVersion 1 的同包协同方式，新增明确能力标识，严格校验未知字段；不可用全局宽松解码掩盖两端不一致。
- 不安装、不联网试听、不使用聊天中的密钥；保留四份旧语音／取证文档修改，不夹带提交，不 push。
- 用户已有原地实施许可，继续当前 master；所有手工文件用 apply_patch，删除旧规则须作为已批准的规则替换实施，不删除用户数据。

## 边界与接口

源码路径以下均相对 `app/src/main/java/com/healthyagent/running/`，测试对应 `app/src/test/java/com/healthyagent/running/`。

| 文件／模块 | 职责 |
| --- | --- |
| `data/sensor/WearableLinkObservation.kt` | 只读运动链路状态、观察代次、连续故障身份与时间；不含设备标识 |
| `data/sensor/ble/KeepB5SessionSupervisor.kt` | 在真实状态转换后更新观察，保留瞬时 Ready 的历史，原 action 列表不变 |
| `data/sensor/ble/KeepB5BleDataSource.kt` | reducer 锁内发布观察，避免晚到 transport effects 覆盖新观察 |
| `data/sensor/SensorDataSource.kt`、`CompositeSensorDataSource.kt` | 可空的只读 `wearableLinkObservation`，复合源转发 |
| `coordinator/WearableLinkAlertGate.kt` | 纯 Kotlin 资格／去重状态，返回通知身份和剩余等待时间 |
| `speech/SpeechRequest.kt`、`SpeechBus.kt` | 可选通知令牌与定向取消；普通语音路径保持原语义 |
| `speech/WearableLinkSpeech.kt` | 唯一真实 BLE 触发工厂，复用 `data_stale` 音频 |
| `coordinator/AppCoordinator.kt` | 独立会话代次、观察收集和计时；不使用 pause/resume 也递增的现有 `runGeneration` 代替会话身份 |
| `diagnostics/DiagnosticEvent.kt` 及格式化器 | 明确 `ble_link_lost` 原因和故障身份，不含秘密与路线 |
| `core/engine/RuleCatalog.kt`、旧 `DataStaleRule.kt` | 移除距离陈旧到语音的错误映射及本轮产生的孤立规则 |
| `core/model/MetricsFrame.kt`、`core/pipeline/RunningDataPipeline.kt` | 已取得有效样本的只读标记；不新增 samples 消费者，不改变距离算法 |
| `coordinator/AppSnapshot.kt`、桥接和 RunningPage | 独立的数据质量枚举与提示，不能覆盖安全提示 |

### 观察模型约定

```kotlin
enum class WearableLinkPhase { IDLE, PREPARING, CONNECTING, READY, RECOVERING }

data class WearableLinkFault(
    val id: Long,
    val episodeId: Long,
    val startedAtMillis: Long,
)

data class WearableLinkObservation(
    val generation: Long = 0,
    val phase: WearableLinkPhase = WearableLinkPhase.IDLE,
    val readyRevision: Long = 0,
    val readySinceMillis: Long? = null,
    val fault: WearableLinkFault? = null,
)
```

`generation` 隔离新跑步和主动终止；`readyRevision` 保留本观察代次曾运动 Ready 的证据。每段连续故障有独立 `id`；不足 10 秒的 Ready 后再失败沿用 `episodeId`，稳定至少 10 秒才切换 episode。重试失败不改变连续故障开始时间。门控器不得依赖 collector 曾收到瞬时 Ready：上述信息由真实 reducer 转换写入状态并留到后续观察。暂停仍属同一运动会话。

### 通知令牌约定

```kotlin
class SpeechNoticeToken {
    @Volatile var cancelled: Boolean = false
        private set
    fun cancel() { cancelled = true }
}
```

`SpeechRequest.noticeToken: SpeechNoticeToken? = null`；`SpeechBus.cancelNotice(token: SpeechNoticeToken)`。取消先使令牌失效，再撤销匹配的接纳／播放任务；接纳、actor 开始和实际执行前均验证当前性，取消命令只能 stop 匹配的 actor operation。晚到取消不得停止后来接纳的其他语音，晚到 terminal 不得发布过期字幕或成功历史。

## Task 1: 真故障来源、防抖与定向语音取消

**Files:** 创建上表观察模型、门控器、通知工厂；修改 Supervisor、BLE 源、源接口／复合源、SpeechRequest／SpeechBus、Coordinator、诊断、RuleCatalog 和 BundledVoiceCatalog 真实触发源测试；移除旧 DataStaleRule 及其孤立测试。

**Interfaces:** 产出上述观察流和令牌接口；门控器消费 `WearableLinkObservation` 及显式单调毫秒。Coordinator 使用现有 `realtimeMillis`（Android 注入 elapsedRealtime），不新增墙上时钟计时。

- [ ] **Step 1: 先写来源与门控失败测试。** 在 `KeepB5SessionSupervisorTest.kt` 中复用现有扫描／握手工具：初次失败和 Prepared→START_PENDING 无 fault；正常 Ready 后真实失败才有 fault；过期 GATT 回调不变；重试不重置起点；短暂 Ready 后 fault id 变而 episode 不变；稳定 Ready 满 10 秒后 episode 变；RunStopped 和新 RunStarted 清旧身份。增加 `WearableLinkAlertGateTest.kt`：

```kotlin
@Test fun continuousFaultNeedsTenSeconds() {
    val gate = WearableLinkAlertGate()
    val ready = WearableLinkObservation(1, WearableLinkPhase.READY, 1, 0)
    gate.reset(ready)
    val fault = ready.copy(
        phase = WearableLinkPhase.RECOVERING,
        fault = WearableLinkFault(1, 1, 1_000),
    )
    assertNull(gate.evaluate(fault, 10_999))
    assertEquals(fault.fault, gate.evaluate(fault, 11_000))
    assertNull(gate.evaluate(fault, 60_000))
}
```

门控器签名：`reset(baseline: WearableLinkObservation)` 建立新基线；`evaluate(observation: WearableLinkObservation, nowMillis: Long): WearableLinkFault?` 仅返回首次到期身份。新观察代次允许在来源已证明 Ready 时启用；进程恢复基线现有 fault 不补播，必须先出现更高 readyRevision。追加 9,999/10,000 边界、短暂／稳定恢复、静音不补发、跳过瞬时 Ready、代次换代的字面量期望。

- [ ] **Step 2: 运行定向 RED。**

```powershell
./gradlew.bat --gradle-user-home .gradle-local :app:testDebugUnitTest --tests '*KeepB5SessionSupervisorTest' --tests '*WearableLinkAlertGateTest' -x :app:checkFrontend -x :app:verifyFrontendBundle --console=plain
```

预期新接口未实现或旧错误行为导致失败；确认不是测试拼写／依赖错误。

- [ ] **Step 3: 最小来源与门控实现。** Supervisor 的 `handle` 可包裹原 reducer：只有被接受的 fail 分支增加内部失败标记，结束后生成观察，不用 diagnostic 字符串推断。不把新动作混入旧 transport actions。标准 HR 预连接即使内部 linkState 是 Ready，也须到运动生命周期才成为观察 READY。故障连续起点只在首次合法失败保存；原 Ready 的持续时间决定是否换 episode。

```kotlin
val fault = observation.fault ?: return null
if (observation.phase != WearableLinkPhase.RECOVERING) return null
if (nowMillis - fault.startedAtMillis < 10_000L) return null
if (submittedEpisodeId == fault.episodeId) return null
submittedEpisodeId = fault.episodeId
return fault
```

上段在观察代次／恢复基线资格校验之后运行；不能遗漏基线和曾 Ready 条件。BLE 源在 `synchronized(reducerLock)` 内赋 `_wearableLinkObservation.value = supervisor.linkObservation`，不增加第二个 sample collector。

- [ ] **Step 4: 先写语音／Coordinator RED，再接通知。** `SpeechBusTest.kt` 使用真实总线与可阻塞 TtsEngine，分别断言：取消未运行 Start 后没有 speak；取消准备中／播放中的匹配 notice 只停该条；旧取消不影响随后 URGENT／普通提示；已取消令牌被晚到 enqueue 拒绝；不污染成功历史／字幕；保留 session-status 行为。`AppCoordinatorTest.kt` 使用显式 observation flow 与测试时钟，断言无 GPS 帧及 PAUSED 仍能按时发通知，恢复／停止／新跑／close 取消，初始故障基线不播。

```kotlin
val token = SpeechNoticeToken()
bus.enqueue(SpeechRequest(trigger, noticeToken = token))
bus.cancelNotice(token)
runCurrent()
assertTrue(engine.spoken.isEmpty())
bus.enqueue(otherTrigger)
runCurrent()
assertEquals(listOf(otherTrigger.speakText), engine.spoken)
```

测试中沿用当前总线测试 fixture 的真实字段，不在生产类增加只供测试的接口。先运行 `--tests '*SpeechBusTest' --tests '*AppCoordinatorTest'` 并记录预期失败。

- [ ] **Step 5: 实现集成。** 门控提交时先记录该 episode 已提交，再 enqueue；是否静音／拒绝不重试。Coordinator timer 定时前和回调中均读取观察 `.value`，同时验证独立会话代次，防 collector 延迟导致恢复后开播。READY、IDLE、不同 fault id／观察代次、停止、新跑和 close 立即 cancel timer/token；暂停不重置会话或门控。计时以 `fault.startedAtMillis + 10_000` 为边界，只安排尚未到期的资格计时，不轮询重播。恢复基线只屏幕展示，重新 Ready 后新故障才可播。

```kotlin
RuleTrigger(
    ruleId = "data_stale",
    severity = Severity.NORMAL,
    speakText = "手环数据中断，请检查",
    ttsPriority = SpeechPriority.APP,
    frame = frame,
)
```

上述代码由 `WearableLinkSpeech` 工厂唯一生成；沿用已有当前 frame／session speech 构造方式。`VoicePolicyTtsEngine` 为 `data_stale` 新增明确的本地优先分支（源码核对发现原来仅 URGENT 跳过联网）。删去 RuleCatalog 中 DataStaleRule，覆盖测试改为真实工厂，不能保留旧错误规则应付资产覆盖。新增安全诊断类型与格式化测试，原因 `ble_link_lost` 和代次数字，不存其他数据。

- [ ] **Step 6: GREEN、回归、自审、独立复审及提交。** 定向覆盖 Supervisor、BleDataSource、CompositeSensorDataSource、Gate、SpeechBus、Coordinator、RuleCatalog、BundledVoiceCatalog、VoicePolicy、DiagnosticLogger。新增 GPS stale/HR fresh 和 HR stale/距离 fresh 的集成断言，均无 `data_stale` 请求。运行 JVM 全量（前端未改时可跳两项前端任务），逐文件 stage、`git diff --cached --check`、现有 `check-commit-files.mjs --staged`，提交“按真实手环故障防抖播报并支持定向取消”。

## Task 2: 数据质量快照、严格契约与跑步页提示

**Files:** 修改 `core/model/MetricsFrame.kt`、`core/pipeline/RunningDataPipeline.kt`、`coordinator/AppSnapshot.kt`、`coordinator/AppCoordinator.kt`、`bridge/BridgeCodec.kt`、`bridge/BridgeSession.kt`；修改 `frontend/src/bridge/contract.ts`、`schemas.ts`、模拟源及各完整快照 fixture，`frontend/src/features/running/RunningPage.tsx` 与必要样式；更新 `contracts/v1/README.md`、valid snapshot/ready fixtures；对应 Kotlin、Vitest 测试同批更新。

**Interfaces:** 消费 Task 1 不变的源和独立故障通知。产出根 `DataQualitySnapshot(gps: String = "waiting", heartRate: String = "not_available")`；`MetricsFrame.hasDistanceSample`、`hasHeartRateSample` 为只读布尔；默认与既有有效帧兼容，真实流水依据已接受 receipt 是否存在赋值。桥接新增 `data-quality.v1` capability，保留现有四项能力。

- [ ] **Step 1: 先写流水、Coordinator 和契约 RED。** 先无样本 tick 后应 waiting；取得零米有效距离后 fresh（不能把 0 当未定位）；一条有效样本首次 tick 已过期时也应 stale；新跑重置 waiting，暂停不拿冻结帧让 GPS 变 stale；暂停 HR 失效显示 stale，第一次尚无 HR 显示 waiting；不支持wearable显示 not_available。完整快照经 Kotlin encode→fixture→Zod decode 保留新字段，未知枚举／字段拒绝。

```kotlin
assertEquals("waiting", coordinator.snapshot.value.dataQuality.gps)
assertEquals("waiting", coordinator.snapshot.value.dataQuality.heartRate)
// 现有 fixture 发出有效 0 米距离和有效心率，推进流水 tick。
assertEquals("fresh", coordinator.snapshot.value.dataQuality.gps)
// 保持心率更新，只让距离超过现有 5 秒新鲜度窗口。
assertEquals("stale", coordinator.snapshot.value.dataQuality.gps)
assertEquals("fresh", coordinator.snapshot.value.dataQuality.heartRate)
assertNull(coordinator.snapshot.value.metrics.paceSecPerKm)
```

上述断言分别放在真实 sample/tick 测试夹具内，保持现有距离与心率算法；非法样本不算已取得有效数据。定向运行 `--tests '*RunningDataPipelineTest' --tests '*AppCoordinatorTest' --tests '*BridgeCodecTest'`，记录预期失败。

- [ ] **Step 2: 最小数据实现。** 无新增 samples 订阅。流水在输出 frame 时报告已取得有效样本；Coordinator 在当前会话 OR 累积 seen 标记，不因暂停／恢复重建流水变回初次 waiting。暂停 HR 用现有 `processPausedHeartRate` 的有效值保留 seen 状态，GPS 保留暂停时观察值；新跑和进程恢复清观察，不推测恢复前数据新鲜。只有可用的 HR 才在指标区域显示 bpm。

```kotlin
val gps = when {
    !hasSeenDistance -> "waiting"
    frame.isDataFresh -> "fresh"
    else -> "stale"
}
val heartRate = when {
    !wearableSupported -> "not_available"
    !hasSeenHeartRate -> "waiting"
    frame.isHeartRateFresh && frame.heartRateSmoothed != null -> "fresh"
    else -> "stale"
}
```

- [ ] **Step 3: 先写 Web RED，再同批接线。** GPS waiting 显示“等待定位”，stale 显示“定位暂未更新”；HR waiting 显示“等待心率”，stale 显示“心率暂未更新”；not_available 保留无手环文案。多个状态与已有安全提示并存，连接 badge 仍取 wearable.state，不把 dataQuality 转成蓝牙错误。

```typescript
expect(screen.getByText('定位暂未更新')).toBeInTheDocument();
expect(screen.getByText('心率暂未更新')).toBeInTheDocument();
expect(screen.getByText('Keep B5 已连接')).toBeInTheDocument();
```

在 RunningPage 真实测试fixture中构造双 stale、wearable connected，再执行上述行为断言。契约使用严格结构：

```typescript
dataQuality: z.object({
  gps: z.enum(['waiting', 'fresh', 'stale']),
  heartRate: z.enum(['not_available', 'waiting', 'fresh', 'stale']),
}).strict(),
```

从 frontend 目录运行 `corepack pnpm test:unit src/features/running/RunningPage.test.tsx src/bridge/schemas.test.ts` 观察 RED；同步 Kotlin wire/mapper 与全部严格 fixture 后 GREEN，不能用 optional 新字段或全局宽松解码掩盖缺失。

- [ ] **Step 4: 完整门禁与交付。**

```powershell
$env:JAVA_HOME='C:/Users/fangr/.jdks/ms-17.0.20'
$env:ANDROID_USER_HOME='D:/fangr/PycharmProjects/healthy agent/.android-user'
$env:Path='C:/Program Files/nodejs;' + $env:Path
./gradlew.bat --gradle-user-home .gradle-local :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun --console=plain
```

preBuild 应执行前端全量检查、测试与构建。统计当前 XML 测试总数，核对主／测试 APK 原签名，分别以独立 PowerShell 子进程运行 `build/device-tests/20260910/check-voice-release-apk.ps1` 及 `-TestPackage`。14 音频 hash 不变、凭据扫描无发现。独立最终复审通过后逐文件提交“显示独立定位与心率数据质量状态”。更新本计划实绩与项目记忆，列明完成和仍需真机配合的部分，不宣称室内模拟等于户外验收。

## 计划自检与进度

- [x] 规格覆盖：第 5.2–5.4 与取消／诊断由 Task 1 覆盖；第 5.1 与严格契约由 Task 2 覆盖；第 8 节相关本地门禁在两个任务末尾执行。
- [x] 接口一致：观察 generation 与 pipeline/runGeneration 分离；短暂恢复换连续 fault id 但保留 episode；root dataQuality 与 wearable.state 分开；SpeechNoticeToken 只作用于其请求。
- [x] 占位扫描：所有数值、类型、主要算法、测试断言和命令已给出，无等待用户补全的实现项。
- [x] Task 1 完成。
- [ ] Task 2 完成。

初始基线：`0a63fa8`；心率第一批已通过 658 JVM／102 前端，本计划必须另取新代码验证结果。

### Task 1 实绩（2026-09-11）

- 来源与门控行为 RED：50 项中 12 项失败；SpeechBus／策略 RED：52 项中 4 项失败；Coordinator RED：53 项中 4 项失败；播放器取消 RED：31 项中 2 项失败；迟到完成 RED：31 项中 1 项失败。对应实现后定向全部通过。
- 独立复审发现关闭后旧 collector 等锁返回仍可刷新，补真实多线程交错测试，RED 为快照 revision 期望 7、实际 8。增加锁内关闭标记后修复，定点复审确认原 P2 关闭，无剩余 Important／Critical。
- 最终 JVM 全量重新运行：72 个套件，686 项，失败／错误／跳过均为 0。前端尚未修改，双 APK 门禁在 Task 2 执行。
- 未安装、未试听、未联网调用；14 音频内容未变，旧四份取证／语音文档未纳入提交。
