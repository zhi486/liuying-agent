# 户外 API 状态与开跑前检测实施计划

> **执行者要求：** 使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐项实施；每项先建立行为失败测试，再最小实现、回归及提交。以复选框记录实际证据，不把本文代码片段当作已经实现。

**目标：** 用户在手机上独立查看文字大模型、Fish 最近真实请求结果与实际播报来源，开跑前手动无声检测，并单独试听本轮生成音频。

**架构：** 原生内存状态仓库记录带配置／网络代次的请求结果；现有客户端提供脱敏结果，现有媒体路径提供真实播放事件。独立跑前控制器负责单轮检测、预览资源及取消；协调器将状态经严格桥接推送 React 首页，不解析磁盘日志或由网页调用 API。

**技术栈：** 当前 Kotlin、Coroutines、JUnit4、OkHttp、Koin、Android MediaPlayer／ConnectivityManager、React、TypeScript、Zod、Vitest；不升级版本、不增加第三方依赖。

**设计依据：** [已确认规格](../specs/2026-09-10-api-status-preflight-design.md)，用户已明确确认书面规格并要求进入实施计划状态。规格提交 `58e904f`，语音代码基线 `bb461ea`。

**当前阶段：** 仅制定计划，未执行下方任务、未构建安装、未发新付费请求。用户尚未选择本计划执行方式。

## 全局约束

- 固定健康提示原文、阈值及已打包的 14 条流萤音频不变，不重新生成资产。
- 在线仍将固定核心与可选陪伴句整段合成；公里数字整句合成，不恢复数字拼接。
- Fish 失败时固定事件播放内置流萤；公里语音跳过，距离、配速和记录不变。
- URGENT 立即本地，零文字 API、零 Fish；总语音关闭时仍保持静音。
- 不引入 Android 系统 TTS 回退，不改变音乐压低、焦点恢复和媒体取消规则。
- 正式语音的接纳起算 8／15 秒期限、可选文字生成最多 2 秒均不变。
- 不改 B5、GPS、千卡、运动记录结构，不处理尚未确定的 VPN 故障根因。
- 手动文字检测上限 2 秒；Fish 检测上限 15 秒，包含网络、下载和媒体准备。检测各可用服务最多一次、不自动重试；两服务独立，不串联生成试听。
- 检测不播放、不申请焦点、不保存运动；试听只播当轮检测文件，零额外 API 请求。语音总开关关闭时允许明确点击的无声检测，但禁止试听。
- 仅 `idle`／`finished`、恢复完成、前台跑步页且无活动会话时检测／试听；暂停仍算运动中。离页、后台、开跑或配置变化取消跑前工作。
- 网络变化提示复查，不凭网络恢复置成功；可观察不到的 VPN 内部换节点不作保证。不取消或延长正式语音以“修复”状态灯。
- Key、声线值、响应正文、原始异常、路线、IP 和 VPN 节点不得进入新增快照、前端或诊断。不要打印真实配置；测试只用明显假值。
- 取消与服务失败分开；只有当前代次和操作能提交终态。进程重启回到未检测，不增加状态数据库。
- 当前工作目录为 `D:/fangr/PycharmProjects/healthy agent`，沿用用户已授权的 master 原地开发；不新建分支／worktree、不推送、不卸载／清数据、不改 VPN／音量／省电。执行时如需改变工作目录策略，先征询用户。
- 原有三份未提交语音文档保持不动；本计划和规格状态更新独立提交。未来每项显式暂存其文件，禁止 `git add -A`。

## 文件职责与依赖

下文路径均相对于上述项目根目录；Android 包根为 `com.healthyagent.running`。

| 任务 | 主要产物 | 依赖 | 独立验收目标 |
| --- | --- | --- | --- |
| 1 | `cloud/CloudServiceModels.kt`、`CloudServiceStatusStore.kt` | 无 | 状态、代次、终态及时间正确 |
| 2 | `cloud/ServiceCallException.kt`、`CloudServiceBindings.kt`；现有客户端小改 | 1 | 结构化安全失败、配置快照配对、检测不重试 |
| 3 | `cloud/VoicePlaybackStore.kt`；媒体开始观察点及 Policy 接入 | 1、2 | API 与实际播放独立，旧语音行为不变 |
| 4 | `cloud/CloudPreflightController.kt`、`PreflightFiles.kt` | 1—3 | 无声单轮、独立试听、可取消且无迟到资源 |
| 5 | `cloud/NetworkChangeTracker.kt`、`AndroidNetworkObserver.kt`；协调器／DI／生命周期接线 | 1—4 | 切网、开跑、后台时许可与状态正确 |
| 6 | 原生／前端桥接契约、命令、能力与 fixtures | 1—5 | 请求立即应答、结果推送、防重发与严格校验 |
| 7 | `CloudServicesPanel.tsx`、样式和首页接入 | 6 | 用户可读、状态不冒认、检测和试听不误触 |
| 8 | 跨层回归与默认无声 instrumentation、交接 | 1—7 | 新功能可验证、旧语音／数据保护不退化 |
| 9 | 经授权的安装与人工验收记录 | 8 及用户配合 | 区分真实 API、播放、听感、户外结论 |

`cloud/` 的生产根目录是 `app/src/main/java/com/healthyagent/running/cloud/`，对应 JVM 测试根为 `app/src/test/java/com/healthyagent/running/cloud/`。不将全部状态逻辑堆入已有较大的 `AppCoordinator.kt`。

## 公共执行与验证规则

每个 PowerShell 会话先明确工作目录；沙箱访问失败时按工具权限请求升级，不通过其他工具绕开限制。

```powershell
Set-Location -LiteralPath 'D:/fangr/PycharmProjects/healthy agent'
$env:JAVA_HOME = 'C:/Users/fangr/.jdks/ms-17.0.20'
$env:ANDROID_USER_HOME = 'D:/fangr/PycharmProjects/healthy agent/.android-user'
```

以下定义仅在当前 shell 使用，避免在每一步复制环境和 Gradle 参数；不创建 `.ps1`：

```powershell
function Test-LiuyingJvm([string[]]$Classes) {
    $testArguments = @('--gradle-user-home', 'D:/fangr/PycharmProjects/healthy agent/.gradle-local', ':app:testDebugUnitTest', '--rerun', '--console=plain')
    foreach ($className in $Classes) { $testArguments += @('--tests', $className) }
    & './gradlew.bat' @testArguments
    if ($LASTEXITCODE -ne 0) { throw 'JVM 测试失败，请检查实际失败原因' }
}
```

