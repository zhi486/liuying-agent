# DeepSeek 实时陪伴句实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 用户已选择主代理直接实施；独立审查只读，不委派实现。

**Goal:** 让 DeepSeek 生成可选短鼓励，与确定性核心一次交给 Fish 发声，并通过手机私密入口配置。

**Architecture:** 独立凭据存储与请求配置对象隔离旧 Anthropic；现有语音准备总预算和媒体流程不变。Coordinator 负责开关、配置互斥及接纳时捕获，Activity 持有短生命周期原生输入框，Web 只收到布尔配置状态。

**Tech Stack:** Kotlin、协程、OkHttp、Kotlin Serialization、Android 原生 AlertDialog、DataStore、React、Zod、JUnit、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-11-outdoor-alerts-deepseek-design.md` 第 6–9 节；用户已审核批准并要求继续本批。

## Global Constraints

- 固定地址 `https://api.deepseek.com/anthropic/v1/messages`，显式模型 `deepseek-flash`，`thinking.type=disabled`、`stream=false`、`max_tokens=100`；禁重定向与自动重试。
- 文字最多 2 秒，包含在现有普通 8 秒／公里 15 秒从 SpeechBus 接纳起算的准备上限内，不重置预算、不自动重试或预热。
- 紧急提示与真实 BLE 通知始终原内置音频，0 AI/Fish；Fish 失败时普通本地核心、公里静默；不使用系统 TTS，不重做 14 音频。
- 无真实密钥／网络也必须完成模拟验证。真实联网、试听及安装独立验收；不使用聊天中的凭据，不保存到代码／文档／日志。
- 只生成陪伴，不新增数字、健康结论或运动指令；不发送路线、GPS 坐标、设备标识、会话标识、联系人或数据库。仅公里可发送现有分段与累计摘要。
- 响应最多 64 KiB；未知／不完整／截断内容不朗读；仅安全结果类别进入诊断，不记录原始异常或正文。
- 新开关持久化键 `deepseek_companion_enabled` 默认 false，旧 `llm_enabled` 不继承。密钥保存不改变开关、不代表连接成功。
- 保持 contractVersion 1，五项现有能力保留并追加 `deepseek-companion.v1`；新字段／命令两端严格同步。
- 原地 master 实施并分批本地提交，沿用已授权工作区方式；保留四份旧 dirty 文档，不 push，不清除用户数据。

## Task 1：专属凭据与受限文字客户端

**Files:** 新建 `app/src/main/java/com/healthyagent/running/data/local/DeepseekCredentialsStore.kt`、`ai/DeepseekRequestConfig.kt`、`ai/DeepseekCompanionTextSource.kt`；修改 `speech/SpeechRequest.kt`、`diagnostics/DiagnosticEvent.kt`、`diagnostics/FileDiagnosticLogger.kt`；对应同目录 JVM 测试。此任务暂不切换生产 DI，避免只接一半便启用网络。

**Interfaces:**

```kotlin
class DeepseekRequestConfig(internal val apiKey: String, val coachTone: CoachTone) {
    override fun toString() = "DeepseekRequestConfig(凭据已隐藏)"
}
class DeepseekCredentialsStore(private val file: File) {
    val configured: StateFlow<Boolean>
    fun capture(coachTone: CoachTone): DeepseekRequestConfig?
    fun save(input: String): Boolean
}
// SpeechRequest 末尾追加，不改变旧位置参数。
val deepseekConfig: DeepseekRequestConfig? = null
class DeepseekCompanionTextSource(
    private val callFactory: Call.Factory,
    private val diagnosticLogger: DiagnosticLogger,
) : CompanionTextSource
```

- [x] **Step 1：存储 RED。** 使用 JUnit TemporaryFolder 与真实文件：缺失时未配置；保存首尾空白裁剪的有效值后可重新加载；空、内部空白／控制字符、513 字符失败并保持旧值；512 字符有效；旧配置对象不会随更换变化；配置及 SpeechRequest 的 toString 不出现测试凭据；故障路径为合法目标的临时文件被目录占用，保存失败不破坏旧文件。

```kotlin
assertFalse(store.configured.value)
assertTrue(store.save("  test-only-deepseek  "))
val first = store.capture(CoachTone.BALANCED)!!
assertFalse(store.save("bad key"))
assertEquals("test-only-deepseek", store.capture(CoachTone.BALANCED)!!.apiKey)
assertFalse(first.toString().contains("test-only-deepseek"))
```

