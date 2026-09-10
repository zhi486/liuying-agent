# 2026-09-09 vivo 室内真机测试

## 结论

vivo V2241A 上的正式 Web UI、真实 KeepB5-21 私有 B3 链路、长暂停保活与心率更新、恢复、短时后台运行、四种会话播报、结束保存及跨进程诊断持久化均取得通过证据。没有使用 Mock，也没有修改生产代码或重新构建。

尚不能把里程碑 A 判为全部完成：本轮没有制造射频断连，没有实听 Beats/QQ 音乐的路由与 duck，也没有进行户外 GPS/公里播报验收。

## 安装核验

- 重新发现一台 USB 真机 vivo V2241A；安装前无流萤 RunningService。
- 本地 APK：`app/build/outputs/apk/debug/app-debug.apk`，22,286,706 bytes。
- SHA-256：`906FF8BA1EDB78F70C472E8604126E2E2A9427509165D5A71E01B9612CFEF15F`。
- 本次 `install --user 0 -r -t -g` 被手机系统拒绝：`INSTALL_FAILED_ABORTED: User rejected permissions`。未重试、未卸载、未清数据，不记为本次安装成功。
- 随后以 `pm path` 定位手机当前 `base.apk`，设备端 `sha256sum` 与本地 APK 完全相同。手机已有所需版本，故直接测试；`lastUpdateTime` 仍为 2026-09-08 10:26:40。

## 真机证据

截图目录：`build/device-tests/20260909/`。时间均为手机本地 +08:00。

| 项目 | 观察与判定 |
| --- | --- |
| 开始 | 17:14:46 接受开始；17:14:57 BLE 从 connecting 进入 ready_private_b3，attemptToken/gattToken 均为 1 |
| 暂停首帧 | 17:15:26，已暂停，KeepB5-21 已连接，心率 71 bpm、距离 0.00 km、用时 00:37、配速 `--`；`03-pause-first.png` |
| 长暂停对照 | 17:16:31，相隔 65 秒，心率变为 80 bpm，其他上述指标不变、B5 仍连接；`05-pause-long.png` |
| 暂停保活 | 生命周期暂停持续 97.624153 秒；暂停窗口内 UPDATE_WORKOUT accepted=97、HEART_BEAT accepted=16；没有 observed link error/reconnect。写入日志不包含 B3 冻结时长字段，冻结指标证据来自 UI，不把 accepted 等同于已读取设备屏幕 |
| 暂停后台 | HOME 后约 20 秒再返回，保持暂停与连接；`04-pause-background.png`；RunningService 为 foreground |
| 恢复 | 17:17:02 恢复；ble_resume_health=healthy，最近有效活动距今 638 ms；无需手动重连。8 秒后用时 00:45、心率 70；`06-resumed.png` |
| 运行后台 | 短暂 HOME/返回后用时 01:12、心率 62，B5 仍连接、前台服务存活；`07-running-background.png`。不是长时锁屏或 OEM 耗电验收 |
| 结束保存 | 17:18:30 停止；Room 记录 FINISHED，126 秒、2.49229131684013 米、平均心率 67，0 Split、5 route points；`09-saved.png` 显示已保存、Health Connect 已同步且包含路线 |
| 历史 | `10-history.png` 与强停重启后的 `11-restarted-history.png` 均显示一条 09/09 17:14 的 2:06 测试记录 |
| 服务与崩溃 | 结束后 RunningService 不再存在；原进程和重启进程各自 pid-filtered logcat 未命中 FATAL EXCEPTION、ANR in、Fatal signal 或 AndroidRuntime |

## 语音与跨进程诊断

- processStartId：`c5f8888a-f0e7-4f84-885c-44a8d8d3e8c7`，总计 309 条事件。
- session_started、session_paused、session_resumed、session_finished 均各有 scheduled 和 tts_success；结束播报于 17:18:31 完成。
- 全部 Android TTS：completed=7、cancelled=1；audio focus granted/released=8/8。含 data_stale 提醒；暂停打断该提醒产生一次 Android TTS cancelled，不是会话播报失败。
- Composite skipped_missing_config=8，使用 Android 本地 TTS；本轮未配置 Fish，也不将系统 TTS 完成回调当作用户实听确认。
- 结束保存、播报终态后执行 force-stop/relaunch，PID 19429→21909；上述旧 processStartId 的全部诊断保留。
- 重启前后 `files/diagnostics/diagnostics.jsonl` 的 SHA-256 完全相同：`19A8766D489B013FC33E9D33BD08DF3DDDAF0ED48AC854B1F6A2BAEDA3D8B222`。
- 重启后 Room 仍有一条 FINISHED 记录，id=`ee2083ce-ad84-42de-a1ca-411f871fb29a`，126 秒。未导出经纬度或蓝牙地址。

