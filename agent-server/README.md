# 流萤 Agent 核心与应用服务（M1A / M1C-1 / M1C-2A / M1C-2B）

这是隔离的 Agent 后端模块，不是已部署服务，也不是手机中可用的完整 Agent。已有显式构造的应用服务、DeepSeek/D1 适配器，但没有 HTTP 服务入口、供应商密钥、默认计数器或自动启动逻辑。Android 跑步、手环、语音和现有 React 界面不依赖本包。

## 运行验证

在此目录使用 Node **22.22.2** 与 pnpm **11.19.0**：

```powershell
corepack pnpm install --offline --frozen-lockfile --ignore-scripts
corepack pnpm check
```

首次机器没有依赖缓存时离线安装会失败，不能据此声称验证已通过。开发依赖固定版本；核心无运行时第三方依赖。测试使用 Node 内置运行器及 TypeScript 类型擦除，不请求真实 DeepSeek/Fish。SQL 测试使用 Node 内置 SQLite；SQLite 和模拟计时器会输出 ExperimentalWarning，未隐藏这些警告。生产代码不依赖 Node SQLite。

`check` 现在依次运行 Node 类型检查、`typecheck:workers` 和离线测试，任一失败立即停止。`tsconfig.workers.json` 使用固定 `@cloudflare/workers-types 5.20260915.1`，只加载 ES2022 与 Workers 类型，检查全部 `src` 及 `type-tests/workers.ts` 的 D1/保活/服务参数契约；不加载完整 `@types/node` 或 DOM lib，不开启 `skipLibCheck`。

类型检查含 NodeJS 命名空间与页面 document 的反向断言，防止环境配置混入。官方 Workers 声明自带 `process:any`、`Buffer:any`，因此不能用它们是否存在来证明 Node 兼容已启用或禁用。类型测试只编译不执行，不依赖临时 Spike 环境；这项日常检查不等于已配置 GitHub CI，也不证明真实 Workers 的兼容日期、绑定、CPU 或 D1 额度已经验收。

## 已有能力

- `src/tools.ts`：验证任务与最小化授权快照，执行 `runs.list`、`runs.summary`。任务最多 20 条摘要；不存在任意数据库、网络、文件、设置或运动控制工具。
- `src/loop.ts`：执行模型选择工具→只读观察→模型继续/答复，最多 3 次模型请求和 6 次工具。未知字段/工具、越权、重复调用停止任务；整批只读结果通过后才进入模型上下文。
- 截止时间默认 30 秒，可信调用方只能缩短；包含额度预留、模型等待与结算等待。取消向依赖传播，忽略取消的异步模型也不能阻塞核心返回。
- 每次模型调用前必须获取额度预留；以实际 token 用量结算，未知用量保留额度。供应商报告超出输入 20,000 / 输出 600 token 的边界时，记录真实用量并停止后续调用。
- 返回最终文本或结构化停止原因，以及不含内部思考的行动记录。未调用工具前不把全部摘要塞入模型上下文。自由文本仍可能包含用户自己输入的敏感信息。
- `src/deepseek.ts`：真实 Chat Completion 协议映射、工具调用 ID 衔接、有效用量保留、取消和有界正文读取。离线测试仅替代 HTTP transport，不替代这些业务逻辑。
- `src/budget.ts` 与 `migrations/0001_agent_budget.sql`：真实 SQL 原子预留、费率快照、幂等结算和持久熔断；SQLite 宿主已验证，尚未在远程 D1 验收。
- `src/device-auth.ts`：个人设备凭据校验、可信发放及事务撤销；随机秘密只保存 SHA-256，90 天到期，同一 owner 的设备共享账户。没有注册或管理 HTTP 入口，也不是不可复制的硬件绑定。
- `src/cloud-tasks.ts` 与 `migrations/0002_agent_device_tasks.sql`：严格提交、同键去重、单账户活跃限制、一次认领、取消/撤回、终态恢复、结果确认和到期清理。保留旧请求标识，不能重新执行已经中断的任务。
- `src/agent-service.ts`：认证/接单→一次认领→真实协议 Loop→持久结果；仅首次新意图得到一次性私密执行闭包，同键恢复不重跑。排队、SQL 等待、模型与结算均受原截止约束。
- `src/execution-ledger.ts`、`src/usage-receipts.ts` 与 `migrations/0003_agent_execution.sql`：执行绑定预留、原子派发许可/未发送封闭、独立用量收据及最多 100 条的账务恢复。恢复不会请求供应商或启动旧任务。

