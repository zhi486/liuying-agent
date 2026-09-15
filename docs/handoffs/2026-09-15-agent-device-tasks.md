# M1C-2A 个人设备与云任务库交接

日期：2026-09-15（Asia/Singapore）。用户已确认 2026-09-14 规格，并继续授权推进；本批仅后端离线库，不代表手机 Agent 已接通。

## 交付与边界

| 状态 | 内容 |
| --- | --- |
| 已实现 | 32 字节随机设备凭据、SHA-256 保存、90 天有效、可信撤销/换机共享 owner 与预算 |
| 已实现 | 严格提交、云 ID、同 owner 请求键去重、异文冲突、单活跃任务、原设备唯一认领 |
| 已实现 | 执行权摘要、取消/撤回、终态先写入者生效、恢复查询、结果确认和 24 小时清理 |
| 已验证 | 类型检查、118 项全量离线测试（原 86＋本批 32）、独立只读复审 |
| 未接通 | 应用服务/Loop/每步派发门禁组合、HTTP、原生在线任务协议、生产 tokenizer、远程 D1 |
| 需后续授权/配合 | 云账户及私密配置、真实付费联调、部署与手机安装；当前无需提供密钥 |

没有发放真实设备凭据、读取真实 API key、真实模型请求或生成运动记录；不修改 Android/React。本机旧 blocked 任务仍不能自动上传。当前 `codex/five-second-zones` 工作区保留所有既有修改；没有 stage/commit/push。

## 代码入口

- `agent-server/src/device-auth.ts`：`createDeviceAuth` 普通认证，与仅可信管理持有的 `createDeviceAdmin` 分离；撤销设备和其活跃任务在同一事务内。
- `src/cloud-task-contracts.ts`：静态错误、严格 UUID/输入/结果/行动校验、DTO 和数据库毫秒时钟表达式。
- `src/cloud-tasks.ts`：`accept/get/findByRequest/cancel/withdraw/acknowledge` 每次认证并在 SQL 内门禁；`claim/finish/purge` 仅可信执行与维护代码使用，不能直接暴露手机路由。
- `migrations/0002_agent_device_tasks.sql`：外键、去重键、部分唯一活跃索引、状态与结果 CHECK；不改写原预算表。
- `test/device-auth.test.ts`（6 项）、`test/cloud-tasks.test.ts`（26 项）、`test/sqlite-d1.ts`：真实 SQL/Web Crypto；仅测试时钟可替换，有原生毫秒对照。

输入不单独存入任务表，只有规范化 SHA-256 指纹；最终答复可能复述输入，仍可能包含健康信息。结果从实际终态写入起最多查询 24 小时，手机确认实际落库后提前清除；到期响应隐藏正文。物理清理需要后续定时调用 `purge`，当前没有云定时器，也不保证备份瞬时消失。

## 验证与复审

在 `agent-server` 执行 `corepack pnpm check`：退出码 0，118 tests / 118 pass / 0 fail。Node 22.22.2 / pnpm 11.19.0 / TypeScript 5.9.2；SQLite/MockTimers 的 ExperimentalWarning 保留，不隐藏。

本批六个 TypeScript 文件的 Prettier 检查通过；`git diff --check` 和逐个未跟踪文件的 `git diff --no-index --check` 通过。存在 Git 的 LF→CRLF 提示，未改动无关文件来消除提示。

测试覆盖同键八次并发只接单一次、跨 owner 与同 owner 多设备、只认领一次、并发取消/完成、撤销事务故障整批回滚、重开文件数据库、已结算旧预算迁移不变、结果确认/撤回/到期不复活和非法结果拒绝。

独立只读审查发现并关闭：

1. SQL CHECK 的 NULL 三值逻辑可能放行非法状态组合；直接 SQL 失败用例后改为明确非空判断。
2. 等待数据库前的时间快照可能放行过截止完成或过期正文；增加 SQL 排队、返回等待、清理与读取间越界及设备到期失败用例，改为数据库执行/观测时间＋返回前应用时间检查。
3. 测试 `%f` 秒位未补零会损失毫秒；固定 `SS.SSS`，验证 0/1/100/999/9100/9999/10999ms，并与未替换的原生 SQLite 时钟对照。

最终复审未发现新阻断问题。它仅确认本批库边界，不确认远程云或真实付费闭环。

## 下一步：M1C-2B

按已批准规格继续细化应用服务实施计划，主代理直接写代码、子代理只读审查：

用户继续推进后，已将派发证明和晚到用量的接口风险细化为 [M1C-2B 书面规格](../superpowers/specs/2026-09-15-agent-execution-service-design.md)。该技术细化待用户审阅，尚未开始 M1C-2B 功能代码；不要把设计文档当成新增测试或手机已接通的证据。

1. 认领成功后，使用云 task ID 和原 deadline 接核心；排队耗时不能重新获得 30 秒。
2. 每次模型步骤前检查原设备、任务状态与授权；以持久门禁和账本预留组织派发。新设备不能继续旧执行。
3. 已知晚到 usage 仍结算；取消/撤销不等于退款，未知费用继续保留。进程重启只恢复状态/账务，不重新调用模型。
4. 先组合真实模块与假供应商验证，不能用脚本业务结果替代身份/存储/账本。实际 Workers/D1 时间语义、生命周期、类型和免费层仍须单独验收。

这些完成后，才处理生产 tokenizer、HTTPS/私密配置和原生 `agent.cloud.tasks.v1`。现有 `agent.tasks.v1` 不变；长期记忆、双 RAG 和全 App $2 封顶尚未实现。
