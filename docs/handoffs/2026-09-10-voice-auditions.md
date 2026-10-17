# 流萤离线核心与联网陪伴：试听原型

日期：2026-09-10，Asia/Singapore。此文件是恢复记录，不是新操作授权。

## 范围与当前状态（第四段连贯合成对照听感已认可）

用户希望核心跑步/健康提示用固定流萤音频内置App，联网时在后面追加闲聊/鼓励，Fish不可用只省略后段，不用系统声线。动态数字是否接受离线拼接尚未定稿；用户要求先做几次语音听效果，并选择从手机Beats听。

这次仅制作试听原型，没有修改生产逻辑或正式安装包，正式App仍可能回退Android语音，不能宣称新分层功能已实现。第三、四段鼓励词预先写好，不是真实在线AI追加链路；试听工具播放四段本地文件，不联网、不定位、不建立跑步记录、不调整系统音量。

## 小样与产物

目录：`build/device-tests/20260910/voice-audition/`（本地忽略目录）。生成脚本 `build/device-tests/20260910/make-voice-audition.mjs` 经用户试听授权使用chatbot现有配置，生成10个短片段、合计58个字符，只向Fish发送示例文本，未输出或落盘凭据。生成成功的片段会复用，避免重复消耗额度。

| 小样 | 内容 | 时长 | SHA256 |
| --- | --- | --- | --- |
| sample-core.mp3 | 心率上升很快，控制节奏。 | 2.469秒 | 6baca6192ab1d4b04a4ebaee4c2540a5572439afbccc595eefcad1c478b1a0b0 |
| sample-stitched.mp3 | 第＋一＋公里，用时＋六＋分＋零＋秒，配速＋六＋分＋零＋秒。 | 9.119秒 | beb97633ebc06c41e75fd62535043f8efece32f15f4c5ed0ee18725bc6b96bed |
| sample-companion.mp3 | 同一核心提示，间隔350ms，接“开拓者，别着急，我会陪你稳稳地跑下去。按照自己的节奏来就好。” | 9.026秒 | f1641a517f59676368238b1ddd9f3481a7468f0ade9e083b88b00a77d21d2151 |
| sample-continuous.mp3 | 第三段原文字不变，由Fish一次连贯生成两句，作为语气对照。 | 8.461秒 | 5ec3edbbf73ed59563d47e0302b131e362825ed34c3377f65bae00181fa442df |

使用FFmpeg裁除片段首尾静音并拼接，未剪句中停顿；48kHz单声道MP3，元数据与本地哈希已核验。还需用户实听评价拼接停顿、声调和音量一致性，不凭文件生成成功判断自然。

三段已复制到手机App私有 `cache/voice_audition/sample-{core,stitched,companion}.mp3`，14:43逐个SHA256与本地一致。缓存不是已经打包进正式APK的离线资源。传输用 `/data/local/tmp/liuying-audition-20260910-{core,stitched,companion}.mp3` 仍保留，没有擅自删除其他文件。

## 临时测试工具与安全检查

- 新增仅androidTest的 `LocalVoiceAuditionTest#playOnePreparedSample`，样本参数只接受core/stitched/companion。仅显式 `allowLocalVoiceAudition=true` 才播放，且要求没有活动跑步。
- 工具使用现有FishAudioTtsEngine、AndroidAudioPlayerPort和USAGE_ASSISTANT，但合成端口只返回固定本地文件；日志provider=fish不代表这次播放又访问了Fish API。
- 前端83/83与 `assembleDebugAndroidTest` 成功；临时测试包SHA256 `BEE5BA2AE0591F2D2A35A689E804223857A5E8D2004B6F05C74C3D1842F3471E`，原签名核验通过，14:34前覆盖安装成功。没有重装正式App。
- 用户拔线并暂停导致第一次默认保护检查未完成、没有结果文件。14:43恢复USB授权为device；无旧instrumentation，记录1/未结束0。
- `default-guard-1443.txt`：依赖图1项通过，本地试听1项因未开启开关按预期跳过（status=-4），不是2项实际播放通过。正式App回前台，记录仍1/0。

## 已播放与下一步