数值校验中的最大时长、距离、配速及心率是防畸形输入的技术边界，不是医学建议或警戒阈值。缺失配速/心率用 `null`，不补造数据。

## 接口与可信边界

核心入口 `runAgent(input, { model, budget }, { signal?, timeoutMs?, checkExecution? })`。必须提供两个端口；应用服务还配置每步预留前及模型结算后的执行检查。没有默认免费模式或内存“生产账本”。`provider-loop.test.ts` 与 `agent-service.test.ts` 将真实协议适配器、SQL 账本和任务库组合测试，不代表真实供应商或云端已接通。

### 本批工厂与配置约束

- `createDeepseekModel({ apiKey, countInputTokens, fetch?, beforeDispatch?, onUsage? })`：端点与模型固定为 DeepSeek 官方 Chat Completion / `deepseek-flash`，关闭 thinking、stream，输出最多 600 token。没有重试或可变端点；请求正文最多 64 KiB、响应最多 128 KiB，30 秒截止覆盖预检和网络，接受更早取消。应用服务强制接入两个可信钩子：计数后申请持久派发许可，完整有效用量先等待收据保存及结算，失败不继续交付答案。
- `countInputTokens` 必须精确应用相同模型的消息/工具模板，接收的是将要发送的完整请求 JSON。**本批不提供生产 tokenizer**；不能直接对 JSON 字符数估算或对 JSON 文本分词来冒充模型实际上下文计数。缺失、失败或超限时不发 HTTP。
- `createD1Budget({ db, ownerId, rate, execution?, now?, newId? })`：数据库和 owner 只能来自可信应用服务；不会匿名建账户。新服务必须提供执行绑定，预留 SQL 同时确认执行权、原设备、截止与费率；月份采用 SQL 执行时刻，防止跨月排队借用旧余额。`now/newId` 用于测试时钟和 ID，生产应使用默认实现。
- `RateCard` 包含 `version/model/inputMicrosPerMillion/outputMicrosPerMillion/checkedAtMillis/expiresAtMillis`；模型固定，单价为正整数微美元/百万 token，有效期最多 24 小时。没有默认生产费率，过期拒绝预留；每笔结算使用原费率快照。当前只估算保守缓存未命中/高峰费用，不等于供应商发票金额。
- 账户表由未来可信配置流程预置，月上限最多 1,800,000 微美元。预留唯一键为 `(owner_id, request_id)`，请求 ID 为 `taskId:1` 至 `taskId:3`，释放后也不能重复领额度。UTC 月份绑定原请求，不自动清除旧月未知账。
- `uncertain` 全额占用。新绑定行只有原子封闭 `prepared → closed_not_dispatched` 才可释放；`may_have_dispatched` 即使收到 `not_dispatched` 也只能保留未知费用。实际收费超预留或释放后又收费时，费用与账户熔断同批提交；没有自动解除熔断或供应商账单对账入口。
- 旧无绑定独立模式保持原规则：核心进入 ModelPort 后即按可能已发送处理，计数失败也保留 uncertain。新服务有持久未发送证明，计数/预检失败可安全封闭释放。旧记录不会套用新证明自动退款。

### 个人设备与云任务库（M1C-2A）

- 普通方法 `accept/get/findByRequest/cancel/withdraw/acknowledge` 每次接收 Authorization 并重新认证，SQL 内仍检查设备有效性和 owner。请求 body 不能声明 owner 或提供指纹。
- `accept` 精确接收 `clientRequestId/localTaskId` 两个小写 UUIDv4，以及 `prompt/consent/summaries`；返回 `{ task, created }`。同键同规范内容返回旧任务，异文为 `conflict`，指纹清除后为 `request_expired`，同 owner 另有活跃任务为 `busy`。输入快照仅供当前请求/执行，不单独存入任务表。
- `claim` 只允许任务原设备，在 SQL 条件更新成功后返回私密执行权；`finish` 必须匹配其 SHA-256，不能覆盖取消和已有不同终态。**二者只给可信应用执行器，不得直接开放手机路由。** M1C-2B 应用服务已将它们连接到 Loop 与协议适配器，HTTP 与手机仍未接入。
- 接单起 30 秒截止，不续租、不重试认领。SQL 截止检查采用应用快照和数据库毫秒时钟较晚者，避免数据库排队放宽时限；结果返回前还检查读取时数据库时间及当前应用时间。
- 最终结果自终态写入起可查询 24 小时，或手机实际落库后 `acknowledge` 立即清除；查询不是确认。`withdraw` 取消活跃任务并清除正文，但保留已有终态。清除后迟到 `finish` 为 `inactive`，不能恢复正文。
- 指纹按接单 24 小时清除；`purge` 为可信维护函数，部署端仍需安排定期执行和失败告警。返回前发现新到期时只隐藏正文，物理清理由后续 SQL/维护完成，不承诺云备份瞬时清除。
- 错误仅静态代码；不输出原始 SQL/供应商异常。DTO 不包含 owner、设备、输入、请求指纹、执行秘密或私有观测时钟。数据库故障不使用旧授权或假内存结果。

