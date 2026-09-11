# 心率警戒设置与提醒节流实施计划

> **执行者要求：** 使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐项实施；以下复选框记录真实完成情况。

**目标：** 用户可以独立设置警戒心率与重复提醒间隔，默认维持原门槛但减少重复提醒，持续时间不再依赖帧数。

**架构：** 纯 Kotlin 的单调时间门控负责连续性与专用冷却；原规则负责生成已有紧急提示。两项偏好通过现有 DataStore、严格双端契约与设置页进入开跑冻结配置，不另建设置架构。

**技术栈：** Kotlin/JVM 17、kotlin.time、JUnit 4、Android DataStore、React/TypeScript、Zod、Vitest；不新增依赖。

**设计依据：** [已批准总规格](../specs/2026-09-11-outdoor-alerts-deepseek-design.md)，本批落实第 4 节和对应契约／验证要求。

## 全局约束

- 自动警戒值与旧 Z5 最低整数心率相同；最大心率 180 时是 162，不调整训练分区。
- `hrAlertThresholdBpm: Int? = null`，手动整数 80–240；`hrAlertRepeatIntervalSec: Int = 120`，整数 30–300；清空阈值恢复自动，非法值拒绝保存。
- 真正连续 10,000 毫秒；评估间隔超过 2,000 毫秒、低值、缺失、陈旧、暂停打断连续性，不清除专用冷却。
- 从事件生成开始计冷却；短暂恢复、暂停、跨公里不能绕过；新跑步重置。不新增跨进程冷却持久化，不补播旧提醒。
- Android 使用现有基于 `SystemClock.elapsedRealtimeNanos` 的 `TimeSource`；其余规则与语音策略不变。
- 本批不修改 14 条音频、手环协议、DeepSeek、定位算法或数据库，不安装、不发付费请求、不处理真实密钥。
- 按已确认偏好在当前 master 原地开发；保留四份旧 dirty 文档，逐文件暂存、本地提交，不 push。
- `contractVersion` 保持 1；同包两端严格增加 `heart-rate-alerts.v1`，不放宽未知字段校验。

## 后续子系统顺序

本计划是第一份独立可测试子计划。完成后按总规格分别制定并执行「数据状态／真实 BLE 通知」和「DeepSeek 私密配置／陪伴句」子计划；这两项仍属已批准工作，不以本批通过表示全部完成。新密钥只在最终真机认证时需要用户填写。

## 文件职责和固定接口

以下 `app/src/main/java/com/healthyagent/running/` 简称 `main/`，`app/src/test/java/com/healthyagent/running/` 简称 `test/`，均相对项目根目录。

| 文件 | 职责 |
| --- | --- |
| 新建 `main/core/engine/rules/HrAlertGate.kt` | 连续超标与冷却；不依赖 Android、帧历史、墙上时间 |
| 修改 `main/core/engine/rules/HrZone5Rule.kt` | 接入门控并复用已有文案与优先级 |
| 修改 `main/core/model/RunConfig.kt` | 两项冻结配置及自动阈值计算 |
| 修改 `main/core/engine/Rule.kt`、`RuleEngine.kt`、`RuleCatalog.kt` | 最小暂停钩子与注入时钟，不改其他规则冷却 |
| 修改 `main/coordinator/AppCoordinator.kt`、`main/di/AppModule.kt` | 暂停钩子、Android 单调时钟接线 |
| 新建 `test/core/engine/HrAlertGateTest.kt`、`HrAlertRuleTest.kt` | 边界、独立门槛、冷却与生命周期测试 |
| 修改 `test/core/engine/RulesTest.kt`、`RuleEngineTest.kt` | 原测试改用真实流逝时间；引擎跨公里／暂停回归 |
| 修改 `main/data/local/SettingsRepository.kt`、`SettingsPatch.kt`、`SettingsDataStore.kt` | 原子保存、空值语义、范围校验和旧设置默认 |
| 修改 `main/coordinator/AppSnapshot.kt`、`main/bridge/BridgeCodec.kt`、`main/bridge/BridgeSession.kt`、`main/ui/web/SettingsCommandMapper.kt` | 字段与能力同步，映射整数可空补丁 |
| 修改 `main/ui/settings/SettingsScreen.kt`、`SettingsViewModel.kt` | 原生备用设置入口同样支持两项偏好 |
| 修改 `frontend/src/bridge/contract.ts`、`schemas.ts` | 严格快照和更新字段、能力元组 |
| 修改 `frontend/src/features/settings/SettingsPage.tsx` | 阈值留空恢复自动、重复间隔、下次跑步生效文案 |
| 修改 `contracts/v1/README.md`、`fixtures/valid/snapshot.json`、`ready.json`、`command.settings-update.json` | 同包契约与样例 |
| 修改相关 bridge、settings、running 测试中的快照与能力样例 | 样例完整但不制造假运行数据 |

