# 在线整段与离线固定流萤语音实施计划

> 执行者须使用superpowers:subagent-driven-development逐项实施、逐项审查；若用户指定单线执行，则使用superpowers:executing-plans。任务内遵循TDD，步骤以复选框记录。

**目标：** 跑步语音在线整段流萤合成，离线只播固定核心、跳过公里语音，URGENT立即本地，彻底移除正式链路的系统声线回退。

**架构：** 保留SpeechBus的调度和会话隔离，新增携带规则身份的语音请求及可区分实际播放/静默跳过的返回值。准备阶段负责可选陪伴文字、限时Fish生成或内置资源选择，并等待真实媒体prepared，再由独立播放阶段申请焦点并开播。生产依赖图接入新路由，既有Fish/Android单引擎类不作无关重构。

**技术栈：** Kotlin、Coroutines、JUnit4、OkHttp、Android MediaPlayer与现有AudioPlayerPort/AudioFocusController、Node.js/FFmpeg小样工具。

**设计依据：** `docs/superpowers/specs/2026-09-10-online-whole-offline-core-voice-design.md`，用户已批准执行。

**工作目录：** `D:/fangr/PycharmProjects/healthy agent`，用户允许先提交当前master检查点再原地实施。基线提交`261f422`，提交后工作区干净；前端83/83、JVM524/524，JVM于2026-09-10 15:38强制重跑通过，测试包编译通过。本文以下文件路径均相对此根目录。

## 全局约束

- 普通音频准备上限8000ms，公里15000ms，可选AI文字2000ms和真实媒体prepare包含在总上限内；实际开播后的播放阶段独立60000ms保护。
- 上述8/15秒用于当前在线整段准备；超时只转本地一次或跳过公里。本地解码保留已有60000ms故障看门以避免无限挂起，期间无焦点且可取消；不增加第二次在线等待预算。
- URGENT不调用AI或Fish，直接本地；语音总开关关闭仍保持静音。
- 固定原文和真实数字不得交给AI改写；AI只提供可选陪伴文字，不增加健康判断或行动建议。
- 在线路径不能把历史缓存命中当作本次联网成功；离线不回放历史公里语音。
- 网络及媒体准备等待不占用音频焦点；只在实际播放时压低音乐，所有终态释放资源。
- 取消/过期不回退、不补播；播放开始后失败不自动重头播本地；系统TTS调用次数必须为0。
- 按ruleId识别km_split；goal_achieved虽然同为SPLIT优先级，仍有本地提示。
- 只新增1条完整公里小样和13句固定音频，复用已有hr_rapid_rise；制作成功的文件不重新收费生成。
- 密钥仅在已授权配置或App私有设置中，禁止写入源码、APK、测试报告和日志。生成脚本只向Fish发送指定非私人样本文字。
- 不改B5、GPS、健康阈值或千卡；不卸载、清数据、调整音量/VPN/系统省电，不推送远端。
- 所有增量提交只能列出当前任务拥有的文件，不使用git add -A；审查以261f422之后的改动为范围。

## 公共验证命令

以下环境只设置于当前PowerShell进程，不改全局配置；运行命令需明确Set-Location，避免工具默认目录偏移。

```powershell
Set-Location -LiteralPath 'D:/fangr/PycharmProjects/healthy agent'
$env:JAVA_HOME = 'C:/Users/fangr/.jdks/ms-17.0.20'
$env:ANDROID_USER_HOME = 'D:/fangr/PycharmProjects/healthy agent/.android-user'
& './gradlew.bat' --gradle-user-home 'D:/fangr/PycharmProjects/healthy agent/.gradle-local' :app:testDebugUnitTest --tests 'com.healthyagent.running.speech.*' --rerun --console=plain
```

每次验证必须检查退出码及JUnit XML的失败/错误/跳过计数；UP-TO-DATE不能作为本轮真实执行证据。测试先失败于缺失行为，再写实现，再绿色；编译错误只用于建立接口，不能代替行为红灯。

## Task 1：完整公里试听与人工听感门槛

**文件：** 修改忽略目录中的`build/device-tests/20260910/make-voice-audition.mjs`；修改`app/src/androidTest/java/com/healthyagent/running/speech/LocalVoiceAuditionTest.kt`；结果写入现有voice-audition目录的独立新文件。

**接口：** 新增`--km-whole`生成变体，输出`sample-km-whole.mp3`和`manifest-km-whole.json`；实机参数`auditionSample=km-whole`。不修改正式App，不调用真实跑步流程。此任务是用户授权的抛弃式试听原型，不把脚本当生产语音实现。