真实 SQLite 与 Web Crypto 离线验证已通过；测试时钟仅替换 SQLite 日期函数，并有原生时钟及毫秒精度对照，不替换业务 SQL。实际 Workers/D1 的时间语义和类型仍待部署前验收。

### 应用服务与账务恢复（M1C-2B）

- `createAgentService({db,rate,apiKey,countInputTokens,fetch?,now?})` 的 `prepare(authorization,input)` 返回 `{task,run}`。第一次 await 前形成不可变输入快照；返回 DTO 与私密执行身份隔离。只有首次接单的 run 非空；闭包一次性、返回 void，不可序列化或暴露为手机路由。
- 宿主显式运行闭包，状态/答复经 `get/findByRequest` 查询持久 DTO；同键 queued 也不给第二个闭包。进程退出导致闭包丢失时只按原截止收敛，不重新执行。`cancel/withdraw` 先持久化再中止本进程执行；`acknowledge` 仍须等手机实际保存。
- 运行使用云 task ID，预算 request ID 为 `cloudTaskId:1..3`。每步预留、实际派发、模型后工具前均查当前执行权限；任务截止和手机设备凭据到期共同限制可用期，SQL 回包等待也保守扣时。派发许可回包还检查费率到期。
- `createUsageReceipts({db,ownerId,execution}).record(reservationId,usage)` 只接受可信适配器解析的有效整数用量，绑定当前执行及确切预留。先提交收据，再复用 `createBudgetSettlement` 结算；冲突保留首条证据并持久熔断。取消结果不取消已启动的记账。
- `recoverExecutions({db,limit?,cursor?,now?})` 每次最多处理 100 条，返回 `processed/receiptsApplied/closed/uncertain/nextCursor`。依游标继续，返回 null 后结束本轮；下一轮从空游标开始，以处理晚到收据。未应用收据重放费用；SQL 确认执行失效的 prepared 封闭释放，已派发未知费用保留；无绑定旧账不自动退款。
- `applyUsageReceipt` 是可信账务维护函数，不能开放客户端用量上报。没有有效/完整 usage、收据写入前退出或写入失败时不能凭空恢复；仍需外部账单核对。上述维护没有自动定时器，需部署宿主安排分页、失败告警与 Worker 生命周期验证。
- `consent: true` 不是身份认证。未来手机在线入口须明确摘要上传许可；旧本机任务不自动上传。当前行动记录不是完整审计或逐句事实引用，模型自由文本不能作为手机控制命令。

### 独立计数与 Free 适配（2026-09-15 后续批）

- [独立计数工具](tools/token-count/README.md) 已可运行：固定官方 V4.1 编码参考与词表、完整当前请求模板、16 项 Python 回归及 7 组固定样本。三轮虚构协议样本为 347/421/512 input tokens。输出明确为 `local_reference_only`，没有替换生产 `countInputTokens`；线上 usage 和 Workers 中的计数性能仍待验证。
- `recoverFreeExecutions({db,now?,cursor?})` 是 Free 专用可信维护入口，固定最多 5 条，不接受调用方放大 limit。最坏 pending receipt 页实测 41 条 SQL；通用 `recoverExecutions` 的 1–100 行能力保留。
- **每次 invocation 只能执行一个 Free 恢复页。** 下次调用携带游标继续；禁止同次 while 翻页、用多个 `waitUntil` 伪装新调用。41 条仅为恢复函数本身，不包括认证、purge、游标读写及其他查询，也不证明 CPU 合格。
- 私密闭包支持 `run({waitUntil?})`，把当前请求的宿主回调传到服务、模型和 Loop。注册底层 operation、晚到预留释放和结算链，不把提前结束的 race 当作全部工作；30 秒原截止不延长、不等待无界 drain、不自动重试或继续已取消的模型请求。
- `background.ts` 只注册返回 void 且消费原始拒绝的保活副本，原链继续处理真实结果/错误。宿主注册抛错时停止并给静态错误，已开始的账务不依此自动退款。保活副本完成不表示账务成功；未知费用、失败告警和对账仍需持久层/宿主负责。