所有新时钟签名统一为 `kotlin.time.TimeSource`：

```kotlin
class HrAlertGate(private val timeSource: TimeSource = TimeSource.Monotonic) {
    fun shouldAlert(heartRate: Int?, isFresh: Boolean, thresholdBpm: Int,
                    repeatIntervalSec: Int): Boolean
    fun onPause()
    fun reset()
}
class HrZone5Rule(timeSource: TimeSource = TimeSource.Monotonic) : Rule
fun RunConfig.effectiveHrAlertThresholdBpm(): Int
// 在现有接口／类中追加：
// Rule.onPause(): Unit（默认空实现）
// RuleEngine.onPause(): Unit
// RuleCatalog.defaultRules(timeSource: TimeSource = TimeSource.Monotonic): List<Rule>
```

## 验证命令

所有 PowerShell 调用显式进入项目，避免沙箱默认目录落到 `C:/`。使用现有依赖，不重新安装锁定版本。

```powershell
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath 'D:/fangr/PycharmProjects/healthy agent'
$env:JAVA_HOME = 'C:/Users/fangr/.jdks/ms-17.0.20'
$env:ANDROID_USER_HOME = 'D:/fangr/PycharmProjects/healthy agent/.android-user'
$env:Path = 'C:/Program Files/nodejs;' + $env:Path
function Test-LiuyingJvm([string[]]$Classes) {
    $testArguments = @('--gradle-user-home', '.gradle-local', ':app:testDebugUnitTest', '--rerun', '--console=plain')
    foreach ($className in $Classes) { $testArguments += @('--tests', $className) }
    & './gradlew.bat' @testArguments
    if ($LASTEXITCODE -ne 0) { throw 'JVM 测试失败' }
}
```

每个提交前运行 `git diff --cached --check` 和：

```powershell
& 'C:/Program Files/nodejs/node.exe' 'build/device-tests/20260910/check-commit-files.mjs' --staged
if ($LASTEXITCODE -ne 0) { throw '暂存内容审计失败，停止提交' }
```

### 任务 1：真实持续时间、独立阈值与生命周期

**文件：** 文件表前八行的生产和测试文件；如音频覆盖测试依赖旧十帧触发，修改其构造为注入单调时间并逐秒评估，不改变资源断言。

**输入：** `MetricsFrame.heartRateSmoothed`、`isHeartRateFresh`；`RunConfig`；现有 Android `TimeSource`。

**输出：** 上述 `HrAlertGate`、规则与配置接口；保持 `hr_z5_danger`、紧急优先级及音频文本不变。

- [x] 先运行现有 `RulesTest`、`RuleEngineTest`、`RuleCatalogTest` 基线。
- [x] 在 `RulesTest` 新增旧代码可编译的失败测试，证明十帧在同一瞬间不等于连续十秒：

```kotlin
@Test
fun `HrZone5Rule — 十帧同时到达不代表持续十秒`() {
    val rule = HrZone5Rule()
    val frames = (1..10).map { makeFrame(it.toLong(), hr = 170, hrZone = HrZone.Z5) }
    assertNull(rule.evaluate(RuleContext(recentFrames = frames), config))
}
```