用户明确“准备好了，播放第一段”后，14:45仅播放core一次，用例3.22秒1/1通过；`core-1444.txt`是结果文件（文件标签不是精确播放时刻）。进程 `844ab54a-fa4b-41ec-94c2-8e23369368af` 有attempted/completed及焦点授予/释放各1次，正式App已恢复前台。用户确认“自然清楚，可以继续第二段”。

14:48第二段stitched已播放一次，9.759秒1/1通过，证据 `stitched-1447.txt`；进程 `2ee9f9b1-1b99-4ea2-a2b8-5a2a3b793ada` 播放和焦点终态完整。用户明确“拼接明显不自然，但继续听第三段”。这份逐字数字拼接效果不接受，不能当成离线数字播报方案已通过。

14:51第三段companion已播放一次，9.907秒1/1通过，证据 `companion-1451.txt`；进程 `da515fc5-1777-46df-90a0-3fad8de5f36a` 播放和焦点终态完整。用户反馈：“两段内容都可以，但中间衔接稍微有点不自然”。固定提示和鼓励内容认可，但衔接未达到最终听感要求；不能猜定只需缩短350ms停顿，前后语气/语速也是待辨别因素。

14:52收尾核对：手机正式主包SHA256仍 `40AC56A698ACA0BE83CECD4DC4FE71974391A23417176DE797367F7FE4BF944F`，数据库完整性ok、原记录1/未结束0/路线点2430，无RunningService；正式App前台，所有试听测试已结束。限定App安全日志保存至 `build/device-tests/20260910/auditions-after-1452/`，3119条、0坏行；这三次播放各4条本地播放器诊断，不是新增云端合成。

用户进一步明确：“感觉第一句末尾和第二句开头的语气不同”。据此制作第四段continuous，文字不变，单次Fish请求连贯生成42字符。独立 `manifest-continuous.json` 保留时长、字节和哈希；已复制手机私有缓存并核对SHA256，不需要重复生成。此对照用于检查分开合成与语气断层的关系，不代表已解决离线核心与动态在线后段的衔接。

临时测试工具白名单新增continuous；前端83/83及 `assembleDebugAndroidTest` 成功。新版测试包SHA256 `0D714D09E2889D024505715685C7CAAAC431B8C040C98BEAB18B7EB4D41DC75D`，原签名核验并覆盖安装成功，正式主包未重装。`default-guard-continuous.txt` 中1项因未明确允许播放按预期跳过（status=-4，0.011秒），没有播放第四段；正式App已恢复前台。

用户回复“方便”后，15:10第四段continuous仅播放一次。播放前设备为device、活动跑步为0、手机小样SHA256符合上表；实机用例9.397秒，1/1通过（status=0，非跳过），结束后正式App恢复前台。结果文件 `continuous-20260910-151025.txt`。用户随后明确反馈：“这次衔接非常自然”。这份小样可作为后续听感基准。

本次对照支持“分开合成的语气差异是衔接不自然的重要因素”的判断；仅一组样本不能排除合成随机差异，也不能推广为任意句子或动态追加都已自然。临时测试包和小样保留便于继续，不影响原记录。

## 最新用户方案：在线整段合成，离线只播固定核心

第四段反馈之后，用户主动更新方案：Fish在线时，将固定提示与闲聊/鼓励的完整文本一次合成；每公里的数字、单位及播报文本也一次合成，不再逐字拼接。Fish离线时，只播放预先下载的固定流萤提示，每公里语音直接跳过。这取代此前“离线核心先播，再接独立在线后段”的设计方向；不再把离线动态数字库作为待办。用户不接受系统默认声线的要求保持不变，公里数据计算、显示、保存不因跳过语音而停用。

用户随后回复“接受”，已批准高优先级健康提醒立即本地、不等网络的例外。当前为brainstorming语音链路设计确认，尚未改生产代码、生成新公里小样或重新安装。已有KmSplitRule能提供整句文本，Fish客户端接受完整text；仍需验证数字读法与真实网络失败链路，不以第四段非数字小样推定公里听感已通过。

完整设计稿：`docs/superpowers/specs/2026-09-10-online-whole-offline-core-voice-design.md`，待用户审阅。建议普通准备上限8秒、公里15秒，可选AI文字最多2秒并包含在总上限中；这些数值尚待确认，不是已实施配置。需14句固定音频，复用已认可的1句，拟新生成13句，另先做1条完整公里小样；制作批次尚未执行。实现须以限时获得当前事件在线音频判断成功，失败或超时按离线规则处理，迟到结果不得重复补播。现有URGENT原文会被EmergencyPhraseCache替换的路径需要停用；`goal_achieved`虽为SPLIT优先级仍应离线播固定提示，不能误按公里跳过。