未来 Workers 宿主的调用形状（示例，不是已创建的 HTTP 入口）：

```ts
const prepared = await service.prepare(authorization, input);
if (prepared.run) {
  const pending = prepared.run({ waitUntil: (work) => ctx.waitUntil(work) });
  ctx.waitUntil(pending);
}
```

回调必须来自当前 `ctx`，不能存入跨请求共享服务配置。真正断连、CPU/D1 计量和进程回收仍须云端验收；`waitUntil` 不是持久队列，也不能突破平台的时长或额度限制。本批未创建云资源、配置真实密钥或改动手机。

### ModelPort 上线责任（M1C 后续）

- 把标准工具定义和消息角色映射到选定供应商的真实工具调用协议；不能把观察内容拼接成更高权限指令。
- 发送前检查真实上下文 token 数不超过预留输入上限，以供应商参数限制输出，禁隐式重试。设置响应体大小限制、取消网络请求并对流式/错误/用量信息做有界解析。
- 只返回规范化 `final` 或 `tools` 决策和实际 `usage`，不返回或记录秘密/内部推理。核心对用量超限的检查是事后熔断，不能撤销已经发生的费用。
- 核心限制工具权限，但不保证任意模型文本都符合运动健康要求；内容评测与真实供应商联调是后续上线门禁。

### BudgetPort 上线责任（M1C 后续 / M5）

- 持久、事务、幂等地执行预留；`taskId:step` 请求 ID 重复时不能再给新调用额度。价格、余额或账本状态不明时拒绝。实际金额使用整数微美元。
- `usage` 按供应商真实用量结算；`uncertain` 保留全部预留待账单对账，不当作零费用或自动超时退款；`not_dispatched` 才能释放未发送费用。
- 取消时异步结算可能还未完成。当前核心会发起晚到预留释放，但进程崩溃/Worker 生命周期结束可能阻断清理；生产服务必须用持久意图与恢复/对账保证最终收敛，不能依赖后台 Promise 存活。
- 结算失败时核心停止后续调用。预留必须保持，最终账单/人工对账处理未知结果；当前核心不自带重试队列。
- 未知费用保留不等于供应商硬封顶。当前手机直连 DeepSeek/Fish 仍在本模块以外，接入全部入口和核实 Fish 价格前，不能声称全 App 月费已控制在 $2 内。

## 后续交付

[工程计划书](../docs/superpowers/plans/2026-09-14-liuying-agent-program.md) 将首次手机可用闭环定义为 M1C。M1B 首页仍仅本机保存；M1C-1 交付适配层，M1C-2A 交付认证与任务库，M1C-2B 交付应用服务、派发证明与收据恢复。2026-09-15 全量类型检查和 154 项离线测试通过，本批新增 36 项；详见 [本批交接](../docs/handoffs/2026-09-15-agent-execution-service.md)。

后续计数工具/Free 适配批已通过后端类型检查与 161 项离线测试，详见 [本批交接](../docs/handoffs/2026-09-15-token-count-free-adapter.md)。下一步是生产计数器的 Workers 可移植性与真实类型验证，再经授权做 D1/CPU/断连探针及小额 usage 对照；这些门槛通过后才接 HTTPS/原生在线协议。远程 D1、私密配置、部署及小额真实联调另需授权。现有本机旧任务不能自动上传。长期记忆、双 RAG、每日更新及全入口费用统一均未完成；不能宣布 M1C 或全 App $2 封顶已完成。

最新批已正式修复两处 Workers 类型兼容差异，并把纯 Workers 编译纳入 `check`；新增 BOM/非法 UTF-8 回归后共 163 项测试通过，见 [类型兼容交接](../docs/handoffs/2026-09-15-workers-typecheck.md)。此前 JS BPE 的 146 组对照仍只是可行性证据，完整 JS 模板未移植；下一项是经授权的最小 Free 真云探针，不自动部署或开启付费。