## 新发现与剩余边界

1. 近零距离配速显示待修：2.49 米/126 秒导致平均配速为 50555.8877 秒/公里，摘要显示 `842:36`，历史显示 `14:02:36`，不适合用户阅读。历史页将配速交给 formatDuration，摘要页使用独立 formatPace；本轮仅记录问题，未改算法、格式化或已保存数据。
2. 室内 GPS 曾短暂可用，运行时也出现“GPS 数据暂不可用，计时继续”。5 个路线点和 2.49 米不能证明户外精度；`0.00 km` 是两位小数显示，不表示数据库距离严格为零。
3. 待用户配合：暂停时 B5 离开范围再回来，验证真实断连自动重连仍保持 Paused；Beats + QQ 音乐实听 duck/恢复及公里播报；户外 GPS、锁屏与长时后台验收。
4. B5 屏幕距离/配速/千卡仍属里程碑 B，未猜测协议字段。
5. 手机留下本轮一条室内测试记录，且界面报告已同步 Health Connect；没有删除任何历史或恢复旧签名迁移备份。最终返回跑步页，无进行中的跑步服务。

## 后续接续

Task 12 Step 7 的 vivo 结束后跨进程持久化已通过；Step 6 的真实长暂停保活、心率和正常恢复已通过，射频断连重连与 Beats/QQ 音乐仍待完成。本次采用本地最新报告续接；Ravenclaw 查询未及时返回，不能声称远端上下文已同步。

## 后续补充：安装成功与声线排查

- 用户配合手机确认后，再次覆盖安装成功；`lastUpdateTime=2026-09-09 17:29:55`，设备端 APK 哈希仍为上方 `906FF8...15F`，原记录保留。此结果取代早先“本次未成功重装”的当前状态，保留前文作为首次尝试记录。
- 语音提醒开关开启；当前输出为 Beats Studio Buds，媒体音量 7/15、助手音量 8/15，未静音。扬声器的助手音量另为 0，但这不能解释用户在 Beats 中的反馈；本轮没有修改这些系统设置。
- 用户分别确认系统 TTS 示例可听、App 开始/暂停播报也可听，但不是流萤声线。因此当前“完全无声”未复现，不声称已确定早先无声原因。
- App 运行时记录 `composite:skipped_missing_config`，实际连接 vivo AIService TTS。自定义声线启用要求 Fish API Key 与 Reference ID 都非空；本次只定位到自定义配置未满足条件，没有将系统 TTS 成功冒充流萤声线成功。
- 试听产生一条 5 秒记录 `e963e576-0f0d-45ff-8be5-f11f29f34460`，已经结束并保存，RunningService 停止；连同原 126 秒记录共两条。新进程 `ab048cbc-fc09-4b1b-a432-5a0eb2ca20cf` 的开始、暂停、结束均有 tts_success。
- 用户指出 chatbot 曾配置 Fish。只读核对 `D:/fangr/PycharmProjects/chatbot/localmodel/.env`，目标字段 `FISH_API_KEY`、`LIUYING_REFERENCE_ID` 均非空且不是所检查的常见占位符；音色 ID 与跑步 App 默认值一致。没有输出值、没有改动 chatbot、没有将密钥复制到 App/APK/报告，也没有调用 Fish 网络接口，故 Key 有效性尚未验证。
- 下一步待用户确认复用配置与接回自定义声线。React 设置页当前未提供 Fish 配置入口，旧 Compose 设置有 Key 输入；不可把真实 Key 硬编码进源码或 APK。
- 17:56 +08:00 新结果：用户表示已把官网 Key 有效期延长至 2029 年，并明确授权只读验证。依据 Fish 官方 OpenAPI，使用 chatbot 现有配置向 `api.fish.audio` 发起认证及已配置音色的 GET 请求，两项均为 HTTP 200。只确认当时凭据被接受和音色可访问，未核对到期日字段。未生成音频、未迁移配置或修改手机，尚不能称为 App 流萤声线已恢复；本次许可不包含配置迁移、合成试听或 Ravenclaw 上传。