- [x] **Step 2：最小存储 GREEN。** 使用同目录临时文件写入与原子替换，成功替换后才更新内存值／configured；加载限长、错误仅未配置。验证输入先 trim，再拒绝内部空白和控制字符。文件路径由 Task 2 DI 指向 noBackupFilesDir，存储不输出原文或原始异常；不迁移旧设置。

- [x] **Step 3：客户端 RED。** 沿用现有 Call.Factory 假网络与 TrackingBody，测试真实 Request 内容／目标与返回处理。固定 endpoint/header/model/thinking/stream/token；普通请求仅核心与 tone，公里仅 split_data/session_summary；缺 config/关闭/URGENT/BLE 均不调用。接受成功自然完成的单个或多个 text 块的合并结果；拒绝 thinking/tool 块、空、换行、数字、结构化内容、重复核心、超过50字、非 end_turn、状态失败。覆盖401/403/429/5xx、64KiB边界、取消和迟到关闭。

```kotlin
assertEquals("https://api.deepseek.com/anthropic/v1/messages", call.request().url.toString())
assertEquals("deepseek-flash", payload["model"]!!.jsonPrimitive.content)
assertEquals("disabled", payload["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
pending.cancel()
runCurrent()
assertTrue(call.isCanceled())
assertTrue(call.respond(validResponse).closed)
```

- [x] **Step 4：客户端／诊断 GREEN。** 从 request.deepseekConfig 读取，不访问 LlmConfig。默认 OkHttp 关闭 followRedirects/followSslRedirects/retryOnConnectionFailure，callTimeout 为2秒；协程超时也取消call。有限读取与 JSON 类型校验，不输出正文；自然完成要求 stop_reason=end_turn，全content须text类型。过滤数字、控制/换行、结构化标记、核心重复和有限运动指令词；说明过滤非自然语言安全保证。DiagnosticEvent 新类型 `DeepseekCompanionResult(outcome)`，只枚举 attempted/success/missing_config/auth/rate_limit/network/timeout/cancelled/invalid_response/server；异常不携带消息。

- [x] **Step 5：定向、全 JVM、自审、独立复审和提交。**

```powershell
./gradlew.bat --gradle-user-home .gradle-local :app:testDebugUnitTest --tests '*Deepseek*Test' --tests '*FileDiagnosticLoggerTest' --tests '*VoicePolicyTtsEngineTest' -x :app:checkFrontend -x :app:verifyFrontendBundle --console=plain
```

先记录真实行为失败再 GREEN；新接口缺失可先补空壳使测试实际失败。全量 JVM 与 staged 凭据审计通过后提交「增加隔离的 DeepSeek 陪伴客户端与私密存储」。

## Task 2：私密入口、独立开关和严格契约

**Files:** 修改 `data/local/SettingsDataStore.kt`、`coordinator/AppCoordinator.kt`、`coordinator/AppSnapshot.kt`、`di/AppModule.kt`、`MainActivity.kt`、`ui/web/NativeWebBridge.kt`、`bridge/BridgeCodec.kt`、`bridge/BridgeSession.kt`、`ui/settings/SettingsScreen.kt`、`SettingsViewModel.kt`；新建 `ui/settings/DeepseekKeyDialog.kt`；对应 JVM／instrumentation 测试。修改 frontend 的 contract/schemas、SettingsPage、完整快照fixtures及测试、contracts/v1/README.md、ready/snapshot和新无参数命令fixture。

**Interfaces:** Coordinator 新增 `requestDeepseekConfiguration(): CommandOutcome`、`cancelDeepseekConfiguration(token: Long)`、`saveDeepseekKey(token: Long, input: String): CommandOutcome`。专用只读 `deepseekConfigurationRequest: StateFlow<Long?>` 仅含非秘密token，由当前Activity消费，不使用已有多消费者Channel，避免Compose页截走事件。保存操作在 IO dispatcher 调用，Coordinator 锁内复核 token 与会话并原子保存；开跑在配置窗口期间拒绝，避免异步save与start竞态。无效／旧token不能清新弹窗。