- [x] 在原脚本新增单次合成文字及互斥变体，不动已有4份小样：

```javascript
const kmWhole = process.argv.includes('--km-whole');
if (kmWhole && continuous) throw new Error('试听变体不能同时选择');
const variant = kmWhole ? 'km-whole' : continuous ? 'continuous' : null;
const kmText = '第一公里，用时六分整，配速每公里六分整。开拓者，我们又一起完成了一公里，我会继续陪着你。';
const clips = variant
  ? [[variant, kmWhole ? kmText : baseClips[0][1] + baseClips.at(-1)[1]]]
  : baseClips;
```

- [x] dry-run、samples及manifest分支统一使用variant；白名单添加`km-whole`。运行`node --check`及`--dry-run --km-whole`，确认只生成1条，没有读配置或联网。
- [x] 只执行一次`--generate --km-whole`，核对时长、编码、SHA256；源文件已存在时复用，不重复请求Fish。
- [x] 编译临时测试包，原签名SHA256须为`ebad2b92cff0324810062be3e8116645cfd352780ad79141be61be148774c603`。通过ADB复制独立新样本并比对手机哈希，只有真机无活动跑步时才能试听。
- [x] 用户明确戴好Beats、准备听之后，运行一次：

```powershell
& 'D:/fangr/Android/Sdk/platform-tools/adb.exe' -s 10AD590X620043S shell am instrument -w -r -e class 'com.healthyagent.running.speech.LocalVoiceAuditionTest#playOnePreparedSample' -e allowLocalVoiceAudition true -e auditionSample km-whole com.healthyagent.running.test/androidx.test.runner.AndroidJUnitRunner
```

- [x] 核对真实播放完成、恢复主页面，请用户评价数字读法及衔接；16:03只播一次、9.097秒1/1，用户确认“可以可以，很满意，很自然”，允许进入生产实现和13句批量制作。
- [x] 增量提交试听工具、听感记录及已批准实施计划（controller明确文档范围），生成缓存不提交；3aeb23b审查通过，无生产修改和额外付费请求。

## Task 2：带规则身份的请求、固定映射和公里整句格式

**新建：** `speech/SpeechRequest.kt`、`speech/BundledVoiceCatalog.kt`、`speech/KilometerSpeechFormatter.kt`（均位于`app/src/main/java/com/healthyagent/running/`下）；相应`app/src/test/java/com/healthyagent/running/speech/*Test.kt`。
**修改：** `speech/TtsEngine.kt`、`core/engine/rules/KmSplitRule.kt`及其直接断言测试。

**产生接口：**

```kotlin
data class SpeechRequest(
    val trigger: RuleTrigger,
    val companionEnabled: Boolean = false,
    val analysis: LlmAnalyzeRequest? = null,
)
data class SpeechDelivery(val text: String, val played: Boolean)
data class BundledVoice(val ruleId: String, val text: String, val fileName: String)
// BundledVoiceCatalog.find(ruleId: String): BundledVoice?
// BundledVoiceCatalog.entries: List<BundledVoice>
// KilometerSpeechFormatter.format(index: Int, durationSec: Long, paceSecPerKm: Double): String
```

在TtsEngine保留现有文本接口，并增加默认兼容入口：

```kotlin
suspend fun speak(request: SpeechRequest): Result<SpeechDelivery> =
    speak(request.trigger.speakText, request.trigger.ttsPriority)
        .map { SpeechDelivery(request.trigger.speakText, played = true) }
```

- [x] 先写公里真实输出红灯，旧格式会失败；字面期望不能由待测格式化器计算：

```kotlin
assertEquals("第一公里，用时六分整，配速每公里六分整。",
    KilometerSpeechFormatter.format(1, 360, 360.0))
assertEquals("第十二公里，用时六分零五秒，配速每公里六分零五秒。",
    KilometerSpeechFormatter.format(12, 365, 365.0))
```

- [x] 增加非整分钟、十/十一/二十公里、小于一分钟、配速无效时不编造数字的用例。无效配速只省略配速子句，保持真实公里和用时；小数秒保持既有统计取整口径。
- [x] 实现14条固定映射，文字来自已批准设计表；未知ID返回null，不按文本近似匹配。以消费者取得正确资源及未知ID不可误播验证映射，避免只测试常量个数。
- [x] 接入KmSplitRule，证明规则仍由真实Split触发且优先级/冷却不变；运行speech/core.engine规则测试至绿色。
- [x] 提交当前任务文件并做规格/代码审查，记录红绿证据。8fd9208/b449097/b288791；全量536/536，审查后增补110/1010边界定向38/38，复审通过。