## 已批准执行与开工检查点（取代上方设计待审阅状态）

用户已“批准执行”完整设计，并要求“先提交修改，再继续动工”。已在当前master保存本地检查点`261f422`（187文件），未推送，提交后工作区干净。提交前前端83/83、JVM524/524通过；JVM于15:38强制重跑、55套件、0失败/错误/跳过，测试包编译通过。工作区与暂存区文件扫描无疑似凭据命中；`.android-user/`和`.gradle-local/`加入忽略规则，签名、缓存、音频小样、抓包不在提交内。旧交接文档4处Markdown行尾双空格为换行，保留原文。

实施计划已写至`docs/superpowers/plans/2026-09-10-online-whole-offline-core-voice.md`，Task 1进行中，生产Task 2—7未开始。公里小样文字为“第一公里，用时六分整，配速每公里六分整。开拓者，我们又一起完成了一公里，我会继续陪着你。”，44字符，只请求Fish一次，生成8.171667秒/131756字节/48kHz单声道MP3，SHA256 `8f6a2357576ee4c284e058c7befe559a01e62bb78d275ef3f9fe80c94dc4a40f`。文件 `build/device-tests/20260910/voice-audition/sample-km-whole.mp3`、独立`manifest-km-whole.json`；不要重新生成或重复收费。旧三段dry-run仍10片段/58字符，continuous仍1片段/42字符。

临时试听工具仅新增`km-whole`白名单。前端83/83及测试包构建36秒通过；测试包SHA256 `18D0956EBD84073676733A907E802061C92CCFB051FB99ADC46B742C0A596E06`，原签名匹配。真机为device，正式主包仍`40AC56A698ACA0BE83CECD4DC4FE71974391A23417176DE797367F7FE4BF944F`，原记录1/未结束0/路线点2430。

安装曾停在手机`com.android.packageinstaller/.PackageInterceptActivity`；随后同一次安装脚本（统一exec session 17569，现已退出0）返回Success，无需重装。已复制到`cache/voice_audition/sample-km-whole.mp3`并核对SHA256一致，传输文件`/data/local/tmp/liuying-audition-20260910-km-whole.mp3`仍保留。`default-guard-km-whole.txt`记录0.043秒、1项因未允许播放按预期跳过（status=-4），没有播放小样。主App已恢复前台；等用户明确戴好Beats、准备听后，另行播放km-whole一次并索取数字读法与衔接听感。未修改正式App、网络/音量设置或运动记录；其余13句固定音频未生成。

16:03：用户回复“方便”后，km-whole只播放一次。播放前USB为device、活动跑步0、小样SHA256一致；实机用例9.097秒、1/1通过（status=0，非跳过），结果`km-whole-20260910-160338.txt`，结束后主App恢复前台。这次播放只用本地文件，未再请求Fish。

最新人工验收：用户确认“可以可以，很满意，很自然”。完整公里数字读法与鼓励衔接通过，作为正式在线整句的自然度基准。无需重播或重新生成小样，允许进入已批准的生产Task 2—7与13句固定音频制作；此反馈不表示正式语音策略已安装。

## 生产实施进度（Task 2已通过，Task 3开工）

试听与已批准计划提交3aeb23b，Task 1独立审查通过。Task 2新增SpeechRequest/Delivery、TtsEngine兼容入口、14条BundledVoiceCatalog与KilometerSpeechFormatter，并接入真实KmSplitRule；文件名为`${ruleId}.mp3`，未知ID不可误匹配，goal_achieved保留独立身份。提交8fd9208、b449097、b288791。前端83/83、全量JVM536/536（b449097）通过；独立审查发现110/1010组合数缺“一”，补2项真实红灯后最小修复，Formatter+Rules定向38/38、前端83/83通过，复审确认修复。最后新增2项后尚未重跑全量，不声称538全量通过。