每项先写给定断言，再运行观察失败；接口缺失编译错误只用于引入最小类型，必须再取得行为断言失败，不能作为唯一 RED。协程时限使用 `testScheduler.timeSource` 配对虚拟调度，不能把真实线程 sleep 当确定性测试。每个代码步骤给出的片段说明具体插入算法或合同；imports 使用表中真实类型，补齐所在文件已有结构，不复制不相关旧代码。

所有新 JVM 测试类以表中名字命名，测试函数用中文。每项 GREEN 后执行 `git diff --check`，显式暂存其生产与测试文件，运行已存在的只读检查脚本，再提交：

```powershell
& 'C:/Program Files/nodejs/node.exe' 'build/device-tests/20260910/check-commit-files.mjs' --staged
if ($LASTEXITCODE -ne 0) { throw '暂存内容审计失败，不得提交' }
```

下列提交步骤中的目录仅包含该任务新模块时才整体列目录；不得把其他人或其他任务的改动捎带提交。安全脚本为本地忽略工具，不加入仓库；恢复执行时先阅读其全文。

## Task 1：安全状态模型和代次仓库

**文件：** 新建 `cloud/CloudServiceModels.kt`、`cloud/CloudServiceStatusStore.kt`；新建 `cloud/CloudServiceStatusStoreTest.kt`（测试根）。

**接口：** 此任务定义后续所有状态字面量与签名，不包含 Android 或网络请求。

```kotlin
enum class ServiceId { TEXT, FISH }
enum class RequestOrigin(val wire: String) { PREFLIGHT("preflight"), RUN("run") }
enum class Availability(val wire: String) {
    DISABLED("disabled"), MISSING_CONFIGURATION("missingConfiguration"), READY("ready")
}
enum class ServiceError(val wire: String) {
    AUTH("auth"), REQUEST_REJECTED("requestRejected"), RATE_LIMITED("rateLimited"),
    SERVER("server"), NETWORK("network"), TIMEOUT("timeout"), CONTENT("content"),
    AUDIO_PREPARATION("audioPreparation"), PLAYBACK("playback")
}
sealed interface ServiceResult {
    data object Success : ServiceResult
    data object Cancelled : ServiceResult
    data class Failure(val error: ServiceError) : ServiceResult
}
@Serializable
data class ServiceAttemptSnapshot(
    val origin: String, val acceptedAtMillis: Long, val finishedAtMillis: Long,
    val elapsedMillis: Long, val result: String, val error: String?
)
@Serializable
data class ServiceStatusSnapshot(
    val availability: String = "missingConfiguration", val inFlight: Boolean = false,
    val lastAttempt: ServiceAttemptSnapshot? = null, val lastSuccessAtMillis: Long? = null,
    val staleReason: String? = null
)
class ServiceRequestToken internal constructor(
    internal val service: ServiceId, internal val configurationEpoch: Long,
    internal val networkEpoch: Long, internal val sequence: Long,
    internal val origin: RequestOrigin, internal val acceptedAtMillis: Long,
    internal val acceptedAt: TimeMark
)
```

`CloudServiceStatusStore(timeSource: TimeSource, nowMillis: () -> Long)` 对外提供：

```kotlin
val snapshot: StateFlow<Map<ServiceId, ServiceStatusSnapshot>>
fun configure(service: ServiceId, availability: Availability, configurationChanged: Boolean): Long
fun begin(service: ServiceId, origin: RequestOrigin, configurationEpoch: Long): ServiceRequestToken?
fun finish(token: ServiceRequestToken, result: ServiceResult): Boolean
fun invalidateNetwork()
```

`configure` 返回该服务配置代次供任务 2 的绑定使用。`begin` 配置不匹配或不可用则返回 null，不能拿到新代次替旧凭据报告。内部用一把短锁保护代次、序号、当前 token 与 Flow；锁内不调用外部回调、播放器或网络。

- [ ] 写以下行为测试，并增加“失败保留最近成功时间、取消不清复查、重复终态只认一次、旧操作不能清新 inFlight、负向调钟仍为非负耗时、首次初始化不标复查”用例：

```kotlin
@Test fun `切网后旧成功不能让状态变绿`() = runTest {
    val store = CloudServiceStatusStore(testScheduler.timeSource) { 1000L }
    val epoch = store.configure(ServiceId.FISH, Availability.READY, false)
    val old = requireNotNull(store.begin(ServiceId.FISH, RequestOrigin.RUN, epoch))
    store.invalidateNetwork()
    assertFalse(store.finish(old, ServiceResult.Success))
    val state = store.snapshot.value.getValue(ServiceId.FISH)
    assertFalse(state.inFlight)
    assertEquals("networkChanged", state.staleReason)
    assertNull(state.lastSuccessAtMillis)
}
```

- [ ] 运行 `Test-LiuyingJvm @('com.healthyagent.running.cloud.CloudServiceStatusStoreTest')`，记录具体断言 RED。
- [ ] 实现 `configure`／`begin`／`finish`；初始 TEXT/FISH 都未配置，禁用优先于缺配置。配置变化清在途、保留带失效标记的历史；网络变化不改配置代次。`finish` 核心判断：

```kotlin
if (currentToken !== token || token.configurationEpoch != configurationEpoch ||
    token.networkEpoch != networkEpoch) return false
val elapsed = token.acceptedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)
val resultWire = when (result) {
    ServiceResult.Success -> "success"
    ServiceResult.Cancelled -> "cancelled"
    is ServiceResult.Failure -> "failure"
}
```

此片段中的 `currentToken`／`configurationEpoch` 为该服务锁内槽位，`networkEpoch` 为仓库值；写入 `lastAttempt` 后清 token。成功／失败清 `staleReason`，取消保留它；只有成功写 `lastSuccessAtMillis`。所有外部时间值取非负，耗时不通过 wall clock 相减。

- [ ] 重跑相同类及当前桥接测试，确认快照没有凭据字段。
- [ ] 显式暂存本项四文件并提交：`git commit -m '增加独立云端请求状态与代次保护'`。

## Task 2：客户端结构化结果与配置绑定

**文件：** 新建 `cloud/ServiceCallException.kt`、`cloud/CloudServiceBindings.kt`；修改 `ai/AnthropicCompanionTextSource.kt`、`speech/CompanionTextSource.kt`、`speech/OkHttpFishSynthesisClient.kt`；修改已有 `ai/AnthropicCompanionTextSourceTest.kt`、`speech/OkHttpFishSynthesisClientTest.kt`，新增 `cloud/CloudServiceBindingsTest.kt`。

**消费：** 任务 1 的 `configure` 返回值、`ServiceError` 与 token。**产出：**