## Task 3：已准备文件的独立播放器与内置资源存取

最终审查修正：下方为Task3最初接口记录，不能再把取得File视为媒体已准备。最终统一修复将现有文件播放器/Android端口拆分为可取消prepare与实际play/startPrepared；普通8秒、公里15秒包含真正prepared，准备无焦点，保留真实清理完成门。具体接口与验证证据以最终修复报告和源码为准。

**新建：** `speech/PreparedVoicePlayer.kt`、`speech/BundledVoiceStore.kt`、`speech/AndroidBundledVoiceStore.kt`及对应单测/实机资源测试。
**消费：** Task 2的BundledVoiceCatalog；现有AudioPlayerPort/AudioFocusController/SpeechAudioProfile。
**产生接口：**

```kotlin
enum class VoiceAudioSource { BUNDLED, ONLINE }
interface BundledVoiceStore { suspend fun open(ruleId: String): File? }
interface VoiceFilePlayer {
    suspend fun play(file: File, source: VoiceAudioSource): Result<Unit>
    fun stop()
    fun shutdown()
}
```

- [x] 用现有端口级测试替身编写行为红灯：只有端口onCompletion才成功；onError失败；取消/停止/60000ms超时释放播放器和焦点各一次；迟到回调不再改终态。
- [x] PreparedVoicePlayer构造注入`audioPlayerFactory: () -> AudioPlayerPort`、`audioFocusController: AudioFocusController`、`diagnosticLogger: DiagnosticLogger`、`playbackTimeoutMillis: Long = 60000`。使用独立操作对象保存CompletableDeferred终态、player和lease；锁内领取清理权，锁外调用端口，避免同步start回调死锁。

```kotlin
// 核心不变量：完成权只有一个，所有清理基于被领取的操作资源。
if (operation.terminal.compareAndSet(null, outcome)) {
    operation.completion.complete(outcome)
    cleanup(operation)
}
```

- [x] 为start同步完成、start过程中stop、旧回调晚于新操作、焦点拒绝、release异常分别增加回归。未知播放结果不能提前成功。
- [x] AndroidBundledVoiceStore从`assets/voice/liuying/v1/`读取清单和资源，核对SHA256后复制到App自有目录供现有File端口使用；只允许清单ID，不能接受任意路径。资源缺失/不匹配返回受控失败，不访问网络。当前为fixture验收，真实14资产归Task6。
- [x] 核对准备资源阶段不请求焦点；真实播放只使用ASSISTANT_SPEECH。42a4544全量558/558；审查后以e42b96e修复清理完成前放行下一播放的竞态，新增latch红灯后定向26/26、前端83/83，复审通过。

## Task 4：限时在线准备、离线回退与纯路由测试

**新建：** `speech/VoicePolicyTtsEngine.kt`、`speech/CompanionTextSource.kt`及测试。
**修改：** `speech/OkHttpFishSynthesisClient.kt`及其测试，新增`readCache: Boolean = true`构造参数；新生产路径传false，原客户端/试听默认行为保持兼容。
**消费：** Task 2/3接口及现有FishSynthesisClient。
**产生接口：**

```kotlin
interface CompanionTextSource {
    suspend fun create(request: SpeechRequest): String?
}
class VoicePolicyTtsEngine(
    private val bundledStore: BundledVoiceStore,
    private val onlineFactory: () -> FishSynthesisClient?,
    private val companionTextSource: CompanionTextSource,
    private val playerFactory: () -> VoiceFilePlayer,
    private val diagnosticLogger: DiagnosticLogger,
) : TtsEngine
```

- [x] 先写路由行为红灯：URGENT零网络且播放对应本地；km_split失败played=false；goal_achieved失败播本地；正常在线只向Fish提交核心加后句的一个text。
- [x] 每个操作独享在线client与player，stop先使该操作失效，再取消它自己的请求和播放器；旧操作清理不得关闭新操作。不能让忙碌标志阻止刚抢占进来的URGENT。
- [x] 以runTest虚拟时钟覆盖7999/8000ms及14999/15000ms边界、2000ms可选AI期限。prepare超时在播放前生效；正在播放时不受这两个期限截断。
- [x] 超时用显式client.cancel配合协程取消；不能仅给阻塞HTTP外层套withTimeout却仍等待IO返回。取消父作用域时也必须取消该操作HTTP；测试不可取消/迟到假服务的结果不能进入播放。