- [x] **Step 1：偏好与互斥 RED。** DataStore 预填旧 llm_enabled=true，新值仍false；新键true不写旧键。Coordinator 测试未配不能开启，活动／恢复不能打开，窗口期间不能开跑，保存失败保旧，cancel无写入，过期save拒绝，保存不自动打开AI。查询及快照只出现新布尔、不出现测试凭据。

```kotlin
assertFalse(SettingsDataStore(dataStoreWithOldEnabled).current().llmEnabled)
assertTrue(coordinator.requestDeepseekConfiguration() is CommandOutcome.Accepted)
val token = coordinator.deepseekConfigurationRequest.value!!
assertTrue(coordinator.startRun() is CommandOutcome.Rejected)
coordinator.cancelDeepseekConfiguration(token)
assertTrue(coordinator.startRun() is CommandOutcome.Accepted)
```

- [x] **Step 2：接入存储与有效状态。** DI 构造 `DeepseekCredentialsStore(File(androidContext().noBackupFilesDir, "deepseek/key"))`。Coordinator 合同映射统一经过 `settingsContract(settings)`，新 deepseekApiKeyConfigured 布尔读取存储，llmEnabled 用新偏好且已配置。原生设置AI开关调用Coordinator.updateSettings；旧Claude输入替换为打开同一私密入口，旧llmApiKey仍不迁移或作为新已配置判断。

- [x] **Step 3：弹窗安全实现和测试。** 原生AlertDialog/EditText只使用实例态，非rememberSaveable，无回填／显示按钮／自动读剪贴板；禁suggestions/autofill/personalized learning、isSaveEnabled=false；Activity及Dialog都设置FLAG_SECURE且结束恢复原flag，所有dismiss/destroy清输入并取消token。保存成功后清空关闭，失败显示固定错误，不展示异常。Android测试验证实际窗口flag、输入配置、dismiss清空和原状态恢复；仅编译不冒充真机执行。

```kotlin
input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
input.isSaveEnabled = false
input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
input.imeOptions = input.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
```

- [x] **Step 4：Web/契约 RED→GREEN。** 新无参数settings.configureDeepseek，body携带key任何字段必须拒绝；只调用协调器打开。新增严格布尔及第六能力，所有fixture同批；UI未配置禁开启但可点配置，运行/暂停禁配置；三种状态文案不把保存说成连接成功。保存密钥无自动网络；关闭开关不要求退出跑步。

```typescript
expect(sendNativeCommand).toHaveBeenCalledWith('settings.configureDeepseek', {});
expect(screen.getByText('已配置，AI 陪伴关闭')).toBeVisible();
expect(screen.queryByDisplayValue('test-only-deepseek')).not.toBeInTheDocument();
```

- [x] **Step 5：前后端回归、自审、独立复审和提交。** 定向 Settings/Coordinator/Bridge、全量前端与JVM、两APK构建；主与测试包签名、凭据、14音频审计通过；提交「增加手机 DeepSeek 私密配置与独立陪伴开关」。尚不安装或真实认证。

## Task 3：接纳时捕获并贯通整句语音

**Files:** `coordinator/AppCoordinator.kt`、`speech/SpeechRequest.kt`、`speech/VoicePolicyTtsEngine.kt`（仅必要预算／取消调整）、`di/AppModule.kt`及对应测试，必要更新无密钥的instrumentation语音用例。不清理无关旧AI模块。

**Interfaces:** Coordinator 为每条可联网请求在enqueue前附 `deepseekCredentialsStore.capture(currentCoachTone)`；仅新AI开关有效时捕获，URGENT/BLE无配置。`SpeechRequest`带不可变已隐藏凭据配置，旧pending/排队copy保留身份与原preparationAcceptedAt。生产DI将CompanionTextSource切到DeepseekCompanionTextSource。

- [ ] **Step 1：生产组合 RED。** 用真SpeechBus/VoicePolicy/DeepseekClient和假Call/假Fish验证：一条有效鼓励只合成一次 core+suffix；2秒超时实际cancel、Fish仅core；Fish失败普通local/km静默；排队吞掉准备时间，不另起8/15秒；开关关闭/密钥代次改变/停止/URGENT使旧call取消且晚响应不可播；关闭后再开不补播。计数验证旧LlmClient/analyze/预生成始终0。