```kotlin
class ServiceCallException(val error: ServiceError) : IOException(error.wire)
fun serviceError(error: Throwable): ServiceError

// 保留 create 兼容旧替身；正式调用方使用 generate，取消必须继续抛出。
interface CompanionTextSource {
    suspend fun create(request: SpeechRequest): String?
    suspend fun generate(request: SpeechRequest): Result<String> {
        val text = create(request)
        return if (text.isNullOrBlank()) Result.failure(ServiceCallException(ServiceError.CONTENT))
        else Result.success(text)
    }
}
```

`AnthropicCompanionTextSource` 增加可注入的只读配置提供器 `configuration: () -> Pair<String, String>`，默认仍读取当前 `LlmConfig.apiKey`／`anthropicModel`；`generate` 覆盖并沿用解析／校验，`create` 委托 `generate().getOrNull()`。该 Pair 仅内部使用，不记录、不进入 DTO。

绑定不是新增配置来源：`CloudServiceBindings` 只保存当前接纳设置与模型，工厂和状态代次在同一短锁捕获，不订阅第二个旧设置 Flow。使用普通 class，避免自动 `toString` 暴露配置。

```kotlin
class BoundCloudServices internal constructor(
    val text: CompanionTextSource?, val fish: FishSynthesisClient?,
    val textEpoch: Long, val fishEpoch: Long
)
// 以下为 CloudServiceBindings 的公开成员合同：
fun update(settings: SettingsSnapshot, model: String)
fun capture(cacheDirectory: File, preflight: Boolean): BoundCloudServices
```

`CloudServiceBindings` 构造器接收 `CloudServiceStatusStore`、`textFactory: (String, String, Boolean) -> CompanionTextSource`、`fishFactory: (String, String, File, Boolean) -> FishSynthesisClient`；工厂最后一个布尔值均为 `preflight`。TEXT epoch 比较 llmEnabled／Key／model，FISH 比较 Key／reference；ttsEnabled 只控制预览取消／播放，不把 Fish API 变为禁用。首次 `update` 不标复查，主题和提示间隔不改变 API 代次。

- [ ] 写 HTTP 401、403、429、500、空文字、格式不合格、网络 IOException 和取消用例，使用现有 fake Call／TrackingBody；测试错误正文含 `测试错误响应正文不可进入诊断`，断言仅安全枚举可见。为绑定写：

```kotlin
@Test fun `旧配置绑定不能借新代次提交成功`() = runTest {
    val store = CloudServiceStatusStore(testScheduler.timeSource) { 1L }
    val oldEpoch = store.configure(ServiceId.TEXT, Availability.READY, false)
    store.configure(ServiceId.TEXT, Availability.READY, true)
    assertNull(store.begin(ServiceId.TEXT, RequestOrigin.RUN, oldEpoch))
}
```

另用记录工厂断言 `capture` 的客户端收到同一设置快照，调用 `update` 后旧绑定仍携带旧 epoch；客户端构造零请求。

- [ ] 运行 `Test-LiuyingJvm @('com.healthyagent.running.ai.AnthropicCompanionTextSourceTest','com.healthyagent.running.speech.OkHttpFishSynthesisClientTest','com.healthyagent.running.cloud.CloudServiceBindingsTest')`，取得行为 RED。
- [ ] 将失败在被折叠前映射为安全结果；未知异常不附原始消息或 cause，取消原样抛出。HTTP 映射核心：

```kotlin
fun httpServiceError(code: Int): ServiceError = when (code) {
    401, 403 -> ServiceError.AUTH
    429 -> ServiceError.RATE_LIMITED
    in 500..599 -> ServiceError.SERVER
    else -> ServiceError.REQUEST_REJECTED
}
```

`httpServiceError` 定义于 `ServiceCallException.kt`；`serviceError` 对 `ServiceCallException` 返回其 enum、`SocketTimeoutException` 返回 TIMEOUT、其他 IOException 返回 NETWORK、其他非取消错误返回 CONTENT。媒体错误由调用方明确映射，不用原始媒体异常推断网络。

- [ ] 为 `capture(..., preflight=true)` 的工厂设置如下实际 OkHttp 选项，保留生产原有重试行为不变；两种模式的 Fish 均 `readCache=false`：

```kotlin
val callFactory = OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
```

核对 Call.Factory 中没有认证重试器或应用层循环；fake 请求计数与选项断言覆盖“不自动重试”，不将一个取消中的 body 重发。

- [ ] 重跑上述三类及全部 speech 测试；兼容 `create` 原空结果行为，现有缓存测试默认参数不改，新增正式／检测 `readCache=false` 用例。
- [ ] 显式暂存本项文件并提交：`git commit -m '保留云端调用安全错误并绑定请求配置代次'`。

## Task 3：真实播报来源及生产请求状态

**文件：** 新建 `cloud/VoicePlaybackStore.kt`、测试 `cloud/VoicePlaybackStoreTest.kt`；修改 `speech/BundledVoiceStore.kt`（`VoiceFilePlayer` 实际位于这里）、`speech/PreparedVoicePlayer.kt`、`speech/VoicePolicyTtsEngine.kt`；修改对应 `PreparedVoicePlayerTest.kt`、`VoicePolicyTtsEngineTest.kt` 和 `SpeechAcceptanceBudgetTest.kt`。

**接口：** 保留旧播放器工厂及 `SpeechDelivery(text, played)` 返回合同，不给 Bus 文本冒充播放来源。增加默认不发事件的接口成员：

```kotlin
interface VoiceFilePlayer {
    fun observeStarted(observer: () -> Unit) {}
    suspend fun prepare(file: File, source: VoiceAudioSource): Result<Unit>
    suspend fun play(): Result<Unit>
    suspend fun play(file: File, source: VoiceAudioSource): Result<Unit> {
        val result = prepare(file, source)
        return if (result.isSuccess) play() else result
    }
    fun stop()
    fun shutdown()
}
@Serializable
data class VoicePlaybackSnapshot(
    val source: String, val purpose: String, val state: String,
    val updatedAtMillis: Long, val error: String?
)
class PlaybackToken internal constructor(internal val sequence: Long)
// VoicePlaybackStore(nowMillis: () -> Long) 的公开合同：
val snapshot: StateFlow<VoicePlaybackSnapshot?>
fun reserve(purpose: String): PlaybackToken
fun started(token: PlaybackToken, source: String)
fun finish(token: PlaybackToken, result: ServiceResult)
fun skipped(token: PlaybackToken)
fun invalidate(token: PlaybackToken)
```

允许 source=`online`／`bundled`／`none`、purpose=`run`／`preview`、state=`playing`／`completed`／`failed`／`cancelled`／`skipped`；只有 `reserve` 不改变最近播报。新的 reserve 作废旧身份；未开始取消不生成“已播放”。`skipped` 仅用于公里不播报，source=none；失败若未开始显示 source=none。