当前进入Task 3：独立文件播放器、焦点清理与APK内置资源校验。执行台账`.superpowers/sdd/2026-09-10-online-whole-offline-core-voice/progress.md`为恢复入口，已完成Task 1/2不要重新派发。生产DI仍是旧链路，13句资源尚未生成，手机正式包未动、未重播试听，离线新策略尚未可用。

Task 3最新状态：独立播放器与AndroidBundledVoiceStore已提交42a4544，定向31/31、JVM558/558、前端83/83。独立审查发现清理未完成时可能提前返回/放行下一播放；新增latch红灯（预期false/1/1，实际true/2/2）后以e42b96e修复，等待真实清理完成才返回并释放active，定向26/26、前端83/83，复审通过。该新增用例后尚未重跑全量，不宣称559全量通过。内置来源诊断新增bundled，旧wire值保持。

目前进入Task 4限时在线准备/离线路由，BASE e42b96e，Task1—3均完成不要重做。清单最终schema为version=1和entries，每条ruleId/text/fileName/sha256/duration（秒Double），严格拒绝未知字段；真实14MP3仍属Task6未制作。手机未动、生产DI未换，离线新策略仍未安装。

Task 4最新状态：05d0519限时路由已实现，定向42/42、全量580/580、前端83/83。覆盖8/15秒含AI2秒、实际阻塞假HTTP的显式cancel、迟到隔离、URGENT零网络、公里失败静默、目标达成本地、禁读历史cache及取消状态。新增preparation_timeout/skipped_offline诊断。随后发现无句号核心直接接称呼黏连，35e11b1最小插入句间标点，RED后22/22、前端83/83，独立复审通过；最后3项新增后未全量，不声称583全量通过。

当前从35e11b1进入Task5可取消AI后句、Bus/Coordinator/DI正式接线，由/root/implement_task5执行。Task1—4完成不可重派；Task6音频制作及Task7安装未开始。新引擎旧text-only入口受控失败，必须接typed请求；旧FishVoiceLiveTest直接消费者也将适配并保留allow保护。至本检查点未操作手机、未增加真实Fish/AI请求，13句固定音频尚未生成。

## 最新检查点：Task 5完成，Task 6制作中

Task5提交494be97：可取消Anthropic后句、typed SpeechBus实际文本/未播放终态、同公里单事件、URGENT原文、本地/在线生产DI均接通；无AndroidTtsEngine回退构造，生产Fish每事件readCache=false。全量JVM595/595、前端83/83和androidTest编译通过，无真实HTTP或手机测试。设置使用AtomicReference与同步回调即时更新，健康/GPS配置仍按现有跑步冻结。

独立审查发现旧Flow快照可能晚于同步设置接纳而回滚静音/Fish配置；3bd18fa用专用Mutex统一设置写入/读取/接纳，Flow只触发锁内重读。两项闸门测试确定性RED后Coordinator45/45、前端83/83 GREEN，定向复审通过。末次新增2项后未做全量，不声称597全量已跑。

当前Task1—5均complete，Task6由/root/implement_task6从BASE3bd18fa制作真实内置资源：只新增已批准13句并复用core，不操作手机；Task7安装与真机路由验收尚未开始。手机仍旧正式包，不能用代码已移除系统回退推定已安装。恢复以本计划台账及Task6报告/代理状态为准；不要重派已完成任务或重播小样。

## 最新检查点：Task 6完成，Task 7验收中

ef96386已封装14个固定MP3与严格清单：13个新Fish请求各1次全部成功，core原字节复用，合计32.293419秒/529580字节，均48kHz单声道。客户端尝试记录和生成报告不等同服务端计费账单；不重复请求已成功文件。真实规则目录10条+4种会话事件覆盖，资源缺失RED后GREEN2/2；JVM599/599、前端83/83、androidTest编译及主APK构建通过。APK15条目、14hash/schema及暂存字节审计通过，独立规格/质量审查通过。

当前/root/implement_task7仅补真机无声路由和明确allow的内置试听测试并最终构建；controller负责最终审查、备份与覆盖安装、设备执行、人工交互。手机只读检查仍旧主包40ac56...944f、数据库quick_check=ok、1条/active0/2430路线点，尚未安装或播放。ignored check-voice-release-device.mjs的--inspect已通过、--backup未执行；check-voice-release-apk.ps1的BOM/语法已通过，待最终APK完成后实际扫描。Task1—6不要重做，恢复读本计划台账与Task7报告/代理状态。