## 18:10 +08:00：流萤声线接入并实听通过

- 用户随后明确同意复用配置接入 App，并合成一句短试听。通过 USB 转发到该 App 自己的 debug WebView，在已有 Bridge 会话内发送 `settings.update`，只更新 Fish Key 与音色两个字段，没有发送新 hello。原生返回 accepted；回读由 `fishApiKeyConfigured=false` 变为 true，Reference ID 与 chatbot 配置一致。
- 凭据仅在导入辅助进程/设备通道内存中处理，随后沿用 App 的私有 DataStore 设置。没有把凭据写入源码、APK、辅助文件或输出日志，没有改动 chatbot。当前 DataStore 字段尚未使用 Keystore 加密，不能将这次导入描述为已完成密钥加密改造。
- 辅助工具：`build/device-tests/20260909/import-fish-config.mjs`，不含配置值。早期调试导航尝试在导入前失败，最终用已观察到的页面点击触发查询后成功；临时 USB 调试转发均已关闭。
- 新增可复用、显式开启的设备测试 `app/src/androidTest/java/com/healthyagent/running/speech/FishVoiceLiveTest.kt`。它使用生产 Koin 的 SettingsRepository 和 TtsEngine，只说“开拓者，我是流萤。准备好一起跑步了吗？”，不启动跑步/GPS。默认缺少 `allowFishVoicePreview=true` 时通过 Assume 跳过，不请求云端。
- `:app:assembleDebugAndroidTest` 于 1m11s 成功，期间前端 lint/format/typecheck 与 77/77 单测通过；正式 app APK SHA-256 仍为 `906FF8...15F`，未修改生产代码或重新交付另一个主包。
- 指定类并显式开启后，direct AndroidJUnitRunner 为 `OK (1 test)`。这是生产 Composite TTS 的返回结果；另外以诊断独立确认没有回退，而不是只靠测试返回判断声线。
- 试听进程 `f4fa0518-b2c7-46b3-8bd9-ef63e837d92b`：18:10:18.608 Fish attempted，18:10:21.206 Fish audio focus granted，18:10:26.406 released 与 completed；共 4 条事件，无 Android/local fallback。全设备诊断合计 Fish attempted=1、completed=1。
- 用户在 Beats 中实听确认：“听到了，是流萤声线”。因此自定义声线的设备合成/播放/耳机实听闭环通过。
- 再以不含授权开关的相同类验证保护机制：runner 返回 assumption skipped（状态 -4，0.01s），不计为另一次语音测试成功；Fish attempted/completed 仍为 1/1，没有额外合成。
- 临时 `com.healthyagent.running.test` 已卸载成功，可从测试 APK 重新安装；正式 App 已重新打开，配置保留，无 RunningService，跑步记录数仍为 2。本轮试听未新增跑步记录。
- 剩余工作：QQ 音乐 duck/恢复和公里播报实听、B5 射频断连重连、户外 GPS/长时后台；后续可补 React 的 Fish 配置/试听入口与 Keystore 存储。本次授权不扩展到 Ravenclaw 上传，未重新尝试上传。

## 18:38 +08:00：QQ 音乐压低/恢复与模拟公里实听通过

用户要求测试 QQ 音乐、公里播报和户外运行，随后明确目前只能室内，QQ 音乐已在 Beats 播放。因此本轮只验收实体手机/耳机的音乐交互和模拟公里播报，不判定户外 GPS 或真实公里自动触发通过。