Policy 构造器尾部增加可选依赖 `statuses: CloudServiceStatusStore? = null`、`bindings: CloudServiceBindings? = null`、`playback: VoicePlaybackStore? = null`；正式 DI 必须全注入，旧测试可逐步保留原 fake factory。绑定存在时使用其捕获客户端，禁止在同次事件再调用旧 onlineFactory 多造请求。

- [ ] 给真实播放器 fake port 增加“只 prepare 没有 started；startPrepared 真调用 start 后才 started；start 抛错零 started；取消后迟到 prepared 零 started”断言。在 Policy fixture 加状态仓库，先写：

```kotlin
@Test fun `只预选在线来源不得显示播放中`() {
    val store = VoicePlaybackStore { 100L }
    val token = store.reserve("run")
    assertNull(store.snapshot.value)
    store.started(token, "online")
    assertEquals("playing", store.snapshot.value?.state)
    store.finish(token, ServiceResult.Success)
    assertEquals("completed", store.snapshot.value?.state)
}
```

- [ ] 运行 `Test-LiuyingJvm @('com.healthyagent.running.cloud.VoicePlaybackStoreTest','com.healthyagent.running.speech.PreparedVoicePlayerTest','com.healthyagent.running.speech.VoicePolicyTtsEngineTest','com.healthyagent.running.speech.SpeechAcceptanceBudgetTest')`，核对 RED。
- [ ] 在 `PreparedVoicePlayer.play` 已有 `startPrepared` 的短 `startGate` 中，`start()` 正常返回后仅发非阻塞、安全的 started 通知；不得等待 UI、网络或 suspend，不改变锁顺序。具体插入点：

```kotlin
// PreparedVoicePlayer 的字段和接口覆盖；观察者只能在开始前设置。
@Volatile private var startedObserver: () -> Unit = {}
override fun observeStarted(observer: () -> Unit) { startedObserver = observer }
```

```kotlin
operation.started = true
start()
runCatching { startedObserver() }
true
```

观察者仅写 `VoicePlaybackStore`；绝不在此执行调试导出或传感器操作。继续保留原平台 Main Looper、cleanupCompleted 与取消栅栏。

- [ ] 在 Policy 真正请求之前创建对应 token：文字仅在启用、有配置、剩余预算大于零时 begin；Fish 在 `synthesize` 前 begin，媒体 prepare 成功后 finish Success，不能在下载完毕就置绿。捕获绑定的 epoch 原样传入；begin 返回 null 时不更新状态，不借用当前 epoch。
- [ ] 把文字分支改为消费 `generate`，自己的 2 秒超时记 TIMEOUT、真实返回 Failure 记安全错误、父级取消记 Cancelled 后重抛；Fish 的 8／15 秒外层超时只在该 Fish token 已创建且未完成时记 TIMEOUT。伪代码替换点使用以下实际结果映射：

```kotlin
val phraseResult = textSource.generate(request)
val observation = phraseResult.fold(
    onSuccess = { ServiceResult.Success },
    onFailure = { ServiceResult.Failure(serviceError(it)) }
)
textToken?.let { statuses?.finish(it, observation) }
val suffix = phraseResult.getOrNull().orEmpty()
```

以上位于原文字超时 block 内；时限仍是 2000ms 与当前剩余期限的较小值。`textSource` 为本次 `BoundCloudServices.text`；只有未注入 bindings 的旧测试路径才使用构造器中的 companionTextSource。null 表示未配置／未启用，直接不请求。`textToken` 为当前操作字段。外层捕获取消先用 `currentCoroutineContext().ensureActive()` 区分调用者取消，不将高优先级抢占标网络故障。Fish API 成功已终结后播放失败不再改 API 状态。

- [ ] 注册每个实际 player 的 started observer，真实开播后更新 online／bundled；`play()` 结束依据 Result 更新播放终态。公里静默直接 `skipped`，URGENT 不创建 API token。任何异常记录都不能改变原返回／回退路径。
- [ ] 运行本项四类及全部 `speech.*`；必须保留 7999/8000、14999/15000、排队耗尽零 API、真实 prepare 超时、迟到不补播和全部焦点释放测试。
- [ ] 显式暂存并提交：`git commit -m '区分真实 API 结果与流萤实际播报来源'`。

## Task 4：无声检测、当轮试听与资源取消

**文件：** 新建 `cloud/CloudPreflightController.kt`、`cloud/PreflightFiles.kt`；测试 `cloud/CloudPreflightControllerTest.kt`、`cloud/PreflightFilesTest.kt`。

**接口：** 消费前述 Store／Bindings／播放器；不注入 SessionManager、RunRepository 或传感器。

```kotlin
@Serializable
data class PreflightSnapshot(
    val state: String = "idle", val previewAvailable: Boolean = false,
    val canCheck: Boolean = false, val canPreview: Boolean = false
)
enum class PreflightDecision { ACCEPTED, NOT_ALLOWED, BUSY, NO_PREVIEW }
data class PreflightEligibility(
    val foreground: Boolean, val runningPage: Boolean, val restorationComplete: Boolean,
    val sessionState: String, val hasActiveSession: Boolean, val ttsEnabled: Boolean,
    val networkObserved: Boolean = true
)
// CloudPreflightController 的公开合同：
val snapshot: StateFlow<PreflightSnapshot>
fun updateEligibility(value: PreflightEligibility)
fun check(): PreflightDecision
fun preview(): PreflightDecision
fun cancel()
fun invalidatePreview()
suspend fun cancelAndAwaitPlaybackRelease()
fun close()
```

构造器明确为 `(statuses: CloudServiceStatusStore, bindings: CloudServiceBindings, playback: VoicePlaybackStore, files: PreflightFiles, playerFactory: () -> VoiceFilePlayer, scope: CoroutineScope, timeSource: TimeSource)`。PreflightSnapshot.state 只允许 `idle`／`checking`／`previewing`／`cancelling`；操作结束恢复 idle，结果留在对应 API／播放快照。`PreflightFiles(root: File)` 提供 `createRound(): File`、`discardRound(directory: File)`；每轮独立 UUID 子目录，校验 canonical parent 严格等于 root、不是链接；只处理该轮直接文件，不做针对 cache 根的递归删除。

- [ ] 新建记录型测试替身：文字 `generate` 计数并返回 Result、Fish `synthesize` 计数并返回 TemporaryFolder 中写有 `test-audio` 的测试文件（fake player 不解析媒体）、播放器各自累计 prepare／play／shutdown。测试目录中定义以下替身，不调用 Android：

