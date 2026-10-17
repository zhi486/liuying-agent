# 隔离 DO 免费运行验证实施计划

> 执行方式：沿用用户选择，由主代理直接实施；按 executing-plans 分批验证，独立代理只读审查。

**目标：** 在已有 Free 测试账户验证 SQLite DO 执行精确计数、完整假模型 Agent 与真实客户端断连后的结果回查。

**架构：** 入口 Worker 鉴权、固定路径分发并用 `ctx.waitUntil` 保持完整 RPC。DO 独立预置虚构身份、完整 await 任务执行，沿用 D1 的正式认证和账务代码；DO 私有 SQLite 仅保存探针身份和诊断状态。

**技术栈：** Wrangler 4.131.2、Vitest 4.1.11、Cloudflare Vitest plugin 1.1.9、现有 JS tokenizer。

**规格：** `docs/handoffs/2026-09-16-free-runtime-options.md`；用户已批准，原文“待批准”仅代表当时状态。

## 全局约束

- 仅新增一个测试 DO namespace，复用 `liuying-free-spike-20260915` Worker/D1。
- 不改正式 App/backend、不迁移 D1、不调用真实模型、不付费、不扩权、不设 Cron/常驻连接。
- 最多20业务请求、就绪检查另限4次，无业务自动重试；固定短有效期；收尾关闭公网，保留资源与证据。
- 保留所有既有修改；本轮不提交或推送 Git。
- 只报告实际测到的 CPU；无内存峰值 API 时明确证据缺口。

## 1. 本地行为与运行时

文件：`agent-server/tools/cloud-spike/agent-probe.mjs`、`agent-probe-identity.test.mjs`、`do-worker.mjs`、`vitest.do.config.mjs`、`do-tests/runtime.spec.mjs`、该目录 package/lock。

接口：`agentProbe(db,name,ctx,options)` 新增 `identity:{ownerId,token}`、`awaitCompletion`、`onEntered`；默认旧行为保持不变。`LiuyingRuntimeProbe.run(path,probeId,progress)` 只处理固定探针路径。

- [x] 基线：`node --experimental-strip-types --test *.test.mjs`，36通过。
- [x] 先写预置身份测试并看到失败，再改最小代码，单测通过。
- [x] DO 集成测试先用404空入口看到行为失败，再实现完整 RPC。
- [x] 本地验证：身份只预置一次、驱逐后保留身份/次数、实例标记改变、其他对象隔离、业务上限20。
- [x] 验证三轮任务397/471/562 tokens，3条收据均settled/applied且每条78微美元虚构账务。
- [x] 验证取消流后原任务完成；结算故障后驱逐再恢复，首次1次、再次0次，失败答案不改写。

运行：

```powershell
npm run test:do
node --experimental-strip-types --test *.test.mjs
```

## 2. 有界云执行与证据

文件：`run-do-probe.mjs`及其 Node 测试、`capture-tail.mjs`及日志脱敏测试、忽略的`wrangler.jsonc`。

- [x] runner测试断言请求序列和硬上限、旧协议不算就绪、任一业务失败不重试。
- [x] 日志白名单只保留 CPU/wall/outcome/路由/探针标记；不保存请求头、凭据或完整模型请求。
- [x] 干运行与独立审查通过后，检查现有账户/Worker/D1，部署唯一 `new_sqlite_classes:["LiuyingRuntimeProbe"]` 迁移。
- [x] 业务序列：setup 1；6例精确计数两遍12；Agent one/three 2；disconnect 1；同任务status 1；receipt-fault 1；recover两次2，总20。
- [x] 收到正在执行首帧后销毁独立TCP socket，确认响应未结束；新连接查询同一taskId且D1只存在一个任务。
- [x] 独立只读D1验收各任务结果、收据与结算；分别记录入口/DO真实 CPU，不以200代替任务成功。

## 3. 关闭、复审与交接

- [x] 关闭 workers.dev/preview、将有效期置为过期；保留 DO 类/绑定/迁移，不能通过恢复旧无DO包意外丢失命名空间。
- [x] 读回部署状态并确认URL不可用；记录固定版本、请求数量、CPU缺口及云内存峰值不可测的限制。
- [x] 本地全回归和独立审查；报告实际完成与剩余，不把探针当作生产部署。结果见`docs/handoffs/2026-09-16-do-runtime-validation.md`。
