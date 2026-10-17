# M1B 原生任务库与首页接口交接

日期：2026-09-14。用户请求：推进原生任务库与首页接口。状态：本批实现、回归和独立只读复审完成；下一批 M1C。

## 交付与边界

- 新增 `app/src/main/java/com/healthyagent/running/agent/`：严格命令解析、任务服务、独立 Room 数据库/事务 DAO。数据库为应用 `noBackupFilesDir/liuying_agent.db`，不迁移运动库、不承诺加密存储。
- 任务、首条用户消息和保存行动原子提交。可信原生 ownerId 隔离；同 owner/clientRequestId/规范化文本重试返回原任务，同键异文拒绝。取消幂等，重复提交不复活已取消记录。
- Native Bridge 增加 `agent.submit/list/detail/cancel` 及 `agent.tasks.v1`；ready 契约、四个共享 fixtures、DI 和真实 WebView 全部接通。
- React 首页可保存、刷新/分页、查看消息及行动、取消。错误保留草稿，结果未知重试复用幂等键，旧查询不能覆盖新状态。冷启动 `#/agent`，四 Tab 和活动跑步入口保留。
- 输入框置于最近任务之前；软键盘弹出时，仅焦点在输入区内才居中滚动完整输入区，避免导航遮住保存按钮；卸载移除监听。

**不是生产云 Agent：** 新任务为 `blocked/cloud_not_connected`，按钮“保存任务”，没有假助手回复、自动上传、云端 worker 或任务后台重发。当前 owner 是原生本机身份，不代表已实现服务认证。M1C 接云端不能自动执行本批历史记录，需要用户显式授权和发起。

没有修改本批范围之外的 BLE/GPS/心率规则/语音链路，没有真实 DeepSeek/Fish 请求。现有手机直连供应商不受 M1A 费用端口控制，仍不能保证全 App $2/月。

## 最终验证

| 项目 | 结果 | 证据范围 |
| --- | --- | --- |
| 前端完整检查 | 160/160 通过 | 工具链、lint、Prettier、TypeScript、Vitest、生产构建 |
| Bundle 门禁 | 通过 | 初始 JS gzip 99,434 字节，总资源 388,198 字节 |
| Android JVM | 772/772 通过 | XML 汇总 failures/errors/skipped 均为 0；不是本批新增 772 项 |
| Agent 核心 | 56/56 通过 | M1A 独立包类型检查与离线循环回归 |
| Room 实库 | 4/4 通过 | 并发 8 次同键、所有者隔离、文件关闭重开、事务回滚 |
| 真实 APK 首页 | 1/1 通过 | 真实 WebView→Bridge→Room 保存/重载/取消/Tab、360px 无横向溢出 |
| 软键盘及进程重启 | 通过 | 输入测试草稿、键盘打开时点击保存、force-stop 后重启能看到本机任务 |
| 独立审查 | 修复后通过 | 原生事务/接线、前端竞争状态、输入区顺序、键盘监听生命周期 |

开发过程中的问题均保留真实记录：

1. 旧 Bridge 拒绝新命令的定向用例先失败；服务用例在缺失类型时编译失败，随后实现通过。Room 仪器测试在 DAO 实现后补齐，不能声称其完整经历 SQL 层 RED→GREEN。
2. 空消息数组触发 Zod refine 访问越界，补长度保护；详情等待中保存失败导致永久加载，先用 deferred 用例复现再修复。
3. 一轮全量前端的两个既有懒加载页面超时；单独 13 项及后续多轮完整检查通过，未增大超时或跳过失败。模拟器冷启动与测试资源竞争是推断，不是已证实根因。
4. 最初仪器 DOM 流程通过，但截图被 Health Connect 系统引导遮挡，未当作视觉验收。仅在隔离模拟器预授所声明权限；测试补前台焦点/绘制等待，并用文档标记避免 reload 后点击旧 DOM。最终截图为真实 App 前台。
5. 输入框被列表挤下、软键盘遮挡保存按钮均补测试和最小修复，最终重新完整构建与模拟器验证。

## 复现

在项目根目录执行 Android 命令，始终保留签名目录：

```powershell
$env:ANDROID_USER_HOME='D:/fangr/PycharmProjects/healthy agent/.android-user'
./gradlew.bat --offline --gradle-user-home .gradle-local :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

在 `frontend` 目录执行 `corepack pnpm check`、`corepack pnpm verify:bundle`；在 `agent-server` 目录执行 `corepack pnpm check`。不要从根目录用 `pnpm --dir` 选择工具链。

本轮设备是专用临时 userdata 的 `emulator-5556`，Android 34、1080×2400，测试时 density 480，即 360px 视口。只安装本项目 debug APK 和测试 APK，预授本 App 声明的跑步及 Health Connect 写权限；未接触真机、真实跑步记录或健康数据。命令仅运行本批两类仪器测试：

```text
adb -s emulator-5556 shell am instrument -w -r -e class com.healthyagent.running.agent.AgentTaskDatabaseTest,com.healthyagent.running.agent.AgentHomepageTest com.healthyagent.running.test/androidx.test.runner.AndroidJUnitRunner
```

截图在 [本机验收目录](agent-tasks-qa-2026-09-14/)：`agent-local-home.png`、`agent-local-detail.png`、`agent-local-cancelled.png`、`agent-keyboard.png`、`agent-after-restart.png`。英文 `keyboard-check-only` 是隔离环境测试文本；未写入用户手机。

收尾已恢复模拟器原 density 420，并通过 `emu kill` 关闭本轮模拟器，保留临时 userdata 以便复现。未操作既有浏览器预览服务；收尾检查 4173 未发现监听，不将其写成仍在运行。

## APK 与工作树

- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `B6CDCEE66BBBC11C4152EDB6F58955EAED86F29BD8121D4E8DBB8886E7533E75`。
- 签名证书 SHA-256：`ebad2b92cff0324810062be3e8116645cfd352780ad79141be61be148774c603`，与既有 debug 签名一致。
- 当前 `codex/five-second-zones` 上仍有用户原有未提交的曲线、总结、UI、图标等工作；保留全部，不要 reset/checkout/清理 untracked。
- 本批新增/修改范围见 [实施清单](../superpowers/plans/2026-09-14-native-agent-tasks.md)。未 Git 提交/推送、未部署、未真机安装。

## 下一步 M1C

1. 明确生产服务身份认证、摘要云处理授权与任务协议；保留本机未授权任务，不自动上传旧记录。
2. 接入持久幂等、并发安全费用预留/结算和任务恢复；不把取消或未知 usage 当成零费用。
3. 真实 DeepSeek 工具适配器对接已通过验证的 M1A，严格限定授权摘要；首页扩展真实执行状态、行动与结果。
4. 先完成无秘密、离线契约测试；部署、真实付费联调和真机安装前另行取得相应授权。密钥由用户填入私密入口，不在聊天或文档中记录。

用户偏好保持主代理直接实施、子代理仅只读复审。长期记忆、双 RAG 与全部入口费用控制仍按 M3/M4/M5 推进，不能因本批有持久消息就宣称具备长期记忆。