```kotlin
private class CountingPlayer : VoiceFilePlayer {
    var prepares = 0
    var plays = 0
    var shutdowns = 0
    private var onStarted: () -> Unit = {}
    override fun observeStarted(observer: () -> Unit) { onStarted = observer }
    override suspend fun prepare(file: File, source: VoiceAudioSource): Result<Unit> {
        prepares++
        return Result.success(Unit)
    }
    override suspend fun play(): Result<Unit> {
        plays++
        onStarted()
        return Result.success(Unit)
    }
    override fun stop() {}
    override fun shutdown() { shutdowns++ }
}
```

fixture 用任务 2 的实际 Bindings，工厂返回上述计数端口；采用 `backgroundScope` 和 `testScheduler.timeSource`。设置 `PreflightEligibility(true, true, true, "idle", false, true)`，断言 `check()==ACCEPTED`；`runCurrent()` 后两服务各一次、所有 player `plays==0`、`previewAvailable==true`；`preview()` 后总 Fish 请求仍为 1、仅新 player `plays==1`。在 `runCurrent()` 前重复 check 必须 BUSY。

- [ ] 再写 1999/2000、14999/15000、非协作迟到返回、缺 Key／AI 关闭零请求、语音关闭零播放、取消不置失败、资源缺失不补请求、试听期间新检测 BUSY、上轮终态不清新操作用例。
- [ ] 运行 `Test-LiuyingJvm @('com.healthyagent.running.cloud.CloudPreflightControllerTest','com.healthyagent.running.cloud.PreflightFilesTest')`，确认行为 RED。
- [ ] 实现 check 原子接纳：同一锁判资格／占用、生成 roundId 和接纳 TimeMark、失效旧预览，然后启动 supervisor 子任务；不要持锁执行 IO。文字与 Fish 并发但各一个，使用捕获的 Bindings，不从检测结果再串联 AI 文本到 Fish。

```kotlin
fun remainingMillis(acceptedAt: TimeMark, limitMillis: Long): Long =
    (limitMillis - acceptedAt.elapsedNow().inWholeMilliseconds).coerceIn(0L, limitMillis)
```

该私有函数用于接纳起算；文字固定使用 `SessionSpeechTriggerFactory.create(SessionSpeechEvent.STARTED)` 构造 `SpeechRequest(companionEnabled=true)`，不附 analysis；Fish 固定文本为“开拓者，我在这里陪着你。”。

- [ ] Fish 下载＋`player.prepare(file, ONLINE)` 完成后报告 API Success，先 shutdown 准备实例，才发布有效预览文件。preview 创建新的 player，观察 started 并播放文件，不创建客户端；准备及播放失败只改播放状态，保持先前 API 结果。
- [ ] 取消先作废轮次、撤销开播资格，再取消 Call／Job／player，异步等待清理；不能在 Main Looper 同步 join 工作线程。`cancel()` 只同步撤销身份并发出取消信号，可能阻塞的 stop／release 在锁外异步执行；等待接口由清理完成信号实现。复用原 `awaitPreparation` 的“可取消等待＋onCancel”模式，只在本控制器抽出私有辅助，不扩大旧 Policy 重构。旧工作 finally 只能删除自身目录；非协作下载迟到时仍删除自身目录，不发布可试听文件。不要为了清理立刻删除下载仍在写入的共享目录。
- [ ] `cancelAndAwaitPlaybackRelease` 仅等跑前实际媒体停止与焦点释放，不等待不合作网络返回；开跑无需等待完整 15 秒。未真正退出的网络 worker 保留原 round 所有权，不能伪装空闲后重用文件。
- [ ] 重跑本项、状态、播放器测试并提交：`git commit -m '增加无声跑前检测和零额外合成试听'`。

## Task 5：网络失效、原生生命周期与开跑隔离

**文件：** 新建 `cloud/NetworkChangeTracker.kt`、`cloud/AndroidNetworkObserver.kt`；修改 `di/AppModule.kt`、`coordinator/AppCoordinator.kt`、`coordinator/RunCommandDispatcher.kt`、`MainActivity.kt`、`ui/web/NativeWebBridge.kt`、`ui/web/WebPreviewHost.kt`；新增 `cloud/NetworkChangeTrackerTest.kt`，扩展 `coordinator/AppCoordinatorTest.kt`。

**接口：** 网络本地签名只用于相等比较，不进入日志／DTO；现有 manifest 已有 ACCESS_NETWORK_STATE，不新增权限。

```kotlin
data class NetworkSignature(
    val handle: Long?, val wifi: Boolean, val cellular: Boolean, val vpn: Boolean,
    val internet: Boolean, val validated: Boolean
)
class NetworkChangeTracker(private val onChanged: () -> Unit) {
    private var initialized = false
    private var last: NetworkSignature? = null
    fun accept(value: NetworkSignature) {
        val changed = synchronized(this) {
            val result = initialized && last != value
            initialized = true
            last = value
            result
        }
        if (changed) onChanged()
    }
}
```

`AndroidNetworkObserver(context: Context, onChanged: () -> Unit)` 提供 `initialized: StateFlow<Boolean>` 和幂等 `start()`／`close()`；用 `registerDefaultNetworkCallback`，依据实际回调 network＋capabilities 构造签名，不在 `onAvailable` 内同步读取 capabilities 来冒认验证。聚合事件在单一 dispatcher；忽略已被新默认网络替代的旧 onLost，不纳入信号强弱、带宽或 SSID。注册后若 activeNetwork 为空，建立全 false 的断网初始签名；否则等待该默认网络的首个 capabilities 建立基线。初始基线未知时 eligibility.networkObserved=false，禁止检测；建立后为 true。监听注册异常则显示安全的网络观察不可用错误并保持禁止检测，不把状态置绿或请求额外权限。

协调器新增 `setPreflightForeground(Boolean)`、`setPreflightRunningPage(Boolean)`、`checkCloudServices(): CommandOutcome`、`previewCloudVoice(): CommandOutcome`、`cancelCloudServices(): CommandOutcome`、`prepareForRunStart(): Boolean`；`prepareForRunStart` 撤销检测许可并立即取消跑前工作，返回是否有媒体清理屏障需要等待。DI 将已有接纳设置回调转发给 Bindings，再更新 eligibility；不得恢复旧配置 Flow。

- [ ] 写初始／重复／网络身份／能力变化用例：

```kotlin
@Test fun `初始网络和重复通知不触发复查`() {
    var changes = 0
    val tracker = NetworkChangeTracker { changes++ }
    val first = NetworkSignature(1, true, false, true, true, true)
    tracker.accept(first)
    tracker.accept(first)
    assertEquals(0, changes)
    tracker.accept(first.copy(handle = 2, wifi = false, cellular = true))
    assertEquals(1, changes)
}
```

