# M1B 原生任务库与首页接口实施计划

> 执行方式：使用 executing-plans，主代理直接实施；子代理仅独立只读审查。用户已确认推进，不重复询问实现方式。

**目标：** 首页能保存并恢复真实本机任务，查询用户消息与行动、取消任务，云端未接通状态真实可见。

**架构：** 独立 `AgentDatabase` + 事务 DAO，单例 `AgentTaskService` 经现有 Native Bridge 服务网页。没有后台云任务，已提交存储不依赖页面作用域；不迁移运动数据库。

**技术栈：** 现有 Room 2.6.1、Kotlin Coroutines/Serialization、React 19 / TypeScript / Zod 4。无新第三方依赖。

**规格：** [M1B 设计](../specs/2026-09-14-native-agent-tasks-design.md)。

## 全局约束

- 保留全部既有未提交工作，留在当前 `codex/five-second-zones` 分支；不提交/推送/部署/付费调用或真机安装。
- 主界面“保存任务”，新任务 `blocked/cloud_not_connected`；无假助手消息、无自动上传、无系统 TTS。
- 原生 UUIDv4；prompt 最长 2000；列表 limit 1—50；每个查询严格限制可信 ownerId。
- 原生库在 noBackupFilesDir。网页草稿不写 localStorage/sessionStorage。
- Android 构建设置 `ANDROID_USER_HOME=D:/fangr/PycharmProjects/healthy agent/.android-user`，使用 `./gradlew.bat --offline --gradle-user-home .gradle-local`。

## Task 1：命令契约与原生事务存储

文件：新增 `agent/AgentCommand.kt`、`agent/AgentTaskModels.kt`、`agent/AgentTaskService.kt`、`agent/local/AgentEntities.kt`、`agent/local/AgentTaskDao.kt`、`agent/local/AgentDatabase.kt`；测试 `agent/AgentTaskServiceTest.kt`、`bridge/AgentCommandCodecTest.kt`、`androidTest/.../agent/AgentTaskDatabaseTest.kt`。

接口：

```kotlin
sealed interface AgentCommand {
    data class Submit(val clientRequestId: String, val prompt: String) : AgentCommand
    data class ListTasks(val offset: Int, val limit: Int) : AgentCommand
    data class Detail(val taskId: String) : AgentCommand
    data class Cancel(val taskId: String) : AgentCommand
}
// 所有者由原生构造器注入，网页命令不接受。
// suspend fun handle(name: String, body: JsonObject): CommandOutcome
// DAO saveLocal(ownerId, clientRequestId, prompt, taskId, now): AgentTaskDetail
// DAO page(ownerId, offset, limit): AgentTaskPage
// DAO detail(ownerId, taskId): AgentTaskDetail?
// DAO cancel(ownerId, taskId, now): AgentTaskDetail?
```

- [x] 先写真实 BridgeCodec 的新命令测试，运行定向 JVM 测试确认旧实现拒绝新命令。
- [x] 新命令采用精确字段解析，原生/前端均拒绝数值字符串、越界、未知字段。服务直接调用也需校验。
- [x] 事务保存任务/用户消息/行动；持久唯一键冲突时比较原文，已取消的重复提交不得复活。
- [x] 测试字面期望：首次状态 blocked、原因 cloud_not_connected、1 条用户消息；同键同文返回相同 ID；同键异文拒绝；取消后仅增加 1 条行动且重试不改时间。
- [x] Room 实库验证并发 8 次同键提交最终 1 任务/1 消息；两个 owner 同键不共享，跨 owner detail/cancel 不返回数据。
- [x] 关闭文件数据库后重开可读原任务/取消状态；故意重复消息主键使保存事务回滚，无孤立任务。

执行偏差：服务测试先写后实现；Room 仪器测试在 DAO 实现后补齐，不能将其声称为 SQL 层完整 RED→GREEN。实际并发、事务与文件恢复均已在隔离模拟器运行验证。