- [x] 运行 `Test-LiuyingJvm @('com.healthyagent.running.core.engine.RulesTest')`，应在新断言失败而非编译失败。
- [x] 新建门控测试，使用 `TestTimeSource`，每秒推进且逐次调用；下列是首个边界用例：

```kotlin
@Test
fun `仅在连续满十秒后触发`() {
    val clock = TestTimeSource()
    val gate = HrAlertGate(clock)
    repeat(10) {
        assertFalse(gate.shouldAlert(162, true, 162, 120))
        clock += 1.seconds
    }
    assertTrue(gate.shouldAlert(162, true, 162, 120))
    assertFalse(gate.shouldAlert(162, true, 162, 120))
}
```

- [x] 新接口导致编译失败时，只加可编译的签名／空行为，再运行得到行为失败；随后实现以下状态更新：

```kotlin
private var aboveSince: TimeMark? = null
private var lastEvaluation: TimeMark? = null
private var lastAlert: TimeMark? = null

fun shouldAlert(heartRate: Int?, isFresh: Boolean, thresholdBpm: Int,
                repeatIntervalSec: Int): Boolean {
    if (lastEvaluation?.elapsedNow()?.let { it > 2.seconds } == true) aboveSince = null
    lastEvaluation = timeSource.markNow()
    if (!isFresh || heartRate == null || heartRate < thresholdBpm) {
        aboveSince = null
        return false
    }
    val start = aboveSince ?: timeSource.markNow().also { aboveSince = it }
    if (start.elapsedNow() < 10.seconds) return false
    if (lastAlert?.elapsedNow()?.let { it < repeatIntervalSec.seconds } == true) return false
    lastAlert = timeSource.markNow()
    return true
}
fun onPause() { aboveSince = null; lastEvaluation = null }
fun reset() { onPause(); lastAlert = null }
```

- [x] 按以下独立边界加入测试并逐个 RED/GREEN：9,999／10,000 毫秒；事件后119,999／120,000 毫秒；30／300秒配置；十次重复帧；2,000／2,001毫秒间隔；低值、空值、HR陈旧；GPS陈旧但HR新鲜仍评估；暂停保留冷却、恢复必须重新十秒；重置允许新会话首报。
- [x] `RunConfig` 加两项默认字段，自动计算先用整数 `(maxHeartRate * 9 + 9) / 10`。写80–240的旧 `HrZoneCalculator.zone` 最小Z5边界对照测试；若浮点旧行为存在差异，记录证据并保持实际边界，不修改训练分区算法。手动阈值独立于max和`hrZone`。
- [x] `HrZone5Rule` 每次评估最新心率（空上下文也清连续），使用 `cooldownMs = 0L` 禁止外层重复门禁；`reset`／`onPause` 委托门控。
- [x] `Rule` 加默认空 `onPause`，`RuleEngine.onPause` 逐项转发，Coordinator进入暂停时调用；只在新跑调用既有reset。`RuleCatalog`传时钟，DI传已有`TimeSource`。
- [x] 加引擎回归：墙钟跳变不绕过专用冷却，跨公里不清冷却，120秒内低值仍被门控观察；`hr_rapid_rise`独立规则不变。Coordinator测试覆盖暂停／恢复钩子的真实提醒结果。
- [x] 运行门控、规则、目录、引擎、Coordinator、音频覆盖测试；确认失败用例修复，旧音频仍全覆盖。
- [x] 审查本任务差异后逐文件暂存并审计，提交：`修复高心率提醒持续时间与专用冷却`。不夹带后续设置页。

### 任务 2：保存设置、双端契约与可见入口

**文件：** 文件表其余各行；新增 `test/data/local/HeartRateAlertSettingsTest.kt`；扩展 `SettingsPatchTest.kt`、`SettingsCommandMapperTest.kt`、`BridgeCodecTest.kt`、`AppCoordinatorTest.kt`、`frontend/src/features/settings/SettingsPage.test.tsx`、`frontend/src/bridge/schemas.test.ts`。