协调器测试扩展已有 fake repository／sensor：检测时 records/checkpoints/route/session 不变；恢复未完成、paused、后台、非跑步页命令被拒；主题更新不失效；换 Key 时旧成功不能回写。

- [ ] 运行 `Test-LiuyingJvm @('com.healthyagent.running.cloud.NetworkChangeTrackerTest','com.healthyagent.running.coordinator.AppCoordinatorTest')`，取得 RED。
- [ ] DI 创建单例状态／播放仓库、Bindings 和跑前控制器，使用当前 TimeSource；网络 observer 在协调器建立观察时 start、close 时释放。网络 onChanged 只做状态 invalidateNetwork 与跑前 invalidatePreview／取消，不 stop 正式 SpeechBus。
- [ ] MainActivity 的 onResume 发布 foreground=true，onPause 在 super 前发布 false 并取消；恢复成功或状态变化时重算 eligibility。不会因页面回来或联网恢复调用 check。
- [ ] WebPreviewHost 的 LocalAssetsOnlyClient 增加路由回调，`onPageFinished` 和 `doUpdateVisitedHistory` 核验可信入口及 fragment=`/run` 后更新 runningPage；onPageStarted／resetDocument／detach 先置 false 并取消。Fragment 回调必须在 Task 8 真机验证，不能仅凭 React 卸载事件证明原生已知道离页。前端取消另作防线，见 Task 7。
- [ ] 开跑双重防护：Dispatcher 在向 RunningService 发 START 前撤销许可；Coordinator.startRun 的直接调用也执行相同防护。不可在其 `@Synchronized` 方法内部等待主线程播放器 release。将原 startRun 内容精确移入私有 `startRunAfterPreflight()`，入口以幂等“等待跑前媒体释放”的 Job 完成后进入原体；先登记 pendingStart，检测资格立刻为 false，重复 START 不重复创建会话。

```kotlin
// 此段运行在协调器 scope 内，外层锁仅登记 pendingStart，不包裹等待。
preflight.cancelAndAwaitPlaybackRelease()
currentCoroutineContext().ensureActive()
startRunAfterPreflight()
```

`preflight` 为本项注入的控制器；无跑前媒体时保持原同步路径。Dispatcher 的撤销只设置开始保留标志，不替 Coordinator 创建第二个 start Job；Coordinator 接管保留并登记唯一 pendingStart。close／服务启动失败取消保留及 Job；异步开始失败发布既有 ShowError，按真实 session／restoration／foreground 重算资格，不能永久锁住检测或迟到建记录。仅挪动原 startRun 方法体，不改距离计时、传感器或 SessionManager 规则。

- [ ] 写“真实 started 后先收到 preview stop／release 再允许 session_started、被撤销的旧预览不取得焦点、重复 START 不重复记录”顺序断言，重跑 Coordinator 和 speech 全量。
- [ ] 显式暂存并提交：`git commit -m '接入网络复查和跑前检测生命周期隔离'`。

## Task 6：快照与双端桥接合同

**文件：** 修改 `coordinator/AppSnapshot.kt`、`coordinator/AppCoordinator.kt`、`bridge/BridgeCodec.kt`、`bridge/BridgeSession.kt`、`ui/web/NativeWebBridge.kt`；前端 `frontend/src/bridge/contract.ts`、`schemas.ts`、`NativeBridgeClient.ts`、`bridgeStore.ts`（后三者与 contract.ts 同目录）；修改 `contracts/v1/README.md`、`contracts/v1/fixtures/valid/ready.json`、`snapshot.json`，同 fixtures/valid 目录新建 `command.services-check.json`、`command.services-preview.json`、`command.services-cancel.json`；更新原生 `bridge/BridgeCodecTest.kt`／`BridgeSessionTest.kt`、前端 `frontend/src/bridge/schemas.test.ts`／`NativeBridgeClient.test.ts`／`BridgeProvider.test.tsx` 和 `frontend/src/features/settings/SettingsPage.test.tsx`、`frontend/src/features/running/RunningPage.test.tsx` 的快照。

**接口：** 任务 1／3／4 的 serializable 模型复用，避免 Kotlin wire 复制一套不一致字段。

```kotlin
@Serializable
data class CloudServicesSnapshot(
    val text: ServiceStatusSnapshot = ServiceStatusSnapshot(),
    val fish: ServiceStatusSnapshot = ServiceStatusSnapshot(),
    val preflight: PreflightSnapshot = PreflightSnapshot()
)
// AppSnapshot 尾部新增带默认值字段：
val cloudServices: CloudServicesSnapshot = CloudServicesSnapshot()
val voicePlayback: VoicePlaybackSnapshot? = null
```

前端新增同名结构字段，新增 `ServiceCapability = 'snapshot.v1' | 'history.pagination' | 'settings.v1' | 'services.v1'`；`NativeBridgeCallbacks.onReady(snapshot: AppSnapshot, capabilities: readonly ServiceCapability[]): void`，bridgeStore 保存 capabilities，断开／错误清空，onSnapshot 不覆盖它。既有测试 snapshot 字面量全部补中性字段，不松开 Zod strict。

- [ ] 新 fixture 使用现有合法 UUID 形状，三命令严格空 body；例如完整 check 信封：

```json
{"contractVersion":1,"type":"command","id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","sentAt":"2026-09-10T14:00:00Z","payload":{"helloId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","name":"services.check","body":{}}}
```

preview/cancel 分别使用独立合法 UUID `dddddddd-dddd-4ddd-8ddd-dddddddddddd` 和 `eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee`，名称对应变更。增加非法 body、未知 error/source、负耗时、非整数时间、成功携带错误码等测试，两端一致拒绝。

- [ ] 同一 check ID 重发只调用控制器一次；同 ID 换 preview 内容拒绝；check 返回 accepted 不等待 15 秒，快照之后可分别更新两个服务。运行 JVM `bridge.*` 和前端 `src/bridge` 测试记录 RED。
- [ ] 命令路由添加以下分支；cancel 只取消检测／预览，绝不调用正式 speechBus.stop：

```kotlin
"services.check" -> coordinator.checkCloudServices()
"services.preview" -> coordinator.previewCloudVoice()
"services.cancel" -> coordinator.cancelCloudServices()
```

`PreflightDecision` 映射：ACCEPTED→Accepted，NOT_ALLOWED／BUSY→COMMAND_NOT_ALLOWED，NO_PREVIEW→RESOURCE_UNAVAILABLE，固定中文说明。协调器通过 Flow 更新 snapshot 的 revision，检测失败只在状态中显示，不乱弹系统提示音。