```kotlin
// 每条消息先分类，再准备，只把最终文件交给播放器。
val urgent = request.trigger.ttsPriority == SpeechPriority.URGENT
val kilometer = request.trigger.ruleId == "km_split"
val preparationLimit = if (kilometer) 15000L else 8000L
```

- [x] 在线失败只有未开始播放且仍有效的固定事件能转本地；取消、已开始播放的失败、迟到结果不重播。无本地资源时返回受控失败，系统TTS从未作为依赖注入此类。
- [x] 验证readCache=false时即使有同文历史缓存也发起本次请求；请求失败不会返回旧缓存。原readCache=true命中路径仍通过旧测试。
- [x] 全部路由/竞态测试绿色后提交并审查；诊断记录来源和终态，不打印text或原始网络异常。05d0519全量580/580；35e11b1补无句末标点核心与后句的分隔，RED后路由22/22、前端83/83，复审通过。

## Task 5：可取消的陪伴文字来源与生产接线

**新建：** `ai/AnthropicCompanionTextSource.kt`及测试。
**修改：** `speech/SpeechBus.kt`、`coordinator/AppCoordinator.kt`、`di/AppModule.kt`、对应SpeechBusTest/AppCoordinatorTest/AppModuleSmokeTest；按需扩展脱敏诊断枚举。
**消费：** Task 2的SpeechRequest/Delivery，Task 4的VoicePolicyTtsEngine/CompanionTextSource。

- [x] 先写Bus红灯：typed入口得到实际本地文本，不能把未播鼓励显示为已播放；played=false仍可显示公里核心，但诊断不是TTS_SUCCESS。保留原文本入口以兼容既有测试。
- [x] ScheduledSpeech保存SpeechRequest，trigger只作派生属性；新增enqueue(request, expectedSessionGeneration)和enqueueSessionStatus(request)，原RuleTrigger入口包装后委托。会话代际/修订号/抢占/结束终态刷新逻辑保持。
- [x] AnthropicCompanionTextSource沿用已有LlmConfig模型/API配置，普通请求仅含固定核心及必要非位置上下文，公里请求复用PromptBuilder的已有摘要；不伪造普通事件的公里分段。仅输出一条短陪伴句，禁止改写核心或提供新的健康动作。
- [x] 注入OkHttp Call.Factory，用可取消的异步请求或等效机制确保2秒到期即取消Call；响应必须use关闭，不记录HTTP响应体/令牌，CancellationException继续抛出。fake Call.Factory测试请求内容、取消和迟到响应，不使用真实AI额度。
- [x] Coordinator为一次公里事件附加同一分段analysis数据与companionEnabled；移除该事件原先独立AnalysisManager语音派发，避免公里后再播一条llm_coach。关闭旧URGENT缓存预生成/consume/回填的正式调用，核心原文固定。
- [x] DI将正式TtsEngine绑定VoicePolicyTtsEngine，每次在线请求建立独立FishSynthesisClient且readCache=false；不实例化AndroidTtsEngine作为回退。已存在的旧类/测试不作无关删除。
- [x] 用真实Coordinator+Bus及外部端口替身验证开始/暂停/恢复/结束、语音关闭、重载配置、跨会话旧请求、URGENT抢占。网络等待期间焦点请求为0；原B5/GPS侧效应保持原测试。
- [x] 494be97全量595/595、前端83/83、androidTest编译通过；审查发现旧Flow回滚设置，3bd18fa以专用Mutex统一读取/接纳，2项确定性RED后45/45 GREEN，复审通过。

## Task 6：固定资源生成、封装与覆盖验收

**新建：** `app/src/main/assets/voice/liuying/v1/manifest.json`及14个清单MP3，实机资源覆盖测试；制作脚本位于忽略的build目录。
**前置：** Task 1用户数字试听通过；Task 3存取接口已绿色。

- [x] 先写资源覆盖失败测试：从真实RuleCatalog的非km规则及四种SessionSpeechEvent产生ID，使用真实store逐个打开；缺文件、哈希错误、未知ID都不能被网络或系统TTS替代。真实资源RED2项1失败，补齐后2/2 GREEN。
- [x] 将已确认的core小样按hr_rapid_rise清单记录复用；只为其余13条设计表原文各请求一次Fish。13次全部成功、无重试，复用core1条。
- [x] 生成manifest记录版本、ruleId、原文、文件名、SHA256、duration；所有文件统一48kHz单声道MP3。仅处理首尾静音，不剪句中停顿，不把参考声线ID/密钥写入manifest。
- [x] 将音频复制到assets后，真实store资源测试绿色；APK内15文件、14项hash和严格schema一致；不依赖ADB临时cache。全量JVM599/599、前端83/83，androidTest编译与主APK构建通过；真机执行仍归Task7。
- [x] ef96386提交14条资源、清单与两份测试，共17文件，暂存字节审计无发现，独立规格/质量审查通过。音质与真实听感不由文件探针代替。