**输入：** 任务1的 `RunConfig.hrAlertThresholdBpm`、`hrAlertRepeatIntervalSec` 和 `effectiveHrAlertThresholdBpm()`。

**输出：** `SettingsSnapshot`／`SettingsContractSnapshot` 同名字段；`SettingsPatch.hrAlertThresholdBpm: NullableSettingUpdate<Int>`、`hrAlertRepeatIntervalSec: Int?`；现有 `settings.update` 支持新字段。

- [x] 先在界面测试加入可运行的失败用例；基于现有 `settings`、`snapshot`、`accepted` fixture：

```tsx
it('允许清空警戒心率恢复自动并保存独立间隔', async () => {
  sendNativeCommandMock.mockResolvedValue(accepted(settings));
  useBridgeStore.setState({ status: 'ready', snapshot });
  await act(async () => { render(<SettingsPage />); });
  await screen.findByText('本机已同步');
  fireEvent.change(screen.getByLabelText(/警戒心率/), { target: { value: '' } });
  fireEvent.change(screen.getByLabelText(/高心率重复提醒间隔/), { target: { value: '180' } });
  fireEvent.click(screen.getByRole('button', { name: '保存跑步偏好' }));
  await waitFor(() => expect(sendNativeCommandMock).toHaveBeenLastCalledWith(
    'settings.update', expect.objectContaining({ hrAlertThresholdBpm: null, hrAlertRepeatIntervalSec: 180 }),
  ));
});
```

- [x] 在 `frontend` 目录运行 `corepack.cmd pnpm test:unit src/features/settings/SettingsPage.test.tsx`，应因缺少入口失败。
- [x] 在真实DataStore配内存Preferences测试保存／清空／原子拒绝；复制现有 `SettingsVoiceDefaultsTest` 的 `MemoryStore` 到本测试作为私有辅助，使用真实 `SettingsDataStore`：

```kotlin
@Test
fun `保存警戒值不跟随最大心率且清空恢复自动`() = runTest {
    val repository = SettingsDataStore(MemoryStore())
    repository.update(SettingsPatch(hrAlertThresholdBpm = NullableSettingUpdate.Set(170)))
    repository.update(SettingsPatch(maxHeartRate = 200, hrAlertRepeatIntervalSec = 180))
    assertEquals(170, repository.current().toRunConfig().effectiveHrAlertThresholdBpm())
    repository.update(SettingsPatch(hrAlertThresholdBpm = NullableSettingUpdate.Clear))
    assertEquals(180, repository.current().toRunConfig().effectiveHrAlertThresholdBpm())
    assertEquals(180, repository.current().hrAlertRepeatIntervalSec)
}
```

- [x] 新字段最小签名后取到行为RED；实现两个Preferences键`hr_alert_threshold_bpm`和`hr_alert_repeat_interval_sec`，缺失默认null/120，Clear移除前者，Unchanged不动；整个patch校验完成后才edit。
- [x] 追加独立错误`INVALID_HR_ALERT_THRESHOLD`、`INVALID_HR_ALERT_INTERVAL`与hasNoChanges检查。测试79/80/240/241、29/30/300/301、空值、旧设置和无关偏好保留。
- [x] `toRunConfig`和两种`SettingsContractSnapshot.from`拷贝新字段；Coordinator现有开跑freeze不改，测试跑中和暂停保存新值后，本跑仍按旧阈值／间隔，下一跑使用新值。
- [x] Kotlin codec严格字段集合、范围和wire结构同批更新；nullable阈值更新映射：

```kotlin
private fun JsonObject.nullableIntUpdate(key: String): NullableSettingUpdate<Int> {
    val value = this[key] ?: return NullableSettingUpdate.Unchanged
    return if (value is JsonNull) NullableSettingUpdate.Clear
    else NullableSettingUpdate.Set(value.jsonPrimitive.int)
}
```