- [ ] ready 能力校验允许已有三项基础 tuple 和附加 services.v1 的四项 tuple，未知／重复仍拒绝；正式包发送四项。这里仅允许“有无能力”的判定，不添加历史快照迁移。旧消息封装版本维持 1，新增字段在新包内同步发出。
- [ ] 用有限枚举、非负安全整数验证 DTO；成功的 error 必须 null，failure 必须有效错误码，cancelled 不带服务错误；只有 `skipped` 可以表达公里跳过，不能由预览产生。时间的先后不以 wall clock 强制比较，elapsed 用单调值。
- [ ] 运行：

```powershell
Test-LiuyingJvm @('com.healthyagent.running.bridge.*','com.healthyagent.running.coordinator.AppCoordinatorTest')
Set-Location -LiteralPath 'D:/fangr/PycharmProjects/healthy agent/frontend'
& 'C:/Program Files/nodejs/corepack.cmd' pnpm exec vitest run src/bridge
& 'C:/Program Files/nodejs/corepack.cmd' pnpm typecheck
```

修复本项清单内所有确由新增字段导致的既有 snapshot 字面量，不改其他页面业务。返回项目根、显式暂存并提交：`git commit -m '扩展云端状态与跑前命令桥接合同'`。

## Task 7：首页状态区域与两种独立操作

**文件：** 新建 `frontend/src/features/running/CloudServicesPanel.tsx`、`CloudServicesPanel.module.css`、`CloudServicesPanel.test.tsx`、`cloudServicePresentation.ts`、`cloudServicePresentation.test.ts`；修改 `RunningPage.tsx`／`RunningPage.test.tsx`；不改主题 token 或重做全页布局。

**接口：**

```typescript
export function serviceStatusLabel(status: ServiceStatusSnapshot): string;
export function safeServiceErrorLabel(error: ServiceErrorCode): string;
export interface CloudServicesPanelProps {
  snapshot: AppSnapshot;
  supported: boolean;
}
```

前端 `ServiceStatusSnapshot`／`ServiceErrorCode` 在 Task 6 contract.ts 中按 Task 1 的 wire 字面量定义。Panel 内调用现有 `sendNativeCommand`，不 fetch、不创建音频、不调用浏览器 TTS。

- [ ] 为格式函数写状态优先级和文字断言；全部使用明确中文，无“永久已连接”分支：

```typescript
it('旧成功遇切网后显示需复查', () => {
  const status: ServiceStatusSnapshot = {
    availability: 'ready', inFlight: false,
    lastAttempt: { origin: 'preflight', acceptedAtMillis: 100, finishedAtMillis: 120,
      elapsedMillis: 20, result: 'success', error: null },
    lastSuccessAtMillis: 120, staleReason: 'networkChanged',
  };
  expect(serviceStatusLabel(status)).toBe('网络已变化，请重新检测');
});
```

安全错误文案固定 AUTH“鉴权或访问被拒”、RATE_LIMITED“请求受限”、SERVER“服务暂时异常”、NETWORK“网络请求失败”、TIMEOUT“请求超时”、CONTENT“返回内容不可用”、AUDIO_PREPARATION“音频准备失败”、PLAYBACK“播放失败”、REQUEST_REJECTED“请求被拒绝”，不展示原始异常。

- [ ] Panel 测试检查两个 label 独立、成功附时间和耗时、只联网未请求仍未检测、内置播放不改变 API label、检测 accepted 不表示成功、快速双击一次请求、无 capability 隐藏、mock 零命令、ttsEnabled=false 禁止试听但检测可用、running／paused 隐藏操作仍有状态、结束页允许检测。
- [ ] 运行前端两个新测试文件，确认行为 RED，再实现核心优先级：

```typescript
if (status.availability === 'disabled') return '未启用';
if (status.availability === 'missingConfiguration') return '未配置';
if (status.inFlight) return '请求中…';
if (status.staleReason === 'networkChanged') return '网络已变化，请重新检测';
if (status.staleReason === 'configurationChanged') return '配置已变化，请重新检测';
if (!status.lastAttempt) return '未检测';
if (status.lastAttempt.result === 'success') return '最近成功';
if (status.lastAttempt.result === 'cancelled') return '已取消';
return '最近失败';
```

Panel 按服务将“请求中”呈现为生成中／合成中；当前失败优先，旧成功仅作为副行。静默检测不生成最近播放卡；试听标记“试听”，来源和播放结果分别显示。

- [ ] 使用原页面 CSS 变量，新区域文字为主，不仅使用颜色；按钮成本文案照规格。pending 用 ref 先同步置位防同一事件循环重入，并结合原生 preflight.state，收到 accepted 后等待原生快照，不在 finally 直接把 UI 当空闲。
- [ ] 在 RunningPage 的 idle、finished、running／paused 三处渲染统一 Panel；supported 来自真实 bridge ready capabilities。页面卸载且实际有检测／试听时发送 services.cancel；无活动操作不发送，catch 只显示本地安全失败提示。NativeWebBridge reset／路由检查为最终防线，不单靠 React cleanup。
- [ ] 相对时间定时刷新只在面板挂载期间运行并清理，不调用 API；时间倒退显示“刚刚”，完整时间仍保留。补在途命令返回晚于页面离开的测试，不能 setState 复活检测。
- [ ] 运行所有前端单测、typecheck、lint、build，再重跑 RunningPage 原手环／运动／权限测试；格式化仅本项变更文件。显式暂存提交：`git commit -m '在跑步首页展示独立云端状态与无声检测'`。

## Task 8：跨层回归、默认无声真机保护和构建

**文件：** 新建 `app/src/androidTest/java/com/healthyagent/running/cloud/CloudPreflightInstrumentedTest.kt`；按实际失败点补上述 JVM 测试；新建 `docs/handoffs/2026-09-10-api-status-preflight.md` 记录构建及未完成项。

**接口：** 默认测试只使用模拟网络／无声播放器；可单独测试真 MediaPlayer.prepare 的资源路径，不开播、不调用 Fish。复用已有 APK 音频作本地准备 fixture，明确不是线上检测成功证据。

- [ ] 加强“真实默认客户端不会读取运行机配置后偷发请求”的测试保护：模拟测试显式传假工厂，测试名与日志注明“模拟”；如编写真实测试入口，第一条指令必须在任何 DI 网络操作前检查参数与无活动会话：

```kotlin
assumeTrue(
    "真实两 API 检测需要用户明确授权",
    InstrumentationRegistry.getArguments().getString("allowLiveCloudCheck") == "true"
)
assertNull(repository.getActiveSession())
```

这里 repository 复用 Koin 中既有 RunRepository，只做读取；真实入口不得由全量测试自动启用。试听另需 `allowCloudPreviewPlayback=true`，默认跳过，不用一个开关同时授权检测和播放。