Task7代码检查点bf101e4：新增VoicePolicyInstrumentedTest（默认5无声、显式allowBundledVoicePreview=true才试听APK内hr_rapid_rise），全量JVM599/599、前端83/83、两个APK构建及原签名验证通过；独立规格/质量测试门通过，尚未真机执行。主APK31136C1EDF97C6F5CF3AE9983376A4D84887151E79032D865433CFF22B9776E4，测试包128F20087FE7AF1106C846486F47B626DBF0F6A3FAD69E686332859EC5C7EFD1；后续修复重建会使这些hash过期，安装须重新核对。

最终只读APK审计发现classes3.dex包含已授权配置中的参考声线ID；安全定位为SettingsDataStore.kt:138的默认常量，261f422基线已有，不是新资源生成带入，也不是Fish API密钥。该项纳入最终全范围审查/统一处置，未输出配置实际值，安装暂停。手机随后USB断开，--settings-check只读检查未能执行，没有读取或改写手机配置；重新连接后应确认参考配置已经持久化，去掉默认字面量不应破坏既有配置。当前未备份、未安装、未播放；原样本与13条已成功生成资产不要重做。

## 最新检查点：USB恢复，最终统一修复进行中

用户已确认“已重新连接并授权”。只读核对手机App私有设置：Fish key和reference均与当前授权配置匹配（仅输出两个布尔值），不需要恢复旧硬编码默认值。手机仍旧主包40ac56...944f；运动记录1条、未结束0、路线点2430，完整性ok，未改配置、未重播。

独立一致性备份已保存至`build/device-tests/20260910/voice-release-20260910T120659751Z/run-records.db`，520192字节，SHA256为`adb9688178ad211467ef4bae984e4eabb337c26aa64fa17aeb65b3e792e5195e`；同目录`before-install.json`记录安全摘要。源与备份副本计数相同、quick_check均ok。安装前仍须重新核对，不能用旧备份覆盖用户后续新运动。

最终审查范围261f422..5f4ecce：两组Important为默认参考声线ID打包和真实Android媒体prepare边界；两项Minor为播放超时异常与Bus诊断不一致、CoachMessage过期注释。统一修复由/root/final_voice_fix执行，BASE5f4ecce，只一波修复、一次定向复审。真实Android端口加假platform闸门已复现取消后迟到start=1的行为红灯。最终方案将真实异步媒体prepare纳入8/15秒并保持准备无焦点；已prepared的短启动与取消原子排序，网络/准备等待不持启动门。修复未提交或验收，Task7整体仍未完成；不得安装此前两个过期APK或宣称户外已通过。

## 最新检查点：a1a84d3已提交，最终复审剩一项，未安装

最终统一修复提交`a1a84d3`，15个生产与直接测试文件：移除共用默认reference；真实Android媒体改为主Looper创建/异步prepare/可取消启动；准备阶段无焦点；播放60秒超时诊断和消息注释修正。原文及14音频完全未改，无新Fish/AI请求、无push。准备/实际播放分阶段，线上8/15秒包含媒体prepared；本地解码保留60秒故障看门，实际开播另计60秒，期间可取消且不会因准备抢焦点。

定向123/123、最终全量66套件615/615、前端83/83、双APK构建和原签名通过。controller实际解包扫描主/测试APK均findings0，主14语音hash一致，未发现授权配置实际值或常见凭据/私钥格式。主APK SHA256 `474F98991F192AD0B70E6D7C19465B8A1D78AB132DB99BD7F1722D21740BF494`，测试`D73F2FEA780A563379A58301988F04212D64DBC0967C864B7442D18DB8F7F17D`；这不是已安装证明。

最终一次定向复审确认上述修复有效、没有发现fix新增破坏，但剩一个原Important：SpeechBus接纳事件后的排队/派发延迟没有从8/15秒预算扣除。示例普通事件t=0接纳、t=3派发、t=10在线准备完成仍会播，而原要求应在t=8转本地；公里事件超过15秒仍可能开始新在线准备。仅从进入prepareOnline起计时，不满足“从接纳起”。不能将真实媒体prepare取消已修复误写为完全时限已通过。