- [x] TypeScript类型分别为`number | null`、`number`，patch为可选同类型。Zod严格范围：

```ts
hrAlertThresholdBpm: z.number().int().min(80).max(240).nullable(),
hrAlertRepeatIntervalSec: z.number().int().min(30).max(300),
```

更新payload使用对应schema的`.optional()`；拒绝小数、字符串、未知字段。Kotlin和Zod同时测试接受null、拒绝越界与非整数。
- [x] 双端ready能力元组末尾增加`heart-rate-alerts.v1`，更新实际BridgeSession发出值、所有ready/snapshot fixtures以及测试构造；契约README解释null与运行冻结语义。
- [x] 设置页draft添加两个字符串，阈值空串保存null，其余Number转换后检查整数及范围，不静默裁剪；重复间隔空串拒绝。自动placeholder显示当前最大心率对应门槛；输入手动值后不随最大心率变化。沿既有field样式添加两项，保留保存结果的“下一次跑步”说明。
- [x] 原生备用SettingsScreen和ViewModel复用相同patch，显示独立警戒值和间隔，不调用网络。追加UI测试：自动门槛、手动保存、清空、非法值不发送、保存失败不伪报成功。
- [x] 执行全量验证：在 `frontend` 目录运行 `corepack.cmd pnpm check`、全量JVM和`:app:assembleDebug :app:assembleDebugAndroidTest`。记录实际测试数，不沿用旧626/83。
- [x] 用requesting-code-review技能作独立复审，核对本批差异及9,999/10,000、119,999/120,000边界和实际冻结接线；有问题回到失败测试再修。
- [x] 逐文件暂存本批设置／契约／测试，审计后提交：`增加可保存的心率警戒值与重复提醒间隔`；更新本计划复选框和实际验证结果。

## 交付检查

- [x] 总规格第4节全部由任务1／2覆盖；第5／6节明确属于后续子计划，不能标记已完成。
- [x] 扫描本计划无占位，接口类型与实现一致；仅已读过的真实文件被覆盖修改。
- [x] 旧dirty文档内容保留；没有新真实密钥、导出或录音入库。
- [x] 汇报表区分本地已完成与待真机验证；不得声称手机已更新。随后继续数据质量／BLE子计划。

## 执行记录

- 2026-09-11：规格已由用户审阅批准；计划提交 `af934f0`，任务1提交 `02d92c0`。首次复现瞬时十帧误触发，门控空实现8项失败、旧规则7项失败、协调器漏暂停通知1项失败均已修复；当时全量JVM 644/644，独立复审无遗留问题。
- 任务2采用原生备用页既有的有效输入自动保存模式；Web主设置页仍整表显式保存。新增原生字段独立编辑代次与错误，保护较晚输入、失败重试和跨字段回显；两项复审问题先用测试复现再修复，复审通过。
- Corepack必须从 `frontend` 目录启动，否则项目外全局pnpm版本不同。已纠正命令，无依赖／锁文件变更。APK审计脚本使用独立PowerShell进程读取退出码，避免继承旧的 `$LASTEXITCODE`；不改脚本或系统设置。
- 首轮整批656项JVM、101项前端及双APK通过；原生复审修复后的最终全量为658/658 JVM、102/102前端，零失败／错误／跳过，前端格式／类型／构建与双APK通过。独立复审无遗留阻断。
- 最终主APK SHA256：`797ae4eb382dd0c1179238d732251ab0848575612c4fa3243fe692d9c315df5f`；测试APK：`0617d896994881ed206a4ba4050408e65a91a8d0817dcb67276fe07da14dfaad`。14条音频哈希一致，双包凭据审计无发现；两包均为原签名 `ebad2b92cff0324810062be3e8116645cfd352780ad79141be61be148774c603`。
- 本批没有安装、发起真实文字／Fish请求或生成音频；未验证手机端新设置和户外效果。已有SDK XML版本提示及旧ArrowBack弃用警告未改变，不为本批改工具链。