## Task 7：全量回归、复审与真机覆盖安装

**文件：** 当前计划、`docs/handoffs/2026-09-10-voice-auditions.md`、新增真实路由的androidTest；不修改无关生产模块。

- [x] 强制运行全量JVM599/599、前端83/83及主/测试APK构建；bf101e4新增5个默认无声和1个显式内置试听入口，独立测试审查通过。以下为本轮实际命令：

```powershell
& './gradlew.bat' --gradle-user-home 'D:/fangr/PycharmProjects/healthy agent/.gradle-local' :app:testDebugUnitTest --rerun :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

- [x] 全范围审查、a1a84d3统一修复及复审后，用户额外授权以bb461ea修复接纳预算；最终定向复审全部通过，无遗留Critical/Important/Minor。最后全量JVM626/626、前端83/83、双APK/原签名/解包凭据审计均通过。
- [x] 无活动跑步时最新一致性备份、核对签名后覆盖安装主/测试包，不卸载/clear；手机实际主包SHA256与最终产物0cda2898...1b9889一致，1条记录/2430轨迹点及私有Fish配置保留。
- [x] 真机运行生产依赖图、14条APK资源和5项假网络无声路由：7通过，显式试听1项未开启而正确跳过，0.325秒；固定提示回退、离线公里、goal_achieved、URGENT零网络及迟到不补播已实跑。不改变手机网络/VPN，也不产生正式运动记录。
- [x] 用户准备后内置core仅试听一次，3.183秒1/1；用户确认流萤声线正确、QQ音乐压低并恢复。
- [ ] 在线整段实听：用户另授权一次真实Fish公里请求，21:23因SocketException走公里静默（1.370秒1项失败），未开播，不能记通过。已询问手机网络/VPN状态，不自动重试或重复收费；需要新授权才再试一次。
- [x] 安装及无声测试后fresh核对：1条记录、active0、2430路线点、完整性ok，key/reference匹配，主包哈希一致，主页面恢复。
- [x] 本轮实际内置/失败在线两个独立日志进程均无Android语音；内置completed与焦点释放完整，失败公里skipped_offline且未占焦点。历史旧Android日志不混入本轮结论。
- [ ] 长时锁屏/户外仍需后续实测，不把短测当户外保证。
- [ ] 更新交接、当前计划复选框和项目记忆，向用户区分完成项与待人工/户外验收项；不推送远端。

## 计划自查与执行记录

- 设计第1—3节由Task 2/4/5覆盖，第4节由Task 3/4/5覆盖，第5节由Task 1/6覆盖，第6节由Task 7覆盖。
- Task 2的SpeechRequest/Delivery同时供Task 4实现与Task 5调用；Task 3的store/player供Task 4消费；Task 6补齐Task 3运行时资源。依赖按顺序实施，不能并行修改同一个文件。
- `TtsEngine.kt`只在Task 2增加兼容接口；`SpeechBus.kt`只归Task 5；`AppCoordinator.kt`/`AppModule.kt`只归Task 5。旧Fish客户端只归Task 4，测试工具只归Task 1。
- 当前完成：Task1—6、用户额外授权后的bb461ea预算修复、626/626全量及最终复审/审计。主/测试包已覆盖安装，真机无声7项通过/试听保护1项跳过，手机0cda2898...1b9889、记录1/0/2430、完整性ok、Fish配置保持。尚未播放新版真实语音，已询问用户是否戴好Beats与QQ音乐准备听内置core；Task7整体仍待人工语音验收，长时锁屏/户外另记。恢复以本计划`.superpowers/sdd/2026-09-10-online-whole-offline-core-voice/progress.md`台账为准。
- Task1听感已经通过，不再等待准备：km-whole仅合成一次、16:03实播一次9.097秒1/1，用户确认“可以可以，很满意，很自然”。8.171667秒音频SHA256为8f6a2357576ee4c284e058c7befe559a01e62bb78d275ef3f9fe80c94dc4a40f；临时包18D095...596E06已装，不重播/重装/重复收费。手机正式包40AC56...944F、上次设备检查记录1/未结束0/路线点2430，安装前须fresh核对。