按SDD最终一波修复及一次定向复审的上限，本轮不再自行派第二波；已向用户请求明确允许再做一轮仅修接纳时间传播与边界测试。待回复期间保留master代码、已审计APK、原数据与备份，不安装、不播放、不产生模拟运动记录，不重复生成音频。若用户允许，以a1a84d3为下一波基线，仅修预算传播及相关时钟测试，回归、独立审查、重新核验最终APK后覆盖安装；Task1—6不得重做。若用户暂停，从本段和本计划台账恢复。

用户随后明确回复“允许，先修好再安装”。这项额外授权已覆盖本轮执行上限；/root/final_voice_fix从a1a84d3执行最小接纳预算修复，要求先提出一致时钟/预算传播接口，用真实Bus→Policy延迟派发取得RED，再回归、复审和安装。简报acceptance-budget-brief.md，报告acceptance-budget-report.md，均在本计划台账目录。不要继续等待同一授权，不重新开始已完成任务或音频制作。

## 最新安装与实听检查点：bb461ea已在手机，内置通过，在线连接失败待查

额外授权修复`bb461ea`已完成并复审通过，无残留发现：Bus把原始TimeMark传至Policy，pending不重置，直接speak入口补标记；生产使用包含休眠时间的elapsedRealtimeNanos，原UI时间不变。修正测试Delay转发后，移除两处标记传播取得有效变异RED4项失败，恢复后定向110/110；最后全量67套件626/626、前端83/83和双APK构建/原签名通过。已通过的媒体/线程和14assets原字节未改。

最终主APK SHA256 `0CDA2898127EA4460E317D8F84990B0A8A32FF4447F6875AA08B05E6FD1B9889`，测试`F28270D9973025D1FD4C40B01C1B0B0112E8BF130A590028616BD0B72E34D60B`；实际解包审计均findings0，主14语音hash一致。原签名不变。安装前最新一致性备份目录`build/device-tests/20260910/voice-release-20260910T131448454Z/`，DB仍520192字节、SHA256 `adb9688178ad211467ef4bae984e4eabb337c26aa64fa17aeb65b3e792e5195e`，与上一份备份完全一致。

主包与测试包均以adb install -r -t覆盖安装Success，无卸载/clear。手机实际主包哈希与最终产物一致；安装后及测试后fresh核对均为1条记录、active0、2430路线点、完整性ok，key/reference匹配true，没有输出配置值。

真机三类默认检查0.325秒：依赖图1项、APK资源1项、假网络路由5项，共7项status0；真实试听1项因未开启allow而status-4跳过，不把OK(8 tests)记成8项实测通过。无声路由覆盖固定失败回退、离线公里、goal_achieved、URGENT零网络和迟到不补播。结果`policy-routes-bb461ea.txt`在最新备份目录。

21:21用户准备后仅播放一次APK内置core，3.183秒1/1通过，结果`bundled-preview-bb461ea.txt`；用户确认“听到了，流萤声线正确，音乐压低并恢复”。日志进程`ae4bccc9-2762-42aa-830e-76c101fd98e4`的bundled attempted/completed和焦点granted/released各1，未调用Fish或Android TTS，不是旧缓存小样。

21:23用户明确授权仅一次真实Fish公里整句且Beats准备好，运行生产链路测试1次；1.370秒失败（status-2），expected TTS_SUCCESS、actual NOT_PLAYED。日志进程`e345a1dd-68c2-4f1a-947e-5574d9808fd4`显示fish attempted后约1.3秒SocketException，随即skipped_offline及Bus not_played；没有进入音频焦点或播放，没有系统声线回退。此为在线连接失败，不能当在线试听通过，也不能仅凭SocketException认定VPN根因。结果`online-km-bb461ea.txt`，未自动重试或额外调用AI/Fish。

本轮白名单诊断`build/device-tests/20260910/voice-release-bb461ea-after-online/`共3135条、0坏行；其中旧Android39次来自历史，不应混为新版行为。失败后主页面已恢复、记录/配置/包哈希再次保持。已问用户当前Wi-Fi/流量及VPN网页可用性，待回复；不擅改网络，真实合成再试需重新明确一次授权。Task7当前内置声线和音乐人工通过、在线实听未通过；长时锁屏/B5户外仍独立待验。不重做Task1—6、音频生成或安装。
