# 流萤 B5 会话与语音可靠性修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** 修复 Keep B5 长暂停后断连且恢复不重连的问题，恢复 11 条本地规则和开始/暂停/恢复/结束/每公里播报，并让 TTS、BLE 与规则仲裁留下可跨重启读取的有限诊断证据。

**Architecture:** 以纯 Kotlin KeepB5SessionSupervisor 分离物理链路和跑步生命周期，以 KeepB5SyncScheduler 产生串行 B3 到期动作，以 BleTransport 隔离 Android GATT；RunningDataPipeline 把最后一条 HR 的原始单调接收锚点交给 PausedHeartRateMonitor，暂停时只更新心率且不重置 freshness。规则由 RuleCatalog 显式构造，公里播报只消费 RunSessionManager 生成的 Split。SpeechBus 同步仲裁、异步播放，Android/Fish TTS 都通过窄平台端口和独立 AudioFocusLease 收口完成、失败、取消与 watchdog。DiagnosticLogger 只接受 typed allowlist 事件并轮转写入私有目录。

**Tech Stack:** Kotlin 2.0.20、JVM 17、Android Gradle Plugin 8.13.2、compileSdk 36、minSdk 26、Coroutines 1.9.0、Koin 4.0.2、OkHttp 4.12.0、kotlinx-serialization、JUnit 4.13.2、coroutines-test 1.9.0、Turbine 1.2.0。

**Spec:** [docs/superpowers/specs/2026-09-05-b5-session-speech-reliability-design.md](../specs/2026-09-05-b5-session-speech-reliability-design.md)

## Global Constraints

- 当前工作树包含大量用户既有改动和未跟踪文件。禁止 git reset、git checkout、覆盖式还原或顺手格式化相邻代码。每个任务开始前记录目标文件 diff，结束时只审查本任务增量。
- AppCoordinator.kt、AppCoordinatorTest.kt 与 AppModule.kt 只允许在 Task 11 统一接线，前面的并行任务不得编辑它们。
- 当前实现图横跨既有 dirty/untracked 文件，任何局部 commit 都可能形成不能从干净 checkout 编译的树。本实施批次一律不自动执行 git add/commit；每个任务结束时把暂存区当作“精确为空”的 allowlist 验证，保留工作树增量和基线审计证据，待用户另行建立干净基线后再决定原子提交。
- 每个生产行为先写失败测试并运行确认红灯，再写最小实现并运行确认绿灯。测试失败若来自既有无关问题，要保留输出并缩小验证范围，不顺手修复。
- Keep B5 的未知 DisplayData 映射不在里程碑 A。不得写入或猜测距离、配速、千卡 dataType、缩放或单位；KeepB5B3Protocol.buildWorkoutStatusRequest 仍只写可信的运动状态与时长。
- 不修改 SensorDataSource 公共合同、CompositeSensorDataSource、AppSnapshot、数据库 schema、前端页面、Bridge 合同或跑步数学模型。RunningDataPipeline 只允许在 Task 5 增加一个默认 no-op 的“已通过现有 source timestamp 顺序检查的 HR + 同次单调接收时刻”回调 seam；不得改变任何帧、窗口、freshness 或配速计算。
- CancellationException 在 SpeechBus、CompositeTtsEngine、AndroidTtsEngine、FishAudioTtsEngine 与测试 fake 中始终重新抛出，不能包装成 Result.failure，也不能触发本地迟到回退。
- 诊断日志禁止蓝牙地址、API key、Authorization、原始 HCI、完整响应体、Throwable.message 和完整堆栈。错误只保存异常 simpleName 与固定中文 SafeDiagnosticSummary。
- 当前 adb devices -l 没有在线设备。安装时必须重新发现真实 serial，禁止复用历史 serial；无设备时交付结论只能是“自动化完成、真机验收待设备重连”。
- 执行批次固定为：Task 1 建立跨层诊断前置；Task 2–6 完成纯 Kotlin 协议、状态、调度、暂停观察与规则；Task 7–9 完成语音仲裁、TTS/焦点与云端回退；Task 10 才接入 Android/B5；Task 11–12 做统一接线、安装与真机证据。除 Task 1 的诊断前置外，不得把 Android 集成提前到纯 Kotlin 与语音批次之前。
- 本文每个 PowerShell fence 都按“可能在全新 shell 中单独执行”编写，不能依赖上一 fence 的变量。调用 `gradlew.bat`、`git` 或 `adb.exe` 等 native executable 后必须立即检查 `$LASTEXITCODE`；非零且未被该步骤明确列为预期审查状态时立刻 throw，禁止在失败测试、失败构建或失败安装后继续提交/验收。

## 执行前门禁

- [ ] 在仓库根目录记录基线，不修改文件：

~~~powershell
$ErrorActionPreference = "Stop"
& git status --short --branch
if ($LASTEXITCODE -ne 0) { throw "无法读取 Git 分支状态" }
& git status --porcelain=v2 --untracked-files=all
if ($LASTEXITCODE -ne 0) { throw "无法读取 Git porcelain" }
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) {
    $stagedPaths | Write-Output
    throw "暂存区非空，停止建立实施基线"
}
& git diff -- app/src/main/java/com/healthyagent/running/data/sensor/ble app/src/main/java/com/healthyagent/running/speech app/src/main/java/com/healthyagent/running/core/engine app/src/main/java/com/healthyagent/running/coordinator app/src/main/java/com/healthyagent/running/di/AppModule.kt
if ($LASTEXITCODE -ne 0) { throw "无法读取目标文件 diff" }
~~~

- [ ] git diff --cached --name-only 必须为空；若已有 staged 内容，停止并请用户先决定其归属，不得擅自 unstage 或夹带提交。
- [ ] 用固定 state JSON 持久保存 IMPLEMENTATION_BASE 与未跟踪文件基线位置；本 fence 自己重新读取 HEAD，不依赖上一 shell：