- [ ] 编写默认无声 instrumentation 用例：核对实际 MainActivity 前后台、WebView hash 路由离页／返回、检查按钮命令去重、异步快照可达、原生拒绝 paused、真实 prepare 无焦点／零 start。对恢复和跨进程不得修改数据库，可使用隔离 fake coordinator fixture；真实记录只做前后计数一致性核对。本项只编译这些用例，安装后的实际运行归 Task 9，不先宣称真机通过。
- [ ] 故意让老轮 Fish 在新配置成功后才返回、让 AI 高优先级取消、让默认网络旧 onLost 晚于新 onAvailable 到达，确认 API 和播放两份状态均不回滚。若回调平台行为不同，先保留证据再调整路由观察，不能删掉离页取消要求。
- [ ] 全量验证（每条失败立刻停下修复，不继续安装）：

```powershell
Set-Location -LiteralPath 'D:/fangr/PycharmProjects/healthy agent/frontend'
& 'C:/Program Files/nodejs/corepack.cmd' pnpm check
if ($LASTEXITCODE -ne 0) { throw '前端全量检查失败' }
Set-Location -LiteralPath 'D:/fangr/PycharmProjects/healthy agent'
& './gradlew.bat' --gradle-user-home 'D:/fangr/PycharmProjects/healthy agent/.gradle-local' :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'JVM 或 APK 构建失败' }
```

读取当前 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总 suites／tests／failures／errors／skipped，核对本轮修改时间。历史 626／83 只是基线，不复用成新结果，不把 UP-TO-DATE 或 instrumentation 进程退出码当测试通过。

- [ ] 读取并运行已有 `build/device-tests/20260910/check-voice-release-apk.ps1`，分别检查主包与 `-TestPackage`；核对 14 MP3 哈希、无凭据、两包证书与旧签名一致，记录新 SHA256。未重新读脚本全文前不执行；任何新测试参数默认必须不联网、不发声。
- [ ] 对照规格第 8 节每行填写证据，做规格与代码质量审查；跨任务范围自 `58e904f` 后的新功能实现提交开始，排除旧未提交语音文档。审查问题修复后重跑受影响测试，不宣称在线／户外已通过。
- [ ] 显式暂存测试与新交接后提交：`git commit -m '增加云端检测跨层回归与安全验收门禁'`。计划仅在取得实证后勾选；本轮设计阶段不得提前勾选。

## Task 9：授权安装与人工确认

**文件：** 只更新新交接及本计划验收项；不加入 APK、数据库、配置、抓包或日志文件。

**前提：** Task 8 本地测试、构建与审查通过且无未解决严重问题；新 instrumentation 已编译但尚待安装运行。用户同意覆盖安装并连接已授权手机。检测与试听分别取得当次授权／准备确认，旧语音测试的一次付费许可已经用过，不能复用。

- [ ] 先阅读现有 `check-voice-release-device.mjs`，以 `--inspect`／`--settings-check` 只读核验设备、无活动记录及配置匹配布尔值；再 `--backup` 生成独立一致性副本，保留原备份。USB 不在时继续文档，不猜设备已连接。
- [ ] 安装前再次核对新主包／测试包哈希与原签名，以及手机确为用户目标设备；本步只检查。下一步只覆盖安装各一次，任一失败停止，绝不 uninstall／clear；安装后核对手机实际 APK 哈希和记录／设置保留。
- [ ] 仅在上述门槛通过且 `adb devices -l` 再确认设备为 `10AD590X620043S` 时执行以下两次覆盖安装；设备变化先重新确认目标，不盲用旧序列号：

```powershell
& 'D:/fangr/Android/Sdk/platform-tools/adb.exe' -s '10AD590X620043S' install -r -t 'D:/fangr/PycharmProjects/healthy agent/app/build/outputs/apk/debug/app-debug.apk'
if ($LASTEXITCODE -ne 0) { throw '主包覆盖安装失败，停止' }
& 'D:/fangr/Android/Sdk/platform-tools/adb.exe' -s '10AD590X620043S' install -r -t 'D:/fangr/PycharmProjects/healthy agent/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
if ($LASTEXITCODE -ne 0) { throw '测试包覆盖安装失败，停止' }
```

- [ ] 先运行 `CloudPreflightInstrumentedTest` 的默认无声项，确认无网络／无声保护真实生效；读取每项 instrumentation 状态，跳过项单列。
- [ ] 用户切到流量＋VPN 后，由用户明确点击一轮“开跑前检测”；告知最多文字一次、Fish 一次。分别记录成功／失败／耗时／时间，任一失败不自动重试；不把仅 Fish 通过写成文字 AI 通过。
- [ ] 用户戴好 Beats 后单独点击“试听刚才生成的语音”；确认零额外 Fish 请求，听感为流萤，QQ 音乐压低并恢复。日志 completed 不替代用户听感。
- [ ] 用户配合切 Wi-Fi／流量或 VPN 时只观察需复查，未再次点击不得新增付费请求；测试离页／后台取消，不更改系统 VPN 或省电设置。
- [ ] 分别报告“代码／模拟／安装／无声真机／真实两 API／Beats／户外长时与 B5”的状态。没有户外或真实公里数据就保持未验收，千卡可信来源仍独立待办。

## 规格覆盖自查与执行交接

| 规格部分 | 实现任务 | 判定证据 |
| --- | --- | --- |
| 1—2 目标及既有策略不变 | 3、8 | 原语音规则、预算、焦点与零系统 TTS 回归 |
| 3 首页与检测／试听交互 | 4、6、7 | 操作互斥、能力门、前端渲染及路由取消 |
| 4 真实结果及成本／时限 | 2—4 | fake Call 次数、结构化结果、真实 prepare、边界时限 |
| 5 状态失效／并发 | 1—5 | 旧配置／网络／操作迟到均不回写，取消独立 |
| 6 组件和桥接边界 | 5—7 | 生命周期、立即应答、双端 strict fixtures |
| 7 错误和隐私 | 1、2、6、8 | 有限 DTO、错误正文不泄露、APK／暂存扫描 |
| 8 验收标准 | 8—9 | 自动与人工分别记录，不合并成一个通过结论 |
| 9 阶段约束 | 本计划 | 仅计划已编写；执行方式待用户选择 |

实施时接口调整若涉及上表任务间合同，应先同步本计划和所有消费者再写代码，不新增通用监控平台、远程后台、轮询任务或额外健康建议。Android 新回调在本计划仅作为实现选择，网络工具本轮不可用；用当前 SDK 编译与 Task 8 设备行为测试验证，不虚构已获得官方网页或真机证据。

执行可选择逐任务子代理并在任务间审查，或本会话按 executing-plans 分批推进。无论方式，Task 9 安装和真实请求都保留独立用户配合门槛。
