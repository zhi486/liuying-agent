# M1C-2B 应用服务实施计划

> 执行方式：主代理依 executing-plans 与 test-driven-development 逐项实现，子代理只读复审。沿用用户确认的当前工作区，不提交、推送或部署。

**目标：** 将已验证的设备认证、云任务库、Agent Loop、DeepSeek 协议与持久预算组合起来，并提供不重跑模型的账务恢复。

**依据：** [已确认设计](../specs/2026-09-15-agent-execution-service-design.md)。只改 `agent-server` 及对应文档；不改手机、前端或真实供应商配置。

**技术：** TypeScript、D1 SQL、Web Crypto；测试使用 Node SQLite 和假 HTTP，不替换业务模块。2026-09-15 实施前重新运行 `corepack pnpm check`，118 项通过。

## 任务 1：执行绑定预算与派发门禁

文件：新增 `agent-server/migrations/0003_agent_execution.sql`、`src/execution-ledger.ts`、`test/execution-ledger.test.ts`；修改 `src/budget.ts`、`test/sqlite-d1.ts`。

- [x] RED：真实任务认证/认领后可预留；错误 execution、失效设备、取消、截止、过期费率不能预留/派发；并发许可只有一次；prepared 可释放、已派发不可释放。运行 `node --experimental-strip-types --test test/execution-ledger.test.ts`，确认行为断言失败。
- [x] GREEN：增量添加 nullable `execution_hash`、`dispatch_state`、`rate_expires_at_millis`，新行只允许 prepared / may_have_dispatched / closed_not_dispatched；收据表 reservation ID 唯一，保存整数 token、接收时间及 applied 标记。旧记录保持空关联。测试宿主只在尚无新字段时执行 0003，文件库重开不重复 ALTER。
- [x] `createExecutionLedger({db, execution})` 根据任务 ID 和 token 摘要查可信 owner，提供 `check(signal?)` 返回剩余毫秒、`beginDispatch(requestId,reservationId,signal)`；使用数据库执行时钟及原设备校验。预留接受可信绑定 `{taskId,executionHash}`，原子 SQL 检查 owner/task/execution/设备/deadline/费率/账户。
- [x] 抽出 `createBudgetSettlement({db,ownerId})`，保留唯一计价算法和旧模式；新行非 usage 结算按持久 dispatch_state 决定原子封闭释放或保留 uncertain，不依据调用者声称退款。
- [x] 验证：定向 SQL 测试、旧预算测试、类型检查；检查文件库迁移兼容、DB 查询耗时不扩大截止、跨月预留不借旧月份额度。

## 任务 2：用量收据与供应商钩子

文件：新增 `src/usage-receipts.ts`、`test/usage-receipts.test.ts`；修改 `src/deepseek.ts`、`test/deepseek.test.ts`。

- [x] RED：先持久收据后结算；第二步故障保留未应用证据；重复幂等，冲突保存第一份并熔断；已取消仍可入账；未授权 reservation 不可写收据。运行定向测试观察断言失败。
- [x] GREEN：`createUsageReceipts({db,ownerId,execution})` 提供 `record(reservationId,usage)`；可信 `applyUsageReceipt({db,ownerId,reservationId})` 单独供维护重放。绑定行使用服务闭包掌握的 ID；查询原价格并复用 settlement，不复制计算。冲突收据事务先持久熔断再抛静态错误。
- [x] RED/GREEN：DeepSeek 增加 `beforeDispatch(requestId,signal)`，只在计数完成后调用且钩子返回后再次检查 signal；归一化有效 usage 后立即等待 `onUsage(requestId,usage)` 成功，不以任务授权状态过滤，不回传原始正文。非法答案仍记账；取消已开始的收据写入不停止账务 Promise；取消未读正文仍及时终止网络。
- [x] 验证：定向钩子/收据/旧供应商测试及类型检查；无有效 usage 保守保留未知额度；已入账不被迟到 uncertain 覆盖。

## 任务 3：应用服务、有限恢复与完整组合验收

文件：新增 `src/agent-service.ts`、`test/agent-service.test.ts`、`test/execution-recovery.test.ts`；修改 `src/loop.ts`、`src/execution-ledger.ts` 和对应测试。

- [x] RED：`prepare` 冻结输入、仅新接单返回一次性 run、云 ID 进入预算；两轮真实协议工具调用得到持久 DTO 和两笔账；重复闭包不能多发请求。模型后撤权不能读工具/保存答案。
- [x] GREEN：`createAgentService` 组合现有模块，`prepare/get/findByRequest/cancel/withdraw/acknowledge` 复用任务库。run 先置一次性标记再 SQL claim，按原 deadline 剩余时间调用 Loop；run 返回 void，不能可靠读写时抛静态 CloudError unavailable。
- [x] Loop 添加可选可信 `checkExecution(signal)`，在每步预算前及每次模型结算后运行，受同一 scope 约束；旧调用无需配置。服务取消/撤回先持久化再本地 abort。执行结束总是清除本地 controller，不缓存授权。
- [x] RED/GREEN：`recoverExecutions({db,limit,cursor})` 最多 100 条，按 reservation ID 游标遍历执行绑定预留，先应用未应用收据，仅对 SQL 证实任务失效的 prepared 原子封闭/释放，may_have_dispatched 转 uncertain；旧无绑定行不自动退款。返回处理数和 nextCursor，不含内容、不触发模型、不依赖本进程是否有任务。
- [x] 组合故障矩阵：排队截止、计数中取消、迟到派发许可、派发后取消/未知账、晚到已知用量、结算后撤权、收据写入失败、写收据后结算失败重开、持久化答案失败、迁移旧数据。先 RED 再 GREEN，避免以伪业务接口模拟通过。
- [x] 验证：全量 `corepack pnpm check`；对本批 TS 运行项目 Prettier 并检查；包含未跟踪文件的空白检查；子代理只读审查并修复具体问题后再次全量验证。
- [x] 更新 README、总计划、交接及相关记忆：区分后端本地完成与未部署/未接手机；记录真实测试数和已知上线门禁，不写真实密钥。

## 完成边界

全部勾选才称 M1C-2B 完成。遇到可修复测试失败继续查根因；若新增真实凭据、费用、部署或范围变化才请求用户决定。保持原 118 项行为，不把测试通过称为远程 D1、云生命周期或全 App $2 封顶验收。

## 实施验收记录（2026-09-15）

最终类型检查与 154/154 项离线测试通过，本批新增 36 项。Prettier check、全工作区 git diff --check 及包括未跟踪后端/本批文档的逐文件空白检查通过；原有 CRLF 提示保留。独立只读复审已关闭设备到期回包与 NULL 收据问题；未改手机、未真实调用、未部署、未提交推送。详见 [交接](../../handoffs/2026-09-15-agent-execution-service.md)。