~~~powershell
$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path ".").Path
$implementationStatePath = Join-Path ([IO.Path]::GetTempPath()) "healthy-agent-b5-reliability-state.json"
if (Test-Path -LiteralPath $implementationStatePath) {
    throw "已存在实施状态 $implementationStatePath；若是同一批次请直接恢复，若要重开批次需先人工核验并移走旧状态"
}
$stagedPaths = @(git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "暂存区非空，停止建立实施基线" }
$implementationBaseOutput = @(& git rev-parse HEAD)
$revParseExit = $LASTEXITCODE
if ($revParseExit -ne 0) { throw "无法解析 IMPLEMENTATION_BASE" }
$implementationBase = ($implementationBaseOutput -join "").Trim()
if ([string]::IsNullOrWhiteSpace($implementationBase)) { throw "IMPLEMENTATION_BASE 为空" }
$baselineRoot = Join-Path ([IO.Path]::GetTempPath()) ("healthy-agent-baseline-" + [Guid]::NewGuid())
$baselineManifestPath = Join-Path ([IO.Path]::GetTempPath()) ("healthy-agent-baseline-" + [Guid]::NewGuid() + ".sha256")
New-Item -ItemType Directory -Path $baselineRoot | Out-Null
Copy-Item -LiteralPath "app/src/main/java/com/healthyagent/running" -Destination (Join-Path $baselineRoot "main-running") -Recurse
Copy-Item -LiteralPath "app/src/test/java/com/healthyagent/running" -Destination (Join-Path $baselineRoot "test-running") -Recurse
Copy-Item -LiteralPath "app/src/androidTest/java/com/healthyagent/running" -Destination (Join-Path $baselineRoot "androidtest-running") -Recurse
Get-ChildItem -LiteralPath $baselineRoot -Recurse -File |
    ForEach-Object {
        $relativePath = $_.FullName.Substring($baselineRoot.Length)
        "{0} {1}" -f $relativePath, (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    } |
    Sort-Object |
    Set-Content -LiteralPath $baselineManifestPath -Encoding UTF8
$implementationState = [ordered]@{
    schemaVersion = 1
    repositoryRoot = $repositoryRoot
    implementationBase = $implementationBase
    baselineRoot = $baselineRoot
    baselineManifestPath = $baselineManifestPath
    createdAtUtc = [DateTime]::UtcNow.ToString("O")
}
$temporaryStatePath = "$implementationStatePath.new"
$implementationState | ConvertTo-Json | Set-Content -LiteralPath $temporaryStatePath -Encoding UTF8
Move-Item -LiteralPath $temporaryStatePath -Destination $implementationStatePath
Get-Content -LiteralPath $baselineManifestPath
Get-Content -LiteralPath $implementationStatePath
Write-Host "实施基线：$implementationBase" -ForegroundColor Cyan
Write-Host "未跟踪文件基线：$baselineRoot" -ForegroundColor Cyan
Write-Host "基线清单：$baselineManifestPath" -ForegroundColor Cyan
Write-Host "实施状态：$implementationStatePath" -ForegroundColor Cyan
~~~

state JSON 是跨 PowerShell fence 的唯一机器可读来源；不得靠聊天文本或 shell 历史恢复变量。每个后续使用基线的 fence 都必须重新加载它，核对 schemaVersion、repositoryRoot、commit 可解析，以及 baselineRoot/manifest 仍存在。main、test、androidTest 三棵 Kotlin 源树都已复制进固定基线；后续对当前为 untracked 的重叠文件使用 git diff --no-index 对比临时基线。Task 12 末尾重新计算并比较 manifest，证明基线副本自身没有漂移。最终不能只依赖 IMPLEMENTATION_BASE，因为 HEAD diff 不包含未跟踪文件。

根据本计划编写时的 porcelain，计划目标中已有脏/未跟踪重叠至少包括 RunningDataPipeline.kt/Test、RuleEngine.kt、SpeechBus.kt、SpeechBusTest.kt、KeepB5BleDataSource.kt、KeepB5GattCallback.kt、KeepB5B3Protocol.kt/Test、KeepB5WorkoutSessionState.kt/Test、AppModule.kt、AppCoordinator.kt/Test。执行前必须按最新 porcelain 重新确认这份交集；每个所属任务末尾用 baselineRoot 做 no-index 审查。禁止用 `git add -p` 猜测 hunk 归属，本批次暂存 allowlist 始终为空。
- [ ] 运行现有定向基线并保存真实输出：

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.KeepB5B3ProtocolTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5WorkoutSessionStateTest" --tests "com.healthyagent.running.core.engine.RuleEngineTest" --tests "com.healthyagent.running.speech.SpeechBusTest" --tests "com.healthyagent.running.coordinator.AppCoordinatorTest"
if ($LASTEXITCODE -ne 0) { throw "执行前定向基线失败" }
~~~

- [ ] 检查设备但不安装：

~~~powershell
$adbPath = "D:/fangr/Android/Sdk/platform-tools/adb.exe"
if (-not (Test-Path -LiteralPath $adbPath)) { throw "adb 不存在：$adbPath" }
$deviceOutput = @(& $adbPath devices -l)
if ($LASTEXITCODE -ne 0) { throw "adb devices 失败" }
$deviceOutput | Write-Output
~~~

## 完成定义

- [ ] 自动化证明 PrivateB3 在 Running、Paused 和跑前显式连接时按规定发送 UPDATE_WORKOUT 与 HEART_BEAT，暂停断开后仍退避重连。
- [ ] 自动化证明旧 epoch、connectionAttemptToken、gattToken、lifecycleRevision 均不能越界；GattCreated 被接受前和 GATT 失效后 callback processor 的 initialize/decision/Android I/O/sink 均为零；Stop 后 Composite 再 disconnect 不会重复关闭 wearable GATT。
- [ ] 暂停态心率与心率区间继续更新；freshness 跨 Running→Paused 沿用最后真实 HR 的单调接收锚点，断流满 5 秒主动清空；时间、距离、配速、分段和规则上下文完全冻结。
- [ ] RuleCatalog 精确构造 11 个唯一 ID；公里播报使用当帧 authoritative Split，不再从 60 帧重算。
- [ ] 四条会话提示、每公里提示、URGENT pending、hard stop、真实完成、失败、取消和 60 秒 watchdog 都有测试。
- [ ] Fish 缺任一配置时零网络调用；普通云端失败回退本地；取消不回退。
- [ ] TTS 和 MediaPlayer 使用同一 speech/assistant profile，每次播放拥有独立、幂等释放的 MAY_DUCK 焦点 lease，不指定输出设备。
- [ ] typed 诊断日志跨实例保留，256 KiB 轮转，最多四个文件；BLE、规则和 TTS 的业务接线均有 fake logger 证明。
- [ ] JVM 全量测试与 debug APK 构建通过；有在线设备时才执行安装和真机门禁。
- [ ] diff 中没有新增 B5 距离、配速或千卡编码。

---

## Task 1：建立 typed 持久诊断日志

**Files:**

- Create: app/src/main/java/com/healthyagent/running/diagnostics/DiagnosticEvent.kt
- Create: app/src/main/java/com/healthyagent/running/diagnostics/DiagnosticLogger.kt
- Create: app/src/main/java/com/healthyagent/running/diagnostics/FileDiagnosticLogger.kt
- Create: app/src/test/java/com/healthyagent/running/diagnostics/FileDiagnosticLoggerTest.kt
- Create: app/src/test/java/com/healthyagent/running/testing/FakeDiagnosticLogger.kt

**Interfaces:**

~~~kotlin
interface DiagnosticLogger {
    fun record(event: DiagnosticEvent)
    suspend fun awaitIdle()
    suspend fun flush()
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun record(event: DiagnosticEvent) = Unit
    override suspend fun awaitIdle() = Unit
    override suspend fun flush() = Unit
}

enum class SpeechOutcomeReason(val wireName: String) {
    POLICY_DISABLED("policy_disabled"),
    DUPLICATE_INTERVAL("duplicate_interval"),
    PRIORITY_BLOCKED("priority_blocked"),
    STALE_SESSION_GENERATION("stale_session_generation"),
    SESSION_STATUS_SCHEDULED("session_status_scheduled"),
    PENDING_SESSION_STATUS_REPLACED("pending_session_status_replaced"),
    TTS_SUCCESS("tts_success"),
    TTS_FAILURE("tts_failure"),
    PLAYBACK_TIMEOUT("playback_timeout"),
    CANCELLED("cancelled"),
}

enum class SafeDiagnosticSummary(val text: String) {
    BLE_OPERATION_FAILED("蓝牙操作失败"),
    TTS_INITIALIZATION_FAILED("本地语音初始化失败"),
    TTS_PLAYBACK_FAILED("语音播放失败"),
    TTS_PLAYBACK_TIMEOUT("语音播放超时"),
    FISH_SYNTHESIS_FAILED("云端语音合成失败"),
    DIAGNOSTIC_IO_FAILED("诊断日志写入失败"),
}

enum class TtsProvider { ANDROID, FISH, COMPOSITE }
enum class TtsEventKind {
    ATTEMPTED,
    SKIPPED_MISSING_CONFIG,
    INITIALIZATION_FAILED,
    COMPLETED,
    FAILED,
    LOCAL_FALLBACK,
    PLAYBACK_TIMEOUT,
    CANCELLED,
}
enum class AudioFocusResult { GRANTED, DENIED, RELEASED }

data class DiagnosticFailure private constructor(
    val type: String,
    val summary: String,
) {
    companion object {
        fun from(error: Throwable, summary: SafeDiagnosticSummary) =
            DiagnosticFailure(
                error::class.java.simpleName.takeCodePoints(80),
                summary.text.takeCodePoints(120),
            )
    }
}

internal fun String.takeCodePoints(maxCount: Int): String {
    val endIndex = offsetByCodePoints(0, codePointCount(0, length).coerceAtMost(maxCount))
    return substring(0, endIndex)
}
~~~

DiagnosticEvent 使用 sealed interface 和明确字段。辅助 enum 固定为：

~~~kotlin
enum class DiagnosticBleLifecycle { STOPPED, RUNNING, PAUSED }
enum class DiagnosticBleLink {
    DISCONNECTED,
    CONNECTING,
    READY_STANDARD_HR,
    READY_PRIVATE_B3,
}
enum class BleReconnectStage { SCHEDULED, STARTED, SUCCEEDED, FAILED }
enum class BleWriteResult { ACCEPTED, REJECTED }
enum class BleResumeDecision { HEALTHY, STALE, STANDARD_HR_SKIPPED }
enum class BleStaleEventKind {
    SCAN_RESULT,
    SCAN_TIMEOUT,
    RECONNECT,
    GATT_CALLBACK,
    CONTROL_ACTION,
    SYNC_WRITE,
}
enum class BleSyncKind { UPDATE_WORKOUT, HEART_BEAT }
enum class BleOperation {
    START_SCAN,
    STOP_SCAN,
    CONNECT_GATT,
    DISCONNECT_GATT,
    CLOSE_GATT,
    DISCOVER_SERVICES,
    ENABLE_NOTIFICATIONS,
    READ_BATTERY,
    READ_FIRMWARE,
    START_WRITE,
    UPDATE_WRITE,
    HEARTBEAT_WRITE,
}
enum class BleFailureReason {
    PERMISSION_MISSING,
    ADAPTER_DISABLED,
    SCAN_FAILED,
    SCAN_TIMEOUT,
    GATT_ERROR,
    REMOTE_DISCONNECTED,
    READY_TIMEOUT,
    START_WRITE_REJECTED,
    SYNC_WRITE_REJECTED,
}
~~~

事件形状固定如下：

~~~kotlin
sealed interface DiagnosticEvent {
    data class BleLifecycleChanged(
        val from: DiagnosticBleLifecycle,
        val to: DiagnosticBleLifecycle,
        val epoch: Long,
        val lifecycleRevision: Long,
    ) : DiagnosticEvent
    data class BleLinkChanged(
        val from: DiagnosticBleLink,
        val to: DiagnosticBleLink,
        val attemptToken: Long,
        val gattToken: Long?,
        val reason: BleFailureReason? = null,
    ) : DiagnosticEvent
    data class BleReconnect(
        val stage: BleReconnectStage,
        val epoch: Long,
        val attemptToken: Long,
        val reason: BleFailureReason? = null,
    ) : DiagnosticEvent
    data class BleSyncWrite(
        val kind: BleSyncKind,
        val result: BleWriteResult,
        val epoch: Long,
        val gattToken: Long,
    ) : DiagnosticEvent
    data class BleResumeHealth(
        val decision: BleResumeDecision,
        val activityAgeMillis: Long?,
    ) : DiagnosticEvent
    data class BleStaleEventIgnored(
        val kind: BleStaleEventKind,
        val epoch: Long,
        val attemptToken: Long,
        val gattToken: Long?,
    ) : DiagnosticEvent
    data class BleStaleWriteIgnored(
        val kind: BleSyncKind,
        val lifecycleRevision: Long,
    ) : DiagnosticEvent
    data class BleOperationFailed(
        val operation: BleOperation,
        val reason: BleFailureReason,
        val failure: DiagnosticFailure,
    ) : DiagnosticEvent
    data class RuleTriggered(val ruleId: String) : DiagnosticEvent
    data class RuleCooldownSkipped(
        val ruleId: String,
        val windowStartedAtMillis: Long,
    ) : DiagnosticEvent
    data class SpeechOutcome(
        val ruleId: String,
        val reason: SpeechOutcomeReason,
    ) : DiagnosticEvent
    data class TtsProviderEvent(
        val provider: TtsProvider,
        val kind: TtsEventKind,
        val failure: DiagnosticFailure? = null,
    ) : DiagnosticEvent {
        companion object {
            fun missingConfiguration() =
                TtsProviderEvent(TtsProvider.COMPOSITE, TtsEventKind.SKIPPED_MISSING_CONFIG)
            fun localFallback() =
                TtsProviderEvent(TtsProvider.COMPOSITE, TtsEventKind.LOCAL_FALLBACK)
        }
    }
    data class AudioFocusEvent(
        val provider: TtsProvider,
        val result: AudioFocusResult,
    ) : DiagnosticEvent
}
~~~

每行 wire contract 固定为顶层 `timestamp`、`processStartId`、`module`、`eventName`、`payload` 五个键；timestamp 是 UTC ISO-8601 字符串，processStartId 是本进程内稳定且跨重建 logger 不变的 UUID 字符串，payload 是仅由对应 typed event 构造的 JSON object（nullable 字段显式写 null），禁止额外键。module/eventName 映射固定为：

| DiagnosticEvent | module | eventName |
| --- | --- | --- |
| BleLifecycleChanged | ble | ble_lifecycle_changed |
| BleLinkChanged | ble | ble_link_changed |
| BleReconnect | ble | ble_reconnect |
| BleSyncWrite | ble | ble_sync_write |
| BleResumeHealth | ble | ble_resume_health |
| BleStaleEventIgnored | ble | ble_stale_event_ignored |
| BleStaleWriteIgnored | ble | ble_stale_write_ignored |
| BleOperationFailed | ble | ble_operation_failed |
| RuleTriggered | rule | rule_triggered |
| RuleCooldownSkipped | rule | rule_cooldown_skipped |
| SpeechOutcome | speech | speech_outcome |
| TtsProviderEvent | tts | tts_provider_event |
| AudioFocusEvent | audio | audio_focus_event |

payload key 精确沿用上面 data class 的字段名；failure 只能是 `{ "type": ..., "summary": ... }`。所有 enum 通过 exhaustive when 显式写 lower_snake_case wire 值，禁止用 enum.name/反射隐式派生；ruleId/type/summary 分别截断到 80/80/120 个 Unicode code point，JSON serializer 负责引号、反斜杠和换行转义。FileDiagnosticLogger 用 exhaustive when 生成不可变 wire DTO 后编码 JSONL，公开 record API 绝不接受 Map、header 或 Throwable。

- [ ] **Step 1: 写失败的持久化、轮转和脱敏测试**

在 FileDiagnosticLoggerTest 中先覆盖以下精确行为：

~~~kotlin
@get:Rule
val temporaryFolder = TemporaryFolder()

@Test
fun appendsAcrossInstancesWithEnvelope() = runTest {
    val directory = temporaryFolder.newFolder("diagnostics")
    val processStartId = "123e4567-e89b-12d3-a456-426614174000"
    val now = Instant.parse("2026-09-05T00:00:00Z")
    FileDiagnosticLogger(
        directory,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        now = { now },
        processStartId = processStartId,
    )
        .also { it.record(DiagnosticEvent.RuleTriggered("km_split")); it.flush() }
    FileDiagnosticLogger(
        directory,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        now = { now },
        processStartId = processStartId,
    )
        .also { it.record(DiagnosticEvent.SpeechOutcome("km_split", SpeechOutcomeReason.TTS_SUCCESS)); it.flush() }

    val lines = File(directory, "diagnostics.jsonl").readLines()
    assertEquals(2, lines.size)
    val decoded = lines.map { Json.parseToJsonElement(it).jsonObject }
    assertEquals(setOf("timestamp", "processStartId", "module", "eventName", "payload"), decoded[0].keys)
    assertEquals("rule", decoded[0].getValue("module").jsonPrimitive.content)
    assertEquals("rule_triggered", decoded[0].getValue("eventName").jsonPrimitive.content)
    assertEquals("km_split", decoded[0].getValue("payload").jsonObject.getValue("ruleId").jsonPrimitive.content)
    assertTrue(decoded.all { it.getValue("timestamp").jsonPrimitive.content == "2026-09-05T00:00:00Z" })
    assertTrue(decoded.all { it.getValue("processStartId").jsonPrimitive.content == processStartId })
}

@Test
fun doesNotPersistThrowableSecrets() = runTest {
    val failure = DiagnosticFailure.from(
        IllegalStateException("Bearer secret-token"),
        SafeDiagnosticSummary.FISH_SYNTHESIS_FAILED,
    )
    val logger = newLogger()
    logger.record(
        DiagnosticEvent.TtsProviderEvent(
            TtsProvider.FISH,
            TtsEventKind.FAILED,
            failure,
        ),
    )
    logger.flush()
    val text = currentLog().readText()
    assertFalse(text.contains("secret-token"))
    assertFalse(text.contains("Bearer"))
}
~~~

再做表驱动 wire 测试：13 个 DiagnosticEvent 各造一个实例，逐一断言上表 module/eventName、payload 精确 key set 和所有 enum 的 lower_snake_case 值；BleOperationFailed 的 failure 只能含 type/summary。ruleId 输入含 `"`、`\`、换行及超过 80 个 code point，解析 JSON 后断言可逆转义、按 code point 截断且每行仍是单个合法 JSON object。timestamp 注入固定 Instant 并断言精确 ISO 值；两个 logger 实例注入同一个 processStartId 后追加两行，证明 envelope 稳定而不是只做 contains 字符串检查。另用两个未注入 processStartId 的 logger 写入不同目录，解析后断言默认 ID 均为合法 UUID 且相同，锁定 `DiagnosticProcessIdentity` 的进程级语义。

再加入：达到 256 KiB 时轮转且只保留 current + .1 + .2 + .3；awaitIdle 与 flush 等待此前入队；目录或轮转失败不向 record 调用者抛异常；BleLinkChanged/BleReconnect 的 allowlisted reason 能序列化但任意异常 message 不能落盘。

- [ ] **Step 2: 运行测试并确认因类型和实现缺失而失败**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.diagnostics.FileDiagnosticLoggerTest"
if ($LASTEXITCODE -eq 0) { throw "新增诊断测试未出现预期红灯" }
Write-Host "已确认诊断层预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 3: 实现 typed 模型、NoOp 与线程安全 fake**

FakeDiagnosticLogger 使用线程安全列表保存事件，暴露 flushCalls、awaitIdleCalls 和按调用顺序保存的 trace，并提供可选 CompletableDeferred<Unit> flushGate，以便 Task 7、Task 10 验证终态记录先于 flush，且 flush 不阻塞业务命令。

- [ ] **Step 4: 实现单 actor JSONL 写入和固定轮转**

~~~kotlin
data class DiagnosticLogPolicy(
    val maxBytes: Long = 256L * 1024L,
    val historyFiles: Int = 3,
)

internal object DiagnosticProcessIdentity {
    val startId: String = UUID.randomUUID().toString()
}

class FileDiagnosticLogger(
    private val directory: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = { Instant.now() },
    private val processStartId: String = DiagnosticProcessIdentity.startId,
    private val policy: DiagnosticLogPolicy = DiagnosticLogPolicy(),
) : DiagnosticLogger
~~~

`DiagnosticProcessIdentity` 是进程级 object，只在进程启动后首次访问时生成一次 UUID；因此 Koin 重建或同进程内重新创建 logger 不会改变默认 processStartId。测试可注入固定 processStartId 与 `now`。record 在调用点通过 `now` 捕获 ISO-8601 UTC 时间，只向 Channel.UNLIMITED 入队且永不抛异常；typed event 转 wire DTO 时，先按 Unicode code point 截断原始字符串，再由 JSON serializer 统一转义，禁止预转义或双重转义。单 actor 在写下一整行前按 UTF-8 字节数轮转。文件名固定为 diagnostics.jsonl、diagnostics.1.jsonl、diagnostics.2.jsonl、diagnostics.3.jsonl。普通记录 flush writer；flush 命令还调用 FileDescriptor.sync。IO 故障只 Timber.w 并继续消费。FileDiagnosticLogger 不得实现 Timber.Tree 或反向收集 Timber，避免把既有地址/十六进制日志写入持久文件。

- [ ] **Step 5: 运行诊断测试并检查范围**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.diagnostics.FileDiagnosticLoggerTest"
if ($LASTEXITCODE -ne 0) { throw "诊断层绿灯失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/diagnostics app/src/test/java/com/healthyagent/running/diagnostics app/src/test/java/com/healthyagent/running/testing/FakeDiagnosticLogger.kt
if ($LASTEXITCODE -ne 0) { throw "诊断层 diff-check 失败" }
~~~

- [ ] **Step 6: 验证范围并保持暂存区为空**

~~~powershell
$ErrorActionPreference = "Stop"
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) {
    $stagedPaths | Write-Output
    throw "本批次暂存 allowlist 为空；发现 staged 路径，停止以避免夹带用户修改"
}
& git status --short -- app/src/main/java/com/healthyagent/running/diagnostics app/src/test/java/com/healthyagent/running/diagnostics app/src/test/java/com/healthyagent/running/testing/FakeDiagnosticLogger.kt
if ($LASTEXITCODE -ne 0) { throw "无法读取诊断层变更范围" }
~~~

预期暂存清单为空；上述五个新文件只保留在工作树，由 Task 12 的固定基线 no-index 审计统一覆盖。

---

## Task 2：区分合法 B3 活动与可选心率

**Files:**

- Modify: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5B3Protocol.kt
- Modify: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5B3ProtocolTest.kt

**Interfaces:**

~~~kotlin
internal data class ParsedWorkoutData(val heartRate: Int?)

internal fun parseWorkoutData(frame: ByteArray): ParsedWorkoutData?
~~~

非空表示 packet type、单分片包络、sid/cid、CRC 与 protobuf wire structure 全部合法；heartRate 允许为空。既有 buildWorkoutStatusRequest 的抓包 fixture 必须继续锁定固定可信状态前缀与动态 elapsed；Running/Paused 只改变 elapsed 是否推进，不发明未知的“暂停状态”protobuf。为保持 Task 2 可独立编译，先保留 parseWorkoutHeartRate 兼容 wrapper，由 Task 10 迁移 KeepB5GattCallback 后再删除。

- [ ] **Step 1: 用现有 fixture 写失败测试**

~~~kotlin
@Test
fun acceptsValidWorkoutDataWithoutHeartRate() {
    val parsed = KeepB5B3Protocol.parseWorkoutData(hex("e7 2a 02 00 00 0e 08 84 e6"))
    assertNotNull(parsed)
    assertNull(parsed?.heartRate)
}

@Test
fun returnsOptionalHeartRateFromValidWorkoutData() {
    val parsed = KeepB5B3Protocol.parseWorkoutData(hex("ea 2a 02 00 00 0e 08 08 4f da d0"))
    assertEquals(79, parsed?.heartRate)
}

@Test
fun keepsStructurallyValidOutOfRangeHeartRateAsActivityWithoutHeartRate() {
    listOf(
        "ea 2a 02 00 00 0e 08 08 00 f1 69",
        "ea 2a 02 00 00 0e 08 08 ad 02 8e ff",
    ).forEach { frame ->
        val parsed = KeepB5B3Protocol.parseWorkoutData(hex(frame))
        assertNotNull(parsed)
        assertNull(parsed?.heartRate)
        assertNull(KeepB5B3Protocol.parseWorkoutHeartRate(hex(frame)))
    }
}
~~~

保留并改写现有错误 CRC、错误 command、错误 protobuf wire type 测试，统一断言 parseWorkoutData 返回 null。心率 varint 为 0 或 301 的两帧 CRC/protobuf 结构合法，所以 parseWorkoutData 必须返回非 null、仅把 heartRate 归一为 null；过渡 wrapper 在“合法但无可用心率”和“坏包”两种情况下都返回 null，测试不能再用 wrapper 的 null 推断活动无效。

- [ ] **Step 2: 运行并确认新 API 尚不存在**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.KeepB5B3ProtocolTest"
if ($LASTEXITCODE -eq 0) { throw "新增 parser 测试未出现预期红灯" }
Write-Host "已确认 KeepB5B3Protocol 预期红灯；继续前人工核对失败仅来自新 API/断言" -ForegroundColor Green
~~~

- [ ] **Step 3: 最小改造解析器**

把 parseHeartRateField 改为 parseWorkoutFields；只有包络、CRC 或 protobuf 结构错误返回 null，完整遍历后返回 ParsedWorkoutData(heartRate)。field 1 的 wire type/varint 必须结构合法，但取值不在 1..300 时只忽略该业务值并返回 ParsedWorkoutData(null)，不能把整帧判坏；合法空 protobuf 同样返回 ParsedWorkoutData(null)。本任务保留以下无重复解析的过渡 wrapper：

~~~kotlin
@Deprecated("Task 10 迁移 GATT 回调后删除")
fun parseWorkoutHeartRate(frame: ByteArray): Int? = parseWorkoutData(frame)?.heartRate
~~~

- [ ] **Step 4: 运行绿灯并审查无 DisplayData 推测**

~~~powershell
$ErrorActionPreference = "Stop"
$implementationStatePath = Join-Path ([IO.Path]::GetTempPath()) "healthy-agent-b5-reliability-state.json"
if (-not (Test-Path -LiteralPath $implementationStatePath -PathType Leaf)) { throw "缺少实施 state JSON" }
$implementationState = Get-Content -LiteralPath $implementationStatePath -Raw | ConvertFrom-Json
$repositoryRoot = (Resolve-Path ".").Path
if ([string]$implementationState.schemaVersion -ne "1" -or [string]$implementationState.repositoryRoot -ne $repositoryRoot) { throw "实施 state 与当前仓库不匹配" }
$implementationBase = [string]$implementationState.implementationBase
$baselineRoot = [string]$implementationState.baselineRoot
$baselineManifestPath = [string]$implementationState.baselineManifestPath
$commitType = git cat-file -t $implementationBase
if ($LASTEXITCODE -ne 0 -or ($commitType -join "").Trim() -ne "commit") { throw "IMPLEMENTATION_BASE 不可解析" }
if (-not (Test-Path -LiteralPath $baselineRoot -PathType Container) -or -not (Test-Path -LiteralPath $baselineManifestPath -PathType Leaf)) { throw "实施基线文件已丢失" }
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.KeepB5B3ProtocolTest"
if ($LASTEXITCODE -ne 0) { throw "KeepB5B3Protocol 绿灯失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5B3Protocol.kt app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5B3ProtocolTest.kt
if ($LASTEXITCODE -ne 0) { throw "协议 diff-check 失败" }
git diff -- app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5B3Protocol.kt
if ($LASTEXITCODE -ne 0) { throw "协议 tracked diff 审查失败" }
git diff --no-index -- "$baselineRoot/main-running/data/sensor/ble/KeepB5B3Protocol.kt" "app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5B3Protocol.kt"
if ($LASTEXITCODE -gt 1) { throw "协议实现 no-index 审查失败" }
git diff --no-index -- "$baselineRoot/test-running/data/sensor/ble/KeepB5B3ProtocolTest.kt" "app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5B3ProtocolTest.kt"
if ($LASTEXITCODE -gt 1) { throw "协议测试 no-index 审查失败" }
~~~

两个 `git diff --no-index` 在协议实现或 fixture 测试有预期差异时返回 exit code 1；必须人工确认只出现本任务的 parser/API 与对应断言，不能用普通 `git diff` 的空输出误判既有 untracked 文件没有变化。

- [ ] **Step 5: 保留工作树增量并保持暂存区为空**

~~~powershell
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
~~~

这两个文件在本计划基线均是既有 untracked 文件，无法安全按 hunk 暂存；暂存区必须保持为空。它们与 Task 10 的 transport 改造共同组成可编译依赖，统一留到批次结束后由用户审阅。

---

## Task 3：实现纯 Kotlin B5 同步调度器

**Files:**

- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5SyncScheduler.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5SyncSchedulerTest.kt

**Interfaces:**

~~~kotlin
internal enum class KeepB5SyncKind { UPDATE_WORKOUT, HEART_BEAT }

internal data class KeepB5SyncDue(
    val kind: KeepB5SyncKind,
    val elapsedIntervals: Long = 0L,
)

internal class KeepB5SyncScheduler(
    private val monotonicMillis: () -> Long,
    private val updateInitialDelayMillis: Long = 4_500L,
    private val updateIntervalMillis: Long = 1_000L,
    private val heartbeatInitialDelayMillis: Long = 6_000L,
    private val heartbeatIntervalMillis: Long = 6_000L,
) {
    fun start()
    fun resetUpdateAccounting()
    fun stop()
    fun nextDelayMillis(): Long?
    fun pollDue(): List<KeepB5SyncDue>
}
~~~

elapsedIntervals 表示自上次已计入时刻到本次唤醒实际经过的完整 1 秒间隔数。首个 UPDATE 虽在 4.5 秒到期，也必须返回 4，而不是 1；之后正常 1 Hz 返回 1，休眠越过 N 个完整间隔时返回 N。每类动作一次唤醒最多返回一个，不补发历史报文。`resetUpdateAccounting()` 只把 UPDATE 的累计锚点和下一次 UPDATE 截止点重置到“当前单调时刻/当前时刻 + 1 秒”，丢弃此前不足一秒及已逾期的 UPDATE 预算；它不改变 HEART_BEAT 截止点，stopped 时为幂等 no-op。

- [ ] **Step 1: 写五个时间表失败测试**

~~~kotlin
@Test
fun emitsFirstUpdateAtFourPointFiveSecondsAndHeartbeatAtSix() {
    var now = 0L
    val scheduler = KeepB5SyncScheduler(monotonicMillis = { now })
    scheduler.start()
    assertEquals(4_500L, scheduler.nextDelayMillis())
    now = 4_500L
    assertEquals(listOf(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 4L)), scheduler.pollDue())
    now = 5_500L
    assertEquals(listOf(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L)), scheduler.pollDue())
    now = 6_000L
    assertEquals(listOf(KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT)), scheduler.pollDue())
}

@Test
fun emitsUpdateBeforeHeartbeatWithoutBacklog() {
    var now = 0L
    val scheduler = KeepB5SyncScheduler(
        monotonicMillis = { now },
        updateInitialDelayMillis = 1_000L,
        heartbeatInitialDelayMillis = 1_000L,
    )
    scheduler.start()
    now = 7_000L
    assertEquals(
        listOf(
            KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 7L),
            KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT),
        ),
        scheduler.pollDue(),
    )
    assertTrue(scheduler.pollDue().isEmpty())
}

@Test
fun keepsHeartbeatCadenceAtSixTwelveAndEighteenSeconds() {
    var now = 0L
    val scheduler = KeepB5SyncScheduler(
        monotonicMillis = { now },
        updateInitialDelayMillis = 60_000L,
    )
    scheduler.start()
    listOf(6_000L, 12_000L, 18_000L).forEach { deadline ->
        now = deadline
        assertEquals(
            listOf(KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT)),
            scheduler.pollDue(),
        )
    }
}

@Test
fun resetDropsOverdueUpdateButKeepsHeartbeatDue() {
    var now = 0L
    val scheduler = KeepB5SyncScheduler(monotonicMillis = { now })
    scheduler.start()

    now = 60_000L
    scheduler.resetUpdateAccounting()
    assertEquals(
        listOf(KeepB5SyncDue(KeepB5SyncKind.HEART_BEAT)),
        scheduler.pollDue(),
    )
    now = 60_999L
    assertTrue(scheduler.pollDue().isEmpty())
    now = 61_000L
    assertEquals(
        listOf(KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, 1L)),
        scheduler.pollDue(),
    )
}
~~~

第五个测试断言 stop 后 nextDelayMillis 为 null 且 pollDue 为空。

- [ ] **Step 2: 运行红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.KeepB5SyncSchedulerTest"
if ($LASTEXITCODE -eq 0) { throw "新增同步调度测试未出现预期红灯" }
Write-Host "已确认同步调度预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 3: 实现单调截止点算法**

start 同时保存 startedAt/lastAccountedUpdateAt 和两个首次截止点。pollDue 先计算 updateDue/heartbeatDue，再固定按 UPDATE_WORKOUT、HEART_BEAT 追加，最后分别从 now 重排下一截止点。UPDATE 的 elapsedIntervals 为 max(1, floor((now - lastAccountedUpdateAt) / updateInterval))，随后只把 lastAccountedUpdateAt 前进 elapsedIntervals * updateInterval，以保留不足一秒的余量；因此 4.5 秒首发返回 4，设备睡眠到 10 秒时累计游标能追到 10 秒，但仍只发一条 UPDATE。`resetUpdateAccounting()` 原子覆盖 lastAccountedUpdateAt/nextUpdateAt，但绝不触碰 nextHeartbeatAt；暂停、恢复或新的跑步开始以它建立 UPDATE 计时线性化点，避免暂停期间积压秒数在恢复后补算。

- [ ] **Step 4: 运行绿灯并保持暂存区为空**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.KeepB5SyncSchedulerTest"
if ($LASTEXITCODE -ne 0) { throw "同步调度绿灯失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5SyncScheduler.kt app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5SyncSchedulerTest.kt
if ($LASTEXITCODE -ne 0) { throw "同步调度 diff-check 失败" }
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
~~~

---

## Task 4：实现双状态 KeepB5SessionSupervisor

**Files:**

- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5SessionSupervisor.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5SessionSupervisorTest.kt

**State and guard types:**

~~~kotlin
internal enum class KeepB5Lifecycle { STOPPED, RUNNING, PAUSED }
internal enum class KeepB5ChannelKind { STANDARD_HR, PRIVATE_B3 }
internal enum class KeepB5ConnectionPhase {
    SCANNING,
    GATT_CONNECTING,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    START_PENDING,
    AWAITING_PRIVATE_ACTIVITY,
}

internal sealed interface KeepB5LinkState {
    data object Disconnected : KeepB5LinkState
    data class Connecting(val phase: KeepB5ConnectionPhase) : KeepB5LinkState
    data class Ready(val channel: KeepB5ChannelKind) : KeepB5LinkState
}

internal data class KeepB5WriteGuard(
    val epoch: Long,
    val connectionAttemptToken: Long,
    val gattToken: Long,
    val lifecycleRevision: Long,
)

internal data class KeepB5SubmittedWrite(
    val operationId: Long,
    val gattGuard: KeepB5GattGuard,
    val lifecycleRevisionAtSubmission: Long,
    val kind: KeepB5SyncKind,
)

internal data class KeepB5AttemptGuard(
    val epoch: Long,
    val connectionAttemptToken: Long,
)

internal data class KeepB5GattGuard(
    val epoch: Long,
    val connectionAttemptToken: Long,
    val gattToken: Long,
)

internal data class PrivateWorkoutActivity(
    val guard: KeepB5GattGuard,
    val receivedAtMillis: Long,
)

internal data class TokenizedSensorSample(
    val guard: KeepB5GattGuard,
    val sample: SensorSample,
    val receivedAtMillis: Long,
)

internal sealed interface KeepB5SessionEvent {
    data object ExplicitConnect : KeepB5SessionEvent
    data object ExplicitDisconnect : KeepB5SessionEvent
    data class RunStarted(val elapsedSeconds: Long) : KeepB5SessionEvent
    data class RunPaused(val elapsedSeconds: Long) : KeepB5SessionEvent
    data class RunResumed(val elapsedSeconds: Long) : KeepB5SessionEvent
    data object RunStopped : KeepB5SessionEvent
    data class ScanMatched(val guard: KeepB5AttemptGuard) : KeepB5SessionEvent
    data class ScanFailed(
        val guard: KeepB5AttemptGuard,
        val reason: BleFailureReason,
    ) : KeepB5SessionEvent
    data class ConnectFailed(
        val guard: KeepB5AttemptGuard,
        val reason: BleFailureReason,
    ) : KeepB5SessionEvent
    data class GattCreated(val guard: KeepB5GattGuard) : KeepB5SessionEvent
    data class GattConnected(val guard: KeepB5GattGuard) : KeepB5SessionEvent
    data class ServicesDiscovered(
        val guard: KeepB5GattGuard,
        val channel: KeepB5ChannelKind,
    ) : KeepB5SessionEvent
    data class NotificationsEnabled(
        val guard: KeepB5GattGuard,
        val channel: KeepB5ChannelKind,
    ) : KeepB5SessionEvent
    data class StartWriteCompleted(
        val guard: KeepB5GattGuard,
        val accepted: Boolean,
    ) : KeepB5SessionEvent
    data class SyncWriteCompleted(
        val submission: KeepB5SubmittedWrite,
        val accepted: Boolean,
    ) : KeepB5SessionEvent
    data class PrivateActivity(
        val activity: PrivateWorkoutActivity,
    ) : KeepB5SessionEvent
    data class SensorSampleReceived(
        val sample: TokenizedSensorSample,
    ) : KeepB5SessionEvent
    data class DeviceInfoReceived(
        val guard: KeepB5GattGuard,
        val deviceInfo: DeviceInfo,
    ) : KeepB5SessionEvent
    data class ReadyTimeout(val guard: KeepB5GattGuard) : KeepB5SessionEvent
    data class LinkFailed(
        val guard: KeepB5GattGuard,
        val reason: BleFailureReason,
    ) : KeepB5SessionEvent
    data class ReconnectDue(val guard: KeepB5AttemptGuard) : KeepB5SessionEvent
    data class SyncDue(
        val pollGuard: KeepB5WriteGuard,
        val due: List<KeepB5SyncDue>,
    ) : KeepB5SessionEvent
}

internal sealed interface KeepB5SessionAction {
    data class BeginScan(val guard: KeepB5AttemptGuard) : KeepB5SessionAction
    data class StopScan(
        val guard: KeepB5AttemptGuard,
        val preserveMatchedTarget: Boolean = false,
    ) : KeepB5SessionAction
    data class ConnectRemembered(val guard: KeepB5AttemptGuard) : KeepB5SessionAction
    data class CancelPendingConnect(val guard: KeepB5AttemptGuard) : KeepB5SessionAction
    data class ActivateGattData(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class SendStart(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class StartSync(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class ResetSyncUpdateAccounting(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class StopSync(val gattToken: Long?) : KeepB5SessionAction
    data class SendWorkoutUpdate(
        val guard: KeepB5WriteGuard,
        val due: KeepB5SyncDue,
    ) : KeepB5SessionAction
    data class SendHeartbeat(val guard: KeepB5WriteGuard) : KeepB5SessionAction
    data class ArmReadyTimeout(
        val guard: KeepB5GattGuard,
        val delayMillis: Long = 20_000L,
    ) : KeepB5SessionAction
    data class CancelReadyTimeout(val guard: KeepB5GattGuard) : KeepB5SessionAction
    data class ScheduleReconnect(
        val guard: KeepB5AttemptGuard,
        val reason: BleFailureReason,
    ) : KeepB5SessionAction
    data class CancelReconnect(
        val epoch: Long,
        val connectionAttemptToken: Long?,
    ) : KeepB5SessionAction
    data class CloseGatt(
        val guard: KeepB5GattGuard,
        val disconnectFirst: Boolean,
    ) : KeepB5SessionAction
    data class PublishConnectionState(
        val state: ConnectionState,
    ) : KeepB5SessionAction
    data class PublishSample(
        val sample: TokenizedSensorSample,
    ) : KeepB5SessionAction
    data class PublishDeviceInfo(
        val guard: KeepB5GattGuard,
        val deviceInfo: DeviceInfo,
    ) : KeepB5SessionAction
    data class RecordDiagnostic(
        val event: DiagnosticEvent,
    ) : KeepB5SessionAction
}

~~~

~~~kotlin
internal class KeepB5SessionSupervisor(
    private val privateActivityStaleAfterMillis: Long = 12_000L,
) {
    fun handle(event: KeepB5SessionEvent, nowMillis: Long): List<KeepB5SessionAction>
    fun captureSyncPollGuard(guard: KeepB5GattGuard): KeepB5WriteGuard?
    fun resolveSyncWrite(
        guard: KeepB5WriteGuard,
        due: KeepB5SyncDue,
    ): KeepB5WriteResolution
    fun resolveGuardedAction(action: KeepB5SessionAction): KeepB5ActionResolution
}

internal sealed interface KeepB5ActionResolution {
    data object Allowed : KeepB5ActionResolution
    data class Rejected(val diagnostic: DiagnosticEvent) : KeepB5ActionResolution
}

internal sealed interface KeepB5WriteResolution {
    data class Allowed(
        val submission: KeepB5SubmittedWrite,
        val elapsedSeconds: Long?,
    ) : KeepB5WriteResolution
    data class Rejected(val diagnostic: DiagnosticEvent) : KeepB5WriteResolution
}
~~~

RecordDiagnostic 是 reducer 输出动作，由 Android/B5 集成任务的串行入口调用 DiagnosticLogger；当前 GattCreated 被接受并写入 supervisor 状态后，必须把 ActivateGattData 作为该 event 的第一个动作，供 data source 在同一 reducerDispatch 临界区非阻塞确认 transport 的完整 callback execution gate。旧/stale GattCreated 不生成该动作，并输出只清理该 exact guard 的 CloseGatt。任何使当前 GATT 失效的转换也必须输出 exact CloseGatt；Task 10 的 reducerDispatch 会把这个动作同时视为“立即 quiesce callback”的同步前置命令，平台 disconnect/close 仍按原动作顺序异步执行。PublishSample 保留原 TokenizedSensorSample，PublishDeviceInfo 保留原 KeepB5GattGuard。两者不只在 reducer 生成时验证 epoch/attempt/gatt，effect 真正写公开流/快照前还要在同一 reducerDispatch 临界区做 final revalidate，避免已排队的旧 GATT 心率、电量或固件污染公开状态；监督器本身不做 IO。
resolveSyncWrite 对 UPDATE 的 Allowed 携带现场生成的 elapsedSeconds，对 HEART_BEAT 携带 null，并为实际提交预留单调 operationId/KeepB5SubmittedWrite。epoch/attempt/gatt 不匹配返回 BleStaleEventIgnored(SYNC_WRITE)，lifecycleRevision 不匹配返回 BleStaleWriteIgnored；调用方必须记录 Rejected.diagnostic，不能只丢弃 bool/null。只有 Allowed 会登记 in-flight operation；调用方必须在同一 reducer 临界区立即调用不挂起的 WRITE_TYPE_NO_RESPONSE transport 方法。
START 与调度器生命周期只绑定 KeepB5GattGuard：开始/暂停/恢复改变 lifecycleRevision 时不能废掉当前握手或持续调度。调度 job 每次 poll 前必须在 `reducerDispatch` 内调用 `captureSyncPollGuard`，得到含 poll 当时 lifecycleRevision 的不可变 `KeepB5WriteGuard`；`SyncDue.pollGuard` 以及由它生成的 SendWorkoutUpdate/SendHeartbeat 全程沿用该版本，禁止在 reducer 消费时改取“当前版本”。若事件到达时 revision 已变化，整个 due batch 只输出 `BleStaleWriteIgnored` 且不推进 elapsed、不生成写动作；若事件已转换为 action 后才变化，最终 resolveSyncWrite 仍会拒绝旧 action。RunStarted、RunPaused、RunResumed 每次被接受并递增 revision 时，还为当前 PrivateB3 同步资源输出 `ResetSyncUpdateAccounting`；该动作由 data source 在同一 `reducerDispatch` 临界区同步执行，形成与 poll 互斥的线性化点，只重置 UPDATE 累计而保留 6 秒 HEART_BEAT cadence。若同步资源尚未建立则按精确 GATT guard 幂等 no-op，随后 StartSync 会从当时重新起算。
resolveGuardedAction 为 BeginScan、ConnectRemembered、SendStart、StartSync、ArmReadyTimeout、ScheduleReconnect、PublishSample 与 PublishDeviceInfo 做 effect-time final gate；控制动作核对 guard、phase、maintainConnection 与所需握手状态。publication 的允许条件精确为“完整 GATT guard 仍是 current 且 maintainConnection=true”，生命周期可以是 STOPPED、RUNNING 或 PAUSED；因此跑前 ExplicitConnect 的当前心率与 DeviceInfo 仍能公开，RunStopped/ExplicitDisconnect 清除 maintainConnection 后才拒绝。控制动作被拒记录 BleStaleEventIgnored(CONTROL_ACTION)，publication 被拒记录 BleStaleEventIgnored(GATT_CALLBACK)。StopScan、CancelPendingConnect、StopSync、CancelReadyTimeout、CancelReconnect 与 CloseGatt 不用“当前 supervisor 状态”门禁，而是按动作自带 guard/token 在资源注册表中精确匹配：找到自己的旧资源就清理，资源已消失便幂等 no-op，绝不能退化成“停止当前资源”。CancelPendingConnect 是 reducer-local immediate cleanup，RunStopped/ExplicitDisconnect 或生成新 attempt 前必须针对旧 attempt 输出；数据源在 reducerDispatch 解锁前调用 transport 的同 guard permit.cancel。尤其 StopScan(A) 只有在 transport 的 activeScan.guard 仍为 A 时才调用 scanPort.stop 并清 A 的 terminal gate；B 已成为 activeScan 后迟到的 StopScan(A) 必须 no-op。ScanMatched 产生 `StopScan(guard, preserveMatchedTarget=true)` 后紧接 ConnectRemembered，让 target 留到后者原子 take；ScanFailed、RunStopped/ExplicitDisconnect 等清理使用 false。StopScan 还必须只取消同 guard 的 scan-timeout job，CancelReconnect 只取消匹配 epoch/attempt 的 backoff job，绝不能误取消新代 job。RecordDiagnostic 不进入 effect actor，而是按 reducer 返回顺序在 reducerDispatch 内同步提交给 DiagnosticLogger 自己的有序 actor。

- [ ] **Step 1: 先写生命周期矩阵失败测试**

测试名与断言固定为：

- 跑前 PrivateB3 完成 START 后以 0 秒冻结更新且 6 秒心跳。
- STOPPED + maintainConnection=true 的当前 StandardHr/PrivateB3 心率 PublishSample 和当前 DeviceInfo PublishDeviceInfo 在生成时与 effect-time resolve 都为 Allowed；ExplicitDisconnect 或 RunStopped 后同一排队 publication 变为 GATT_CALLBACK stale。跑前 PrivateB3 合法 activity 仍可进入 Ready，不能因尚未 Running 被吞掉。
- 当前 ScanMatched 必须输出 `StopScan(同 guard, preserveMatchedTarget=true)` 后 ConnectRemembered；当前 ScanFailed（含 SCAN_TIMEOUT）输出 `StopScan(..., false)` 后统一 ScheduleReconnect。旧 attempt 的 match/failure/timeout 只记 stale，不停止新扫描或重复退避。
- 当前 GattCreated 写入新 GATT 身份后，第一个动作恰好是同 guard 的 ActivateGattData；旧 attempt/stale GattCreated 不输出 activation，而是输出自身 exact CloseGatt，只清理自己的 binding。activation 前到达的任何 callback 都不能执行 mapper、GATT I/O 或进入 supervisor。
- Running UPDATE 按 elapsedIntervals 推进，Paused UPDATE 始终返回 frozenElapsedSeconds。
- Running/Paused/Resumed 的 SyncDue 必须携带 poll 时 lifecycleRevision；pause/resume 后才送达的旧 SyncDue 整批拒绝，不能推进 frozenElapsedSeconds 或生成 transport action。每次已接受的 RunStarted/RunPaused/RunResumed 对当前 private sync 输出同 guard 的 ResetSyncUpdateAccounting，StandardHr 或无 sync 时不输出。
- 暂停 60 秒期间 maintainConnection 为 true，断开会 ScheduleReconnect。
- RunStopped/ExplicitDisconnect 必须输出 CancelReconnect、StopScan（连同 scan timeout）、CancelPendingConnect、StopSync、CancelReadyTimeout 与 CloseGatt；任何递增 connectionAttemptToken 的转换也先取消旧 attempt permit。gate 住长 backoff 或尚未执行的 connect dispatcher task 后 stop，job/permit 立即终结，推进时钟或放行 dispatcher 都不再回送 ReconnectDue、调用 connector 或启动扫描。
- 暂停断开重连后以冻结时长恢复同步。
- PrivateB3 恢复时活动年龄 12_000 毫秒以内复用，超过 12_000 毫秒 CloseGatt 并重连。
- PrivateWorkoutActivity 的 receivedAtMillis 是 callback 到达时刻而非 reducer 消费时刻；通知先到但队列延迟消费超过 12 秒时，resume 仍判 stale 并重连。
- StandardHr 恢复不做私有活动年龄判断。
- StandardHr 从 NotificationsEnabled 到 Running/Paused/Resume 全程均不产生 SendStart、StartSync、SendWorkoutUpdate 或 SendHeartbeat。
- 重复 NotificationsEnabled 和 StartWriteCompleted 幂等，不重复 START 或 StartSync。
- 同一 guard 在 ENABLING_NOTIFICATIONS 可发生至多一次 pending channel `STANDARD_HR→PRIVATE_B3` 横切而不回退 phase；这是 standard-first 失败的唯一合法 fallback。切换后 private CCCD success 产生唯一 SendStart；生产 executor 已抑制迟到 standard descriptor result，若 fake 直接注入迟到 NotificationsEnabled(STANDARD_HR)，supervisor 也只记 stale，绝不能把 channel 切回。metadata 启动权只在 executor 的 STANDARD_PENDING→STANDARD_READY 原子转换中，supervisor 不触发 metadata。
- START_PENDING 期间发生 pause/resume 后，当前 GATT 的 START 回调仍有效；下一次 SyncDue 必须在 poll 时捕获新 lifecycleRevision，而转换前已 poll 的 SyncDue 和已排队 write 都被拒绝。
- 状态变化前上述 guarded action 的 resolveGuardedAction 为 Allowed；接受 RunStopped 或更新 attempt/gatt 后，同一 action 必须返回带 CONTROL_ACTION diagnostic 的 Rejected。CloseGatt/StopScan 等 cleanup action 不走该拒绝门禁，但必须在各自资源注册表按动作 guard/token 精确匹配，不能作用到更新一代资源；每个 CloseGatt 还必须在 reducerDispatch 解锁前同步触发 exact callback gate quiesce，不能等 effect actor 才禁止旧 I/O。
- GATT 创建后发 20 秒 ready timeout；StandardHr 就绪或 PrivateB3 首个合法活动会 CancelReadyTimeout。当前 START reject、ReadyTimeout、SyncWriteCompleted(false) 与其他 current LinkFailed 必须走同一 `failCurrentGatt` 归约：reducer 先让旧 GATT/sync 资格失效并清 pendingPrivateActivity 与该 GATT 全部 in-flight operation，再严格输出 `StopSync(oldGattToken) → CancelReadyTimeout(oldGuard) → CloseGatt(oldGuard, disconnectFirst=true) → PublishConnectionState(ERROR) → ScheduleReconnect(newAttemptGuard, typedReason)`；分别使用 START_WRITE_REJECTED、READY_TIMEOUT、SYNC_WRITE_REJECTED。旧/重复结果只记 stale，不能重复 cleanup/backoff。
- 当前 attempt 的 ConnectFailed 直接进入 ERROR/退避且不创建 gattToken；旧 attempt 的失败只记 stale。
- 表驱动覆盖 SCANNING→GATT_CONNECTING→DISCOVERING_SERVICES→ENABLING_NOTIFICATIONS→START_PENDING→AWAITING_PRIVATE_ACTIVITY；任一后期状态收到较早或重复阶段事件都不回退且不重复动作。
- 旧 epoch、attempt、gatt 让 resolveSyncWrite 返回带 BleStaleEventIgnored(SYNC_WRITE) 的 Rejected；旧 lifecycleRevision 返回带 BleStaleWriteIgnored 的 Rejected。
- 当前 gatt 的 DeviceInfoReceived 才产生带同一 guard 的 PublishDeviceInfo；SensorSampleReceived 也产生保留原 TokenizedSensorSample 的 PublishSample。两种 publication 在生成时 resolve 为 Allowed；B GattCreated 或 RunStopped 后，同一已排队 action 的 effect-time resolve 必须变成带 GATT_CALLBACK diagnostic 的 Rejected。旧 A 在 B 就绪后的迟到电量/设备信息只记 stale，不能覆盖 B。
- 当前 gatt 且 operationId 仍在 in-flight 的 SyncWriteCompleted(accepted=false) 进入 LinkFailed/退避且只记录一次 REJECTED；accepted=true 记录对应 UPDATE/HEART_BEAT 成功。提交后发生 pause/resume 时，completion 仍按 submission.gattGuard + operationId 认领，不能因为 lifecycleRevisionAtSubmission 已旧而吞掉真实失败；revision 仅用于诊断。旧 epoch/attempt/gatt、未知或重复 operationId 的结果只记录 stale，不改变当前链路或重复重连。
- 对 START reject、ready timeout 和 sync failure 分别断言上述五个动作的完整顺序；用 CloseGatt gate 卡住前三个 cleanup 时，ScheduleReconnect 尚未注册/触发，释放后才允许 backoff，因而坏 GATT 与新 scan 永不并存。CloseGatt 抛普通异常也必须继续 PublishConnectionState/ScheduleReconnect。
- RunStopped 后 Composite 的 ExplicitDisconnect 不再递增 epoch，同一 gattToken 只 CloseGatt 一次。

核心断言示例：

~~~kotlin
val pausedGuard = readyPrivateSupervisor(
    lifecycle = KeepB5Lifecycle.PAUSED,
    elapsedSeconds = 42L,
)
assertEquals(
    42L,
    (supervisor.resolveSyncWrite(
        pausedGuard,
        KeepB5SyncDue(KeepB5SyncKind.UPDATE_WORKOUT, elapsedIntervals = 18L),
    ) as KeepB5WriteResolution.Allowed).elapsedSeconds,
)
assertTrue(
    supervisor.handle(
        KeepB5SessionEvent.LinkFailed(
            KeepB5GattGuard(
                pausedGuard.epoch,
                pausedGuard.connectionAttemptToken,
                pausedGuard.gattToken,
            ),
            BleFailureReason.GATT_ERROR,
        ),
        60_000L,
    ).any { it is KeepB5SessionAction.ScheduleReconnect },
)
~~~

- [ ] **Step 2: 运行红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.KeepB5SessionSupervisorTest"
if ($LASTEXITCODE -eq 0) { throw "新增会话监督器测试未出现预期红灯" }
Write-Host "已确认会话监督器预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 3: 实现最小 reducer 与不变量**

实现时只维护一个当前 attempt、一个当前 GATT 和一个同步资格，以及 pendingChannel、privateFallbackUsed、frozenElapsedSeconds、lastPrivateActivityAtMillis、pendingPrivateActivity、inFlightWriteOperationIds、nextWriteOperationId、epoch、connectionAttemptToken、gattToken、lifecycleRevision。ExplicitConnect 或 Running/Paused 建立 maintainConnection；只有 ExplicitDisconnect/RunStopped 清除。每次开始/暂停/恢复/结束都递增 lifecycleRevision，显式断开或结束递增 epoch；每次新扫描/连接尝试递增 connectionAttemptToken。阶段只能单向推进；唯一例外不是 phase 回退，而是同 guard、仍在 ENABLING_NOTIFICATIONS 且尚未 ready 时允许一次 pendingChannel 从 STANDARD_HR 横切到 PRIVATE_B3，并置 privateFallbackUsed。此后任何 standard NotificationsEnabled 都按 stale 忽略，PRIVATE_B3 也不能再次横切。Private 通知启用只产生 SendStart；START accepted 后进入 AWAITING_PRIVATE_ACTIVITY 并 StartSync；首个合法 WorkoutData 才 Ready(PRIVATE_B3)。若同一当前 GATT 的合法活动因跨 Flow 调度先于 StartWriteCompleted 到达，先暂存在 pendingPrivateActivity，START accepted 后立即消费一次、Ready 并 CancelReadyTimeout；失败、断开或 token 变化必须丢弃 pending。接受活动时用 `maxOf(lastPrivateActivityAtMillis, event.receivedAtMillis)` 更新，绝不能改用 handle 的 nowMillis，也不能被同 guard 反序的旧 callback 把时间倒退；nowMillis 只用于计算年龄。StandardHr 通知启用直接 Ready(STANDARD_HR)。当前 ConnectFailed 按 attempt guard 进入统一退避，不能生成 GATT 身份。所有带 current GATT 的终端失败统一调用一个 reducer-private `failCurrentGatt`：在生成任何 action 前先摘除 current GATT、清 sync/pending/in-flight 状态，再按测试锁定的 cleanup→ERROR→backoff 顺序输出；数据源必须保持同 batch 顺序，不能让 ScheduleReconnect 越过 CloseGatt。`captureSyncPollGuard` 只有在完整 GATT guard/current sync 资格都匹配时才返回当前 revision 快照；handle(SyncDue) 先比较完整 pollGuard，旧 revision 整批拒绝，当前 revision 才按原顺序生成沿用同一 guard 的 actions。RunStarted/RunPaused/RunResumed 接受新 elapsed 基准并输出 ResetSyncUpdateAccounting；它不停止 sync 或移动 heartbeat。SyncWriteCompleted 先用 submission.gattGuard 与 operationId 认领并移除 in-flight；同一 GATT 已提交写的失败即使跨过 pause/resume 仍统一转 failCurrentGatt，只有旧 GATT/epoch 或未知、重复 operation 才出 stale diagnostic。

- [ ] **Step 4: 运行绿灯并检查 reducer 无 Android 依赖**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.KeepB5SessionSupervisorTest"
if ($LASTEXITCODE -ne 0) { throw "会话监督器绿灯失败" }
rg -n "^import android" app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5SessionSupervisor.kt
if ($LASTEXITCODE -eq 0) { throw "纯 Kotlin supervisor 出现 Android import" }
if ($LASTEXITCODE -gt 1) { throw "supervisor import 审查执行失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5SessionSupervisor.kt app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5SessionSupervisorTest.kt
if ($LASTEXITCODE -ne 0) { throw "会话监督器 diff-check 失败" }
~~~

预期 rg 无输出。

- [ ] **Step 5: 保持暂存区为空**

~~~powershell
$ErrorActionPreference = "Stop"
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
~~~

---

## Task 5：新增暂停态心率观察器

**Files:**

- Create: app/src/main/java/com/healthyagent/running/coordinator/PausedHeartRateMonitor.kt
- Create: app/src/test/java/com/healthyagent/running/coordinator/PausedHeartRateMonitorTest.kt
- Modify: app/src/main/java/com/healthyagent/running/core/pipeline/RunningDataPipeline.kt
- Modify: app/src/test/java/com/healthyagent/running/core/pipeline/RunningDataPipelineTest.kt

**Interface:**

~~~kotlin
data class PausedHeartRateState(
    val heartRate: Int?,
    val hrZone: HrZone?,
)

data class PausedHeartRateSeed(
    val heartRate: Int?,
    val hrZone: HrZone?,
    val receivedAtMillis: Long?,
)

class PausedHeartRateMonitor(
    private val tickSource: Flow<Long>? = null,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun beginRunning(generation: Long)
    fun recordRunningSample(
        generation: Long,
        sample: SensorSample,
        receivedAtMillis: Long,
    )
    fun freezeForPause(generation: Long, maxHeartRate: Int): PausedHeartRateSeed
    fun invalidateRunning()
    fun observe(
        samples: Flow<SensorSample>,
        maxHeartRate: Int,
        seed: PausedHeartRateSeed,
        ticks: Flow<Long> = tickSource ?: defaultTicks(),
    ): Flow<PausedHeartRateState>
}

// RunningDataPipeline 现有成员方法保留前两个参数；第三个参数默认 no-op。
fun process(
    samples: Flow<SensorSample>,
    ticks: Flow<Long> = tickSource ?: defaultTicks(),
    onOrderedHeartRateReceived: (sample: SensorSample, receivedAtMillis: Long) -> Unit = { _, _ -> },
): Flow<MetricsFrame>
~~~

freshness 绝不能在 pause 时重置。RunningDataPipeline 已在 sample collector 入口为每条样本取得 `receivedAtMillis = monotonicClockMillis()`；本任务只在样本通过既有 `lastSourceTimestampByType` 顺序检查后、HEART_RATE 分支内调用默认 no-op 的 onOrderedHeartRateReceived，并原样传出该 SensorSample 与同一次单调接收时刻，不重采时钟、不改变现有 PipelineState 或 MetricsFrame 计算。AppCoordinator 在 Task 11 为每次 running pipeline generation 调用 beginRunning，并把这个回调接到 recordRunningSample；后者只接受该 generation 的合法 HR。freezeForPause 与 recordRunningSample 共用 monitor 内部短锁，形成唯一交接线性化点：record 先赢则样本进入 seed，freeze 先赢则该 generation 随即关闭，迟到 callback 永远不能续命。seed 保存最后一条真实合法 HR 的原始值、按同一次跑步冻结的 maxHeartRate 计算的区间和原始 receivedAtMillis；pause 时不调用 monotonic clock 改写它。调用方必须把本次会话的 activeRunConfig.maxHeartRate 在暂停临界区冻结为局部值，并把同一个值同时传给 freezeForPause 与 observe，不能退回构造期 runConfig。start/resume 建立新 generation 并清空旧 receipt，stop/close invalidate。

observe 开始时用自己的同域 monotonicClockMillis 与 seed.receivedAtMillis 算年龄：`now - receivedAtMillis in 0 until 5_000` 才立即发布 seed HR；年龄已满 5_000ms 的完整 seed 首条只发布 null，绝不能先短暂复活 HR；全 null 或 malformed seed 保持静默。后续合法 HR 仍按它在 monitor collector 处的本次单调接收时刻续期。这样不修改 SensorDataSource 公共合同；代价仅是暂停瞬间可能从 RunningDataPipeline 的平滑值切到最后一条真实原始 HR，确保展示值和 freshness 锚点来自同一条样本，而不是借更新样本的时间延长旧展示值。

- [ ] **Step 1: 写纯 Flow 失败测试**

~~~kotlin
@Test
fun acceptsOnlyHeartRateAndCalculatesZone() = runTest {
    val samples = MutableSharedFlow<SensorSample>()
    val ticks = MutableSharedFlow<Long>()
    PausedHeartRateMonitor(monotonicClockMillis = { 1_000L })
        .observe(
            samples,
            maxHeartRate = 200,
            seed = PausedHeartRateSeed(null, null, null),
            ticks = ticks,
        )
        .test {
            samples.emit(SensorSample(SampleType.DISTANCE, 999.0, 1L))
            samples.emit(SensorSample(SampleType.HEART_RATE, 150.0, 2L))
            assertEquals(PausedHeartRateState(150, HrZone.Z3), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
}
~~~

另写：

- beginRunning(7) 后在 t=0 record HR=150，t=4_999 调用 freezeForPause；seed 精确携带 150/Z3/0，而不是 4_999。observe 使用 empty samples 与可控 ticks，立即得到 HR=150/Z3；令单调时钟为 5_000 并 emit tick=5_000 后直接清为 null，取消 collector 后继续 emit 不再产出。另以 observer 从 t=0 启动的同一 seed 覆盖生产 defaultTicks：4_999ms 保留，5_000ms 清空。
- 同样在 t=0 记录 HR，但到绝对 t=5_000 才 freeze：observe 的第一项必须直接是 null，期间绝不能先 emit 150。freeze 后迟到的 generation 7 callback、beginRunning(8) 后迟到的 generation 7 callback 都不能改变 seed 或让过期 HR 复活；generation 8 在新真实样本前 freeze 得到全 null。
- 依次输入 HEART_RATE 的 0.0、301.0、NaN、POSITIVE_INFINITY、150.5，全部不 emit 且不刷新 running seed 或 paused freshness；即使最后一个无效值在绝对 t=4_999 到达，原 t=0 的合法 HR 仍在 t=5_000 准时清空。只有 1..300 的有限整数值有效。表测部分为空、越界、hrZone 不匹配、负/未来 receivedAtMillis 的 malformed seed 均不能发布 HR。
- 设备 source timestamp 即使旧于上一帧或发生墙钟回拨，只要是现在收到的合法 HR 就按本次 monotonic 接收时刻更新并保持 fresh，随后满 5 秒再清空；非心率样本不产生状态。
- RunningDataPipelineTest 注入依次返回 1_234/2_345 的 monotonicClockMillis：有序 HR callback 精确收到对应值，不能收到 SensorSample.timestampMillis；同类型 source timestamp 反序而被现有检查拒绝的样本不调用 callback。给 callback 与默认 no-op 两条 pipeline 输入相同样本/tick，所得 MetricsFrame 全对象序列相等，证明此 seam 不改变跑步计算。

- [ ] **Step 2: 运行红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.coordinator.PausedHeartRateMonitorTest" --tests "com.healthyagent.running.core.pipeline.RunningDataPipelineTest"
if ($LASTEXITCODE -eq 0) { throw "新增暂停心率测试未出现预期红灯" }
Write-Host "已确认暂停心率预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 3: 实现 5 秒 freshness 和 HrZoneCalculator**

PausedHeartRateMonitor 在短锁内只保存 activeRunningGeneration、是否仍开放和最后一条合法 HR(value + receivedAtMillis)。beginRunning 切到新 generation 并清空旧值；recordRunningSample 同时校验 generation/open、SampleType.HEART_RATE、finite、无小数及 1..300，合法才替换 receipt；freezeForPause 对匹配 generation 原子关闭入口并用该原始 receipt 构造 seed，不读取当前时钟；invalidateRunning 清空并拒绝迟到 callback。RunningDataPipeline 的 hook 只传递它已经采到的单调 receipt，不负责校验值或计算区间。

observe 初始化时验证 seed 三字段同时非空、heartRate/zone 一致且 receivedAtMillis 非负；完整 seed 的年龄在 0 until 5_000 才立即发布 HR，年龄大于等于 5_000 或为负时第一项直接发布 null，绝不能先发布 HR。全 null seed 保持静默；部分为空或字段不一致的 malformed seed 归一为空且不抛异常。随后合并 sample 与 tick 为内部事件：sample 到达时用 monitor 的当前单调时钟重置 freshness，而非相信设备 timestamp。每次有效样本或 freshness 从 fresh 变 stale 时才 emit；已经为空时无需重复 emit null。

- [ ] **Step 4: 运行绿灯并保持暂存区为空**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.coordinator.PausedHeartRateMonitorTest" --tests "com.healthyagent.running.core.pipeline.RunningDataPipelineTest"
if ($LASTEXITCODE -ne 0) { throw "暂停心率观察器绿灯失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/coordinator/PausedHeartRateMonitor.kt app/src/test/java/com/healthyagent/running/coordinator/PausedHeartRateMonitorTest.kt app/src/main/java/com/healthyagent/running/core/pipeline/RunningDataPipeline.kt app/src/test/java/com/healthyagent/running/core/pipeline/RunningDataPipelineTest.kt
if ($LASTEXITCODE -ne 0) { throw "暂停心率 diff-check 失败" }
$implementationStatePath = Join-Path ([IO.Path]::GetTempPath()) "healthy-agent-b5-reliability-state.json"
if (-not (Test-Path -LiteralPath $implementationStatePath -PathType Leaf)) { throw "缺少实施 state JSON" }
$implementationState = Get-Content -LiteralPath $implementationStatePath -Raw | ConvertFrom-Json
$repositoryRoot = (Resolve-Path ".").Path
if ([string]$implementationState.schemaVersion -ne "1" -or [string]$implementationState.repositoryRoot -ne $repositoryRoot) { throw "实施 state 与当前仓库不匹配" }
$implementationBase = [string]$implementationState.implementationBase
$baselineRoot = [string]$implementationState.baselineRoot
$baselineManifestPath = [string]$implementationState.baselineManifestPath
$commitType = git cat-file -t $implementationBase
if ($LASTEXITCODE -ne 0 -or ($commitType -join "").Trim() -ne "commit") { throw "IMPLEMENTATION_BASE 不可解析" }
if (-not (Test-Path -LiteralPath $baselineRoot -PathType Container) -or -not (Test-Path -LiteralPath $baselineManifestPath -PathType Leaf)) { throw "实施基线文件已丢失" }
git diff --no-index -- "$baselineRoot/main-running/core/pipeline/RunningDataPipeline.kt" "app/src/main/java/com/healthyagent/running/core/pipeline/RunningDataPipeline.kt"
if ($LASTEXITCODE -gt 1) { throw "RunningDataPipeline no-index 审查失败" }
git diff --no-index -- "$baselineRoot/test-running/core/pipeline/RunningDataPipelineTest.kt" "app/src/test/java/com/healthyagent/running/core/pipeline/RunningDataPipelineTest.kt"
if ($LASTEXITCODE -gt 1) { throw "RunningDataPipelineTest no-index 审查失败" }
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
~~~

---

## Task 6：恢复 11 条规则并让 Split 成为公里唯一事实

**Files:**

- Create: app/src/main/java/com/healthyagent/running/core/engine/RuleCatalog.kt
- Create: app/src/test/java/com/healthyagent/running/core/engine/RuleCatalogTest.kt
- Modify: app/src/main/java/com/healthyagent/running/core/model/RuleContext.kt
- Modify: app/src/main/java/com/healthyagent/running/core/engine/RuleEngine.kt
- Modify: app/src/main/java/com/healthyagent/running/core/engine/rules/KmSplitRule.kt
- Modify: app/src/test/java/com/healthyagent/running/core/engine/RuleEngineTest.kt
- Modify: app/src/test/java/com/healthyagent/running/core/engine/RulesTest.kt

**Rule catalog:**

~~~kotlin
object RuleCatalog {
    fun defaultRules(): List<Rule> = listOf(
        HrZone5Rule(),
        HrRapidRiseRule(),
        HrTooLowRule(),
        CadenceDropRule(),
        CadenceTooHighRule(),
        PaceDeviationRule(),
        StartTooFastRule(),
        EfficiencyAbnormalRule(),
        DataStaleRule(),
        KmSplitRule(),
        GoalAchievedRule(),
    )
}
~~~

精确 ID 顺序为 hr_z5_danger、hr_rapid_rise、hr_too_low、cadence_drop、cadence_too_high、pace_deviation、start_too_fast、efficiency_abnormal、data_stale、km_split、goal_achieved。

- [ ] **Step 1: 写目录失败测试**

~~~kotlin
@Test
fun returnsElevenUniqueRulesInStableOrder() {
    val ids = RuleCatalog.defaultRules().map(Rule::id)
    assertEquals(
        listOf(
            "hr_z5_danger",
            "hr_rapid_rise",
            "hr_too_low",
            "cadence_drop",
            "cadence_too_high",
            "pace_deviation",
            "start_too_fast",
            "efficiency_abnormal",
            "data_stale",
            "km_split",
            "goal_achieved",
        ),
        ids,
    )
    assertEquals(11, ids.distinct().size)
}
~~~

- [ ] **Step 2: 写 authoritative Split 失败测试**

在 RuleContext 增加一次性字段：

~~~kotlin
val completedSplit: Split? = null
~~~

RulesTest 构造 `Split(index=3, distanceMeters=3_000.0, durationSec=407L, avgHeartRate=null, avgCadence=null, avgPaceSecPerKm=395.0, hrZoneDistribution=null)`，同时给当前 frame 放入互相冲突的 `elapsedSeconds=999`、`distanceMeters=4_250`、`currentPaceSecPerKm=222.0`，断言 KmSplitRule 文本精确为“第3公里，用时6分47秒，配速6分35秒”；这样 index、用时和配速任一硬编码/误读 frame 都会红。completedSplit=null 时不触发。测试按现有 Split 完整构造，不给生产模型补默认值；可复用测试 helper，但 helper 必须显式传出上述七个字段。删除 KmSplitRule 的 lastKm、窗口反推与有状态 reset。

- [ ] **Step 3: 写规则诊断冷却失败测试**

RuleEngine 构造器增加末尾默认依赖：

~~~kotlin
class RuleEngine(
    private val rules: List<Rule>,
    initialConfig: RunConfig,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
)
~~~

测试用总会触发且 cooldownMs=10_000 的规则：时钟 12_000 首次触发记录精确 `RuleTriggered(ruleId)`；12_500 起同一冷却窗口连续三帧只记录一个 `RuleCooldownSkipped(ruleId, windowStartedAtMillis=12_000)`；越过窗口后再次触发。reset 后在 30_000 重新触发、30_001 skip 的 windowStartedAtMillis 必须为 30_000；对 splitResetRules 调用 onSplit 后在 40_000/40_001 重复同样断言，证明 last trigger 与已记录窗口标记一起重置，而不只是比较事件数量。

- [ ] **Step 4: 运行红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.core.engine.RuleCatalogTest" --tests "com.healthyagent.running.core.engine.RuleEngineTest" --tests "com.healthyagent.running.core.engine.RulesTest"
if ($LASTEXITCODE -eq 0) { throw "新增规则测试未出现预期红灯" }
Write-Host "已确认规则层预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 5: 实现目录、Split 消费和冷却诊断**

RuleEngine 构建 updatedContext 时保留调用者传入的 completedSplit。用 lastLoggedCooldownTriggerAt[ruleId] 记住本窗口对应的 lastTriggerTime；相同 trigger 时间只记一次 skip。实际触发时记录 RuleTriggered。reset 清全部，onSplit 对 splitResetRules 同时清 lastTriggerTimes 与 cooldown 日志标记。

- [ ] **Step 6: 运行绿灯、审查重叠文件并保持暂存区为空**

~~~powershell
$ErrorActionPreference = "Stop"
$implementationStatePath = Join-Path ([IO.Path]::GetTempPath()) "healthy-agent-b5-reliability-state.json"
if (-not (Test-Path -LiteralPath $implementationStatePath -PathType Leaf)) { throw "缺少实施 state JSON" }
$implementationState = Get-Content -LiteralPath $implementationStatePath -Raw | ConvertFrom-Json
$repositoryRoot = (Resolve-Path ".").Path
if ([string]$implementationState.schemaVersion -ne "1" -or [string]$implementationState.repositoryRoot -ne $repositoryRoot) { throw "实施 state 与当前仓库不匹配" }
$implementationBase = [string]$implementationState.implementationBase
$baselineRoot = [string]$implementationState.baselineRoot
$baselineManifestPath = [string]$implementationState.baselineManifestPath
$commitType = git cat-file -t $implementationBase
if ($LASTEXITCODE -ne 0 -or ($commitType -join "").Trim() -ne "commit") { throw "IMPLEMENTATION_BASE 不可解析" }
if (-not (Test-Path -LiteralPath $baselineRoot -PathType Container) -or -not (Test-Path -LiteralPath $baselineManifestPath -PathType Leaf)) { throw "实施基线文件已丢失" }
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.core.engine.RuleCatalogTest" --tests "com.healthyagent.running.core.engine.RuleEngineTest" --tests "com.healthyagent.running.core.engine.RulesTest"
if ($LASTEXITCODE -ne 0) { throw "规则层绿灯失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/core/engine app/src/main/java/com/healthyagent/running/core/model/RuleContext.kt app/src/test/java/com/healthyagent/running/core/engine
if ($LASTEXITCODE -ne 0) { throw "规则层 diff-check 失败" }
git diff --no-index -- "$baselineRoot/main-running/core/engine/RuleEngine.kt" "app/src/main/java/com/healthyagent/running/core/engine/RuleEngine.kt"
if ($LASTEXITCODE -gt 1) { throw "RuleEngine no-index 审查失败" }
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
~~~

AppModule 与 AppCoordinator 仍不得在本任务修改。

---

## Task 7：实现会话播报工厂和 SpeechBus 仲裁

**Files:**

- Create: app/src/main/java/com/healthyagent/running/speech/SessionSpeechTriggerFactory.kt
- Create: app/src/main/java/com/healthyagent/running/speech/TtsExceptions.kt
- Create: app/src/test/java/com/healthyagent/running/speech/SessionSpeechTriggerFactoryTest.kt
- Modify: app/src/main/java/com/healthyagent/running/speech/SpeechBus.kt
- Modify: app/src/main/java/com/healthyagent/running/speech/TtsEngine.kt
- Modify: app/src/test/java/com/healthyagent/running/speech/SpeechBusTest.kt
- Modify: app/src/test/java/com/healthyagent/running/testing/FakeTtsEngine.kt
- Modify: app/src/test/java/com/healthyagent/running/ai/AnalysisManagerTest.kt

**Session triggers:**

~~~kotlin
enum class SessionSpeechEvent { STARTED, PAUSED, RESUMED, FINISHED }

object SessionSpeechTriggerFactory {
    fun create(event: SessionSpeechEvent): RuleTrigger = when (event) {
        SessionSpeechEvent.STARTED -> status("session_started", "流萤已开始记录，出发吧。")
        SessionSpeechEvent.PAUSED -> status("session_paused", "跑步已暂停。")
        SessionSpeechEvent.RESUMED -> status("session_resumed", "跑步已恢复。")
        SessionSpeechEvent.FINISHED -> status("session_finished", "跑步已结束。")
    }

    private fun status(id: String, text: String) = RuleTrigger(
        ruleId = id,
        severity = Severity.INFO,
        speakText = text,
        ttsPriority = SpeechPriority.APP,
    )
}
~~~

- [ ] **Step 1: 写四条固定 trigger 失败测试**

断言四个 event 的 ruleId、中文全文、Severity.INFO、SpeechPriority.APP 完全等于上表。

- [ ] **Step 2: 把 SpeechBus 行为写成失败测试**

目标 API：

~~~kotlin
class SpeechBus(
    private val ttsEngine: TtsEngine,
    initialPolicy: SpeechPolicy = SpeechPolicy(enabled = true, minIntervalSec = 4),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val latestMessage: StateFlow<CoachMessage?>
    fun configure(policy: SpeechPolicy)
    fun enqueue(trigger: RuleTrigger)
    internal fun enqueue(trigger: RuleTrigger, expectedSessionGeneration: Long)
    fun enqueueSessionStatus(trigger: RuleTrigger)
    internal fun sessionGenerationToken(): Long
    fun resetSession()
    fun stop()
    fun shutdown()
}
~~~

测试用 StandardTestDispatcher 和可控 FakeTtsEngine，精确覆盖：

- enqueueSessionStatus 返回前已经登记 session_status_scheduled，但 speak 在 scheduler 推进后才开始。
- 同一会话提示两次合法发生可绕过 4 秒文本去重。
- 非紧急播报中快速 PAUSED→RESUMED 会取消旧 operation，最终只发布 RESUMED。
- URGENT 播放期间不 stop URGENT，只保留 revision 最大的 pending status；URGENT 成功、URGENT 普通失败或 PlaybackTimeoutException 后都提升最新 pending。
- URGENT 活跃时先排入 PAUSED，再用 RESUMED 替换 pending：恰好记录一次 `SpeechOutcome("session_paused", PENDING_SESSION_STATUS_REPLACED)`，而不是静默覆盖；URGENT 终态后只播放 RESUMED。被替换项自己的 ruleId 必须进入诊断，最新 pending 不能被误记为 replaced。
- hard stop/reset/shutdown 使 session generation 与 operation ID 失效、清 pending，旧 finally 不提升。
- 任意尚未播放的 pending session status 被 reset/stop/shutdown 清除时，都必须用该 pending 自己的 ruleId 恰好记录一次 CANCELLED；只有 FINISHED 还要安排唯一 finished terminal flush。若 pending 先被 PAUSED→RESUMED 替换并已记录 PENDING_SESSION_STATUS_REPLACED，hard stop 只能再取消当前 RESUMED，不能重复取消已替换的 PAUSED。
- FINISHED 在 URGENT 后作为 pending 尚未提升时，reset/stop/shutdown 必须先记录 `SpeechOutcome(session_finished, CANCELLED)` 并安排唯一 finished terminal flush，再清 pending；不能因为从未调用 ttsEngine.speak 就漏掉 terminal barrier。
- TTS 真实完成前 latestMessage 不更新；失败和取消不写 speakHistory。
- 旧 operation 的迟到 done/finally 不覆盖新 priority、message 或 pending。
- 两个线程连续替换会话状态时，较早替换动作即使延迟，也不能在较新 playback 启动后调用共享引擎 stop。
- policy disabled、duplicate、priority block、stale generation 和所有终态写入精确 SpeechOutcomeReason；PlaybackTimeoutException 必须映射 PLAYBACK_TIMEOUT，CancellationException 必须映射 CANCELLED 而非 TTS_FAILURE。
- policy disabled 时会话状态不 schedule、不 speak，只记录 POLICY_DISABLED；configure 必须继续支持设置观察器动态更新开关和最短间隔。
- session_finished 的 POLICY_DISABLED、真实 success、普通 failure、timeout 或 cancellation 终态记录后，在独立 actor scope 再异步执行恰好一次 2 秒限时 diagnosticLogger.flush；SESSION_STATUS_SCHEDULED 不是终态，不能提前 flush。用 flushGate 证明 enqueueSessionStatus、terminal 发布和下一条命令都不被 flush 阻塞。SpeechBus 是 TTS/session_finished terminal flush 的唯一 owner，AppCoordinator 不得再重复调用 flush。

- [ ] **Step 3: 运行红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.speech.SessionSpeechTriggerFactoryTest" --tests "com.healthyagent.running.speech.SpeechBusTest"
if ($LASTEXITCODE -eq 0) { throw "新增 SpeechBus 测试未出现预期红灯" }
Write-Host "已确认 SpeechBus 预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 4: 实现同步仲裁、串行副作用 actor 与 pending**

内部状态固定包括 activeOperationId、activePlaybackJob、currentPriority、sessionGeneration、sessionStatusRevision 和：

~~~kotlin
private data class PendingSessionStatus(
    val revision: Long,
    val sessionGeneration: Long,
    val trigger: RuleTrigger,
)
~~~

锁内做判定、状态更新，并用非挂起 trySend 把带 operationId 的 Start/Replace/HardStop 命令写入 Channel.UNLIMITED；这样状态顺序与命令顺序具有同一个线性化点。单 actor 是唯一允许 cancel activePlaybackJob、调用 ttsEngine.stop 和启动新 playback 的位置；播放本身在 actor 的子 Job 中运行，terminal 再回送 actor。不得让各 enqueue 调用者在锁外直接 stop，否则旧替换会产生 ABA 并停掉新播放。会话提示不查 speakHistory。当前非 URGENT 时新 status 使旧 operation 失效并发 Replace；当前 URGENT 时不变更 activeOperationId，只覆盖 pending。播放 terminal 回调必须同时匹配 operationId 和 sessionGeneration 才能发布或提升 pending。

本任务先在 TtsExceptions.kt 定义 PlaybackTimeoutException，供 SpeechBus 映射诊断；Task 7 不依赖后续平台引擎。hard stop 在同一 actor 命令内先摘除 pending：若存在，无论 STARTED/PAUSED/RESUMED/FINISHED 都以其自身 ruleId 发布且只发布一次 CANCELLED；随后才使 operation/generation 失效并清理。session_finished 的 terminal 命令先写入最终 SpeechOutcome，再 launch 一个 `withTimeoutOrNull(2_000) { diagnosticLogger.flush() }`；policy disabled 以及 hard stop 清除尚未播放的 pending FINISHED 分支也走同一幂等 terminal helper。hard stop 取消旧 operation，但仍允许这次有限 flush 使用独立 actor scope；每个被接受的 FINISHED operation/revision 最多触发一次 terminal 及一次 flush。TtsEngine 文档契约改为：Result.success 仅代表真实 onDone/completion；普通失败返回 Result.failure；取消或 stop 以 CancellationException 结束挂起调用。

- [ ] **Step 5: 修正 FakeTtsEngine 的取消语义**

Task 7 在唯一共享测试夹具中固定以下 API，Task 9/11 不再各自发明字段名：

~~~kotlin
data class FakeTtsRequest(
    val text: String,
    val priority: SpeechPriority,
)

class FakeTtsEngine : TtsEngine {
    val requests: List<FakeTtsRequest>
    val completedTexts: List<String>
    val stopCalls: Int
    val shutdownCalls: Int

    fun completeNextSuccessfully()
    fun completeNextWithFailure(
        failure: Throwable = IllegalStateException("模拟 TTS 失败"),
    )
    fun cancelNext()
}
~~~

Fake 用每请求独立的 CompletableDeferred 控制真实终态；`requests` 在 speak 入口登记，只有 success 才把该请求文本追加到 completedTexts。三个 terminal helper 优先结束最早的 unresolved request；若当前尚无请求，就把该 terminal 预排给下一次 speak，因此 Task 9 可以在同步调用 Composite.speak 前预置结果。failure 返回 Result.failure，cancel/stop/shutdown 让对应 speak 抛 CancellationException；实现显式 catch CancellationException 后重新抛出，禁止 runCatching 包住 gate.await。stopCalls/shutdownCalls 记录实际入口调用次数，stop 只结束当前请求，shutdown 结束全部未决请求；迟到 terminal helper 只能命中原 operation，不能完成后来的请求。同步迁移 SpeechBusTest 与 AnalysisManagerTest：`spokenTexts→completedTexts`、`stopCount→stopCalls`、`shutdownCount→shutdownCalls`，`shouldFail/speakGate` 改用上述 terminal helper；仓库测试源码执行 `rg "spokenTexts|shouldFail|speakGate|stopCount|shutdownCount"` 后必须零匹配。

AnalysisManagerTest 现有两个 SpeechBus 都必须显式注入 `StandardTestDispatcher(testScheduler)`，在断言请求/播放结果前用 runCurrent/推进对应 gate；禁止让单测依赖默认 Dispatchers.Default 的真实线程调度。生产 AnalysisManager 不改。

- [ ] **Step 6: 运行绿灯、审查重叠文件并保持暂存区为空**

~~~powershell
$ErrorActionPreference = "Stop"
$implementationStatePath = Join-Path ([IO.Path]::GetTempPath()) "healthy-agent-b5-reliability-state.json"
if (-not (Test-Path -LiteralPath $implementationStatePath -PathType Leaf)) { throw "缺少实施 state JSON" }
$implementationState = Get-Content -LiteralPath $implementationStatePath -Raw | ConvertFrom-Json
$repositoryRoot = (Resolve-Path ".").Path
if ([string]$implementationState.schemaVersion -ne "1" -or [string]$implementationState.repositoryRoot -ne $repositoryRoot) { throw "实施 state 与当前仓库不匹配" }
$implementationBase = [string]$implementationState.implementationBase
$baselineRoot = [string]$implementationState.baselineRoot
$baselineManifestPath = [string]$implementationState.baselineManifestPath
$commitType = git cat-file -t $implementationBase
if ($LASTEXITCODE -ne 0 -or ($commitType -join "").Trim() -ne "commit") { throw "IMPLEMENTATION_BASE 不可解析" }
if (-not (Test-Path -LiteralPath $baselineRoot -PathType Container) -or -not (Test-Path -LiteralPath $baselineManifestPath -PathType Leaf)) { throw "实施基线文件已丢失" }
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.speech.SessionSpeechTriggerFactoryTest" --tests "com.healthyagent.running.speech.SpeechBusTest" --tests "com.healthyagent.running.ai.AnalysisManagerTest"
if ($LASTEXITCODE -ne 0) { throw "SpeechBus 绿灯失败" }
rg -n "spokenTexts|shouldFail|speakGate|stopCount|shutdownCount" app/src/test/java
if ($LASTEXITCODE -eq 0) { throw "测试仍引用旧 FakeTtsEngine API" }
if ($LASTEXITCODE -gt 1) { throw "FakeTtsEngine API 审查执行失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/speech app/src/test/java/com/healthyagent/running/speech app/src/test/java/com/healthyagent/running/testing/FakeTtsEngine.kt app/src/test/java/com/healthyagent/running/ai/AnalysisManagerTest.kt
if ($LASTEXITCODE -ne 0) { throw "SpeechBus diff-check 失败" }
git diff --no-index -- "$baselineRoot/main-running/speech/SpeechBus.kt" "app/src/main/java/com/healthyagent/running/speech/SpeechBus.kt"
if ($LASTEXITCODE -gt 1) { throw "SpeechBus 实现 no-index 审查失败" }
git diff --no-index -- "$baselineRoot/test-running/speech/SpeechBusTest.kt" "app/src/test/java/com/healthyagent/running/speech/SpeechBusTest.kt"
if ($LASTEXITCODE -gt 1) { throw "SpeechBus 测试 no-index 审查失败" }
git diff --no-index -- "$baselineRoot/test-running/ai/AnalysisManagerTest.kt" "app/src/test/java/com/healthyagent/running/ai/AnalysisManagerTest.kt"
if ($LASTEXITCODE -gt 1) { throw "AnalysisManager 测试 no-index 审查失败" }
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
~~~

---

## Task 8：让 Android TTS 等待真实完成并管理独立音频焦点

**Files:**

- Create: app/src/main/java/com/healthyagent/running/speech/SpeechAudioProfile.kt
- Create: app/src/main/java/com/healthyagent/running/speech/TextToSpeechPort.kt
- Create: app/src/main/java/com/healthyagent/running/speech/AndroidTextToSpeechPort.kt
- Create: app/src/main/java/com/healthyagent/running/speech/AudioFocusController.kt
- Create: app/src/main/java/com/healthyagent/running/speech/AndroidAudioFocusController.kt
- Modify: app/src/main/java/com/healthyagent/running/speech/TtsExceptions.kt
- Create: app/src/test/java/com/healthyagent/running/speech/AndroidTtsEngineTest.kt
- Create: app/src/test/java/com/healthyagent/running/speech/AndroidTextToSpeechPortTest.kt
- Create: app/src/test/java/com/healthyagent/running/speech/AudioFocusControllerTest.kt
- Modify: app/src/main/java/com/healthyagent/running/speech/AndroidTtsEngine.kt

**Ports:**

~~~kotlin
enum class SpeechAudioProfile { ASSISTANT_SPEECH }

interface TextToSpeechPort {
    suspend fun awaitInitialization(): Result<Unit>
    fun setAudioProfile(profile: SpeechAudioProfile): Result<Unit>
    fun setListener(listener: Listener)
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
    fun shutdown()

    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int?)
        fun onStop(utteranceId: String)
    }
}

interface AudioFocusController {
    fun request(): AudioFocusLease
}

interface AudioFocusLease {
    val granted: Boolean
    fun release()
}
~~~

Android adapter 将 ASSISTANT_SPEECH 唯一映射为 CONTENT_TYPE_SPEECH + USAGE_ASSISTANT；焦点使用 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK，不设置 preferred device。每次 request 创建自己的 AudioFocusRequest 和一次性 release 标记。

- [ ] **Step 1: 写 engine 和 lease 失败测试**

~~~kotlin
private fun newEngine(
    port: FakeTextToSpeechPort,
    focus: FakeAudioFocusController,
) = AndroidTtsEngine(
    port = port,
    audioFocusController = focus,
    initializationTimeoutMillis = 3_000L,
    playbackTimeoutMillis = 60_000L,
    newUtteranceId = { "utterance-1" },
)
~~~

逐项断言：

- awaitInitialization 未完成 3 秒时返回 TtsInitializationTimeoutException，并记录 ANDROID/INITIALIZATION_FAILED，failure 只含安全 type/summary。
- awaitInitialization 立即返回 Result.failure 时不申请焦点、不 speak，并返回普通 failure；记录 ANDROID/INITIALIZATION_FAILED 而不是普通 playback FAILED。
- awaitInitialization 直接抛普通异常时同样返回普通 failure和 ANDROID/INITIALIZATION_FAILED，且焦点/speak 都为零；若抛 CancellationException 则原样向上传播并只记录 ANDROID/CANCELLED，不能记录 INITIALIZATION_FAILED/FAILED。
- setAudioProfile 返回 failure 时不申请焦点、不 speak，并返回普通 failure。
- setAudioProfile 直接抛普通异常时返回普通 failure，仍不申请焦点、不 speak；CancellationException 原样向上传播并只记录 ANDROID/CANCELLED。
- port.speak 返回 false 时立即普通 failure，不等待 watchdog，lease 只释放一次。
- port.speak 直接抛普通异常时返回普通 failure且当前 lease 恰好释放一次；CancellationException 原样向上传播，若已拿到 lease 也恰好释放一次。
- speak 被底层接受后，在 onDone 前 suspend；onDone 才 success 并释放当前 lease 一次。
- onError 返回普通 failure；onStop 抛 CancellationException。
- 60 秒无回调时 port.stop、PlaybackTimeoutException、lease release 各一次。
- 外部 coroutine cancel 或 engine.stop 结束挂起调用，迟到 onDone 无效。
- active playback 期间 shutdown 使挂起调用以 CancellationException 结束，port.stop、port.shutdown 与本次 lease release 各一次；迟到 onDone 不二次结束或释放。
- 焦点未 granted 仍尝试 speak 并记录结果。
- audioFocusController.request 直接抛普通异常时按 DENIED 处理，使用不持有系统资源的 no-op lease、记录一次 DENIED 并仍尝试 speak；CancellationException 则原样向上传播且不 speak，只记录 ANDROID/CANCELLED。
- 连续两次播放的旧 lease 迟到 release 不会释放新 AudioFocusRequest。
- setAudioProfile 精确收到 ASSISTANT_SPEECH。
- FakeDiagnosticLogger 收到焦点 GRANTED/DENIED、真实 COMPLETED、普通 FAILED、PLAYBACK_TIMEOUT 与 CANCELLED typed 事件。
- 每条实际取得 lease 的 success、普通 failure、timeout、onStop、外部取消、stop 与 shutdown 路径都恰好记录一次 RELEASED；初始化/profile/focus CancellationException 等未取得 lease 的路径 RELEASED 为零。

- [ ] **Step 2: 运行红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.speech.AndroidTtsEngineTest" --tests "com.healthyagent.running.speech.AndroidTextToSpeechPortTest" --tests "com.healthyagent.running.speech.AudioFocusControllerTest"
if ($LASTEXITCODE -eq 0) { throw "新增 Android TTS 测试未出现预期红灯" }
Write-Host "已确认 Android TTS 预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 3: 实现窄 Android adapters**

AndroidTextToSpeechPort 独占 TextToSpeech 初始化和 UtteranceProgressListener 映射，并提供只在 JVM 测试使用的 internal platform-factory seam。awaitInitialization 只有在平台 init status=SUCCESS、`isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= LANG_AVAILABLE`、setLanguage 不是 LANG_MISSING_DATA/LANG_NOT_SUPPORTED 且 setSpeechRate(1.0f) 提交成功后才 Result.success；缺语言数据或任一步普通失败返回安全初始化 failure，不能把“对象已创建”误当 ready。AndroidTextToSpeechPortTest 用 fake platform 精确断言 Locale.SIMPLIFIED_CHINESE、language availability 检查、setLanguage 和 1.0f rate 的调用顺序及失败分支，不依赖设备语言包。AndroidAudioFocusController 独占 AudioManager/AudioFocusRequest。抽出内部 OnceAudioFocusLease，使 release 幂等且只 abandon 自己持有的 request。

- [ ] **Step 4: 重写 AndroidTtsEngine 终态**

~~~kotlin
class AndroidTtsEngine(
    private val port: TextToSpeechPort,
    private val audioFocusController: AudioFocusController,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val initializationTimeoutMillis: Long = 3_000L,
    private val playbackTimeoutMillis: Long = 60_000L,
    private val newUtteranceId: () -> String = { UUID.randomUUID().toString() },
) : TtsEngine {
    constructor(context: Context) : this(
        port = AndroidTextToSpeechPort(context),
        audioFocusController = AndroidAudioFocusController(context),
    )
}
~~~

Context 次构造器只为 Task 8 到 Task 11 之间保持现有 AppModule 可编译；Task 11 改为显式注入共享 focus/logger 后，不在生产图使用它。初始化完成后、port.speak 前才申请 focus。awaitInitialization、setAudioProfile、request focus 与 port.speak 各自显式 `catch (cancelled: CancellationException) { throw cancelled }`；其余普通异常才规范化。初始化/profile/speak 的普通异常返回 Result.failure；focus request 的普通异常则构造 `granted=false` 的 no-op lease、记录 DENIED 并继续尝试播放。每个 utterance 用一次性完成门闩；成功、普通错误、onStop、watchdog、外部取消、stop、shutdown 全部经同一 finalize 路径清 isSpeaking 和 lease。已获取 lease 后的任意终态都恰好 release 一次；CancellationException 必须越过 Result 边界抛出，不能同时记录 FAILED。

- [ ] **Step 5: 运行绿灯和现有 SpeechBus 回归**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.speech.AndroidTtsEngineTest" --tests "com.healthyagent.running.speech.AndroidTextToSpeechPortTest" --tests "com.healthyagent.running.speech.AudioFocusControllerTest" --tests "com.healthyagent.running.speech.SpeechBusTest"
if ($LASTEXITCODE -ne 0) { throw "Android TTS 绿灯失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/speech app/src/test/java/com/healthyagent/running/speech
if ($LASTEXITCODE -ne 0) { throw "Android TTS diff-check 失败" }
~~~

- [ ] **Step 6: 保持暂存区为空**

~~~powershell
$ErrorActionPreference = "Stop"
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
~~~

---

## Task 9：收窄 Fish 合成/播放器端口并实现可靠本地回退

**Files:**

- Create: app/src/main/java/com/healthyagent/running/speech/FishSynthesisClient.kt
- Create: app/src/main/java/com/healthyagent/running/speech/OkHttpFishSynthesisClient.kt
- Create: app/src/main/java/com/healthyagent/running/speech/AudioPlayerPort.kt
- Create: app/src/main/java/com/healthyagent/running/speech/AndroidAudioPlayerPort.kt
- Create: app/src/test/java/com/healthyagent/running/speech/FishAudioTtsEngineTest.kt
- Create: app/src/test/java/com/healthyagent/running/speech/CompositeTtsEngineTest.kt
- Create: app/src/test/java/com/healthyagent/running/speech/OkHttpFishSynthesisClientTest.kt
- Modify: app/src/main/java/com/healthyagent/running/speech/FishAudioTtsEngine.kt
- Modify: app/src/main/java/com/healthyagent/running/speech/CompositeTtsEngine.kt

**Ports and engines:**

~~~kotlin
interface FishSynthesisClient {
    suspend fun synthesize(text: String): File
    fun cancel()
    fun shutdown()
}

interface AudioPlayerPort {
    fun start(
        file: File,
        profile: SpeechAudioProfile,
        listener: Listener,
    ): Result<Unit>
    fun stop()
    fun release()

    interface Listener {
        fun onCompletion()
        fun onError(what: Int, extra: Int)
    }
}

class FishAudioTtsEngine(
    private val synthesisClient: FishSynthesisClient,
    private val audioPlayerFactory: () -> AudioPlayerPort,
    private val audioFocusController: AudioFocusController,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val playbackTimeoutMillis: Long = 60_000L,
) : TtsEngine {
    constructor(apiKey: String, referenceId: String, context: Context) : this(
        synthesisClient = OkHttpFishSynthesisClient(
            apiKey = apiKey,
            referenceId = referenceId,
            cacheDirectory = File(context.cacheDir, "tts_cache"),
        ),
        audioPlayerFactory = { AndroidAudioPlayerPort() },
        audioFocusController = AndroidAudioFocusController(context),
    )
}

class CompositeTtsEngine(
    private val local: TtsEngine,
    private val cloud: TtsEngine?,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val actorDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TtsEngine
~~~

cloud=null 是缺少 API key 或 reference ID 的唯一表示；Composite actor 记录 SKIPPED_MISSING_CONFIG 后直接启动 local 子操作，不得构造 HTTP request，也不得绕过 actor 从调用线程执行 local。

Fish 的 Context 次构造器只为 Task 9 到 Task 11 之间保持现有 AppModule 可编译；Task 11 改为显式共享 focus/logger 后不再在生产图使用。Composite 的 logger 与 actorDispatcher 都有默认值，因此现有二参数调用继续编译；内部唯一 actor scope 必须由 `SupervisorJob() + actorDispatcher` 构造，测试传 StandardTestDispatcher 才能可靠卡住 Start/ChildTerminal，生产使用 Dispatchers.Default。

- [ ] **Step 1: 写 Composite 失败测试**

~~~kotlin
@Test
fun missingCloudConfigurationCallsOnlyLocal() = runTest {
    val local = FakeTtsEngine().apply { completeNextSuccessfully() }
    val logger = FakeDiagnosticLogger()
    val engine = CompositeTtsEngine(local = local, cloud = null, diagnosticLogger = logger)

    assertTrue(engine.speak("测试", SpeechPriority.APP).isSuccess)
    assertEquals(listOf("测试"), local.requests.map { it.text })
    assertTrue(logger.events.containsTtsEvent(TtsEventKind.SKIPPED_MISSING_CONFIG))
}
~~~

再写：cloud 普通 failure 后 local 恰好一次；cloud success 时不调用 local；cloud 或 local 子引擎抛 CancellationException 时，用 `withTimeout` 证明 Composite.speak 会立即以同一个取消异常完成、不会永久挂起，且 cloud 取消时 local 为零次。Composite.stop/shutdown 分别发生在 cloud synthesis、cloud playback、local fallback playback 三个阶段时，都取消当前子引擎且不会迟到启动另一层。用 gate 卡在 cloud 普通 failure 的 ChildTerminal 已入队、actor 尚未处理的窗口：先取消 caller，再放行 actor，local 请求必须保持零次；另测同一窗口 stop 后 local 也保持零次。

再把 StandardTestDispatcher 通过公开的 actorDispatcher 参数注入，gate 住尚未消费的 Start：caller 先取消再放行时 cloud/local 调用都为零且请求及时结束；speak 与 shutdown 竞态时，无论 Start 先入队还是 command channel 已关闭，deferred 都在 timeout 内异常完成。active provider.stop 抛普通异常时 actor 仍取消/完成 deferred并能处理下一请求；cloud.shutdown 抛普通异常时仍调用 local.shutdown，两个 provider 每个至多一次，重复 shutdown 幂等且没有悬挂请求。异常只写安全 typed diagnostic。

- [ ] **Step 2: 写 Fish 播放与取消失败测试**

测试精确覆盖：

- synthesize 挂起期间不请求焦点；取消调用 client.cancel 且不创建 player。
- 合成完成后先请求独立 focus lease，再以 ASSISTANT_SPEECH start。
- player start 被接受后直到 onCompletion 才 success。
- synthesisClient 抛网络/鉴权普通异常、audioPlayerFactory 抛异常、player.start 返回 Result.failure 都转换为 Fish 的 Result.failure，不直接抛出；已创建资源只释放一次。
- player.start 直接抛普通异常时同样转 Result.failure 并释放 player/lease；抛 CancellationException 时原样传播、绝不触发 Composite fallback，已取得资源仍各释放一次。
- 焦点 denied 仍以 ASSISTANT_SPEECH 启动 player，并记录 DENIED；不得把焦点拒绝当作静音成功。
- audioFocusController.request 抛普通异常时使用 granted=false 的 no-op lease、记录 DENIED 并仍启动 player；抛 CancellationException 时原样传播且 player 不启动。
- onError 普通失败；60 秒无回调时 stop/release/lease release 各一次并返回 PlaybackTimeoutException。
- 播放协程取消或 engine.stop 时 player stop/release、lease release 各一次，迟到 completion 无效。
- shutdown 同时终止当前操作、client.shutdown 与 player release。
- FakeDiagnosticLogger 精确收到 FISH/ATTEMPTED、COMPLETED、FAILED、CANCELLED、PLAYBACK_TIMEOUT 以及对应 GRANTED/DENIED/RELEASED 焦点终态；player.start 的 Result.failure 还要驱动 Composite 的 LOCAL_FALLBACK，并且 local 恰好调用一次。

- [ ] **Step 3: 运行红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.speech.FishAudioTtsEngineTest" --tests "com.healthyagent.running.speech.CompositeTtsEngineTest" --tests "com.healthyagent.running.speech.OkHttpFishSynthesisClientTest"
if ($LASTEXITCODE -eq 0) { throw "新增 Fish/Composite 测试未出现预期红灯" }
Write-Host "已确认 Fish/Composite 预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 4: 实现 OkHttp 与 MediaPlayer adapters**

OkHttpFishSynthesisClient 注入 Call.Factory（生产默认使用现有 10 秒连接/30 秒读取的 OkHttpClient），并以 operation-local cancellation flag + AtomicReference<Call?> 保存当前 Call。取消先置 flag 再 call.cancel；execute 因取消抛出的 IOException 必须依据 flag/job state 转为 CancellationException。下载先写 .part，取消/失败删除 partial，成功后再原子替换缓存文件；finally 用 compareAndSet，旧 Call 不得清掉新 Call。OkHttpFishSynthesisClientTest 用 fake Call 锁定 POST `https://api.fish.audio/v1/tts`、`Authorization: Bearer <key>`、application/json body 中 text/reference_id/format=`mp3`；200 响应完整写入非空缓存后第二次同文本零 HTTP。另覆盖取消→CancellationException、partial 删除、旧 finally 不清新 Call、非 2xx/空 body 关闭 response body。HTTP 失败只映射为状态类别异常，异常/typed 日志不得包含响应 body、Authorization 或 API key。AndroidAudioPlayerPort 只包装一个 MediaPlayer，并把 ASSISTANT_SPEECH 映射为与本地 TTS 相同的 AudioAttributes。

- [ ] **Step 5: 实现 Fish 和 Composite 终态**

Fish 网络阶段不持有焦点；拿到文件且即将 start 时才 request。focus request 普通异常与 denied 都统一成不持有系统资源的 denied/no-op lease并继续播放，CancellationException 原样传播。每次 speak 只拥有一个 player 与一个 lease，并通过 operation ID/一次性门闩忽略迟到回调。Fish 对初始化/factory/player.start 等每个边界都先单独重抛 CancellationException，对其余 Exception 统一转 Result.failure，因此同步工厂/提交失败能触发 Composite 回退；实际取得的 lease 每个终态只 release/记录 RELEASED 一次。

Composite 自己使用 AtomicLong generation + Channel.UNLIMITED 单 actor 串行 Start、ChildTerminal、ChildCancelled、Cancel、HardStop、Shutdown。每个 operation 还拥有独立的 `AtomicBoolean invalidated`，并随 Start/child terminal 一起传递。`speak()` 创建 operationId/generation/invalidated/CompletableDeferred 后用 trySend 投递 Start；投递失败必须立即标记 invalidated 并以“engine 已 shutdown”的异常完成 deferred，不能继续 await 永挂。等待者被取消时，必须先把“仅本 operation”的 invalidated 从 false 置 true，再非挂起投递 Cancel，最后原样抛出 CancellationException，不能无条件递增全局 generation 而误杀后来请求。actor 消费 Start 时必须再次核对 closed=false、generation 仍 current、invalidated=false；任一不满足就以取消/关闭异常完成该 deferred，provider 调用为零。只有通过 final check 才选择 cloud 子操作，或在 cloud=null 时记录缺配置并启动 local 子操作。

每个 provider 子 Job 必须用一次性 terminal reporter：普通 Result 或普通异常规范化后投递带 operationId/generation/provider/result/invalidated 的 ChildTerminal；CancellationException 则用 `trySend`/`invokeOnCompletion` 非挂起地投递 ChildCancelled 后再重新抛出。actor 收到 ChildTerminal 或 ChildCancelled 时，先同时核对 activeOperationId、generation 与该 operation 的 invalidated；有效 ChildCancelled 以同一个 CancellationException 异常完成 deferred，绝不回退，确保 provider 自身取消不会留下永远未完成的调用。caller 取消即使晚于 ChildTerminal 入队，actor 也会先看到共享 invalidated=true，因而不能启动 local。

只有 actor 能记录 LOCAL_FALLBACK 并从 cloud 普通失败转入 local；任何调用线程、catch/finally 或子 Job 都不得直接调用 `local.speak()`。stop 在公开入口先递增 generation 并原子标记当前 active operation invalidated，再投递带代次的 HardStop；actor 取消对应 child、调用当时活跃 provider 的 stop，并以 CancellationException 完成旧 deferred。provider.stop 的普通异常单独转安全 diagnostic，finally 仍完成 deferred并保住 actor；CancellationException 不伪装成 FAILED。shutdown 全局递增 generation、原子置 closed、标记 active operation invalidated，再走同一 actor 状态机；cloud.shutdown 与 local.shutdown 用分离 try/catch/finally，第一方普通异常不能跳过第二方清理，最后才关闭命令通道并完成所有 pending deferred。shutdown/stop 都幂等。迟到 terminal 只记录/忽略，不能启动 local，也不能完成新请求。这一结构是唯一允许的实现，不保留串行直连的第二套路径。

- [ ] **Step 6: 运行全部 speech 测试并保持暂存区为空**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.speech.*"
if ($LASTEXITCODE -ne 0) { throw "Speech 全量测试失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/speech app/src/test/java/com/healthyagent/running/speech
if ($LASTEXITCODE -ne 0) { throw "Fish/Composite diff-check 失败" }
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
~~~

---

## Task 10：把 Android GATT 收窄为 BleTransport 并接入监督器

**Files:**

- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/BleTransport.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/AndroidKeepB5BleTransport.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattPort.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattBindingCoordinator.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattCallbackMapper.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattCallbackProcessor.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattDecisionExecutor.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattEventMapper.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/AndroidKeepB5GattPort.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5ScanPort.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/AndroidKeepB5ScanPort.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5DeviceMatcher.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5ConnectPermit.kt
- Create: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeyedConflatedEventQueue.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/BleTransportEventQueueTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5GattBindingTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5GattCallbackMapperTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5GattCallbackProcessorTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5GattDecisionExecutorTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5GattEventMapperTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5ScanGateTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5DeviceMatcherTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5ConnectPermitTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeyedConflatedEventQueueTest.kt
- Create: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5BleDataSourceTest.kt
- Modify: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5BleDataSource.kt
- Modify: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattCallback.kt
- Modify: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5B3Protocol.kt
- Modify: app/src/main/java/com/healthyagent/running/data/sensor/ble/BleReconnectBackoff.kt
- Modify: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5B3ProtocolTest.kt
- Preserve unchanged: app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5WorkoutSessionState.kt
- Preserve unchanged: app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5WorkoutSessionStateTest.kt

**Target signatures（仅列实施者必须遵守的 API，方法体在对应步骤按 TDD 实现）:**

~~~kotlin
internal sealed interface BleTransportEvent {
    data class ScanMatched(val guard: KeepB5AttemptGuard) : BleTransportEvent
    data class ScanFailed(
        val guard: KeepB5AttemptGuard,
        val reason: BleFailureReason,
    ) : BleTransportEvent
    data class ConnectFailed(
        val guard: KeepB5AttemptGuard,
        val reason: BleFailureReason,
    ) : BleTransportEvent
    data class GattCreated(val guard: KeepB5GattGuard) : BleTransportEvent
    data class GattConnected(val guard: KeepB5GattGuard) : BleTransportEvent
    data class ServicesDiscovered(
        val guard: KeepB5GattGuard,
        val channel: KeepB5ChannelKind,
    ) : BleTransportEvent
    data class NotificationsEnabled(
        val guard: KeepB5GattGuard,
        val channel: KeepB5ChannelKind,
    ) : BleTransportEvent
    data class StartWriteCompleted(
        val guard: KeepB5GattGuard,
        val accepted: Boolean,
    ) : BleTransportEvent
    data class SyncWriteCompleted(
        val submission: KeepB5SubmittedWrite,
        val accepted: Boolean,
    ) : BleTransportEvent
    data class GattDisconnected(
        val guard: KeepB5GattGuard,
        val reason: BleFailureReason?,
    ) : BleTransportEvent
    data class GattError(
        val guard: KeepB5GattGuard,
        val reason: BleFailureReason,
    ) : BleTransportEvent
    data class DeviceInfoUpdated(
        val guard: KeepB5GattGuard,
        val deviceInfo: DeviceInfo,
    ) : BleTransportEvent
}

internal interface BleTransport {
    val controlEvents: Flow<BleTransportEvent>
    val privateActivities: Flow<PrivateWorkoutActivity>
    val samples: Flow<TokenizedSensorSample>

    fun startScan(guard: KeepB5AttemptGuard): Boolean
    fun stopScan(guard: KeepB5AttemptGuard, preserveMatchedTarget: Boolean)
    fun connectRemembered(guard: KeepB5AttemptGuard): Boolean
    fun cancelPendingConnect(guard: KeepB5AttemptGuard)
    fun activateGattData(guard: KeepB5GattGuard)
    fun quiesceGattCallbacks(guard: KeepB5GattGuard): Job
    fun sendStart(guard: KeepB5GattGuard, epochSeconds: Long): Boolean
    fun sendWorkoutUpdate(submission: KeepB5SubmittedWrite, elapsedSeconds: Long): Boolean
    fun sendHeartbeat(submission: KeepB5SubmittedWrite): Boolean
    fun closeGatt(guard: KeepB5GattGuard, disconnectFirst: Boolean): Job
    fun shutdown(): Job
}

internal interface ReconnectBackoffPolicy {
    fun nextDelayMillis(): Long
    fun reset()
}

// 现有实现保留原类名与构造器，只实现可注入窄接口。
internal class BleReconnectBackoff(
    private val initialDelayMillis: Long,
    private val maxDelayMillis: Long,
) : ReconnectBackoffPolicy {
    var attempt: Int = 0
        private set

    init {
        require(initialDelayMillis > 0L)
        require(maxDelayMillis >= initialDelayMillis)
    }

    override fun nextDelayMillis(): Long {
        val exponent = attempt.coerceAtMost(MAX_SAFE_EXPONENT)
        val multiplier = 1L shl exponent
        val delayMillis = if (initialDelayMillis > maxDelayMillis / multiplier) {
            maxDelayMillis
        } else {
            (initialDelayMillis * multiplier).coerceAtMost(maxDelayMillis)
        }
        if (attempt < Int.MAX_VALUE) attempt++
        return delayMillis
    }

    override fun reset() {
        attempt = 0
    }

    private companion object {
        const val MAX_SAFE_EXPONENT = 62
    }
}

internal enum class ScanAvailability { READY, PERMISSION_MISSING, ADAPTER_DISABLED }

internal interface KeepB5ScanPort {
    fun availability(): ScanAvailability
    fun start(listener: Listener): Boolean
    fun stop()

    interface Listener {
        fun onMatch(target: KeepB5ConnectTarget)
        fun onFailure(reason: BleFailureReason)
    }
}

internal object KeepB5DeviceMatcher {
    val knownPrefixes: List<String> = listOf("Keep B5", "KeepB5", "Keep_B5", "KEEP")
    fun matches(advertisedName: String?, safeDeviceName: () -> String?): Boolean
}

internal interface KeepB5ConnectTarget

internal class KeepB5ConnectPermit {
    fun tryStart(block: () -> Unit): Boolean
    fun cancel()
    fun isCancellationRequested(): Boolean
}

internal class KeepB5GattRegistrationGate {
    fun activate(): Boolean
    fun close(): Boolean
    suspend fun awaitActivation(): Boolean
    fun tryAcquireExecution(): Boolean
    fun isActive(): Boolean
}

internal data class GattCharacteristicSnapshot(
    val uuid: UUID,
    val properties: Int,
)

internal data class GattServiceSnapshot(
    val characteristicsByService: Map<UUID, List<GattCharacteristicSnapshot>>,
)

internal sealed interface RawGattCallbackEvent {
    data class ConnectionStateChanged(
        val status: Int,
        val newState: Int,
    ) : RawGattCallbackEvent
    data class ServicesDiscovered(
        val status: Int,
        val services: GattServiceSnapshot,
    ) : RawGattCallbackEvent
    data class DescriptorWrite(
        val status: Int,
        val serviceUuid: UUID,
        val descriptorUuid: UUID,
        val characteristicUuid: UUID,
    ) : RawGattCallbackEvent
    data class CharacteristicChanged(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val value: ByteArray,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattCallbackEvent
    data class CharacteristicRead(
        val status: Int,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val value: ByteArray,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattCallbackEvent
}

internal sealed interface GattCallbackDecision {
    data class Emit(val signal: RawGattSignal) : GattCallbackDecision
    data object DiscoverServices : GattCallbackDecision
    data class EnableNotifications(
        val channel: KeepB5ChannelKind,
        val fallbackOnFailure: KeepB5ChannelKind? = null,
    ) : GattCallbackDecision
    data class NotificationDescriptorResult(
        val channel: KeepB5ChannelKind,
        val accepted: Boolean,
    ) : GattCallbackDecision
    data object ReadDeviceMetadata : GattCallbackDecision
    data object ReadFirmware : GattCallbackDecision
    data class UpdateBattery(
        val percent: Int?,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : GattCallbackDecision
    data class UpdateFirmware(val version: String?) : GattCallbackDecision
}

internal enum class GattIoSubmission { ACCEPTED, REJECTED, UNAVAILABLE }

internal interface KeepB5GattIoPort {
    fun discoverServices(): GattIoSubmission
    fun enableNotifications(channel: KeepB5ChannelKind): GattIoSubmission
    fun readBattery(): GattIoSubmission
    fun readFirmware(): GattIoSubmission
}

internal sealed interface RawGattSignal {
    data object Connected : RawGattSignal
    data class ServicesDiscovered(val channel: KeepB5ChannelKind) : RawGattSignal
    data class NotificationsEnabled(val channel: KeepB5ChannelKind) : RawGattSignal
    data class PrivateNotification(
        val frame: ByteArray,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattSignal
    data class StandardHeartRateNotification(
        val frame: ByteArray,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattSignal
    data class BatteryLevel(
        val percent: Int,
        val receivedAtMillis: Long,
        val wallClockMillis: Long,
    ) : RawGattSignal
    data class DeviceInfoUpdated(val deviceInfo: DeviceInfo) : RawGattSignal
    data class Disconnected(val reason: BleFailureReason?) : RawGattSignal
    data class Error(val reason: BleFailureReason) : RawGattSignal
}

internal sealed interface MappedGattOutput {
    data class Control(val event: BleTransportEvent) : MappedGattOutput
    data class Activity(val value: PrivateWorkoutActivity) : MappedGattOutput
    data class Sample(val value: TokenizedSensorSample) : MappedGattOutput
}

internal interface KeepB5GattSession {
    fun sendStart(epochSeconds: Long): Boolean
    fun sendWorkoutUpdate(elapsedSeconds: Long): Boolean
    fun sendHeartbeat(): Boolean
    fun shutdownCallbacks(): Job
    fun disconnect()
    fun close()
}

internal fun interface KeepB5GattConnector {
    fun connect(
        target: KeepB5ConnectTarget,
        registrationGate: KeepB5GattRegistrationGate,
        listener: KeepB5GattSessionListener,
    ): KeepB5GattSession?
}

internal interface KeepB5GattSessionListener {
    fun onSignal(session: KeepB5GattSession, signal: RawGattSignal)
}

internal class KeepB5GattCallbackMapper(
    private val duplicateWindowMillis: Long = 250L,
) {
    fun mapCallback(event: RawGattCallbackEvent): List<GattCallbackDecision>
}

internal class KeepB5GattDecisionExecutor(
    private val io: KeepB5GattIoPort,
    private val deviceName: String,
    private val signalSink: (RawGattSignal) -> Unit,
    private val diagnosticLogger: DiagnosticLogger,
    private val registrationGate: KeepB5GattRegistrationGate,
) {
    fun initialize()
    fun execute(decision: GattCallbackDecision)
}

internal class KeepB5GattCallbackProcessor(
    private val mapper: KeepB5GattCallbackMapper,
    private val executor: KeepB5GattDecisionExecutor,
    private val registrationGate: KeepB5GattRegistrationGate,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) {
    fun offer(event: RawGattCallbackEvent): Boolean
    fun shutdown(): Job
}

internal class KeepB5GattEventMapper {
    fun map(
        guard: KeepB5GattGuard,
        signal: RawGattSignal,
    ): List<MappedGattOutput>
}

internal interface KeepB5GattBindingSink {
    fun control(event: BleTransportEvent): Boolean
    fun activity(value: PrivateWorkoutActivity): Boolean
    fun sample(value: TokenizedSensorSample): Boolean
}

internal class KeepB5GattBindingCoordinator(
    private val connector: KeepB5GattConnector,
    private val scope: CoroutineScope,
    private val connectDispatcher: CoroutineDispatcher,
    private val newGattToken: () -> Long,
    private val eventMapper: KeepB5GattEventMapper,
    private val sink: KeepB5GattBindingSink,
    private val diagnosticLogger: DiagnosticLogger,
) {
    fun connect(guard: KeepB5AttemptGuard, target: KeepB5ConnectTarget): Boolean
    fun cancelPendingConnect(guard: KeepB5AttemptGuard)
    fun activateGattData(guard: KeepB5GattGuard)
    fun quiesceGattCallbacks(guard: KeepB5GattGuard): Job
    fun sendStart(guard: KeepB5GattGuard, epochSeconds: Long): Boolean
    fun sendWorkoutUpdate(submission: KeepB5SubmittedWrite, elapsedSeconds: Long): Boolean
    fun sendHeartbeat(submission: KeepB5SubmittedWrite): Boolean
    fun close(guard: KeepB5GattGuard, disconnectFirst: Boolean): Job
    fun shutdown(): Job
}

internal class KeyedConflatedEventQueue<K : Any, V : Any>(
    private val merge: (pending: V, incoming: V) -> V = { _, incoming -> incoming },
) {
    val values: Flow<V>
    fun offer(key: K, value: V): Boolean
    fun close()
}

internal class AndroidKeepB5ConnectTarget(
    val device: BluetoothDevice,
) : KeepB5ConnectTarget

internal class AndroidKeepB5GattConnector(
    private val context: Context,
    private val monotonicMillis: () -> Long,
    private val wallClockMillis: () -> Long,
    private val processorScope: CoroutineScope,
    private val processorDispatcher: CoroutineDispatcher,
    private val diagnosticLogger: DiagnosticLogger,
) : KeepB5GattConnector

internal class AndroidKeepB5ScanPort(
    private val context: Context,
) : KeepB5ScanPort

internal class AndroidKeepB5BleTransport(
    private val scanPort: KeepB5ScanPort,
    private val gattConnector: KeepB5GattConnector,
    private val scope: CoroutineScope,
    private val connectDispatcher: CoroutineDispatcher,
    private val newGattToken: () -> Long,
    private val diagnosticLogger: DiagnosticLogger,
) : BleTransport
~~~

现有 `BleReconnectBackoff` 保持类名、构造器与算法不变，只给 `nextDelayMillis`/`reset` 加 `override`；既有 `BleReconnectBackoffTest` 继续直接覆盖这个实现，data source 测试 fake 才实现 `ReconnectBackoffPolicy`，不另造第二套 backoff。

AndroidKeepB5BleTransport 内部持有 `KeepB5AttemptGuard -> KeepB5ConnectTarget` 与 `KeepB5GattSession identity -> KeepB5GattGuard` 的映射；每个回调必须回显创建操作时捕获的完整 guard，禁止在消费回调时读取 current epoch 再包装旧 token。控制事件和 TokenizedSensorSample 分别走自己的 Channel.UNLIMITED FIFO；心率样本继续保持逐样本语义，不做 conflate，旧 A 样本由 supervisor guard 丢弃，绝不能挤掉或覆盖 B。只有 PrivateWorkoutActivity 使用按 KeepB5GattGuard keyed-conflate 的队列。事件只携带 guard、typed value 和 enum，不把 Android handle 暴露给 supervisor。扫描回调只能发布 ScanMatched；由 data source 把它映射为 KeepB5SessionEvent.ScanMatched，再且仅在 supervisor 返回 ConnectRemembered action 时调用 transport.connectRemembered。transport 绝不能在 scan callback 内自行 connect，旧扫描结果因此无法越过 supervisor 门禁。

attempt→target 只是 ScanMatched 到 ConnectRemembered 之间的一次性交接：connectRemembered 在同步临界区按完整 guard 取出并删除 target，再把局部强引用交给 connect dispatcher。ScanMatched action batch 的 `StopScan(preserveMatchedTarget=true)` 只停扫描/终止 gate/取消 timeout，必须保留该 guard target 供紧随其后的 ConnectRemembered take；ScanFailed、新 attempt、RunStopped/ExplicitDisconnect 与 shutdown 使用 false 并删除仍未取走的匹配 entry。新 attempt 必须重新扫描，不允许复用旧 target，映射不会跨重连累积。BleTransport 不保留无调用者的 forgetDevice API。

`KeepB5GattPort.kt` 中的 target/session/connector/listener 接口是 JVM 可 fake 的唯一 Android GATT 窄边界；`AndroidKeepB5GattPort.kt` 提供 AndroidKeepB5ConnectTarget、AndroidKeepB5GattConnector 与内部 AndroidKeepB5GattSession。每次 connect 都创建独立 session。原 KeepB5GattCallback 中的 discovered characteristics、privateWorkoutStarted、requestId、batteryPercent 和 write target 全部迁入该 session 实例，禁止多个 GATT 共用这些可变字段。

Android callback 自身只防御性复制 status、newState、service UUID、characteristic UUID、descriptor UUID、完整的 service→characteristic(UUID+properties) snapshot 与 ByteArray；对 notification/read 还在 callback 入口各采样一次 monotonic receivedAtMillis 和 wallClockMillis 并写进 RawGattCallbackEvent，然后仅用 `offer` 投入该 session 独占的 Channel.UNLIMITED `KeepB5GattCallbackProcessor`。每个 connect binding 在调用 connector 前创建唯一 KeepB5GattRegistrationGate，并把同一实例传给 Android session、processor、executor 与 binding sink；状态只能原子地 `PENDING → ACTIVE → CLOSED` 或 `PENDING → CLOSED`，CLOSED 永不重开。processor 的单 actor 在 PENDING 时只允许 raw callback 排队；`awaitActivation()` 返回 true 只表示 activation 曾经赢过，actor 仍必须立即重查 isActive，确认仍为 ACTIVE 才先且仅先调用一次 gated executor.initialize，再逐个执行 `mapper.mapCallback(raw)` 的完整 decision batch。若先 CLOSED，或 activation 后、actor 获得调度前又 CLOSED，均丢弃队列且 initialize/map/I/O/sink 全部为零。只有 supervisor 已接受 GattCreated 后产生的 `ActivateGattData(exactGuard)` 能把对应 gate 置 ACTIVE。

mapper 的 250ms 去重，以及 executor 的 notification state、attempted set、battery/firmware snapshot 都严格 actor-confined，禁止 Android callback 线程直接调用 mapper/executor，也不另加可并发的旁路。`shutdown(): Job` 先让同一个 gate CLOSED，使后续 offer=false，再关闭 command channel并取消 actor，然后返回可 join 的唯一 actor Job。每个真实 Android I/O 前，executor 必须调用同一 registrationGate.tryAcquireExecution；它与 close 的状态转换在同一个 AtomicReference 上线性化。permit 成功即定义该单次非挂起调用已经开始，必须在同一 actor 调用栈立即调用 I/O，中间禁止 suspend、yield、launch 或 dispatcher hop；关闭先赢则 permit=false 且真实调用为零，permit 先赢则只允许这一调用返回。每个 decision、signalSink 前以及每个可能同步回调的 I/O 返回后仍检查 isActive，关闭后不得再 fallback、metadata I/O 或 sink；actor Job 真正完成前 transport 不关全局队列。测试注入 StandardTestDispatcher 控制 actor。

每个 session 拥有一个独立的纯 Kotlin KeepB5GattCallbackMapper；它是唯一识别 GATT_SUCCESS/STATE_CONNECTED/STATE_DISCONNECTED、B5 private/标准心率/电量/固件 service+characteristic+properties 组合与 CCCD UUID 的层。notification/read 各自保留现有 250ms 去重语义，key 为 serviceUuid+characteristicUuid+payload，使用 Raw event 的 monotonic receivedAtMillis；API 33+ 与 legacy 对同一数据的双回调只产生一次 decisions，而 A/B processor/mapper 的去重状态互不共享。mapper 和 executor 必须把这两个时间原样穿透到 RawGattSignal，之后任何 actor/queue/consumer 都不得重采。

通道选择保持现有 standard-first：标准 HR measurement 必须位于标准 HR service 且支持 NOTIFY/INDICATE；PrivateB3 只有 private command 位于 private service且支持 WRITE_NO_RESPONSE、private notify 位于同 service且支持 NOTIFY/INDICATE 才算完整。两者同时有效时 mapper 输出 `EnableNotifications(STANDARD_HR, fallbackOnFailure=PRIVATE_B3)`；只有 private 有效才直接选择 private；都无效则 Error。DescriptorWrite mapper 只校验 CCCD 的 service/owner characteristic 并输出 `NotificationDescriptorResult(channel, accepted)`，绝不自行附加 NotificationsEnabled、ReadDeviceMetadata 或 fallback。KeepB5GattDecisionExecutor 每个 session 维护 attempted notification channel set 和单调状态 `NONE → STANDARD_PENDING → STANDARD_READY` 或 `NONE/STANDARD_PENDING → PRIVATE_PENDING → PRIVATE_READY`，失败终态为 TERMINAL_ERROR。执行首次 Enable 时先 Emit(ServicesDiscovered(channel)) 再提交 I/O；standard 同步 REJECTED/普通异常或当前 STANDARD_PENDING 的 descriptor failure 都至多触发一次 private fallback，private 缺失/属性错误/再次失败才 Emit Error，不重复订阅。

只有 status=success、descriptor=CCCD 且 service+owner characteristic 组合匹配时，mapper 才产生 accepted=true 的 descriptor result；错误 status 产生同 channel accepted=false，错误 owner 直接忽略。executor 仅接受与当前 PENDING channel 相同的 result：STANDARD_PENDING success 原子转 STANDARD_READY，随后精确 Emit(NotificationsEnabled(STANDARD_HR)) 并启动一次 ReadDeviceMetadata；PRIVATE_PENDING success 转 PRIVATE_READY，仅 Emit(NotificationsEnabled(PRIVATE_B3))，绝不启动 metadata read，START 仍只能由 supervisor 后续 SendStart 发起。切到 PRIVATE_PENDING/READY 后迟到的 standard success/failure 都无副作用；STANDARD_READY 后迟到 standard failure 也不得 fallback；重复 result 同样 no-op。private/standard characteristic change 匹配 service 后才复制成对应 notification signal；电量 read 成功时无论 payload 是否有效都输出 nullable UpdateBattery，随后 ReadFirmware，read status 失败则不改旧值但仍 ReadFirmware；固件 read 成功时输出 nullable UpdateFirmware。session 独占 batteryPercent 与 firmwareVersion，执行任一 Update 后用自己的两个字段构造并 Emit(DeviceInfoUpdated)；有效电量还额外 Emit(BatteryLevel)，继续保留现有 BATTERY SensorSample 行为，无效值会清空本 session 旧字段且不发电量 sample。非成功连接/发现 status 规范化为 Error/Disconnected。Android adapter 只执行这些 decisions，不再自行推断协议或发送 START。

每个 AndroidKeepB5GattSession 创建并拥有一个生产 KeepB5GattCallbackProcessor、KeepB5GattDecisionExecutor 和一个实现 KeepB5GattIoPort 的 Android I/O adapter；AndroidKeepB5GattConnector 必须从 transport 生产构造链显式接收同一个 DiagnosticLogger，以及 processorScope/processorDispatcher，并把 binding 传入 connect 的同一个 KeepB5GattRegistrationGate 逐 session 下传，禁止 session 自建默认 ACTIVE gate、NoOp logger 或不可收口的全局 scope。batteryPercent、firmwareVersion、attempted notification channels 与所有 decision 执行状态都只存在该 executor/session actor 内，因此 JVM 测试可以直接验证生产 processor/executor 而不是 fake 自己。session 的 `shutdownCallbacks()` 必须同步关闭同一个 registration/processor offer gate 并返回 processor.shutdown() 的同一个 Job，重复调用返回同一 Job。processor 只有在 activation 后、接受任何 raw callback 的 mapper/decision 前才执行一次 `initialize()`，发布 `DeviceInfoUpdated(DeviceInfo(name=deviceName, batteryPercent=null, firmwareVersion=null))`；binding 若尚未拿到 gattToken 时产生的同步 Connected 等 raw callback 只在 session processor FIFO 排队，等 GattCreated 被接受并 activation 后按原序处理。这样新当前 GATT 即使选择 PrivateB3 或 standard metadata 永不回调，也会清掉 data source 中上一设备的 name/battery/firmware snapshot，同时未被 supervisor 接受的 session 绝不会 discover/subscribe/read。Emit 原样进入该 session listener；UpdateBattery/UpdateFirmware 先更新 nullable session 字段，再 Emit 自身完整 DeviceInfo snapshot，有效电量另外 Emit BatteryLevel。DiscoverServices/EnableNotifications 的同步 REJECTED/普通异常分别记录 DISCOVER_SERVICES/ENABLE_NOTIFICATIONS 的 BleOperationFailed，再按上面的 fallback/error 规则收口；CancellationException 原样传播。共享 GattIoSubmission 必须穷举：DiscoverServices 的 UNAVAILABLE 与 REJECTED 都只 Emit Error(GATT_ERROR)，其中只有 REJECTED/throw 具备 DiagnosticFailure 并记录 BleOperationFailed；STANDARD_HR enable 的 UNAVAILABLE/REJECTED/throw 都走同一个 once-only private fallback，PRIVATE_B3 enable 的三者都只 Emit Error(GATT_ERROR)。任何分支都不能等待不存在的 callback。

ReadDeviceMetadata 还必须收口“没有 callback”的提交失败：readBattery 返回 UNAVAILABLE 时直接尝试 ReadFirmware；返回 REJECTED 或抛普通异常时记录 READ_BATTERY/BleOperationFailed 后恰好尝试一次 ReadFirmware。ReadFirmware 的 UNAVAILABLE 安全结束；REJECTED/普通异常只记录 READ_FIRMWARE/BleOperationFailed 并结束，不等待永远不会来的 callback、不杀死 effect/callback actor。所有 Android I/O 调用对 CancellationException 原样传播。metadata 更新只能经带本 GATT guard 的 DeviceInfoUpdated 回到 supervisor；PublishDeviceInfo 写入 KeepB5BleDataSource 自己持有的当前 deviceInfo snapshot，getter 不再读取废弃 KeepB5GattCallback 字段。切换/断开 GATT 时保留最后一次已发布 snapshot 供 UI 展示，只有新当前 GATT 的 PublishDeviceInfo 才替换；旧 A 的迟到更新由 guard 丢弃。

KeepB5GattEventMapper 再把 guard、协议解析与 signal 已携带的两个时间组合为 MappedGattOutput，绝不调用 sendStart，也不读取任何 clock。signal.receivedAtMillis 只写入 PrivateWorkoutActivity/TokenizedSensorSample.receivedAtMillis，signal.wallClockMillis 只写入公开 SensorSample.timestampMillis；两种时钟禁止互换。PrivateNotification 合法无心率时输出一个 Activity、零 Sample；含 79 时输出同一 receivedAtMillis 的一个 HEART_RATE Sample，source 固定 `keep_b5_private`。StandardHeartRateNotification 只输出一个 source=`keep_b5_standard` 的 HEART_RATE Sample；BatteryLevel 输出一个同样 source=`keep_b5_standard` 且双时钟正确的 BATTERY sample。type、value、timestampMillis 与 source 都是既有 SensorDataSource 公共合同，不能只断言 type。未知 private 响应输出空列表。Connected/services/CCCD/disconnect/error/deviceInfo 都映射为一个对应 Control output。

KeepB5GattBindingCoordinator 只依赖这些端口并负责 immutable guard 绑定；它在 onSignal 时让 eventMapper 使用 signal 已携带的双钟值，队列消费时不得重打时间戳。双钟只由 AndroidKeepB5GattConnector 创建的 callback adapter 注入；生产明确使用 `SystemClock.elapsedRealtime()` 与 `System.currentTimeMillis()`，测试分别注入可控函数。AndroidKeepB5BleTransport 组合 coordinator、control FIFO、sample FIFO 与 activity keyed-conflate。由于三条消费流彼此独立，仅仅先 trySend GattCreated 并不能形成消费顺序：每个 binding 在 connect 前创建上述默认 PENDING 的 registration gate，GattCreated 由 binding 自身先进入 control FIFO，processor.initialize、mapper、全部 control/data signal 与全部 discover/subscribe/read I/O 都不能越过 gate。data source 接受 GattCreated、更新 supervisor 状态并在同一锁内同步执行 ActivateGattData 后，exact binding 才把 gate 置 ACTIVE，processor 随后按 session-local raw FIFO 顺序开始 initialize 与 callback decisions；sample 继续完整 FIFO，activity 继续通过 receivedAtMillis-max keyed-conflate。控制 collector 尚在处理 GattCreated 时无法消费后续 control FIFO，因此顺序严格是 GattCreated→DeviceInfo→Connected。旧 guard activation 为 no-op；stale GattCreated 必须产生自身 exact CloseGatt cleanup 而不 activation。close、attempt cancellation 或 shutdown 先把 gate 置 CLOSED 并让 processor 丢弃尚未执行的 raw callback，不能释放到另一 session。JVM 测试只用 FakeTarget/FakeGattSession，不 mock final Android 类。`transport.shutdown(): Job` 必须先在一个锁内标记 closed、终止 active scan/target/connect intake、cancel 全部 pending permits，并对资源注册表中的全部 binding 同步执行 gate close + callback shutdown（不只是 supervisor 当前 GATT），随后才在独立 teardown scope 逐个通过同一个 once-only close helper 以 disconnectFirst=true 清理；该 helper 复用并 join 已取得的 callback Job，再等待 connect dispatcher jobs 收口，最后才关闭 control/activity/sample 队列并完成共享 shutdown Job。重复 shutdown 返回同一个 Job；正在 connect 但尚未绑定的 gate 也先 CLOSED，迟到 handle 构造出的 processor 永不执行 callback，并由 closed binding 路径单独关闭一次。

KeepB5ScanPort 是扫描、权限与 adapter 状态的 JVM seam；AndroidKeepB5ScanPort 才接触 BluetoothLeScanner。KeepB5ScanGateTest 用 FakeScanPort 直接实例化生产 AndroidKeepB5BleTransport，验证 terminal CAS、guard→target 保存、guard-specific stop 与 shutdown，而不是只测 FakeBleTransport。transport 在一个锁内持有唯一 activeScan(guard, terminalGate)：stopScan(A, ...) 只有在 activeScan.guard==A 时才移除注册并调用 port.stop；B 已接替 activeScan 后的迟到 StopScan(A) 必须 no-op，不能清 B gate 或停 B 扫描。preserveMatchedTarget 只控制同 guard 已匹配 target 是否留给 ConnectRemembered，不放宽 active guard 检查。生产 Context 构造器只负责创建 AndroidKeepB5ScanPort/AndroidKeepB5GattConnector、私有 scope、双时钟和一个 transport 级共享 `AtomicLong`；所有并发 GattBinding 都用同一个 `incrementAndGet` 分配全局唯一 gattToken，不能依赖 binding 局部锁。

AndroidKeepB5ScanPort 对每个 ScanResult 先取 `scanRecord?.deviceName`；只有它为 null 才通过捕获 SecurityException 的安全读取取得 `device.name`，再交给纯 KeepB5DeviceMatcher。matcher 对 `advertisedName ?: safeDeviceName` 使用四个既有前缀 `Keep B5`、`KeepB5`、`Keep_B5`、`KEEP` 做 ignoreCase startsWith；两者都 null、空串或非前缀均不 match。只有 match 才调用 listener.onMatch，Android adapter 不复制另一套名称判断。

KeyedConflatedEventQueue 只用于 activity 且只允许一个 collector：首次 pending guard 决定 FIFO 位置，同一 guard 在消费前再次 offer 用注入的 merge 选择 receivedAtMillis 较大者、不移动位置；不同 guard 永不互相覆盖。不能用墙钟或 last-in-wins 合并，反序旧 callback 也不能覆盖 pending 新活动。close 先拒绝后续 offer（返回 false），再让既有 pending 值按顺序排空并使 values 正常完成。control sink 和 sample sink 都直写各自 Channel.UNLIMITED，不经过 keyed-conflate。

当前 B3 START/UPDATE_WORKOUT/HEART_BEAT 明确使用 `WRITE_TYPE_NO_RESPONSE`，所以本里程碑所说的 raw write result 是 Android `writeCharacteristic` 的同步提交返回值，不等待不保证存在的 `onCharacteristicWrite` ack。sendStart/sendWorkoutUpdate/sendHeartbeat 必须捕获 adapter 的 int/bool 返回值，在返回调用方之前先把同一个 accepted 值与创建时的完整 guard/submission 写入 StartWriteCompleted/SyncWriteCompleted FIFO；方法本身再返回该 accepted 值，只用于同批次立即门禁。这个 transport event 是 reducer completion 的唯一来源，调用方不得根据返回值再合成第二个 completion 或 LinkFailed。UPDATE 返回后才允许处理同批 HEART_BEAT，因此这已经等待并观察了 raw result；UPDATE=false 时不发送 HEART_BEAT，失败/重连状态只由已入队的 SyncWriteCompleted 驱动。每个 operationId 必须恰好产生一个 completion event。若后续把特征改成 WRITE_TYPE_DEFAULT，必须另开设计把异步 ack timeout 纳入监督器，本计划不混用两种语义。

三种 send 方法必须对 CancellationException 重新抛出，对 SecurityException/其余 Exception 规范化为 accepted=false；即使 adapter 抛异常，也要在返回或传播前以一次性 terminal gate 投递恰好一个对应 completion，并记录只含 BleOperation、BleFailureReason 与 DiagnosticFailure 的 BleOperationFailed。START/UPDATE/HEARTBEAT 的返回 false 与异常路径都不得泄漏 in-flight operation 或杀死 effect actor。

startScan 也由 transport 唯一收口：每个 attempt guard 有一次性 scan terminal gate，ScanMatched、立即 ScanFailed 与异步 onScanFailed 都必须 CAS 竞争同一个终态。只有赢得 gate 的 ScanMatched 能写入该 guard 的 remembered target 并发布一次事件；failure 先赢后，late match 不得保存 target 或触发连接。权限缺失、adapter disabled、底层 startScan 同步 false/throw 分别在返回 false 前发布一个 PERMISSION_MISSING/ADAPTER_DISABLED/SCAN_FAILED 的 ScanFailed；异步 onScanFailed 也只能赢 gate 一次。data source 只使用 Boolean 阻止继续本批动作，绝不再合成 ScanFailed。stopScan 匹配 active guard 后先使 terminal gate 终止，再调用 port.stop；普通异常记录 typed BleOperationFailed 后继续 effect actor，CancellationException 仍向上传播。15 秒扫描 timeout 由 data source 在 start accepted 后按 attempt guard 注册并直接回送 supervisor；和 transport terminal 竞态时靠 FIFO、terminal gate 与 attempt/phase 幂等，只能产生一次退避，绝不能停掉下一代扫描。

`connectRemembered` 每次调用都先创建一个独立 GattBinding、KeepB5GattRegistrationGate 和 KeepB5ConnectPermit；binding 实现 KeepB5GattSessionListener 并以闭包捕获 immutable attempt guard，Coordinator 自身绝不作为 A/B 共用 listener。该方法只在锁内原子 take target、登记 attempt→permit/binding 并向可注入 connect dispatcher launch，因此不阻塞 effect actor。permit 用 AtomicReference 实现 `PENDING → STARTED → COMPLETED`、`PENDING → CANCELLED` 或 `STARTED → CANCEL_REQUESTED → COMPLETED`；dispatcher task 在真正调用 connector 前用 tryStart 的 CAS 竞争唯一启动线性化点，CAS 成功后必须在同一 dispatcher 调用栈立即把 target、同一 registrationGate 与 listener 传入 connector.connect，中间禁止 suspend、yield、另一次 launch 或 dispatcher hop，但也绝不能把 connector.connect 整段放在 monitor 内。cancel 在任意状态都只做有限次 CAS 并立即返回：若 PENDING→CANCELLED 先赢，先关闭 registrationGate，dispatcher 放行后 connector.calls=0；若 STARTED 先赢，该 attempt 在语义上已于 stop 前启动，cancel 仍先关闭 gate、标记 CANCEL_REQUESTED 并在已有 session 时启动 callback shutdown，绝不等待 Android 调用返回。connector 返回/null/throw、同步 callback pending 回放和 bind 前后都重新检查 cancellation；取消后的 handle 因同一 gate 已 CLOSED 而不执行任何 callback decision/I/O，并通过 binding 自己的 once-only 异步 cleanup 关闭，任何 GattCreated/ConnectFailed/其他业务 signal 都不得发布。remembered target 缺失、coordinator/binding 已关闭、scope/dispatcher 当场拒绝启动时，必须在返回 false 前关闭 gate 并让 ConnectFailed 赢 terminal gate，data source 不二次合成。

每个未取消且真正进入 connector 的调用必须恰好收口为 GattCreated 或 ConnectFailed；已标记 CANCELLED/CANCEL_REQUESTED 的调用只收口为内部 LOCAL_CANCELLED。launch 已成功但 task 尚未执行时，普通生命周期变更 cancel permit 并把该 binding 记为 LOCAL_CANCELLED，不再发布会触发重连的 ConnectFailed，因为 supervisor 已经先行换代；STARTED 已先赢但 connector 尚未返回时规则相同，生命周期回调不等待它。生产 connect scope 除 transport.shutdown 外不得被任意取消。shutdown 同样先 cancel 全部 pending permits，并以共享 shutdown Job 在后台等待 dispatcher jobs/processor jobs 结束后再关 sink；公开 shutdown 返回的 Job 可由显式 teardown 等待，但 RunLifecycleAwareSensor 回调绝不 join。若 dispatcher task 在 transport 仍 open 且 permit 未取消时异常取消/拒绝执行，invokeOnCompletion 必须让唯一 ConnectFailed(GATT_ERROR) 赢 terminal gate，不能留下悬挂 attempt；已取消则只落 LOCAL_CANCELLED。callback 或 return path 首次拿到非空 session handle 时，通过该 binding 自己的一次性 bind 临界区绑定 handle，并从 transport 级共享 AtomicLong.incrementAndGet 分配新 gattToken；只有再次确认 permit 未取消后才先把 GattCreated 放入全局 control FIFO，再允许该路径发布初始 DeviceInfo/GattConnected 等 pending 信号。这满足“GATT 实例创建后才分配 token”，也保证同步 callback 不能越过 GattCreated，且并发 A/B binding 不会因为各自局部锁生成重复 token。未取消的 return null/throw 不生成 phantom gattToken，只发布带原 attempt guard 的 ConnectFailed 并恰好进入一次退避；取消后的 null/throw 不发布。若 connector 已开始后 RunStopped/epoch 才被接受，返回 handle 仍只带旧 guard 并被 binding cleanup 关闭；后续 stale action 只能按该 guard 清自己，不能查 current handle 后误关新连接。

每个 binding/bound session 有独立的一次性 quiesce/close 状态。`quiesceGattCallbacks(exactGuard)` 是 reducer-local 的同步非挂起操作：在短锁内让 registration gate 永久 CLOSED、调用该 session 的 `shutdownCallbacks()` 恰好一次并保存 callback Job；若 handle 尚未 bind，只关闭 gate，迟到 bind 必须立即启动并保存同一类 callback shutdown。它绝不 join、disconnect 或 close，未命中 exact guard 时返回已完成 Job，因此可安全位于 reducerDispatch。重复 quiesce 返回同一个 callback Job。`closeGatt` 先复用该 quiesce helper，再把 binding 换成同时保存 callback Job 与唯一 teardown Job 的 close tombstone并立即返回 teardown Job；若 reducer 已 quiesce，绝不能第二次 shutdownCallbacks。重复 close 从 tombstone 取得同一个进行中或已完成 teardown Job，不重复任何平台 I/O/processor shutdown，tombstone 保留到 transport shutdown。teardown Job 内按顺序执行可选 disconnect、必做 close，最后 join 已同步取得的 callback Job：disconnect 抛普通异常也必须继续 close，close 抛普通异常也必须继续 callback join；两种普通异常分别记录 DISCONNECT_GATT/CLOSE_GATT 的 BleOperationFailed，全部局部清理完成后才移除 active binding/session 引用。若任一步收到 CancellationException，必须先在不可取消的局部 cleanup 中完成余下 close、callback join 与 active 注册移除，再在 Job 边界原样重抛，不能把取消包装成普通失败，也不能遗留 callback actor。CancelPendingConnect 对同 attempt 的未激活 binding 同步做同一 quiesce，并把可能已有 handle 的平台 cleanup 投到 teardown scope；不可中断 connect 在 cancel/shutdown 后迟到返回 handle 时复用同一 helper 并异步等待，不阻塞生命周期回调。`transport.shutdown()` 也先同步 quiesce 所有 binding，再复用 close helper，收集并 join 全部返回 Job 与 connect dispatcher Job，确认所有 processor 完成后才关闭三条 sink 并清除 tombstone；重复 shutdown 返回同一个共享 Job。

- [ ] **Step 1: 写 fake transport 的竞态失败测试**

KeepB5BleDataSource 增加 internal 测试构造入口：

~~~kotlin
internal constructor(
    transport: BleTransport,
    scope: CoroutineScope,
    monotonicMillis: () -> Long,
    epochSeconds: () -> Long,
    reconnectBackoff: ReconnectBackoffPolicy,
    diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
)
~~~

测试精确覆盖：

- KeepB5GattCallbackMapperTest 直接从 raw scalar DTO 锁定 Android callback 语义：success+connected 精确得到 `Emit(Connected), DiscoverServices`；正确 UUID 若挂在错误 service 下必须忽略；snapshot 同时含合法 standard/private 时只先选 `EnableNotifications(STANDARD_HR, PRIVATE_B3)`，standard measurement 与 private command/notify 任一 properties 不合格时按完整性选择或 Error。标准/private CCCD success/failure 都只映射为对应 channel 的 `NotificationDescriptorResult(accepted=true/false)`，mapper 本身绝不输出 NotificationsEnabled、ReadDeviceMetadata、fallback 或 START/write。private/标准心率 change 防御性复制，错误 service 不映射；battery read success 输出 nullable UpdateBattery+ReadFirmware、failure 只 ReadFirmware，firmware read success 输出 nullable UpdateFirmware；disconnect/连接发现错误规范化。
- 同一 mapper 在 250ms 内收到 API 33+ 与 legacy 的相同 service+characteristic+payload notification/read 时只输出第一份，超过窗口再输出；A/B 两个 mapper 对相同 payload 各输出一次，证明 dedupe per-session。read 去重后 metadata→firmware chain 也只能启动一次。
- KeepB5GattCallbackProcessorTest 用真实 KeepB5GattRegistrationGate：PENDING 时 offer Connected/Services/descriptor/read 并推进 StandardTestDispatcher，只允许 raw FIFO 累积，initialize、mapper、discover/enable/read 与 signalSink 调用全部为 0；activate 后才按原 FIFO 处理且 initialize 恰好一次。PENDING 时先 close 则 awaitActivation=false、队列丢弃，后续 offer=false，activation 永远不能复活；另测 activate 成功但 actor 尚未调度时立即 close，随后推进 dispatcher 仍是 initialize/map/I/O/sink 全零。再用两个线程/barrier 并发 offer descriptor/read raw event，放行后仍按 channel actor 的线性化顺序逐个执行且每个 callback 的 decision batch 不穿插；同一 session 只订阅一次、fallback 一次、metadata chain 一次，battery/firmware snapshot 不撕裂。gate 住当前 I/O 后调用 shutdown：立即 gate CLOSED/offer=false，释放已经开始的单个 I/O 后不再发生 fallback/metadata/sink，join 返回的同一 Job 后 actor 才算完成；重复 shutdown 幂等。A/B 两个 gate/processor 的状态完全隔离。
- KeepB5GattDecisionExecutorTest 直接实例化生产 executor 时显式创建并 activate KeepB5GattRegistrationGate，禁止测试绕过 gate：initialize 恰好先发布一次 `DeviceInfo(name, null, null)`；discover 的 UNAVAILABLE/REJECTED/throw 都恰好 Error 一次，后两者记录 DISCOVER_SERVICES；standard enable 的 UNAVAILABLE/REJECTED/throw 或 STANDARD_PENDING descriptor failure 各只 fallback private 一次，private enable 的 UNAVAILABLE/REJECTED/throw 都恰好 Error 一次，REJECTED/throw 记录 ENABLE_NOTIFICATIONS；重复 fallback decision 不重复订阅。standard enable ACCEPTED 不碰 private。标准 pending success 只产生 NotificationsEnabled+一次 metadata，private pending success 只产生 NotificationsEnabled、没有 metadata；切入 private 后迟到 standard success 不读 metadata，迟到 standard failure不重复 fallback；standard ready 后迟到 failure 也不 fallback。ReadDeviceMetadata 的 battery UNAVAILABLE/REJECTED/throw 都恰好继续一次 firmware，后两者记录 READ_BATTERY；firmware UNAVAILABLE 安全结束，REJECTED/普通异常记录 READ_FIRMWARE 且 actor 可继续；先 Update 有效 metadata 再 Update null 会清空旧字段；每次更新 Emit 当前完整 DeviceInfo，有效电量额外 Emit BatteryLevel。另让 gate 在每个 I/O 前分别 close，断言 tryAcquireExecution=false 且对应真实 I/O/sink 为零；permit 已先赢时仅当前调用可返回，随后 decision 为零。两个 executor 的 channel/battery/firmware 状态完全隔离，A 的迟到 decision 不改变 B snapshot；CancellationException 原样传播。
- KeepB5GattEventMapperTest 锁定 RawGattSignal→MappedGattOutput 与双时钟：合法无心率 private frame 是一条 activity/零 sample；heartRate=79 是同一 monotonic receivedAt 的一条 activity/一条 HEART_RATE sample；标准心率只产生一条 sample；有效 BatteryLevel 继续产生一条 BATTERY sample；未知 START/HEART_BEAT 响应为空；disconnect/error/deviceInfo 各映射一个带原 guard 的 control event。构造 receivedAt=12_345、wallClock=1_700_000_000_000 的 signal 后，所有 TokenizedSensorSample.receivedAtMillis 必须是 12_345，所有 SensorSample.timestampMillis 必须是墙钟值；对 private HR、standard HR 与 battery 分别全对象断言 type/value/timestamp/source，source 精确为 `keep_b5_private` 或 `keep_b5_standard`。墙钟回拨序列也不能把 monotonic 值写进公开 timestamp，现有 sample pipeline 不得因时钟域混用把刚到的 HR 判 stale。
- KeepB5B3ProtocolTest 在删除过渡 wrapper 时同步删除/改写全部 parseWorkoutHeartRate 断言；0/301、合法无 HR 与坏包语义全部直接由 parseWorkoutData 的“非空 activity + nullable HR”或 null 结果锁定，测试源码不能残留旧符号。
- 用 raw callback 在 monotonic t=0/wallClock=W 生成 notification/battery event，gate 住 decision executor/event queue 到 t=12_001 才消费；最终 activity/sample.receivedAtMillis 仍为 0、SensorSample.timestampMillis 仍为 W，resume 按 12_001ms 年龄判 stale，证明中间层没有重采时钟。
- 暂停断开后按 backoff 重连并恢复冻结 UPDATE/HEART_BEAT。
- backoff 延续既有 1s→2s→4s…上限 30s：每次 current failure 安排重连时只消费一次 nextDelayMillis；连续失败严格递增。ExplicitConnect 新会话、ExplicitDisconnect/RunStopped 终止会话，以及 StandardHr Ready/PrivateB3 首个合法 activity 进入 Ready 时 reset；仅开始一次 retry scan 不 reset。表测“失败→失败→Ready→再断开”为 1s、2s、reset、1s，并测 stop 后新 explicit connect 从 1s 开始。
- startScan 的权限缺失、adapter disabled、同步 false/throw 与异步 onScanFailed 各只产生一个带 attempt guard 的规范化 ScanFailed 和一次退避；stopScan 抛普通异常只记录 BleOperationFailed，effect actor 继续处理后续 stop/close。
- KeepB5DeviceMatcherTest 对四个前缀及大小写/后缀逐一为 true，对 null/空/非 B5 为 false；advertisedName 非空时优先使用它，只有为 null 才使用 safeDeviceName。AndroidKeepB5ScanPort seam 测 safe device name 抛 SecurityException 时按 null 处理且不 onMatch。
- BeginScan(A) accepted 后 14_999ms 无事，15_000ms 回送带 A guard 的 SCAN_TIMEOUT 并只退避一次；match/failure/StopScan(A) 会取消 A timeout，推进时钟不再产生事件。A timeout 已排队但 B 扫描已 active 时只记 stale，不调用 port.stop、不取消 B timeout、不触发第二次退避。
- 同一 scan gate 中 ScanMatched(A) 先赢后，第二个 match(B) 与 onScanFailed 均零新增事件且 remembered target 仍为 A；ScanFailed 先赢后，late match 不保存 target，后续 ConnectRemembered 真实调用为零。
- A 扫描结束后 B 已成为 activeScan 时，迟到 StopScan(A) 不增加 scanPort.stopCalls、不清 B terminal gate；B 的 match 仍能正常赢 gate 并发布。只有 StopScan(B) 才能停止这次扫描。
- 旧扫描结果、旧扫描 timeout、旧 reconnect 不启动新连接。
- fake connectGatt 在 return 前同步触发 connected callback 时，activation 前 control FIFO 仍只有同一 captured guard 的 GattCreated，discoverServices/其余 GATT I/O 为 0；supervisor 接受 GattCreated 并同步 ActivateGattData 后，才严格追加初始 DeviceInfoUpdated(name,null,null)→GattConnected，并开始 discoverServices。
- processor.initialize 或同步 callback 在 connectGatt return/bind 前产生的 raw event 只留在该 session processor FIFO；绑定分配 token 后但 GattCreated 未消费时继续不得 initialize/map/I/O/sink。绑定被接受并 activation 后 control 总顺序必须是 GattCreated→初始 DeviceInfoUpdated(name,null,null)→其余同步 callback 映射；bind failure/cancel/shutdown 先关 gate并清空 raw FIFO，不能泄漏到另一 session。
- 让 activity/sample collectors 先运行并 gate 住 control collector：同 binding 在 GattCreated 尚未被 reducer 接受时注入 Connected、Services、两个 private notification、battery/firmware read callback，推进全部 processor dispatcher 后 initialize、mapper、discover/enable/read、control/activity/sample sink 调用仍全部为 0。接受后同步 ActivateGattData，再放行 processor；samples 按 raw FIFO 全部出现，activity consumer 暂停期间由 keyed-conflate 只保留 receivedAtMillis 最大的值。stale GattCreated、RunStopped、close/cancel/shutdown 均永不执行尚未 activation 的 raw callback；A 的 activation 不能释放 B，反之亦然。
- 另一路先正常 activation，再用 effectGate 卡住 CloseGatt 的平台 teardown；同步接受 RunStopped 后、effect actor 尚未执行 CloseGatt 时注入 Connected/Services/descriptor/read/notification callback，断言 registration gate 已 CLOSED、offer=false，discover/enable/read 与全部 signal/global sink 均零新增。随后释放 teardown，只复用 reducer 时已启动的 callback Job，shutdownCallbacks 总计一次。
- A/B 两个 connect 都在 return 前触发 callback，再以 B→A 反序放行 return；每个独立 GattBinding 仍生成自己的 token 并回显各自 attempt guard，不能把 A callback 绑定到 B。
- 用 barrier 让 A/B GattBinding 并发首次绑定，生产共享 AtomicLong.incrementAndGet 返回的两个 gattToken 必须 distinct；不能由两个 binding 的局部锁各自产生相同 token。
- 用 gate 卡住 connectGatt 返回，在此期间接受 RunStopped 并递增 epoch；gate 放行后旧 handle 只被关闭一次，不能发布当前连接或关闭随后建立的新 handle。
- connectGatt return null 或 throw 都只产生一个带原 attempt guard 的 ConnectFailed、零 gattToken，并进入一次退避。
- remembered target missing、binding/coordinator closed、dispatcher launch rejected 三种 connect 前失败都在返回 false 前产生唯一 ConnectFailed；data source 不补第二次事件，也不会停留在 GATT_CONNECTING。
- KeepB5ConnectPermitTest 用 barrier 锁定二选一线性化：cancel 先赢 PENDING 时释放 dispatcher 后 block 调用为 0；tryStart 的 CAS 先赢后 gate 住 block，cancel 仍立即返回并把状态置为 CANCEL_REQUESTED，释放后 block 最多调用一次。相同 guard 重复 cancel 幂等，不影响另一 attempt permit。
- transport/data source 用 dispatcher gate 卡在 launch accepted、task 尚未执行处，随后接受 RunStopped 或生成新 attempt；reducerDispatch 内 CancelPendingConnect 必须立即完成，放行 dispatcher 后 connector.calls=0、binding/permit 注册清空且不生成重连事件。反向竞态让 STARTED 先赢并 gate 住 connector：onRunStopped future 必须在 gate 未释放时已经完成，随后返回的旧 handle 被异步 disconnect/close 各一次、业务 event 为零；新 attempt 无需等待旧 Android 调用即可建立自己的独立 permit/binding。
- launch accepted 后 connect Job 在执行 block 前异常取消：transport 仍 open 时 invokeOnCompletion 发布唯一 ConnectFailed 且 supervisor 只退避一次；transport.shutdown 触发的取消则由 shared shutdown Job 终结 attempt、零业务 event，join 完成后无 permit/binding/job 残留。
- 真实 ScanMatched action batch 严格执行 `StopScan(A, preserve=true)→ConnectRemembered(A)`，后者仍能原子取到 A target 并发起一次 connect；take 后第二次调用以及新 attempt 都得到 missing/ConnectFailed。`StopScan(preserve=false)`、ScanFailed/RunStopped/ConnectFailed/shutdown 后 target map 为空，不能跨重连复用或累积。
- adapter 先后绑定 A/B 两个 handle 后，迟到的 A activity/sample/control event 仍回显 A 的 immutable guard；不能在回调时查询 current binding 而伪装成 B。
- B activity 已入 keyed-conflate、随后 late A activity 在 collector 读取前到达时，B 仍被消费并进入 Ready，A 只记 stale；sample FIFO 中 B 心率后到 late A 心率时两条都按到达顺序保留，supervisor 只发布 B、丢弃 A，不能通过 conflate 让任一条消失。
- activity callback 在 t=0 入队、到 t=12_001 才消费时仍携带 receivedAtMillis=0；resume 必须按 12_001 毫秒年龄判 stale，而非按消费时刻判健康。
- queue 层在 collector 读取前对同一 guard 先 offer receivedAtMillis=10_000、再 offer 9_000，最终只能输出 10_000 一条；这条测试必须直接锁定 merge，不得仅依赖 reducer 的 maxOf。
- 同一 guard 的 receivedAtMillis=10_000 活动先消费、9_000 的迟到活动后消费时，lastPrivateActivityAtMillis 仍为 10_000；resume 不得因时间倒退误判 stale。
- 每个 FakeGattSession 使用不同 command target/requestId/battery/firmware；B active 后 late A service discovery、notification、battery 和 firmware 回调不能改变 B 后续写入 target/request sequence，也不能触发 PublishDeviceInfo 覆盖 B。电量读取完成或失败后都继续请求该 session 的 firmware；最终 B DeviceInfo 同时保留自己的 batteryPercent 与 firmwareVersion。
- data source 先公开 A 的完整 DeviceInfo，再绑定新 B：B processor.initialize 的首条 DeviceInfo(nameB, null, null) 必须在 GattCreated 后通过当前 guard 发布并立即替换 A；B 走 PrivateB3 或 standard metadata 永不回调时 getter 都保持 B/null/null，绝不能永久显示 A。STOPPED + ExplicitConnect 场景同样允许这次替换与后续心率公开。
- 旧 GATT error 只关闭旧 token，不关闭当前 token。
- 旧 gattToken 的 PrivateWorkoutActivity/TokenizedSensorSample 不刷新 activity、不进入 Ready、不发布 SensorSample，只允许清理事件自身旧 GATT。
- 暂停前排队的 UPDATE 在 lifecycleRevision 变化后不写 transport。
- 用虚拟时钟覆盖精确竞态：Running poll 得到旧 revision 的 UPDATE 后 pause，暂停 60 秒且不让 scheduler poll，再 resume；迟到旧 SyncDue 整批拒绝，resume 临界区内已执行 ResetSyncUpdateAccounting。随后立即 poll 最多只允许发送保留 cadence 的 HEART_BEAT，不能发送 UPDATE；+999ms 仍无 UPDATE，满 +1_000ms 才以 resumedElapsedSeconds + 1 发送唯一 UPDATE，绝不能一次跳增 60 秒。
- 用 effectGate 卡住 BeginScan、ConnectRemembered、SendStart、StartSync、ArmReadyTimeout、ScheduleReconnect，期间同步接受 RunStopped/新 attempt，再放行后各真实 port/timer 调用均为 0 且记录 CONTROL_ACTION stale。cleanup 另按资源身份断言：旧 CloseGatt(A) 只会关闭仍登记的 A；若 activeScan 仍是 A，StopScan(A) 执行一次，若 B 已接替则迟到 StopScan(A) 为 no-op 且 B 不受影响。final resolve 与不挂起 side effect 必须在同一个 reducerDispatch 临界区，不能先解锁再调用 port。
- 用 publicationGate 卡住已排队的 A PublishSample/PublishDeviceInfo，随后先处理 B GattCreated 或 RunStopped，再放行；公开 samples 与 data source 自有 deviceInfo snapshot 均保持不变，并各记录 GATT_CALLBACK stale。当前 B publication 才能写入；getter 不再读取 KeepB5GattCallback 的共享字段。
- retry wait 投影 ERROR，真正 retry 投影 CONNECTING，ready 投影 CONNECTED，stop 投影 DISCONNECTED。
- Running/Paused 下非主动且 status=SUCCESS 的 GattDisconnected(null) 规范化为 REMOTE_DISCONNECTED 并只退避一次；主动 CloseGatt 后的迟到 disconnect 只按 stale 清理，不重连。
- onRunStopped 后延迟 disconnect 对 wearable close 次数仍为 1。
- 长 reconnect backoff job 在 RunStopped/ExplicitDisconnect 后立刻取消；推进测试时钟不产生 ReconnectDue、不再次 startScan，新的 attempt job 不会被旧 CancelReconnect 误取消。
- 100 个控制事件先发后收仍 FIFO 全部到达；高频 activity 只保留最新值且不挤掉控制事件。
- 合法但无心率 WorkoutData 仍刷新 activity/进入 Ready，发布 SensorSample 次数为 0；未知 START/HEART_BEAT 响应不产生 PrivateWorkoutActivity。
- 当前 guard 的合法 WorkoutData(heartRate=79) 在两个内部流各出现一次：activity 只刷新活性，sample path 恰好发布一个公开 HEART_RATE SensorSample；不能从 activity 再发布第二次心率。
- Private CCCD 只产生 NotificationsEnabled、metadata read 次数为零；只有 supervisor 的 SendStart action 调用 transport.sendStart，且它是 private descriptor success 后提交的第一个新 GATT operation，不会被电量/固件 read 抢占。
- full integration 覆盖 both-present：standard enable 同步 false/throw 或 standard descriptor failure 后，各只产生一次 `ServicesDiscovered(PRIVATE_B3)`/private subscription；private CCCD success 被 supervisor 接受并只产生一次 SendStart/StartSync。fallback 已开始后迟到 standard CCCD success/failure 在 executor 内无输出，不读 metadata、不反切 channel；standard 已 ready 后迟到 failure 也不 fallback。private properties 不完整则一次 Error/退避而非重复订阅。
- PrivateWorkoutActivity 先于 StartWriteCompleted 到达时会暂存；随后 START accepted 仍 Ready 并取消 timeout，不误等 20 秒。
- FakeGattSession 的 shutdownCallbacks 返回可控 Job；普通断连、current failure、RunStopped 或重连替换一经 supervisor 返回 exact CloseGatt，reducerDispatch 就必须同步调用 quiesceGattCallbacks，立即使旧 processor offer=false、registration gate CLOSED、未执行 raw FIFO 丢弃且 shutdownCallbacks 恰好一次，但绝不 join。effect actor 后续调用 closeGatt 必须复用该 callback Job，teardown Job 在 callback Job 释放前不完成。完成后旧 session 不能再触达任一全局 sink，而并存的 B session 仍可继续处理。重复 quiesce/close 分别得到同一 callback/teardown Job，平台 disconnect/close 和 callback shutdown 都只执行一次，且 RunLifecycleAwareSensor 回调无需等待这个 gate。
- shutdown 后 controlEvents、privateActivities 与 samples 三个 collector 都完成，且不会再接受回调事件。
- 同时登记旧 A 与当前 B 两个 bound session 后调用 transport.shutdown，A/B 都各 disconnect/close 恰好一次，两个 callback processor 都完成，scan/target/job 注册表清空后才关闭三条队列；重复 shutdown 不增加调用。另有 in-flight C connect 时，A/B 先被收口，C 迟到 handle 仍只关闭一次。
- gate 卡住 connectGatt 后调用 transport.shutdown，再放行返回 handle：closed binding 不再向已关闭 channel 发布 GattCreated，直接 disconnect/close 该 handle且各一次。
- guard-specific close 中 disconnect 抛普通异常时仍调用 close 恰好一次并记录 DISCONNECT_GATT/BleOperationFailed；close 自己抛普通异常时仍 join shutdownCallbacks 返回的 Job、记录 CLOSE_GATT/BleOperationFailed，teardown Job 完成后 effect actor 才继续后续命令，RunStopped 的 BLE terminal flush 仍会启动。CancellationException 也先完成不可取消的局部 teardown 再从 Job 原样传播；重复 close 同一 guard 不再触碰 session。
- UPDATE_WORKOUT 与 HEART_BEAT 同次到期而 transport.sendWorkoutUpdate 返回 false 时，SyncWriteCompleted(false) 已先进入 FIFO、sendHeartbeat 调用次数为 0，并只进入一次 LinkFailed/重连路径；true 时 UPDATE result 必须在 HEART_BEAT result 前进入 FIFO。
- START reject、ready timeout、UPDATE/HEART_BEAT failure 分别穿过真实 effect actor；supervisor 接受 failure 的 reducerDispatch 内已先 quiesce exact GATT，故即使 gate 住 CloseGatt 的平台 close 或已启动 processor shutdown，新增 raw callback 也不能触发 I/O/sink。StopSync 与 CancelReadyTimeout 随 effect batch 完成，但 teardown Job、ScheduleReconnect 均未完成且扫描次数不增长，释放全部 teardown 后才投影 ERROR/注册 backoff。每条路径清空未执行 raw callback 与 supervisor pending activity/全部 current in-flight，普通 close 异常也继续唯一 backoff，重复/迟到 failure 不再清理或调度。
- START、UPDATE、HEART_BEAT 的每个 write operation 各只产生一个 completion；同步返回值不能导致 data source 再回送重复事件。
- START、UPDATE 与 HEARTBEAT 的 adapter throw 都被规范化为唯一 completion(false) + 对应 START_WRITE/UPDATE_WRITE/HEARTBEAT_WRITE 的 typed BleOperationFailed；UPDATE/HEARTBEAT 的 in-flight operation 都被认领清除，UPDATE 失败时同批 HEART_BEAT 为零，effect actor 在三种路径后都继续处理下一命令。
- FakeDiagnosticLogger 实际收到 lifecycle/link/reconnect/heartbeat/stale event/stale write，断开 reason 只能是 BleFailureReason enum。
- RunStopped 的 lifecycle/link 终态诊断都先进入 FakeDiagnosticLogger；同一个 reducerDispatch 返回前 exact GATT gate 已关闭且 processor shutdown 已启动，随后 effect actor 才执行 CloseGatt 并触发一次 BLE terminal flush。用未完成 callback/close gate 证明 onRunStopped 仍立即返回，同时 stop 返回后注入的任何 callback 均为零 I/O/sink；用未完成 flushGate 证明 flush 不阻塞 effect actor 完成 stop，也不阻塞后续 stale disconnect 清理。

- [ ] **Step 2: 运行红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.KeepB5BleDataSourceTest" --tests "com.healthyagent.running.data.sensor.ble.BleTransportEventQueueTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5GattBindingTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5ConnectPermitTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5GattCallbackMapperTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5GattCallbackProcessorTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5GattDecisionExecutorTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5GattEventMapperTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5ScanGateTest" --tests "com.healthyagent.running.data.sensor.ble.KeepB5DeviceMatcherTest" --tests "com.healthyagent.running.data.sensor.ble.KeyedConflatedEventQueueTest"
if ($LASTEXITCODE -eq 0) { throw "新增 B5 集成测试未出现预期红灯" }
Write-Host "已确认 B5 集成预期红灯；继续前人工核对失败仅来自新增 seam/断言" -ForegroundColor Green
~~~

- [ ] **Step 3: 移出 Android 操作并改 GATT 回调边界**

KeepB5GattCallback/AndroidKeepB5GattSession 只采集 RawGattCallbackEvent 并交给 session callback processor；Private CCCD 成功最终只 Emit NotificationsEnabled，不读 metadata，也不自行 sendStart；只有 executor 接受当前 STANDARD_PENDING 的成功 descriptor result 才 Emit NotificationsEnabled 后 ReadDeviceMetadata。KeepB5GattEventMapper 是唯一调用 parseWorkoutData/标准心率 parser 的层：合法 private frame 总是生成只含 KeepB5GattGuard + receivedAtMillis 的 PrivateWorkoutActivity；仅当 parsed heartRate 非空时，另生成同一 receivedAtMillis、墙钟 timestamp 的 HEART_RATE TokenizedSensorSample。活动流只刷新 B3 活性，只有 sample 事件能产生 PublishSample，因此最终公开心率恰好一次。标准心率也包装成逐条 FIFO 的 TokenizedSensorSample。每个 session 独占 characteristics、write target、requestId、private started、batteryPercent 与 firmwareVersion；initialize 先发布本 session 的 name/null/null，standard 路径的 battery read 后继续 firmware read，任一 metadata update 只发布该 session 的 DeviceInfo。只有 supervisor 的 PublishSample/PublishDeviceInfo action 才进入公开状态。迁移完成后从 KeepB5B3Protocol 删除过渡 parseWorkoutHeartRate wrapper，并用 rg 证明零调用。Connected、services、CCCD、write result、DeviceInfo、Disconnected、Error 全部映射到上面穷举的 BleTransportEvent，再由 data source 映射为 KeepB5SessionEvent；当前且仍需连接的 GattDisconnected(null) 在边界规范化为 REMOTE_DISCONNECTED，主动关闭后的迟到事件由 guard 判 stale。shutdown 时显式关闭 control、activity、sample 三个 channel/queue。

- [ ] **Step 4: 在 KeepB5BleDataSource 串行执行 supervisor actions**

所有 supervisor 状态转换只经过一个 `synchronized reducerDispatch`：临界区内调用 supervisor.handle，立即按返回顺序把 RecordDiagnostic 交给非挂起 logger.record，并在锁内同步执行 `ActivateGattData`、`ResetSyncUpdateAccounting`、`CancelPendingConnect`，以及每个 CloseGatt 的 `quiesceGattCallbacks(exactGuard)` 前置步骤，再把仍需异步执行的完整 action batch 投递到 Channel.UNLIMITED effect actor。Activate 只会来自已接受的 current GattCreated，按完整 KeepB5GattGuard 非阻塞打开对应 binding 的整个 callback execution gate；它必须发生在 reducer 状态更新之后、解锁和任何 effect 之前。每个 CloseGatt 即表示 supervisor 已使该 GATT 失效，因此无论它在 batch 的何处，reducerDispatch 都必须在解锁前同步 quiesce exact binding，永久关闭 gate并启动 processor shutdown；只保存返回 Job，绝不 join、disconnect 或 platform close，effect actor 之后仍按原 batch 顺序执行 CloseGatt。Reset 只在资源注册表中完整 KeepB5GattGuard 命中当前 scheduler 时调用其非挂起方法，不命中新代资源时幂等 no-op；它与 scheduler poll 共用这把锁，因而 pause/resume 与 UPDATE 计时锚点具有单一线性化顺序。CancelPendingConnect 同样只命中完整 attempt guard 的 permit/binding，并同步关闭尚未被 supervisor 接受的 registration gate、启动已有 processor shutdown，再做无锁/非阻塞的原子取消标记与 cleanup 投递；禁止等待 connector、dispatcher job、callback Job 或平台 close。registration/quiesce helper 只做 CAS/短锁且不在持有 transport/binding 锁时调用 data source sink，避免 reducer→transport 与 callback→reducer 形成锁环。reconnectBackoff 的所有访问也在 reducerDispatch 内串行：ScheduleReconnect 被接受时恰好调用一次 nextDelayMillis；ExplicitConnect、ExplicitDisconnect、RunStopped 或首次进入任一 Ready 时 reset，retry scan 本身不 reset。除这些 reducer-local bookkeeping 外，扫描、连接、平台关闭、延时任务和公开流副作用才由 effect actor 执行。这样 onRunStopped 在返回 AppCoordinator 前已经把 BLE lifecycle terminal、pending connect 取消和全部失效 GATT callback gate 关闭放入明确顺序并启动 processor shutdown，AppCoordinator 随后登记 session_finished；logger IO、Android 操作、Job join 与 teardown 都不阻塞协调器线程，保持 RunLifecycleAwareSensor 的既有快速合同。

effect actor 对 BeginScan、ConnectRemembered、SendStart、StartSync、ArmReadyTimeout、ScheduleReconnect、PublishSample 与 PublishDeviceInfo 的执行必须重新进入同一个 reducerDispatch 临界区，在锁内完成 `resolveGuardedAction -> 对应的不挂起 port/timer/公开状态提交`，禁止校验后解锁再调用；Rejected 只记录对应 CONTROL_ACTION 或 GATT_CALLBACK diagnostic。callback processor 的 discover/enable/read/signal 不走 effect actor，而由与 exact accepted GATT 绑定、且会在 GATT 失效的同一 reducerDispatch 内关闭的 registration gate 做最终资格门禁。BeginScan 只有在 transport.startScan 返回 accepted=true 后才为同 guard 注册 15_000ms scan-timeout job；到期经 reducerDispatch 回送 `KeepB5SessionEvent.ScanFailed(guard, SCAN_TIMEOUT)`。match、transport failure 或 StopScan 都只取消同 guard job。cleanup 不查 supervisor current-state，但查资源身份：StopScan 只在 activeScan.guard 匹配时停止并取消同 guard scan timeout，CancelReconnect 只取消匹配 backoff，StopSync/CancelReadyTimeout/CloseGatt 同样按 guard/token 精确清理；资源已消失或更新一代时 no-op。CloseGatt 的 callback quiesce 已在产生它的 reducerDispatch 内完成，此处只复用 tombstone 做平台 teardown并 join 同一个 callback Job。

同步 job 使用 KeepB5SyncScheduler。每次唤醒先进入 reducerDispatch，按该 scheduler 的完整 GATT guard 调用 `captureSyncPollGuard`，在同一临界区执行 pollDue 并立即 handle `SyncDue(pollGuard, due)`；这样 poll 要么在线性化点前捕获旧 revision，随后被 event/action final gate 拒绝，要么在线性化点后看到已经 reset 的 UPDATE 锚点和新 revision。禁止先在锁外 poll、排队后再读取当前 lifecycleRevision。每个真实写入还必须在同一个 reducerDispatch 临界区内完成 `resolveSyncWrite -> transport.send*` 这一段短小、不挂起的 WRITE_TYPE_NO_RESPONSE 提交：Rejected 只记录 typed diagnostic 而不调用 transport；Allowed 把 KeepB5SubmittedWrite 交给 transport，UPDATE 使用 elapsedSeconds，HEART_BEAT 不带时长。这样生命周期转换要么发生在提交前并使旧动作被拒绝，要么发生在提交后且 completion 仍按 in-flight operation 认领，不存在中间缝隙。同次唤醒的 UPDATE 与 HEART_BEAT 串行；UPDATE 返回 false 时只丢弃本轮剩余 HEART_BEAT，不能再次回送 LinkFailed，因为 transport 已经发布唯一 SyncWriteCompleted(false)。任一 current completion failure 都停止当前同步 job。

effect actor 对 reducer 返回的每个 action batch 保持严格 FIFO，绝不把 ScheduleReconnect 拆到可越过前序 cleanup 的独立 launch。RunStopped batch 按 reducer 顺序完成 CancelReconnect、StopScan（并取消匹配 scan timeout）、StopSync、CancelReadyTimeout、PublishConnectionState 与 CloseGatt；current-GATT failure batch 则按 StopSync、CancelReadyTimeout、CloseGatt、PublishConnectionState(ERROR)、ScheduleReconnect 完成。执行 CloseGatt 时 effect actor 复用 reducer-local quiesce 已保存的 callback Job，并必须等待 transport 返回的唯一 teardown Job 完成后才取本 batch 的下一项；这个等待只发生在 actor，不发生在 RunLifecycleAwareSensor 回调或 reducerDispatch 内。因此失效 session 的 callback gate 在生命周期命令返回前已经 CLOSED，新的 callback 不会产生 Android I/O/sink；同时坏 session 的平台 close 或已开始的 callback Job 即使被 gate 卡住，新 ERROR/backoff/scan 也尚不存在。每个 cleanup 只按自己的 guard/token 命中资源，单项普通异常不能中断后续清理。对应 RecordDiagnostic 已由 reducerDispatch 先入队。RunStopped 整个 batch 完成后，即使 disconnect/close 曾抛普通异常，也从独立 flush scope 启动 `withTimeoutOrNull(2_000) { diagnosticLogger.flush() }`，不得让 flushGate 阻塞 effect actor。connect action 只调用 non-blocking transport.connectRemembered；实际 connectGatt 在 transport connect dispatcher 上运行，结果再回送 reducerDispatch。生产 Context 构造器保持兼容，并提供 constructor(context: Context, diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger) 创建 AndroidKeepB5BleTransport；Task 11 生产路径显式传真实实例。

- [ ] **Step 5: 让旧布尔会话状态退出生产路径并运行 B5 全量测试**

KeepB5BleDataSource 必须不再引用 KeepB5WorkoutSessionState。该类和测试当前都是用户未跟踪文件，删除不会留下可恢复的 Git 历史，因此本计划保留原文件不变；完成后以 rg 证明生产路径零引用，待用户单独审阅后再决定清理。

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.data.sensor.ble.*"
if ($LASTEXITCODE -ne 0) { throw "B5 全量测试失败" }
rg -n "KeepB5WorkoutSessionState" app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5BleDataSource.kt app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattCallback.kt app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5B3Protocol.kt
if ($LASTEXITCODE -eq 0) { throw "旧布尔会话状态仍在生产路径" }
if ($LASTEXITCODE -gt 1) { throw "旧布尔会话状态审查执行失败" }
rg -n "parseWorkoutHeartRate" app/src/main/java/com/healthyagent/running/data/sensor/ble app/src/test/java/com/healthyagent/running/data/sensor/ble
if ($LASTEXITCODE -eq 0) { throw "旧心率解析 wrapper 仍存在" }
if ($LASTEXITCODE -gt 1) { throw "旧心率解析入口审查执行失败" }
git diff --check -- app/src/main/java/com/healthyagent/running/data/sensor/ble app/src/test/java/com/healthyagent/running/data/sensor/ble
if ($LASTEXITCODE -ne 0) { throw "B5 diff-check 失败" }
~~~

预期 rg 无输出。

- [ ] **Step 6: 用临时基线审查整个 BLE 增量并保持暂存区为空**

~~~powershell
$ErrorActionPreference = "Stop"
$implementationStatePath = Join-Path ([IO.Path]::GetTempPath()) "healthy-agent-b5-reliability-state.json"
if (-not (Test-Path -LiteralPath $implementationStatePath -PathType Leaf)) { throw "缺少实施 state JSON" }
$implementationState = Get-Content -LiteralPath $implementationStatePath -Raw | ConvertFrom-Json
$repositoryRoot = (Resolve-Path ".").Path
if ([string]$implementationState.schemaVersion -ne "1" -or [string]$implementationState.repositoryRoot -ne $repositoryRoot) { throw "实施 state 与当前仓库不匹配" }
$implementationBase = [string]$implementationState.implementationBase
$baselineRoot = [string]$implementationState.baselineRoot
$baselineManifestPath = [string]$implementationState.baselineManifestPath
$commitType = git cat-file -t $implementationBase
if ($LASTEXITCODE -ne 0 -or ($commitType -join "").Trim() -ne "commit") { throw "IMPLEMENTATION_BASE 不可解析" }
if (-not (Test-Path -LiteralPath $baselineRoot -PathType Container) -or -not (Test-Path -LiteralPath $baselineManifestPath -PathType Leaf)) { throw "实施基线文件已丢失" }
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
git diff --no-index -- "$baselineRoot/main-running/data/sensor/ble" "app/src/main/java/com/healthyagent/running/data/sensor/ble"
if ($LASTEXITCODE -gt 1) { throw "BLE main no-index 审查失败" }
git diff --no-index -- "$baselineRoot/test-running/data/sensor/ble" "app/src/test/java/com/healthyagent/running/data/sensor/ble"
if ($LASTEXITCODE -gt 1) { throw "BLE test no-index 审查失败" }
$mainWhitespace = @(& git diff --no-index --check -- "$baselineRoot/main-running/data/sensor/ble" "app/src/main/java/com/healthyagent/running/data/sensor/ble")
$mainWhitespaceExit = $LASTEXITCODE
if ($mainWhitespaceExit -gt 1) { throw "BLE main no-index check 执行失败" }
if ($mainWhitespace.Count -ne 0) {
    $mainWhitespace | Write-Output
    throw "BLE main 实施增量包含 whitespace error"
}
$testWhitespace = @(& git diff --no-index --check -- "$baselineRoot/test-running/data/sensor/ble" "app/src/test/java/com/healthyagent/running/data/sensor/ble")
$testWhitespaceExit = $LASTEXITCODE
if ($testWhitespaceExit -gt 1) { throw "BLE test no-index check 执行失败" }
if ($testWhitespace.Count -ne 0) {
    $testWhitespace | Write-Output
    throw "BLE test 实施增量包含 whitespace error"
}
git diff --no-index -- "$baselineRoot/main-running/data/sensor/ble/KeepB5WorkoutSessionState.kt" "app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5WorkoutSessionState.kt"
if ($LASTEXITCODE -ne 0) { throw "KeepB5WorkoutSessionState 实现被意外修改" }
git diff --no-index -- "$baselineRoot/test-running/data/sensor/ble/KeepB5WorkoutSessionStateTest.kt" "app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5WorkoutSessionStateTest.kt"
if ($LASTEXITCODE -ne 0) { throw "KeepB5WorkoutSessionState 测试被意外修改" }
~~~

本任务依赖 Task 2 中实施基线即为 untracked 的 KeepB5B3Protocol.kt/Test，同时 KeepB5BleDataSource.kt、KeepB5GattCallback.kt 也已有重叠差异；把新 transport 文件单独提交会得到无法在干净 checkout 编译的树。因此 `git diff --cached --name-only` 必须仍为空；目录级 no-index 命令完整展示并检查新文件、重叠文件与依赖文件（exit code 1 表示存在待审差异，是预期），两份 WorkoutSessionState 精确比较则必须无输出/返回 0。

---

## Task 11：统一接线 AppCoordinator 与 Koin

**Files:**

- Modify: app/src/main/java/com/healthyagent/running/coordinator/AppCoordinator.kt
- Modify: app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt
- Modify: app/src/main/java/com/healthyagent/running/di/AppModule.kt
- Create: app/src/androidTest/java/com/healthyagent/running/di/AppModuleSmokeTest.kt

这是唯一允许同时修改三个集成文件的任务。先重新读取它们的当前工作树版本，不能从 HEAD 覆盖未提交实现。

- [ ] **Step 1: 写四条会话播报和拒绝分支失败测试**

使用真实 SessionSpeechTriggerFactory、可控 SpeechBus/FakeTtsEngine，执行 startRun→pauseRun→resumeRun→stopRun 并推进测试 dispatcher，断言：

~~~kotlin
assertEquals(
    listOf(
        "流萤已开始记录，出发吧。",
        "跑步已暂停。",
        "跑步已恢复。",
        "跑步已结束。",
    ),
    fakeTts.completedTexts,
)
~~~

为每个非法/重复命令再断言 requests 数量不变。项目没有 MockK/Mockito 且 SpeechBus 是具体 final 类，因此不用不可实现的 spy：记录 sessionGenerationToken，断言 accepted pauseRun/stopRun 前后代次不变，accepted startRun 恰好建立新代次，rejected startRun 不 reset；close 后 FakeTtsEngine.shutdownCalls 为 1。状态提示替换可能合法调用底层 engine.stop，不能把 stopCalls 当作 SpeechBus hard-stop 证据。

再用 ttsEnabled=false 的 RunConfig 执行四个 accepted 状态命令：FakeTtsEngine 请求为零，每次只产生 POLICY_DISABLED 诊断，不产生 SESSION_STATUS_SCHEDULED。

- [ ] **Step 2: 写暂停快照与 generation 失败测试**

测试先建立一帧已运行快照，然后暂停并发送新心率、距离、步频与 tick：

- 心率和 hrZone 随新鲜 B5 样本变化。
- elapsedSeconds、distanceMeters、paceSecPerKm、cadence、split 数量与 RuleEngine evaluate 次数保持暂停瞬间值。
- 5 秒无心率时只把 heartRate/hrZone 清空。
- resume 后来自旧 monitor 的迟到 sample/tick 不更新快照；正常 pipeline 成为唯一有效写入者。
- 另测 RunningDataPipeline 在绝对单调 t=0 接受 HR=150 并让快照显示 HR/Z3，直到 t=4_999 才接受 pause 且之后零样本：monitor handoff seed 的 receivedAtMillis 仍为 0，暂停后只剩 1ms；向注入 monitor 的可控 tick flow 发出绝对 t=5_000 后精确清空，不能把 pause 时刻写成新锚点。再在 t=4_999 注入 0/301/NaN/Infinity/150.5，仍于 t=5_000 清空，证明无效样本不续命。另一路在 t=5_000 才 pause，第一轮 monitor 处理就直接清空，绝不能短暂复活 150；freeze 后旧 pipeline callback 即使迟到也无效。
- 用构造期 runConfig.maxHeartRate=180、随后加载并开始本次跑步的 activeRunConfig.maxHeartRate=200、HR=150 建立 Running Z3；pause 后 seed 和后续 paused sample 仍按 200 计算并保持 Z3，不能错误退回 180 而跳成 Z4。
- 对 `processPausedHeartRate` 以及 startRun/pauseRun/resumeRun/stopRun/close 做 JVM 反射，断言对应 Java method 带 `Modifier.SYNCHRONIZED`；internal 方法编译后可能带模块后缀，因此用 `name.startsWith("processPausedHeartRate")` 唯一定位。再通过只读的 `pipelineGenerationToken()` 捕获 paused generation，同线程进入 `synchronized(coordinator)`，先 reentrant 调用 resumeRun，再直接调用 `processPausedHeartRate(staleState, pausedGeneration)`，退出临界区后断言 heartRate/hrZone 仍为空。这样结构性锁定校验与写入不可拆锁，并确定性证明旧 generation/state 不会回写，无真实线程、超时或调度假通过；start、stop、close 也分别顺序覆盖相同的失效保证。

- [ ] **Step 3: 写真实规则目录跨公里失败测试**

AppCoordinatorTest 使用 RuleCatalog.defaultRules 与可控 realtimeMillis：先在 0 毫秒输入 0m 帧，再输入 119 个距离/时间线性增长的有效中间帧，最后在 400_000 毫秒输入第 121 个 1000m 帧。这样 RuleEngine 的最近 60 帧中最早距离已大于 50m，旧 KmSplitRule 必然无法从窗口找到公里起点。断言同一个 RunSessionManager Split 只生成一条：

~~~text
第1公里，用时6分40秒，配速6分40秒
~~~

同时断言下一帧 completedSplit 已清为 null，不重复播报。

- [ ] **Step 4: 写结束诊断 flush 所有权测试**

使用共享 trace 的 Fake RunLifecycleAwareSensor 与真实 SpeechBus，先断言 accepted stop 的 `sensor.onRunStopped` 完成标记严格早于 SESSION_STATUS_SCHEDULED。FakeSensorDataSource 不产生 BLE terminal flush，因此接受 stopRun 后只同步登记 SESSION_STATUS_SCHEDULED，FakeDiagnosticLogger.flushCalls 仍为 0；让结束 TTS 返回真实终态后，先出现 `SpeechOutcome(session_finished, TTS_SUCCESS)`，再出现恰好一次 speech terminal flush。`ttsEnabled=false` 时 POLICY_DISABLED 是同步终态，随后同样只安排一次 speech terminal flush。用未完成 flushGate 证明命令立即 Accepted、保存流程及后续命令不等待 flush；拒绝 stopRun 时 flushCalls 保持 0。另在 Task 10 的真实 B5 集成测试中，RunStopped reducerDispatch 会先 record BLE lifecycle、effect actor 随后产生一次独立 BLE terminal flush，因此共享 logger 的完整真机结束路径预期是 BLE 1 次 + Speech 1 次，而不是全局最终恰好 1 次。AppCoordinator 自身不得调用 flush。

- [ ] **Step 5: 运行协调器红灯**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.coordinator.AppCoordinatorTest"
if ($LASTEXITCODE -eq 0) { throw "新增 AppCoordinator 测试未出现预期红灯" }
Write-Host "已确认 AppCoordinator 预期红灯；继续前人工核对失败仅来自新增断言" -ForegroundColor Green
~~~

- [ ] **Step 6: 接入暂停观察器、Split 和会话状态**

AppCoordinator 构造器末尾新增默认依赖，保持既有测试可逐步迁移：

~~~kotlin
private val pausedHeartRateMonitor: PausedHeartRateMonitor = PausedHeartRateMonitor(),
~~~

增加 pausedHeartRateJob，并让 cancelPipeline 同时递增既有 pipelineGeneration、取消 pipelineJob 与 pausedHeartRateJob、调用 pausedHeartRateMonitor.invalidateRunning。ensurePipelineStarted 取得新的 pipelineGeneration 后，先调用 `pausedHeartRateMonitor.beginRunning(generation)`，再把 RunningDataPipeline 的 onOrderedHeartRateReceived 接到 `recordRunningSample(generation, sample, receivedAtMillis)`；回调和 frame collector 都捕获同一个 generation。pauseRun 在命令被接受、冻结快照的同一 `@Synchronized` 路径，先捕获 `val pausedMaxHeartRate = activeRunConfig.maxHeartRate`，再用取消前的 generation 调用 `freezeForPause(generation, pausedMaxHeartRate)`，随后执行 cancelPipeline，最后以递增后的 pipelineGeneration 作为 paused collector generation，并把同一个 pausedMaxHeartRate 与返回的不可变 seed 传给 observe。绝不能在此处读取构造期 runConfig，也不能让 freeze 与 observe 使用不同的最大心率。freeze 与 pipeline callback 在 monitor 短锁中竞争，因而 pause 后迟到的旧 callback 即使早已从 source 取出也不能续命。生产 RunningDataPipeline 与 monitor 都使用 System.nanoTime 单调域；AppCoordinatorTest 给两者注入同一个可控单调函数，禁止拿 wall-clock SensorSample.timestampMillis 计算年龄。新鲜 seed 只使用原 receipt 剩余窗口，过期 seed 首轮直接清空；暂停后新合法样本才以新的 monitor 接收时刻重新建立 5 秒窗口。

新增 `@Synchronized internal fun processPausedHeartRate(state: PausedHeartRateState, generation: Long)`，在 AppCoordinator 同一个实例 monitor 内原子完成 `pipelineGeneration == generation && sessionManager.currentState == RunState.PAUSED` 校验和 `_snapshot.update`；paused collector 只能调用这个方法，禁止先调用 isCurrentPipelineGeneration 再在锁外 updateSnapshot。另加只读的 `@Synchronized internal fun pipelineGenerationToken(): Long = pipelineGeneration`，仅供确定性并发测试捕获 token，不参与生产控制流。startRun、pauseRun、resumeRun、stopRun 与 close 的 generation 失效都必须经过这个相同的 AppCoordinator monitor；现有 start/pause/resume/stop 保持 `@Synchronized`，close 新增 `@Synchronized` 并先 cancelPipeline，再做 shutdown/cancel。这样旧 collector 无论在交接前还是交接后到达，都不可能在 resume/start/stop/close 返回后回写。该原子提交只复制：

~~~kotlin
metrics = current.metrics.copy(
    heartRate = state.heartRate,
    hrZone = state.hrZone?.toContractValue(),
)
~~~

start/resume/stop/close 都先使旧 monitor generation 失效。startRun 在 resetSession 后 enqueue STARTED；pause/resume/stop 只在命令已接受后登记对应 status。stopRun 必须先同步调用 `sensor.onRunStopped()`，待其 reducerDispatch 返回后才 enqueue FINISHED，以保证共享 DiagnosticLogger 中 BLE lifecycle record 先于 session_finished schedule。删除 pauseRun/stopRun 中的 speechBus.stop。

processFrame 改为只把当帧 Split 传入评估：

~~~kotlin
val triggers = ruleEngine.evaluate(
    frame,
    ruleContext.copy(completedSplit = split),
)
ruleContext = ruleContext.copy(
    recentFrames = (ruleContext.recentFrames + frame).takeLast(60),
    elapsedSeconds = frame.elapsedSeconds,
    completedSplit = null,
)
~~~

被接受的 stopRun 只把 RunStopped 交给 sensor source，并登记 session_finished；BLE terminal flush 由 KeepB5BleDataSource actor 在完成本地终态动作后负责，TTS/session_finished terminal flush 由 SpeechBus 在结果记录后负责。AppCoordinator 不直接 launch flush，也不把 flush 放入 Activity、Service、Application teardown，因此不会产生提前 flush 或重复 owner。

- [ ] **Step 7: 统一 AppModule 生产图**

删除 11 个 single<Rule> 与对应 imports；只保留 RuleCatalog.defaultRules。生产单例接线精确为：

~~~kotlin
single<DiagnosticLogger> {
    FileDiagnosticLogger(File(androidContext().filesDir, "diagnostics"))
}
single<AudioFocusController> {
    AndroidAudioFocusController(androidContext())
}
single {
    PausedHeartRateMonitor()
}
single {
    AppCoordinator(
        sensorDataSource = get(),
        pipeline = get(),
        runConfig = get(),
        ruleEngine = get(),
        speechBus = get(),
        sessionManager = get(),
        repository = get(),
        settingsRepository = get(),
        healthConnectSync = get(),
        analysisManager = get(),
        emergencyPhraseCache = get(),
        pausedHeartRateMonitor = get(),
    )
}
single {
    RuleEngine(
        rules = RuleCatalog.defaultRules(),
        initialConfig = get(),
        diagnosticLogger = get(),
    )
}
~~~

真机 SensorDataSource 分支用 KeepB5BleDataSource(androidContext(), diagnosticLogger = get())。TTS 图使用同一个 AudioFocusController 和 DiagnosticLogger：

~~~kotlin
single<TtsEngine> {
    val logger = get<DiagnosticLogger>()
    val focus = get<AudioFocusController>()
    val settings = runBlocking { get<SettingsRepository>().current() }
    val local = AndroidTtsEngine(
        port = AndroidTextToSpeechPort(androidContext()),
        audioFocusController = focus,
        diagnosticLogger = logger,
    )
    val cloud = if (
        settings.fishApiKey.isNotBlank() &&
        settings.fishReferenceId.isNotBlank()
    ) {
        FishAudioTtsEngine(
            synthesisClient = OkHttpFishSynthesisClient(
                apiKey = settings.fishApiKey,
                referenceId = settings.fishReferenceId,
                cacheDirectory = File(androidContext().cacheDir, "tts_cache"),
            ),
            audioPlayerFactory = { AndroidAudioPlayerPort() },
            audioFocusController = focus,
            diagnosticLogger = logger,
        )
    } else {
        null
    }
    CompositeTtsEngine(local = local, cloud = cloud, diagnosticLogger = logger)
}
~~~

SpeechBus、KeepB5BleDataSource、RuleEngine、AndroidTtsEngine、FishAudioTtsEngine 和 CompositeTtsEngine 的生产构造都必须显式传同一个 get<DiagnosticLogger>()；NoOp 只用于测试/兼容构造。AppCoordinator 不拥有 logger/flush。SpeechBus 与 KeepB5BleDataSource 各使用自己的 SupervisorJob/flush scope，不借用 Activity scope。

新增 `AppModuleSmokeTest`，复用 manifest 启动的 `HealthyAgentApp` 和 `GlobalContext.get()`，禁止测试内再次 startKoin/stopKoin。测试先断言 target Application 确为 HealthyAgentApp，再从真实生产图解析 AppCoordinator、TtsEngine、SensorDataSource、RuleEngine；DiagnosticLogger 与 AudioFocusController 各连续 get 两次并 assertSame，证明基础设施绑定是生产 singleton。测试只解析，不开始跑步、不连接设备、不播放、不 shutdown 全局 singleton。组件私有字段不做反射内窥；“所有消费者共享实例”由 AppModule 每个生产 factory 显式 get 与各组件的 fake 单测共同证明。

~~~kotlin
@RunWith(AndroidJUnit4::class)
class AppModuleSmokeTest {
    @Test
    fun productionGraphResolvesAndInfrastructureIsSingleton() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(application is HealthyAgentApp)
        val koin = GlobalContext.get()

        assertNotNull(koin.get<AppCoordinator>())
        assertNotNull(koin.get<TtsEngine>())
        assertNotNull(koin.get<SensorDataSource>())
        assertNotNull(koin.get<RuleEngine>())
        assertSame(koin.get<DiagnosticLogger>(), koin.get<DiagnosticLogger>())
        assertSame(koin.get<AudioFocusController>(), koin.get<AudioFocusController>())
    }
}
~~~

- [ ] **Step 8: 运行集成绿灯和结构断言**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.coordinator.AppCoordinatorTest" --tests "com.healthyagent.running.core.engine.*" --tests "com.healthyagent.running.speech.*" --tests "com.healthyagent.running.ai.AnalysisManagerTest" :app:compileDebugAndroidTestKotlin
if ($LASTEXITCODE -ne 0) { throw "AppCoordinator 集成绿灯失败" }
rg -n "single<Rule>" app/src/main/java/com/healthyagent/running/di/AppModule.kt
if ($LASTEXITCODE -eq 0) { throw "AppModule 仍含逐条 Rule 注册" }
if ($LASTEXITCODE -gt 1) { throw "Rule 注册审查执行失败" }
rg -n "speechBus\.stop" app/src/main/java/com/healthyagent/running/coordinator/AppCoordinator.kt
if ($LASTEXITCODE -eq 0) { throw "AppCoordinator 仍直接 hard-stop SpeechBus" }
if ($LASTEXITCODE -gt 1) { throw "SpeechBus 所有权审查执行失败" }
$pausedMonitorMatches = @(rg -n "pausedHeartRateMonitor\s*=\s*get\(\)" app/src/main/java/com/healthyagent/running/di/AppModule.kt)
if ($LASTEXITCODE -ne 0) { throw "AppModule 未显式注入 PausedHeartRateMonitor" }
if ($pausedMonitorMatches.Count -ne 1) {
    $pausedMonitorMatches | Write-Output
    throw "PausedHeartRateMonitor 生产注入必须恰好一处"
}
git diff --check -- app/src/main/java/com/healthyagent/running/coordinator/AppCoordinator.kt app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt app/src/main/java/com/healthyagent/running/di/AppModule.kt app/src/androidTest/java/com/healthyagent/running/di/AppModuleSmokeTest.kt
if ($LASTEXITCODE -ne 0) { throw "Task 11 diff-check 失败" }
~~~

前两个 rg 均预期无输出；第三个必须恰好命中 AppCoordinator 生产 factory 一次，证明生产图使用 Koin singleton 而不是构造器默认值。

- [ ] **Step 9: 用临时基线审查三个重叠文件并保持暂存区为空**

~~~powershell
$ErrorActionPreference = "Stop"
$implementationStatePath = Join-Path ([IO.Path]::GetTempPath()) "healthy-agent-b5-reliability-state.json"
if (-not (Test-Path -LiteralPath $implementationStatePath -PathType Leaf)) { throw "缺少实施 state JSON" }
$implementationState = Get-Content -LiteralPath $implementationStatePath -Raw | ConvertFrom-Json
$repositoryRoot = (Resolve-Path ".").Path
if ([string]$implementationState.schemaVersion -ne "1" -or [string]$implementationState.repositoryRoot -ne $repositoryRoot) { throw "实施 state 与当前仓库不匹配" }
$implementationBase = [string]$implementationState.implementationBase
$baselineRoot = [string]$implementationState.baselineRoot
$baselineManifestPath = [string]$implementationState.baselineManifestPath
$commitType = git cat-file -t $implementationBase
if ($LASTEXITCODE -ne 0 -or ($commitType -join "").Trim() -ne "commit") { throw "IMPLEMENTATION_BASE 不可解析" }
if (-not (Test-Path -LiteralPath $baselineRoot -PathType Container) -or -not (Test-Path -LiteralPath $baselineManifestPath -PathType Leaf)) { throw "实施基线文件已丢失" }
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) { throw "本批次暂存 allowlist 为空；停止以避免夹带用户修改" }
git diff --no-index -- "$baselineRoot/main-running/coordinator/AppCoordinator.kt" "app/src/main/java/com/healthyagent/running/coordinator/AppCoordinator.kt"
if ($LASTEXITCODE -gt 1) { throw "AppCoordinator 实现 no-index 审查失败" }
git diff --no-index -- "$baselineRoot/test-running/coordinator/AppCoordinatorTest.kt" "app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt"
if ($LASTEXITCODE -gt 1) { throw "AppCoordinator 测试 no-index 审查失败" }
git diff --no-index -- "$baselineRoot/main-running/di/AppModule.kt" "app/src/main/java/com/healthyagent/running/di/AppModule.kt"
if ($LASTEXITCODE -gt 1) { throw "AppModule no-index 审查失败" }
git diff --no-index -- "$baselineRoot/androidtest-running" "app/src/androidTest/java/com/healthyagent/running"
if ($LASTEXITCODE -gt 1) { throw "AppModule smoke androidTest no-index 审查失败" }
git diff -- app/src/main/java/com/healthyagent/running/coordinator/AppCoordinator.kt app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt app/src/main/java/com/healthyagent/running/di/AppModule.kt
if ($LASTEXITCODE -ne 0) { throw "Task 11 tracked diff 审查失败" }
~~~

git diff --no-index 在有差异时返回 exit code 1，这是预期审查信号。当前 AppCoordinator.kt 与 AppCoordinatorTest.kt 在实施基线是既有 untracked 文件；按全局规则，本批次不暂存、不提交，固定基线 diff 是唯一增量归因依据。

---

## Task 12：全量回归、APK、安装与真机证据

**Files:**

- Verify only: app/src/main/**
- Verify only: app/src/test/**
- Verify only: app/src/androidTest/**
- Verify only: app/build/outputs/apk/debug/app-debug.apk
- Verify only on connected device: files/diagnostics/**

- [ ] **Step 1: 运行所有定向回归**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.healthyagent.running.diagnostics.*" --tests "com.healthyagent.running.data.sensor.ble.*" --tests "com.healthyagent.running.core.pipeline.*" --tests "com.healthyagent.running.core.engine.*" --tests "com.healthyagent.running.speech.*" --tests "com.healthyagent.running.coordinator.*"
if ($LASTEXITCODE -ne 0) { throw "定向回归失败" }
~~~

- [ ] **Step 2: 运行 JVM 全量测试并构建含 Web UI 的 debug APK**

~~~powershell
$ErrorActionPreference = "Stop"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PwebUiEnabled=true
if ($LASTEXITCODE -ne 0) { throw "JVM 全量测试或 debug APK 构建失败" }
if (-not (Test-Path -LiteralPath "app/build/outputs/apk/debug/app-debug.apk" -PathType Leaf)) { throw "Gradle 返回成功但 debug APK 不存在" }
$apk = Get-Item -LiteralPath "app/build/outputs/apk/debug/app-debug.apk"
$apk | Select-Object FullName, Length, LastWriteTime | Format-List
~~~

记录 Gradle BUILD SUCCESSFUL、测试数量和 APK 的长度/更新时间。不得用 Task 1–11 的局部绿灯替代本步。

- [ ] **Step 3: 做范围和秘密静态审查**

~~~powershell
$ErrorActionPreference = "Stop"
function Invoke-GitChecked {
    param([string[]]$Arguments, [string]$FailureMessage)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}
function Show-NoIndexDiff {
    param([string]$Before, [string]$After, [string]$Label)
    & git diff --no-index -- $Before $After
    $exitCode = $LASTEXITCODE
    if ($exitCode -gt 1) { throw "$Label no-index 审查执行失败" }
}
function Assert-NoIndexEqual {
    param([string]$Before, [string]$After, [string]$Label)
    & git diff --no-index -- $Before $After
    if ($LASTEXITCODE -ne 0) { throw "$Label 被本批次意外修改" }
}
function Assert-NoIndexWhitespaceClean {
    param([string]$Before, [string]$After, [string]$Label)
    $checkOutput = @(& git diff --no-index --check -- $Before $After)
    $exitCode = $LASTEXITCODE
    if ($exitCode -gt 1) { throw "$Label no-index whitespace 审查执行失败" }
    if ($checkOutput.Count -ne 0) {
        $checkOutput | Write-Output
        throw "$Label 实施增量包含 whitespace error"
    }
}
function Assert-RgAbsent {
    param([string]$Pattern, [string[]]$Paths, [string]$FailureMessage)
    $matches = @(& rg -n $Pattern @Paths)
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) {
        $matches | Write-Output
        throw $FailureMessage
    }
    if ($exitCode -gt 1) { throw "rg 审查执行失败：$Pattern" }
}
function Assert-RgCount {
    param([string]$Pattern, [string[]]$Paths, [int]$ExpectedCount, [string]$FailureMessage)
    $matches = @(& rg -n $Pattern @Paths)
    $exitCode = $LASTEXITCODE
    if ($exitCode -gt 1) { throw "rg 审查执行失败：$Pattern" }
    if ($exitCode -eq 1) { $matches = @() }
    if ($matches.Count -ne $ExpectedCount) {
        $matches | Write-Output
        throw $FailureMessage
    }
}
$implementationStatePath = Join-Path ([IO.Path]::GetTempPath()) "healthy-agent-b5-reliability-state.json"
if (-not (Test-Path -LiteralPath $implementationStatePath -PathType Leaf)) { throw "缺少实施 state JSON" }
$implementationState = Get-Content -LiteralPath $implementationStatePath -Raw | ConvertFrom-Json
$repositoryRoot = (Resolve-Path ".").Path
if ([string]$implementationState.schemaVersion -ne "1" -or [string]$implementationState.repositoryRoot -ne $repositoryRoot) { throw "实施 state 与当前仓库不匹配" }
$implementationBase = [string]$implementationState.implementationBase
$baselineRoot = [string]$implementationState.baselineRoot
$baselineManifestPath = [string]$implementationState.baselineManifestPath
$commitType = git cat-file -t $implementationBase
if ($LASTEXITCODE -ne 0 -or ($commitType -join "").Trim() -ne "commit") { throw "IMPLEMENTATION_BASE 不可解析" }
if (-not (Test-Path -LiteralPath $baselineRoot -PathType Container) -or -not (Test-Path -LiteralPath $baselineManifestPath -PathType Leaf)) { throw "实施基线文件已丢失" }
Invoke-GitChecked -Arguments @("status", "--short") -FailureMessage "无法读取最终工作树状态"
$stagedPaths = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0) { throw "无法读取暂存区" }
if ($stagedPaths.Count -ne 0) {
    $stagedPaths | Write-Output
    throw "暂存区非空，停止验收以避免夹带用户修改"
}
if ([string]::IsNullOrWhiteSpace($implementationBase)) {
    throw "缺少 IMPLEMENTATION_BASE；从执行记录恢复后再验收，不能改用当前 HEAD"
}
Invoke-GitChecked -Arguments @("diff", "--name-status", "$implementationBase..HEAD") -FailureMessage "无法读取已提交路径"
Invoke-GitChecked -Arguments @("diff", "--check", "$implementationBase..HEAD") -FailureMessage "已提交增量包含 whitespace error"
Invoke-GitChecked -Arguments @("diff", "$implementationBase..HEAD", "--", "app/src/main", "app/src/test", "app/src/androidTest") -FailureMessage "无法读取已提交实现 diff"
Show-NoIndexDiff -Before "$baselineRoot/main-running" -After "app/src/main/java/com/healthyagent/running" -Label "main-running"
Show-NoIndexDiff -Before "$baselineRoot/test-running" -After "app/src/test/java/com/healthyagent/running" -Label "test-running"
Show-NoIndexDiff -Before "$baselineRoot/androidtest-running" -After "app/src/androidTest/java/com/healthyagent/running" -Label "androidtest-running"
Assert-NoIndexWhitespaceClean -Before "$baselineRoot/main-running" -After "app/src/main/java/com/healthyagent/running" -Label "main-running"
Assert-NoIndexWhitespaceClean -Before "$baselineRoot/test-running" -After "app/src/test/java/com/healthyagent/running" -Label "test-running"
Assert-NoIndexWhitespaceClean -Before "$baselineRoot/androidtest-running" -After "app/src/androidTest/java/com/healthyagent/running" -Label "androidtest-running"
Assert-RgAbsent -Pattern "parseWorkoutHeartRate" -Paths @("app/src/main", "app/src/test") -FailureMessage "旧 parseWorkoutHeartRate 仍存在"
Assert-RgAbsent -Pattern "single<Rule>" -Paths @("app/src/main/java/com/healthyagent/running/di/AppModule.kt") -FailureMessage "AppModule 仍含逐条 Rule 注册"
Assert-RgAbsent -Pattern "speechBus\.stop" -Paths @("app/src/main/java/com/healthyagent/running/coordinator/AppCoordinator.kt") -FailureMessage "AppCoordinator 仍直接停止 SpeechBus"
Assert-RgCount -Pattern "pausedHeartRateMonitor\s*=\s*get\(\)" -Paths @("app/src/main/java/com/healthyagent/running/di/AppModule.kt") -ExpectedCount 1 -FailureMessage "PausedHeartRateMonitor 生产注入不是恰好一处"
Show-NoIndexDiff -Before "$baselineRoot/main-running/core/pipeline/RunningDataPipeline.kt" -After "app/src/main/java/com/healthyagent/running/core/pipeline/RunningDataPipeline.kt" -Label "RunningDataPipeline"
Show-NoIndexDiff -Before "$baselineRoot/test-running/core/pipeline/RunningDataPipelineTest.kt" -After "app/src/test/java/com/healthyagent/running/core/pipeline/RunningDataPipelineTest.kt" -Label "RunningDataPipelineTest"
Show-NoIndexDiff -Before "$baselineRoot/main-running/core/engine/RuleEngine.kt" -After "app/src/main/java/com/healthyagent/running/core/engine/RuleEngine.kt" -Label "RuleEngine"
Show-NoIndexDiff -Before "$baselineRoot/main-running/speech/SpeechBus.kt" -After "app/src/main/java/com/healthyagent/running/speech/SpeechBus.kt" -Label "SpeechBus"
Show-NoIndexDiff -Before "$baselineRoot/test-running/speech/SpeechBusTest.kt" -After "app/src/test/java/com/healthyagent/running/speech/SpeechBusTest.kt" -Label "SpeechBusTest"
Show-NoIndexDiff -Before "$baselineRoot/main-running/data/sensor/ble/KeepB5BleDataSource.kt" -After "app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5BleDataSource.kt" -Label "KeepB5BleDataSource"
Show-NoIndexDiff -Before "$baselineRoot/main-running/data/sensor/ble/KeepB5GattCallback.kt" -After "app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5GattCallback.kt" -Label "KeepB5GattCallback"
Show-NoIndexDiff -Before "$baselineRoot/main-running/data/sensor/ble/KeepB5B3Protocol.kt" -After "app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5B3Protocol.kt" -Label "KeepB5B3Protocol"
Show-NoIndexDiff -Before "$baselineRoot/test-running/data/sensor/ble/KeepB5B3ProtocolTest.kt" -After "app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5B3ProtocolTest.kt" -Label "KeepB5B3ProtocolTest"
Assert-NoIndexEqual -Before "$baselineRoot/main-running/data/sensor/ble/KeepB5WorkoutSessionState.kt" -After "app/src/main/java/com/healthyagent/running/data/sensor/ble/KeepB5WorkoutSessionState.kt" -Label "KeepB5WorkoutSessionState"
Assert-NoIndexEqual -Before "$baselineRoot/test-running/data/sensor/ble/KeepB5WorkoutSessionStateTest.kt" -After "app/src/test/java/com/healthyagent/running/data/sensor/ble/KeepB5WorkoutSessionStateTest.kt" -Label "KeepB5WorkoutSessionStateTest"
Show-NoIndexDiff -Before "$baselineRoot/main-running/di/AppModule.kt" -After "app/src/main/java/com/healthyagent/running/di/AppModule.kt" -Label "AppModule"
Show-NoIndexDiff -Before "$baselineRoot/main-running/coordinator/AppCoordinator.kt" -After "app/src/main/java/com/healthyagent/running/coordinator/AppCoordinator.kt" -Label "AppCoordinator"
Show-NoIndexDiff -Before "$baselineRoot/test-running/coordinator/AppCoordinatorTest.kt" -After "app/src/test/java/com/healthyagent/running/coordinator/AppCoordinatorTest.kt" -Label "AppCoordinatorTest"
Assert-RgAbsent -Pattern "Authorization|Bearer|fishApiKey|apiKey" -Paths @("app/src/main/java/com/healthyagent/running/diagnostics") -FailureMessage "生产诊断包出现秘密字段或值入口"
$currentBaselineManifest = Get-ChildItem -LiteralPath $baselineRoot -Recurse -File |
    ForEach-Object {
        $relativePath = $_.FullName.Substring($baselineRoot.Length)
        "{0} {1}" -f $relativePath, (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    } |
    Sort-Object
$baselineDrift = Compare-Object (Get-Content -LiteralPath $baselineManifestPath) $currentBaselineManifest
if ($null -ne $baselineDrift) {
    $baselineDrift | Format-Table
    throw "临时基线副本已漂移，停止验收并重新建立可信基线"
}
~~~

三个精确范围的旧实现模式应无匹配；SpeechBusTest 中对 speechBus.stop 的 hard-stop 测试是允许且必须的。先人工审阅 `$implementationBase..HEAD` 的 name-status、完整 diff 与 diff-check；本批次原则上不产生实现 commit，因此若这里出现实现路径应立即追查。再审阅 main-running/test-running/androidtest-running 三个全目录 no-index diff，它们是覆盖 dirty/untracked 文件的最终完整实施增量，不能用 HEAD diff 或基线 manifest 代替。随后逐一审阅所有已知基线重叠文件的 no-index diff，便于精确归因。协议差异只能包含合法活动/可选心率和既有固定状态+动态时长编码及对应测试，不能新增 DisplayData 距离/配速/千卡；两份 KeepB5WorkoutSessionState 对比必须无输出并返回 0。`Assert-NoIndexWhitespaceClean` 明确接受 no-index 的 0/1 内容差异状态，但只要 `--check` 有输出就判为 whitespace error。暂存区必须为空，manifest 比较必须为空；每次都从固定 state JSON 加载并验证 implementationBase、baselineRoot 与 baselineManifestPath，不能悄悄改用当前 HEAD 或新建基线。生产诊断包不得出现保存秘密值的字段；脱敏单测中故意出现的假 Bearer 文本不属于生产泄露。

- [ ] **Step 4: 重新发现设备 serial**

~~~powershell
$ErrorActionPreference = "Stop"
$adbPath = "D:/fangr/Android/Sdk/platform-tools/adb.exe"
if (-not (Test-Path -LiteralPath $adbPath)) { throw "adb 不存在：$adbPath" }
$deviceOutput = @(& $adbPath devices -l)
$adbDevicesExit = $LASTEXITCODE
if ($adbDevicesExit -ne 0) { throw "adb devices 失败" }
$deviceLines = @(
    $deviceOutput |
        Select-Object -Skip 1 |
        Where-Object { $_ -match "\sdevice(\s|$)" }
)
if ($deviceLines.Count -ne 1) {
    throw "需要且只能有一台 online Android 设备；当前数量为 $($deviceLines.Count)"
}
$deviceSerial = ($deviceLines[0] -split "\s+")[0]
~~~

本步无 online 设备时立即停止设备阶段，结论写“自动化完成、真机验收待设备重连”，不把它标为代码失败，也不宣称里程碑 A 全部完成。

- [ ] **Step 5: 有设备时运行 instrumentation 并安装**

~~~powershell
$ErrorActionPreference = "Stop"
$adbPath = "D:/fangr/Android/Sdk/platform-tools/adb.exe"
if (-not (Test-Path -LiteralPath $adbPath)) { throw "adb 不存在：$adbPath" }
$deviceOutput = @(& $adbPath devices -l)
$adbDevicesExit = $LASTEXITCODE
if ($adbDevicesExit -ne 0) { throw "adb devices 失败" }
$deviceLines = @($deviceOutput | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice(\s|$)" })
if ($deviceLines.Count -ne 1) { throw "安装前需要且只能有一台 online Android 设备" }
$deviceSerial = ($deviceLines[0] -split "\s+")[0]
.\gradlew.bat :app:connectedDebugAndroidTest -PwebUiEnabled=true
if ($LASTEXITCODE -ne 0) { throw "instrumentation 测试失败" }
$installOutput = @(& $adbPath -s $deviceSerial install -r "D:/fangr/PycharmProjects/healthy agent/app/build/outputs/apk/debug/app-debug.apk")
if ($LASTEXITCODE -ne 0 -or -not ($installOutput -match "^Success$")) {
    $installOutput | Write-Output
    throw "APK 安装失败，禁止继续使用旧安装包验收"
}
$launchOutput = @(& $adbPath -s $deviceSerial shell monkey -p com.healthyagent.running -c android.intent.category.LAUNCHER 1)
if ($LASTEXITCODE -ne 0 -or -not ($launchOutput -match "Events injected: 1")) {
    $launchOutput | Write-Output
    throw "App 启动失败"
}
~~~

保存 instrumentation 报告并确认其中实际执行且通过 `AppModuleSmokeTest.productionGraphResolvesAndInfrastructureIsSingleton`；仅有 `connectedDebugAndroidTest` 任务成功但目标 smoke 被过滤或未发现，不能算 Koin 生产图验收通过。

- [ ] **Step 6: 完成 vivo + Keep B5 + Beats 的人工验收**

依次记录：

1. 开始跑步：App 出现心率，耳机听到“流萤已开始记录，出发吧。”
2. 暂停至少 60 秒：B5/App 心率继续变化，手机端时长、距离、配速不增长；恢复后无需手动连接且听到恢复提示。
3. 再次暂停，把 B5 暂时移出范围直至 App 显示断开，再放回范围：应自动经历 ERROR→CONNECTING→CONNECTED，仍保持 Paused；恢复后继续。
4. QQ 音乐播放中触发暂停/恢复和一次公里播报：音乐短暂 duck，播报走当前 Beats 路由，结束后音乐恢复。
5. 结束跑步：听到结束提示，记录正常保存；不得要求手工断开 B5。

- [ ] **Step 7: 强停、重启并读取上一进程诊断日志**

~~~powershell
$ErrorActionPreference = "Stop"
$adbPath = "D:/fangr/Android/Sdk/platform-tools/adb.exe"
if (-not (Test-Path -LiteralPath $adbPath)) { throw "adb 不存在：$adbPath" }
$deviceOutput = @(& $adbPath devices -l)
$adbDevicesExit = $LASTEXITCODE
if ($adbDevicesExit -ne 0) { throw "adb devices 失败" }
$deviceLines = @($deviceOutput | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice(\s|$)" })
if ($deviceLines.Count -ne 1) { throw "强停前需要且只能有一台 online Android 设备" }
$deviceSerial = ($deviceLines[0] -split "\s+")[0]

# 人工已听到结束提示后，再等待超过两个异步 flush timeout，并确认当前进程终态已落盘。
Start-Sleep -Seconds 3
$terminalDeadline = [DateTime]::UtcNow.AddSeconds(8)
$terminalSeen = $false
$previousProcessStartId = $null
do {
    $currentLines = @(& $adbPath -s $deviceSerial exec-out run-as com.healthyagent.running cat files/diagnostics/diagnostics.jsonl)
    if ($LASTEXITCODE -ne 0) { throw "强停前无法读取 current 诊断日志" }
    $currentEntries = @($currentLines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_ | ConvertFrom-Json })
    if ($currentEntries.Count -gt 0) {
        $previousProcessStartId = $currentEntries[-1].processStartId
        $terminalSeen = @(
            $currentEntries | Where-Object {
                $_.processStartId -eq $previousProcessStartId -and
                $_.payload.ruleId -eq "session_finished" -and
                $_.payload.reason -in @("policy_disabled", "tts_success", "tts_failure", "playback_timeout", "cancelled")
            }
        ).Count -gt 0
    }
    if (-not $terminalSeen) { Start-Sleep -Milliseconds 250 }
} while (-not $terminalSeen -and [DateTime]::UtcNow -lt $terminalDeadline)
if (-not $terminalSeen) {
    throw "当前进程的 session_finished 终态尚未落盘；禁止 force-stop，先保留进程取证"
}

& $adbPath -s $deviceSerial shell am force-stop com.healthyagent.running
if ($LASTEXITCODE -ne 0) { throw "force-stop 失败" }
$launchOutput = @(& $adbPath -s $deviceSerial shell monkey -p com.healthyagent.running -c android.intent.category.LAUNCHER 1)
if ($LASTEXITCODE -ne 0 -or -not ($launchOutput -match "Events injected: 1")) {
    $launchOutput | Write-Output
    throw "重启 App 失败"
}
Start-Sleep -Seconds 1
$listing = @(& $adbPath -s $deviceSerial shell run-as com.healthyagent.running ls -l files/diagnostics)
if ($LASTEXITCODE -ne 0) { throw "重启后无法列出诊断目录" }
$listing | Write-Output

$allPersistedLines = [Collections.Generic.List[string]]::new()
foreach ($fileName in @("diagnostics.3.jsonl", "diagnostics.2.jsonl", "diagnostics.1.jsonl", "diagnostics.jsonl")) {
    $fileLines = @(& $adbPath -s $deviceSerial exec-out run-as com.healthyagent.running cat "files/diagnostics/$fileName")
    $catExit = $LASTEXITCODE
    if ($catExit -ne 0) {
        if ($fileName -eq "diagnostics.jsonl") { throw "重启后 current 诊断日志不存在" }
        Write-Host "历史轮转文件不存在（允许）：$fileName" -ForegroundColor Yellow
        continue
    }
    $fileLines | Write-Output
    foreach ($line in $fileLines) { $allPersistedLines.Add($line) }
}
if (-not (($allPersistedLines -join "`n") -match [regex]::Escape([string]$previousProcessStartId))) {
    throw "重启后未找到上一进程 processStartId，跨进程持久化证据失败"
}
~~~

不存在的历史轮转文件允许命令提示缺失；current 必须存在，并能看到 BLE 生命周期/重连/心跳、规则触发或冷却、Speech/TTS 终态。抽查不得含设备地址、Bearer、API key、响应 body 或原始 B3/HCI。

- [ ] **Step 8: 输出阶段结果表**

结果必须分为：

| 状态 | 内容 |
| --- | --- |
| Done | 实际通过的单测、构建、安装与已执行真机场景 |
| Needs user action | 未执行的户外跨公里、断线或耳机验证 |
| Intentionally deferred | B5 距离、配速、千卡 DisplayData 动态映射（里程碑 B） |
| Assumptions | 设备、Fish 配置、音频路由与测试环境的实际状态 |

只有 Step 1–7 全部有真实证据时才宣称里程碑 A 完成；否则准确报告自动化完成度和剩余真机动作。

## 里程碑 B 后续入口

里程碑 A 稳定后另写计划：使用官方 Keep 做 2–3 分钟可控运动并开启 Bluetooth HCI snoop，记录 B5 屏幕指标变化点，形成可复现 cmd06 DisplayData fixtures，再确定 dataType、单位和缩放。该工作不回填到本计划，也不阻塞本计划实施。
