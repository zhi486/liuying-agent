# M1C-1 模型适配与持久预算交接

日期：2026-09-14。当前用户请求“继续下一步”，承接原生任务库与首页已完成的 M1B。结论：**M1C-1 离线模块已验收，完整 M1C 尚未完成。**

## 本批交付

| 范围 | 已完成 | 尚未代表什么 |
| --- | --- | --- |
| DeepSeek 适配 | 固定官方协议、只读工具映射、多轮 ID 衔接、输入计数依赖、600 输出限制、30 秒/取消、64/128 KiB 请求/响应限制、脱敏错误与实际用量归一化 | 未提供生产精确 tokenizer；未请求真实 API |
| D1 预算 | 账户/预留迁移、单 SQL 并发预留、幂等结算、费用快照、过期拒绝、未知占额、迟到费用、事务回滚、持久熔断 | 未部署或验证远程 D1；无自动开户/充值/对账/解除熔断 |
| 组合闭环 | 真实适配器＋真实 SQLite 执行预算 SQL＋runAgent，两轮读取授权摘要并答复；未知/非法输出正确占额或记账 | 不是手机真实联网对话 |

实现：`agent-server/src/deepseek.ts`、`src/budget.ts`、`src/d1.ts`、`migrations/0001_agent_budget.sql`。测试：`test/deepseek.test.ts`、`test/budget.test.ts`、`test/sqlite-d1.ts`、`test/provider-loop.test.ts`。无新增运行时依赖。

## 验证证据

在 `agent-server` 目录运行：

```powershell
corepack pnpm check
```

- 类型检查通过，完整测试 **86/86**：既有核心 56，本批新增 30（预算 14、适配器 10、组合 6，含嵌套子测试）。
- 13,440 微美元测试账户只能并发预留两笔 6,720；相同 owner/request 永久拒绝重复，包括已释放请求。不同所有者隔离。
- 两轮各用输入 100 / 输出 40 token，按测试费率各记 78 微美元。金额用 BigInt 乘法后向上取整，不裁剪真实超限用量。
- 使用真实文件数据库关闭/重开检查持久性；跨 UTC 月保留旧未知账。用 SQLite 触发器令 batch 第二条 SQL 失败，确认费用更新一起回滚。
- 已覆盖相同/冲突 usage、usage 与 released/uncertain 两种先后顺序；费用不清零。释放后实际收费或超预留时，同事务记录费用并熔断。
- 取消计数/网络/正文读取、迟到 HTTP 响应、模拟 30 秒总截止、超大正文、非法工具/用量、401/429/500 均由假 transport 驱动真实适配器验证。
- 本批 TypeScript Prettier 检查通过；`git diff --check` 通过。Git 保留既有 LF/CRLF 提示；Node SQLite 与 MockTimers 会输出 ExperimentalWarning，不隐藏警告。
- 独立只读复审适配器、预算与组合测试，未发现 P1/P2；建议的额外竞态场景已固化。

SQLite 验证的是实际 SQL 和事务边界，不能替代官方 Workers 类型包编译、远程 D1 batch 或免费层 CPU/额度测试。默认 Node SQLite 同步宿主也不是远程并发负载测试。

## 必须保留的边界

1. 工厂从可信服务获取 key、owner、rate，不读取环境或自动访问手机秘密。不能将它直接暴露成匿名 HTTP 接口。
2. 输入计数器接收完整请求 JSON，但必须应用相同模型的聊天/工具模板；不能把字符比例、字节数或对 JSON 原文分词当精确上下文计数。缺失/失败/超限不发请求。
3. 已进入 ModelPort 后，核心预检失败也按 uncertain 保留额度；不等于实际扣费。迟到已收费请求、进程退出、Worker 生命周期与人工对账仍需后续持久恢复服务。
4. Usage 当前没有缓存/峰谷拆分，费用是按有效保守费率的估算，不是供应商账单；费率无默认值、有效期最多 24 小时。账户月限额最多 $1.80，仅覆盖该端口。
5. **现有手机直连 DeepSeek/Fish 不受此账本约束，不能保证全 App $2/月。** 没有真实 API 调用或 Fish 额度消耗。
6. `taskId:1..3` 是预算请求唯一键，不是完整任务恢复机制；不能重启后自动从第 1 轮重跑。M1B 的旧本机任务也不能自动上传/执行。

## 下一批建议

继续 M1C 应用服务：先确定单用户身份与设备绑定、摘要授权/撤回、任务幂等状态与恢复协议，再补实际 Workers 类型与远程 D1 门禁及经过验证的 tokenizer。只有这些具备后才连接手机首页发送/详情/取消。

真实云端账号配置、部署、付费联调及手机安装需要用户单独配合/批准；密钥在私密入口填写，不发到聊天。长期记忆、Health RAG、Firefly Lore RAG、完整跑步周期与全入口预算仍属于 M2—M5，不在本批宣称完成。

## 工作区

沿用 `codex/five-second-zones`，HEAD `b2cdb7d70b7ee06c2982f34738a59c869ed6c634`。主代理写代码、子代理只读复审；既有 Android/React/曲线/图标/文档脏修改全部保留。本批未改 Android/React、未重建 APK、未启动模拟器/安装真机、未提交或推送。

恢复入口：[总体计划](../superpowers/plans/2026-09-14-liuying-agent-program.md)、[本批设计](../superpowers/specs/2026-09-14-agent-provider-budget-design.md)、[本批已勾选实施计划](../superpowers/plans/2026-09-14-agent-provider-budget.md)、[M1B 交接](2026-09-14-native-agent-tasks.md)。
