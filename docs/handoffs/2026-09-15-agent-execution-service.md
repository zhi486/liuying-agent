# M1C-2B 应用服务、派发证明与用量收据交接

日期：2026-09-15。用户明确“使用推荐方案”后，按已批准规格实施。仅后端本地库，不是云端或手机验收。

## 本批结果

| 项目 | 状态与证据 |
| --- | --- |
| 应用服务组合 | 已实现认证/接单、私密一次性执行、Loop、真实协议适配器及持久 DTO；两轮只读工具测试每轮结算 78 微美元（固定测试费率/用量） |
| 派发与权限 | 预留绑定云任务/原设备/执行权；prepared 原子派发或封闭；每步及模型后检查权限；回包跨任务、设备或费率到期时停止 |
| 收据与恢复 | 有效用量先持久收据后结算；相同幂等、冲突熔断；单批最多 100 条、游标分页；恢复只记账/收敛状态，不重跑模型 |
| 回归 | typecheck 与全量 154/154 通过（基线 118，本批新增 36）；全部真实 SQLite/Web Crypto + 假 HTTP |
| 独立审查 | 主代理写代码，子代理只读；设备到期回包漏洞及 NULL 收据主键问题均已修复并复核关闭 |
| 交付边界 | 未请求真实 DeepSeek/Fish；未修改 Android/React、安装 APK、创建云资源、提交或推送 |

原有 SQLite / MockTimers ExperimentalWarning 保留。本批 Prettier check、全工作区 git diff --check 和包含未跟踪后端/本批文档的逐文件空白检查均通过；已有 LF/CRLF 转换提示保留。

## 实现位置

- `agent-server/src/agent-service.ts`：prepare、一次性 run、取消通知与结果落库；返回 DTO 的修改不能改变闭包身份。
- `src/execution-ledger.ts`：执行检查、CAS 派发、有限恢复；返回前扣除 SQL 往返耗时；UTC 预算月份来自 SQL 执行时刻。
- `src/usage-receipts.ts`：最小用量证据、冲突熔断与重放；结算复用 `src/budget.ts` 的唯一计价逻辑。
- `src/deepseek.ts` / `src/loop.ts`：可信发送前/用量钩子及模型后权限复检，原独立模式兼容。
- `migrations/0003_agent_execution.sql`：增量执行字段及收据；旧数据不改写，旧预留没有新的未发送证明。
- 测试新增 `agent-service`、`execution-ledger`、`execution-recovery`、`usage-receipts`；SQL 延迟/故障仅在测试宿主边界插入，业务 SQL 与事务仍真实执行。

## 验证的故障边界

覆盖并发同键/闭包重入、输入及返回 DTO 篡改、原截止与时钟回拨、SQL 一直不回包、计数中取消、许可晚到、模型后撤权/检查失败、收据写入失败、结果保存失败、收据后结算失败并关闭/重开文件库、分页与旧数据迁移、跨月预算、有效用量与未知通知竞争。

曾出现 Windows 测试清理早于 SQLite 关闭的 EBUSY；已调整测试资源关闭/清理顺序，重开用例及全量验证通过，不涉及用户数据库。

临时文件收尾：用户明确允许后，已删除首次失败用例遗留 `C:/Users/fangr/AppData/Local/Temp/liuying-receipt-UmrlBs/ledger.sqlite` 及其空目录，并验证不存在。仅虚构测试数据，可由测试重建；未删除用户数据。

## 下一步（未执行）

后续核验记录：[精确 token 与 Workers Free 可行性](2026-09-15-tokenizer-workers-feasibility.md)。已确认官方编码路径与免费条件，但计数运行、云端资源和生命周期验收仍未通过；具体缺口以该记录为准。

1. 为 M1C 后续写小批可行性计划：精确模型消息模板/tokenizer、Workers 类型与免费层 CPU/生命周期验证；不能以字符估计冒充精确计数。
2. 细化 HTTPS 与原生在线协议，清楚区分本机/云端任务，配置设备凭据和用户摘要上传授权；旧 M1B 任务不自动上传/执行。
3. 远程 D1、秘密配置、部署与真实小额联调须另行授权；没有后台定时维护或 Worker 退出保证。

未知/丢失 usage 不能自动恢复供应商账单；may_have_dispatched 不按超时退款。当前手机直连 DeepSeek/Fish 尚未受此账本控制，不能保证全 App 月费 $2。M1C 手机闭环、M2 完整周期、M3 长期记忆、M4 双 RAG、M5 全入口预算与 M6 发布仍未完成。

工作区继续保留 `codex/five-second-zones`；HEAD 未改变，`agent-server` 连同前几批修改仍未跟踪/未提交。不要把本交接当作 Git 已保存或已推送。