```kotlin
assertEquals(listOf("跑步已暂停。能陪着你，我很开心。"), fish.synthesizedTexts)
assertEquals(0, legacyLlm.calls)
assertEquals(1, deepseekCalls.size)
```

- [ ] **Step 2：接线并保持预算。** DI替换文字源，不改Fish目的地址／音频焦点／缓存策略。Coordinator按实际新开关与捕获配置构造请求；设置关闭时沿用现有停止旧准备的路径，密钥更换也取消旧配置请求；保留公里摘要构造，不调用旧AnalysisManager网络或EmergencyPhraseCache预生成。外层2秒／8/15秒预算继续从原接纳时刻扣除。

- [ ] **Step 3：完整门禁、复审、提交与记忆。**

```powershell
$env:JAVA_HOME='C:/Users/fangr/.jdks/ms-17.0.20'
$env:ANDROID_USER_HOME='D:/fangr/PycharmProjects/healthy agent/.android-user'
$env:Path='C:/Program Files/nodejs;' + $env:Path
./gradlew.bat --gradle-user-home .gradle-local :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun --console=plain
```

统计新XML与前端结果，不复用旧696/105；APK原签名和14音频hash、主/测试APK及staged秘密扫描均通过后提交「接入 DeepSeek 实时鼓励整句语音」。独立复审无Important/Critical才交付。更新项目提供方与户外检查点；完整状态面板仍后置重核，不宣称新入口已认证。真机安装、用户输入和一次真实试听在之后按规格逐项确认。

## 自检与进度

- [x] 规格覆盖：Task 1 覆盖6.2凭据隔离/客户端/安全诊断；Task 2覆盖6.1私密入口/新开关/严格契约；Task 3覆盖6.3预算/语音组合和旧AI隔离，完整门禁在各任务末尾。
- [x] 接口一致：SpeechRequest末尾可空配置、capture在接纳前；凭据只在专属对象/noBackup存储；同一Coordinator锁负责配置与开跑互斥。
- [x] 官方文档2026-09-11复核：兼容基址仍为 https://api.deepseek.com/anthropic ，显式deepseek-flash，x-api-key/max_tokens/thinking均支持；网页工具404后通过只读公开HTTP读取，不调用真实API。
- [x] Task 1 完成。
- [x] Task 2 完成。
- [ ] Task 3 完成。

### Task 1 验证记录（2026-09-11）

- 存储初始 6 项测试中 4 项按预期 RED；客户端初始 10 项中 9 项按预期 RED；补实现后定向及全 JVM 通过。
- 自审／独立复审补测并修复：损坏 UTF-8 不得显示已配置；核心换标点不得重复；“一小时／壹小时”不得新增数字。保留“一起／一直”等非数值词组。
- 最终完整 JVM：717 项、0 失败、0 错误。未切换生产文字源、未安装、未请求真实 API、未试听。
- 独立复核：2 项 Important 与 1 项 Minor 已关闭，可进入 Task 2。

### Task 2 验证记录（2026-09-11）

- 独立开关、配置／保存与开跑互斥、过期令牌、恢复阶段限制、无密钥不可开启均有 RED→GREEN 测试；恢复后的新开关若缺少私密凭据则清为关闭，避免后来填写意外启用。
- Web 只发空 body 并接收布尔状态；新增第六能力，两端对新布尔拒绝字符串 true，全部快照样例同步。
- 为避免中间提交的新开关误触发旧 Anthropic，生产文字源提前切为 DeepSeek；Task 3 捕获配置前不会发送文字请求。
- 复审修复：原生恢复完成无新快照的按钮禁用滞留；Dialog 异步 OnDismiss 的同步幂等清理及旧窗口身份保护；OnShow 测试分主线程回调。独立复核全部关闭。
- 完整门禁：724 JVM、111 前端，lint／格式／类型／bundle、主和测试 APK 构建通过；3 项新 Android 窗口测试仅编译，未在真机执行。
- 主 APK SHA-256：ECCF32EA3E5741FCBAB8EB3F11C5BE049012557FE00ECA97902CE8AF6C601773；测试 APK：378497E0CFB1C423EE5A039CAE25D0E1BDC12D76B47C3AFDBB663024A5EA7568。
- 两包原签名不变，14 条音频哈希一致，主／测试包凭据扫描无发现。未安装、未联网生成、未试听。
