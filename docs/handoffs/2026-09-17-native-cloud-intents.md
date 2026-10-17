# S3：Android 原生在线意图持久层

日期：2026-09-17。用户在 S2 完成后要求继续，按六批计划实施 S3。主代理实现，子代理只读契约核对与独立复审，无阻断问题。没有 Git 提交／推送。

## 本批交付

| 内容 | 结果 |
| --- | --- |
| 独立在线意图 | 新 `agent_cloud_intents` 表，旧 `agent_tasks`、消息和行动保留，不自动上传 |
| 不可变快照 | 两个独立小写 UUIDv4、HTTPS origin、owner/device/credentialRef、原始 prompt、授权版本与时间 |
| 固定授权 | `prompt_only_v1` 确定性生成 cloudProcessing=true、runSummaries=false、summaries=[] |
| 恢复标记 | 提交前持久化一次 CAS；取消可升级为撤回，撤回不降级，重复操作不改时间 |
| Room 迁移 | 独立 AgentDatabase 1→2，仅增表/索引，生产 builder 注册迁移，无破坏性兜底 |

代码：

- `app/src/main/java/com/healthyagent/running/agent/cloud/CloudTaskIntent.kt`
- `app/src/main/java/com/healthyagent/running/agent/cloud/CloudTaskIntentDao.kt`
- `app/src/main/java/com/healthyagent/running/agent/local/AgentDatabase.kt`
- JVM `CloudTaskIntentTest`、仪器 `CloudTaskIntentDatabaseTest`。

没有新增 HTTP 客户端、凭据库、云结果/ack 存储、Bridge/UI、WorkManager、后台重发或新的依赖。旧任务服务、运动数据库、BLE/GPS/心率/语音不改。现有手机语音直连供应商仍不在云账本封顶范围内。

## 下一批必须遵守的调用顺序

1. 可信原生配置提供固定 origin、owner/device 和不可换绑的凭据代次标识。`credentialRef` 不是秘密，也不能是可换绑的 `current` 槽位。真实 Bearer 留 S4 Keystore。
2. 用户明确同意后调用 `CloudTaskIntent.confirm`，生成独立 request/local UUID；保留返回对象直到 `dao.save` 成功。同键重试必须沿用全部原始快照，不能改输入或生成新授权时间。
3. `dao.save` 事务完成后，才调用 `claimSubmission`。只有返回 true 且提交完成的调用方可以发起一次 POST。它表示“可能已提交”，不表示云端接单。
4. CAS 成功后任何崩溃/超时都不得清零；恢复只查询原请求键。404 不授权自动重发或换键。取消/撤回已经落盘时 CAS 不给发网许可。
5. `requestControl` 仅持久化本机待控制意图，不等于云端已经取消、撤回或退款；S4 负责云端幂等控制和确认。结果事务写入及 acknowledge 同样留 S4。

完整身份不匹配的查询/控制不给结果；同键改身份或内容拒绝覆盖。尚未引入换机/换凭据后的授权迁移。origin 当前以严格字符串匹配，S4 可信配置应固定规范拼写，不能随意在省略端口和显式 `:443` 等写法之间切换。

prompt 保留原始文本，不在恢复时重新 trim；长度按 UTF-16 ≤2000，空白集合与服务端 JS 一致，拒绝孤立 surrogate 和保留图片 token。`submission()` 不包含身份/凭据/运动数据，`toString()` 脱敏。Room 仍是 noBackupFilesDir 内普通 SQLite，不承诺数据库加密。

## 验证证据

| 检查 | 结果 |
| --- | --- |
| 新增 JVM 规则测试 | 4/4；先观察实现缺失导致编译失败，再实现通过 |
| Android JVM 全量 | 776/776；XML 汇总 failures/errors/skipped 均 0 |
| 前端完整门禁 | 160/160，lint/格式/类型/构建/Bundle 通过；本批无前端源码变化 |
| 主包及测试包 | assembleDebug、assembleDebugAndroidTest 成功，保留原项目签名 |
| Room 仪器测试 | 12/12（新增在线意图 8＋旧任务库 4），4.548 秒 |
| 独立复审与差异检查 | 无阻断，git diff --check 通过 |

仪器测试覆盖：8 路同键保存及一次 CAS、完整快照冲突、四字段身份隔离、待控制优先级与时钟回拨、文件库关闭重开、唯一键失败不残留、分页/非法输入，以及固定 v1 schema 上 blocked/cancelled 两条旧任务连同消息/行动升级后完整保留。Room 打开迁移库时进行了新 schema 校验；生成 DAO 中三个变更方法均包装为真实 `withTransaction`。

恢复证据是 SQLite 文件关闭重开，不是本轮手机杀进程/真实断网或云结果恢复验收。原有四项数据库测试的自身临时文件清理逻辑未改；新仪器测试不删除现有数据库。

## 复现和运行环境

JDK `C:/Users/fangr/.jdks/ms-17.0.20`；`ANDROID_USER_HOME` 使用项目 `.android-user`；Room 仍 2.6.1，无依赖升级。按 [Android 官方迁移说明](https://developer.android.com/training/data-storage/room/migrating-db-versions) 注册显式迁移且保留数据，不采用 destructive fallback；本项目未启用 schema export，测试沿用固定旧建表语句＋Room 实库校验。

```powershell
$env:JAVA_HOME='C:/Users/fangr/.jdks/ms-17.0.20'
$env:ANDROID_USER_HOME='D:/fangr/PycharmProjects/healthy agent/.android-user'
./gradlew.bat --offline --gradle-user-home .gradle-local :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

本轮使用新建 userdata、无窗口/无快照/read-only 的 Android 34 隔离模拟器 `emulator-5556`，仅安装本项目主包和测试包；未连接真机。执行：

```text
adb -s emulator-5556 shell am instrument -w -r -e class com.healthyagent.running.agent.CloudTaskIntentDatabaseTest,com.healthyagent.running.agent.AgentTaskDatabaseTest com.healthyagent.running.test/androidx.test.runner.AndroidJUnitRunner
```

模拟器已通过 `emu kill` 关闭；没有执行工作区目录清理。启动日志在忽略目录 `build/device-tests/s3-8877ac9bb30d47a8b99e642a0ae58128/`。既有 SDK XML/废弃 API/旧测试命名警告未扩大修复。

主 APK SHA-256：`ABC5F7207BDB3B6611DBADFE6689127D276A0CDABCD9B62E4FAFA85CA92D3F75`。签名 SHA-256：`ebad2b92cff0324810062be3e8116645cfd352780ad79141be61be148774c603`，与既有签名一致。

## 下一步

S4 原生固定 HTTPS 客户端、Keystore 凭据与云结果恢复；先做六路由假服务回归、禁止重定向/隐式付费重试、Room 成功持久化后才 ack。结果/ack 状态需要另做增量 schema 设计。S5 再提供 `agent.cloud.tasks.v1` 与明确“保存本机/在线执行”入口。

本轮没有读真实密钥、部署、配置云端、签发设备、付费模型请求或真机修改。当前不需要用户填密钥；后续实际云配置和手机联调到对应门禁时再提醒。后端 235/运行时 25+1 跳过属于前批证据，本轮没有重跑或用来声称新云性能。