## Task 2：Native Bridge 与真实宿主

文件：修改 `BridgeCodec.kt`、`BridgeSession.kt`、`NativeWebBridge.kt`、`WebPreviewHost.kt`、`MainActivity.kt`、`di/AppModule.kt`；共享 fixtures 与 `contracts/v1/README.md`。

- [x] Koin 创建独立数据库和服务；NativeWebBridge 四个新命令转发服务，不修改 AppCoordinator、RunningService、语音或跑步控制。
- [x] 新增 `agent.tasks.v1` capability，同步 ready fixture/原生校验/前端 tuple 及相应测试；添加四种合法命令 fixture。
- [x] 验证两个不同 envelope ID 使用同 clientRequestId 可恢复同一任务；旧文档结果继续由 BridgeSession 隔离。
- [x] 宿主入口切换 `#/agent`，配合实际 WebView/前端路由验收，不能只 grep 常量当功能测试。

## Task 3：首页持久任务交互

文件：修改 `bridge/contract.ts`、`bridge/schemas.ts`、`features/agent/AgentPage.tsx`、其 CSS；新增 `bridge/agentTasks.ts`、`bridge/agentTasks.test.ts`、`features/agent/useAgentTasks.ts`、`AgentPage.test.tsx`；更新路由测试。

- [x] 先写 Zod 与页面测试：无 Bridge 禁用；就绪查询真实任务；保存调用 agent.submit，返回 blocked 后不显示助手回复；保存失败草稿保留。
- [x] 公共任务数据 schema 严格校验状态原因、时间、序号、消息数、ID 对应；accepted.data 的通用 JSON 校验不能替代业务 schema。
- [x] 保存连点同步锁；未知结果重试复用独立幂等键，刷新仅 list；修改草稿重新生成键。
- [x] 分离 list/detail/mutation 序号；保存/取消使旧查询失效，卸载后不写状态，Bridge 失效隐藏旧详情。
- [x] 页面补齐分页、详情关闭、取消失败重试；禁用样式仅作用于 disabled，保留 44px 触控与现有主题。

小屏验收补充：输入框与保存错误整体移到最近任务之前，避免任务积累挤走主输入入口；保留原有主题和四 Tab。独立审查发现保存失败可能留下旧详情加载态，先用 deferred 测试复现，再改为保存开始关闭旧详情，复审通过。

测试样例边界：

```ts
// 真正跨边界请求，而非静态文案检查：
// 保存失败后再次点保存，断言两次 agent.submit 的 clientRequestId 一致。
// 先发旧 list，随后 cancel 成功，再让旧 list 返回 blocked：页面仍显示 cancelled。
// unmount / remount 后只能 agent.list，不能产生 agent.submit。
```

## Task 4：回归、实库和独立复审

- [x] 前端目录运行 `corepack pnpm check` 与 `corepack pnpm verify:bundle`（最终由 Gradle 门禁再次完整执行）。
- [x] 全量 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；真实 XML 统计 772 测试、0 失败/错误/跳过。
- [x] 在隔离模拟器执行新 Room 仪器测试，不碰用户真机/记录；使用专用临时 userdata 的 emulator-5556，已关闭。
- [x] 360px 真实 APK 首页/详情/取消、软键盘输入和保存、进程重启恢复通过；分页由前端自动化覆盖，所有记录均为模拟器测试数据。
- [x] 只读子代理复审范围限定本批，修复有效缺陷并补回归；`git diff --check` 与工作树保留检查。
- [x] 写交接与阶段状态，明确 M1C 生产模型/预算/鉴权仍未接通。

最终结果：前端 160/160、Android JVM 772/772、M1A 核心 56/56、Room + WebView 仪器测试 5/5；完整构建与 bundle 检查通过。软键盘遮挡保存按钮的问题采用输入区聚焦/视口变化时居中滚动修复，补失败用例、独立复审和真实软键盘截图。详见 `docs/handoffs/2026-09-14-native-agent-tasks.md`。