| 项目 | 证据与结果 |
| --- | --- |
| 测试入口 | 在 `FishVoiceLiveTest` 新增 `configuredVoiceSpeaksSimulatedKmSplit`，只在 `allowFishKmPreview=true` 时运行；用模拟 Split 调用生产 KmSplitRule，再交给生产 Koin SpeechBus/TtsEngine。未运行定位、数据管线或真实会话，不保存模拟距离。 |
| 播报内容 | 第 1 公里，用时 6 分 00 秒，配速 6 分 00 秒；规则 ID 为 km_split，优先级 SPLIT。 |
| 构建/执行 | 测试包构建 53 秒成功，前端 77/77 通过；指定单个用例 direct runner `OK (1 test)`，8.415 秒。正式主 APK 仍为 `906FF8...15F`，生产逻辑未修改。 |
| Fish 与总线 | 进程 `ab0e2e1d-85cb-4c8d-8cd2-d9ff4658f05d`：18:38:03.198 attempted；05.141 focus granted；11.491 released；11.496 completed；11.504 km_split tts_success。无系统 TTS 回退。 |
| 音乐压低 | 18:38:05.660，系统明确记录 QQ 音乐 player 16911 `ducking (strong)`；06.540–11.594 采样显示 LOSS_TRANSIENT_CAN_DUCK 且位于 ducked 列表。 |
| 音乐恢复 | 18:38:11.488 `unducking piid:16911`；12.612 起仅 QQ 音乐持有 GAIN、loss=none、ducked 列表为空；原始播放器状态恢复后为 started。 |
| 用户实听 | 用户确认“流萤播报清楚，音乐变轻且自动恢复”；因此不只依据焦点回调判定成功。 |
| 无额外付费请求 | 不传授权开关运行整个试听类，两个用例均 assumption skipped（状态 -4），不是两次播放成功；设备累计 Fish attempted/completed 仍为 2/2，含 18:10 的首次试听，本轮只新增一次。 |
| 收尾 | 本轮前后 run_sessions 均为 2；临时测试包卸载 Success，正式 App 重新打开，无 RunningService；试听 PID 5398 的日志未命中 fatal/ANR。 |

采样说明：临时 PowerShell 摘要的播放器 `state` 正则误匹配到 `mutedState:none`，该字段已排除，不用它推断音乐暂停/播放；上述判断以原始播放器行、系统 ducking/unducking 日志、独立焦点采样及用户实听为准。

剩余验收：户外真实 GPS 距离/路线、实际跨越 1 公里的自动触发、长时间锁屏/后台，以及 B5 暂停态射频断连重连。用户能出门时，再进行至少 1 公里的真实步行或慢跑，结束后核对记录、路线、Split 与语音诊断；本轮没有伪造定位替代户外结果。

### 实听后的响度反馈

- 用户随后补充“流萤声音有点轻”。音乐压低/恢复及播报可听的结果仍成立，但响度满意度尚未通过，应继续调优。
- 只读检查：当前 Beats 的 bt_a2dp 输出，STREAM_MUSIC=7/15、STREAM_ASSISTANT=8/15，均未静音；流萤播放器确实使用 USAGE_ASSISTANT，源码中没有额外降低 MediaPlayer 音量的设置。
- 这些结果只说明两个音量通道独立且助手通道尚有调节空间，不能据此断定 Fish 音频本身没有偏轻。当前未调整系统音量、未改生产代码、未再次合成。拟先征求用户同意，将助手通道从 8 调至 10、音乐保持 7，再复听；原助手值 8 可恢复。
- 末次 `media_session volume --get` 复查：助手仍为 8/15，媒体已为 6/15；变化来源未确认，本轮没有执行音量写入。后续只考虑调整助手，音乐保留届时当前值，不主动恢复为先前的 7。

### 18:59 +08:00：用户手动调音后响度确认合适

- 用户澄清：流萤正在说话时按音量键能看到“小 V 音量”，说完后再按看不到。该真机上的可见入口随助手播报出现；通道未丢失，无需据此改音频路由。
- 经用户 OK 同意，再执行一次现有模拟公里试听用例，direct runner `OK (1 test)`，6.266 秒；没有改生产代码、系统音量或启动跑步。
- 进程 `178ee7e6-a276-45c3-9bde-8fc5a305752e`：18:59:37.991 Fish attempted，38.030 focus granted，44.139 released，44.140 completed，44.147 km_split tts_success；没有系统回退。provider attempted 不等于新增 HTTP 合成次数，不能据此判断是否再次计费。
- 复听前助手/音乐为 8/15、6/15；用户操作后只读回查为 15/15、8/15。助手未执行音量写入，不能把本轮描述成“音乐音量保持不变”。用户确认“调好了，现在响度合适”。
- 本轮设备与耳机组合的响度实听通过；15/15 已是助手通道上限，只记录本次用户设置，不设为 App 默认，也不外推为其他耳机的合适音量。
- 临时测试包已卸载 Success，正式 App 重新打开，无 RunningService，记录仍为 2。户外 GPS/真实公里触发/长时锁屏及 B5 暂停态射频重连继续待验收。
