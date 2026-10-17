# 流萤 Agent 核心与应用服务（M1A / M1C-1 / M1C-2A / M1C-2B）

最新正式状态（2026-09-25）：Fun-ASR 流式输入及费用仅估算记账已完成配套真机安装／云端发布，配置与 Secret 已就位，历史数据保留，用户确认入口可见。待用户手动真实识别验收；未自动录音或调用模型。见[正式发布交接](../docs/handoffs/2026-09-25-fun-asr-production.md)。下方本地阶段描述为历史状态。

最新本地进展（2026-09-25）：用户已选定阿里 Fun-ASR 流式＋热词方案。新增设备认证的 `GET /v1/agent/voice/stream` WebSocket，原生分帧录音、可修订实时预览、最终文字仅回填草稿；不自动回退百度。需服务端 Secret `FUN_ASR_API_KEY` 及 `FUN_ASR_WORKSPACE_ID`、`FUN_ASR_REGION`、`FUN_ASR_VOCABULARY_ID`，当前后3项占位，热词 ID 必须由同账号／地域／模型创建并确认可用。尚未部署、上传密钥或调用真实识别；下方“待选”是历史状态。见[流式语音交接](../docs/handoffs/2026-09-25-fun-asr-streaming.md)。

后续优化（2026-09-24）：获准本地私密复用后，仅核对了chatbot百度字段完整性，没有输出／上传密钥或请求真实接口。供应商应用令牌已改为有界内存复用，命中省一次鉴权往返；289后端、32本地workerd通过（1既有跳过）。用户反馈准确率／速度不理想，流式识别＋热词为待选方案，不自动换供应商，仍未部署。下方“未读取”属于上一批实现阶段。

本地开发中（2026-09-24）：新增首页单次语音输入，参考 chatbot 的百度 ASR。`POST /v1/agent/voice` 只接受已登记设备、`X-Voice-Consent: baidu-asr-v1` 与 `audio/pcm;rate=16000`，正文限3200—960000字节且为偶数；鉴权后才读取并转发。需要私密配置 `BAIDU_ASR_API_KEY`、`BAIDU_ASR_SECRET_KEY`，缺失仅使语音入口不可用。音频和转写不写 D1/DO，不创建 Agent 任务，不重试，返回文字供手机核对；没有启用自动语音回复。本轮未部署、未读取 chatbot 私密配置、未调用真实百度接口。该 ASR 费用尚不计入模型 token 账本，须单独核对供应商账单；详见[语音输入交接](../docs/handoffs/2026-09-24-agent-voice-input.md)。

最新本地变更（2026-09-24）：按用户要求改为[费用仅估算与记账](../docs/handoffs/2026-09-24-report-only-cost.md)。月预算、历史熔断标记及费率到期不再阻止执行；手机任务展示费用／token 快照，未知费用不显示成零。**尚未部署或安装真机**，下方线上旧版本的到期门槛在发布前仍存在。需要先更新能接收可选 `cost` 字段的手机端，再发布后端；不改供应商额度或账户套餐。

最新真实验收（2026-09-23）：[手机官方资料单次问答](../docs/handoffs/2026-09-23-first-live-knowledge-answer.md)通过，用户看到Firefly回答，本机确认仅新增1条任务、completed/acknowledged、基础资料来源且剧透关闭/未携带记忆。费率仍于9月24日22:46:50（UTC+8）到期；不代表所有问题、网络或计费精度已验证。

最新配置（2026-09-23）：[正式模型配置与费率](../docs/handoffs/2026-09-23-production-runtime-secrets.md)经授权上传，密钥/费率两项Secret存在，新版本 `d6a08a76-0437-445c-b724-60964eb0d2a2` 占100%流量。费率到2026-09-24 22:46:50（UTC+8）有效，之后停止新任务；尚未调用模型验证密钥或真实回答，下方Secret为空是历史状态。

最新上线（2026-09-23）：[知识选择新版正式部署与手机安装](../docs/handoffs/2026-09-23-knowledge-production-update.md)已完成，正式版本 `f27ed58e-a634-4ded-b7a7-98664d5d6320` 占100%流量；手机覆盖安装、Room v6迁移和原数据保留核对通过。供应商Secret仍为空，未请求真实模型；下方未部署描述是本地开发批次历史。

最新本地进展（2026-09-23）：[手机知识选择、剧透许可与来源展示](../docs/handoffs/2026-09-23-mobile-knowledge.md)已接通单次授权、服务检索、结构化来源及 Room v6 保存恢复。279 后端、215 前端、803 JVM、32 隔离模拟器测试通过，29 本地 workerd 通过、1 既有跳过。资料仍是[官方语料补充](../docs/handoffs/2026-09-23-official-knowledge-corpus.md)中的 WHO 一份与流萤官网两份，无自动更新。未部署、上传密钥、请求真实模型或安装真机；下方 9 月 18 日是历史云状态，不代表本轮重新核验。

最新检查点（2026-09-18）：用户已批准在现有 Free 账户新建正式资源并登记手机。[正式云与真机登记](../docs/handoffs/2026-09-18-production-cloud-enrollment.md)已完成 Worker `liuying-agent`、独立 D1、0001—0004 迁移与唯一设备登记；电脑经正式 HTTPS 验证正确凭据返回 404、缺失/错误凭据返回 401。真机覆盖安装成功，跑步库迁到 v3，原 4 条跑步和 7295 路线点完整保留。用户已确认保存私密配置，手机非秘密身份三项与目标一致、对应 97 字节密文已落盘；未读取凭据内容，也未从手机发起云认证。生产 Secret 名称清单为空，未请求模型。下一步经私密渠道配置新供应商密钥与有效费率，另行批准单次真实回答验收。生产使用显式 `--config wrangler.production.jsonc`，根本地配置及关闭的 Cron 不变；下文历史阶段描述以此为准。

这是独立的 Agent 后端模块。已有应用服务、DeepSeek/D1 适配器、通用精确计数工厂及 Worker/DO 入口；正式服务目前仅完成部署和设备认证，缺少供应商配置时仍在新任务执行前拒绝请求，不能视为完整在线 Agent 已可用。Android 跑步、手环和语音不依赖本包；首页在线 Agent 是单独授权入口。[手机闭环](../docs/handoffs/2026-09-18-phone-cloud-loop.md)上一批 800 原生、212 前端、47 模拟器用例通过，本批另有 27 个本地运行时用例通过、1 项既有诊断跳过；这些不代替真实模型验收。

## 运行验证

在此目录使用 Node **22.22.2** 与 pnpm **11.19.0**：

```powershell
corepack pnpm install --offline --frozen-lockfile --ignore-scripts
corepack pnpm check
```

首次机器没有依赖缓存时离线安装会失败，不能据此声称验证已通过。依赖固定版本；计数模块使用 `@huggingface/tokenizers 0.2.0`，pnpm 补丁仅修复上游类型声明的相对路径扩展名，不改变算法。差分测试还需按 [计数工具准备步骤](tools/token-count/README.md) 安装锁定资产与 Python 环境。测试使用 Node 内置运行器及 TypeScript 类型擦除，不请求真实 DeepSeek/Fish。SQL 测试使用 Node 内置 SQLite；SQLite 和模拟计时器会输出 ExperimentalWarning，未隐藏这些警告。生产代码不依赖 Node SQLite。

`check` 现在依次运行固定计数资产校验、Node 类型检查、`typecheck:workers` 和离线测试，任一失败立即停止。`tsconfig.workers.json` 使用固定 `@cloudflare/workers-types 5.20260915.1`，只加载 ES2022 与 Workers 类型，检查全部 `src` 及 `type-tests/workers.ts` 的 D1/保活/服务参数契约；不加载完整 `@types/node` 或 DOM lib，不开启 `skipLibCheck`。

类型检查含 NodeJS 命名空间与页面 document 的反向断言，防止环境配置混入。官方 Workers 声明自带 `process:any`、`Buffer:any`，因此不能用它们是否存在来证明 Node 兼容已启用或禁用。类型测试只编译不执行，不依赖临时 Spike 环境；这项日常检查不等于已配置 GitHub CI，也不证明真实 Workers 的兼容日期、绑定、CPU 或 D1 额度已经验收。

## 已有能力

- `src/tools.ts`：验证任务与最小化授权快照，执行 `runs.list`、`runs.summary`。任务最多 20 条摘要；不存在任意数据库、网络、文件、设置或运动控制工具。
- `src/loop.ts`：执行模型选择工具→只读观察→模型继续/答复，最多 3 次模型请求和 6 次工具。未知字段/工具、越权、重复调用停止任务；整批只读结果通过后才进入模型上下文。
- 截止时间默认 30 秒，可信调用方只能缩短；包含额度预留、模型等待与结算等待。取消向依赖传播，忽略取消的异步模型也不能阻塞核心返回。
- 每次模型调用前必须获取额度预留；以实际 token 用量结算，未知用量保留额度。供应商报告超出输入 20,000 / 输出 600 token 的边界时，记录真实用量并停止后续调用。
- 返回最终文本或结构化停止原因，以及不含内部思考的行动记录。未调用工具前不把全部摘要塞入模型上下文。自由文本仍可能包含用户自己输入的敏感信息。
- `src/deepseek.ts`：真实 Chat Completion 协议映射、工具调用 ID 衔接、有效用量保留、取消和有界正文读取。离线测试仅替代 HTTP transport，不替代这些业务逻辑。
- `src/budget.ts` 与 `migrations/0001_agent_budget.sql`：真实 SQL 请求登记、费率快照、幂等结算和异常标记；不再按费用熔断账户。本批在本地 SQLite／workerd 验证，尚未部署本次修改。
- `src/device-auth.ts`：个人设备凭据校验、可信发放及事务撤销；随机秘密只保存 SHA-256，90 天到期，同一 owner 的设备共享账户。没有注册或管理 HTTP 入口，也不是不可复制的硬件绑定。
- `src/cloud-tasks.ts` 与 `migrations/0002_agent_device_tasks.sql`：严格提交、同键去重、单账户活跃限制、一次认领、取消/撤回、终态恢复、结果确认和到期清理。保留旧请求标识，不能重新执行已经中断的任务。
- `src/agent-service.ts`：认证/接单→一次认领→真实协议 Loop→持久结果；仅首次新意图得到一次性私密执行闭包，同键恢复不重跑。排队、SQL 等待、模型与结算均受原截止约束。
- `src/execution-ledger.ts`、`src/usage-receipts.ts` 与 `migrations/0003_agent_execution.sql`：执行绑定预留、原子派发许可/未发送封闭、独立用量收据及最多 100 条的账务恢复。恢复不会请求供应商或启动旧任务。

数值校验中的最大时长、距离、配速及心率是防畸形输入的技术边界，不是医学建议或警戒阈值。缺失配速/心率用 `null`，不补造数据。

## 接口与可信边界

### 本次授权记忆（2026-09-18）

任务及 HTTP 提交可选增加 `memoryContext: { version: "selected_memories_v1", approved: true, items: [{ id, version, content }] }`。未带字段仍沿用原格式、规范化指纹和系统提示；显式提供 null、空数组或未授权上下文均拒绝。最多 5 条，每条正文最多 500 个 UTF-16 单元，须已 trim、非空、合法纯文本；ID 是小写 UUIDv4，版本为正 Int，禁止重复 ID 和额外字段。

本字段只代表已认证客户端为本次任务提供的选择快照，不代表服务端能证明用户在屏幕上的操作，也不证明内容是事实。客户端从本机库重读并核对版本、归属、删除状态，展示问题与记忆后取得单次授权；发送事务再次核对。保存记忆、跑步陪伴开关及本机预览都不是这份授权。手机已接独立 `selected_memories_v1` 路径，旧 `prompt_only_v1` 不能扩展解释；真实云验收仍待完成。

应用服务在首次 await 前复制并冻结条目；完整上下文进入任务指纹，同请求更换正文或版本为 conflict，同内容重放不再请求模型。Loop 把选中记忆序列化为独立 user 级资料、当前问题后置；仅带记忆时加入不可信资料说明。记忆不会进入 system 正文，也不会新增工具或扩大跑步摘要授权。注入防护不能保证模型永远不被文本干扰，但工具执行权限由代码约束，不取决于模型声明。

输入快照不单独持久化在云任务表，结果文本可能引用记忆，仍适用结果确认／撤回／到期规则；这不是模型供应商不留存的承诺。当前结果没有结构化“实际采用了哪些记忆”的回执，不能把提供给模型的条目说成每条都被采用。后续手机在线阶段需记录本次提供的 ID/版本、发送前重新检查已修改／删除条目，并说明已发出的请求不能通过本机删除追溯撤回。

核心入口 `runAgent(input, { model, budget }, { signal?, timeoutMs?, checkExecution? })`。必须提供两个端口；应用服务还配置每步预留前及模型结算后的执行检查。没有默认免费模式或内存“生产账本”。`provider-loop.test.ts` 与 `agent-service.test.ts` 将真实协议适配器、SQL 账本和任务库组合测试，不代表真实供应商或云端已接通。

### 本批工厂与配置约束

- `createDeepseekModel({ apiKey, countInputTokens, fetch?, beforeDispatch?, onUsage? })`：端点与模型固定为 DeepSeek 官方 Chat Completion / `deepseek-flash`，关闭 thinking、stream，输出最多 600 token。没有重试或可变端点；请求正文最多 64 KiB、响应最多 128 KiB，30 秒截止覆盖预检和网络，接受更早取消。应用服务强制接入两个可信钩子：计数后申请持久派发许可，完整有效用量先等待收据保存及结算，失败不继续交付答案。
- `countInputTokens` 必须精确应用相同模型的消息/工具模板，接收的是将要发送的完整请求 JSON。`src/token-count/counter.ts` 提供显式 `createInputTokenCounter(assetBytes)`，锁定资源校验后返回该函数；已由正式 DO 懒加载注入。不能直接对 JSON 字符数估算或对 JSON 文本分词来冒充模型实际上下文计数。缺失、失败或超限时不发 HTTP。
- `createD1Budget({ db, ownerId, rate, execution?, now?, newId? })`：数据库和 owner 只能来自可信应用服务；不会匿名建账户。新服务必须提供执行绑定，预留 SQL 同时确认执行权、原设备、截止与费率；月份采用 SQL 执行时刻，防止跨月排队借用旧余额。`now/newId` 用于测试时钟和 ID，生产应使用默认实现。
- `RateCard` 包含 `version/model/inputMicrosPerMillion/outputMicrosPerMillion/checkedAtMillis/expiresAtMillis`；每笔结算使用原费率快照。有效期最多 24 小时仅用于质量标记，过期仍使用旧价；缺失或无效时用内置参考价并标明。估算不包含缓存／离峰折扣，不等于供应商发票金额。
- 账户保留旧月额度／熔断字段以兼容已有数据库，但不以这些字段决定接单。唯一键为 `(owner_id, request_id)`，请求 ID 为 `taskId:1` 至 `taskId:3`，释放后也不能重复派发。UTC 月份绑定原请求，不自动清除旧月未知账。
- `uncertain` 保留待确认金额，不阻断后续任务。新绑定行只有原子封闭 `prepared → closed_not_dispatched` 才可释放；`may_have_dispatched` 即使收到 `not_dispatched` 也只能保留未知费用。费用超预留或释放后又收费时只标记异常，不关闭功能；没有供应商账单对账入口。
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
- 运行使用云 task ID，记账 request ID 为 `cloudTaskId:1..3`。每步登记、实际派发、模型后工具前均查当前执行权限；任务截止和手机设备凭据到期共同限制可用期，SQL 回包等待也保守扣时。费率到期不影响派发许可。
- `createUsageReceipts({db,ownerId,execution}).record(reservationId,usage)` 只接受可信适配器解析的有效整数用量，绑定当前执行及确切预留。先提交收据，再复用 `createBudgetSettlement` 结算；冲突保留首条证据并标记异常，不熔断账户。取消结果不取消已启动的记账。
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

前一批已正式修复两处 Workers 类型兼容差异，并把纯 Workers 编译纳入 `check`；新增 BOM/非法 UTF-8 回归后共 163 项测试通过，见 [类型兼容交接](../docs/handoffs/2026-09-15-workers-typecheck.md)。当时 JS BPE 的 146 组对照仅为可行性证据；此后的云探针见 [隔离 DO 验证](../docs/handoffs/2026-09-16-do-runtime-validation.md)。

### 通用精确计数切片 A（2026-09-16）

现已移植完整支持域的 JS 模板并提供生产计数工厂。计数覆盖当前协议内的新文本，不查固定请求 hash；严格解析工具历史、Unicode scalar、标准序列化与 64 KiB 边界。孤立代理及两个特殊图像标记在任务接单前拒绝；ASCII `<image>文字</image>` 仍按文字处理。任务 2,000 code units 的产品限制不变。

冻结 7 组 golden、146 组请求、新种子 Unicode/added-token 输入均对照独立 Python 模板与完整 token ID 摘要。真实三轮服务加假 HTTP 还验证同一 body 计数/发送、有效收据结算、超限和取消零派发。构建门禁校验模板与字典，不自动下载；资源初始化校验运行时字典快照，失败不估算。

此“精确”仅相对于锁定官方 profile，不是在线 API 账单认证或云端性能结论。切片 A 历史交接见 [通用计数](../docs/handoffs/2026-09-16-general-tokenizer.md)；正式宿主接线的当前进展如下，手机仍未接入。

### 正式 Worker / DO 本地接入（2026-09-16，B/C）

已实现六个 HTTPS 路由、D1 设备认证与 owner 路由、DO 持久 owner 绑定、短接单互斥、早回执、完整 RPC 保活、动态有界账务收尾和取消通知。DO 只持久化 owner/version；D1 仍保存全部身份/业务/费用状态。任意支持域文字使用真实计数工厂，词表作为 Data 模块加载，入口不初始化 BPE。

新配置 `wrangler.jsonc` 默认关闭 workers.dev、preview 和 routes，仅含本地 D1 占位 ID；不能直接当生产配置部署。密钥和费率无默认值。Node 保留 NodeNext 检查；Workers 使用 ESNext/Bundler 对齐 Wrangler 生成声明，仍严格检查、不启用 skipLibCheck、不引入 Node/DOM 环境。生成文件已包含二进制模块声明。

```powershell
corepack pnpm check
corepack pnpm test:runtime
corepack pnpm test:disconnect
corepack pnpm test:restart
corepack pnpm exec wrangler types --include-runtime false --strict-vars false --check
```

`test:disconnect` 先执行 **dry-run，不部署**，再仅在 127.0.0.1 启动 HTTPS。测试 wrapper 人为延迟回执交付，真实销毁客户端 socket，在没有收到响应的情况下按原请求键恢复；模型出站全部由本地替身处理，不接触真实 API。自签证书例外仅限该回环请求，不修改全局 TLS。

当前证据：221 项 Node、双类型/资源门禁、Python 16 项通过；workerd 22 项通过、1 项插件中断 API 诊断用例显式跳过；独立本地 HTTPS 断连与有/无收据两模式进程强制重启通过。真实 bundle 测试发现 Workers 不支持 `redirect: error`，已改为 manual，并由既有非 2xx 拒绝逻辑拦截全部重定向，避免转发凭据。

`test:restart` 使用正式 bundle、真实本地 D1/SQLite DO 和合成供应商，依次运行 `receipt` / `no-receipt` 两个独立目录场景。测试 subclass 在收据 batch 提交后或执行前永久挂起，在原截止前用固定 Miniflare 5 的进程强制关闭路径终止 workerd；同目录重新启动，不重做迁移/身份预置。核对内存实例标识变化、owner 保留、执行窗口内原键重放仍 running、不再派发。有收据恢复 1→0 次，合成费率记账恒定 324 微美元；无收据在截止后多次恢复仍 uncertain、保留 6720 微美元预留。两者按真实原截止变为 timed_out、答案不复活。所有观察/中断辅助代码均不进入生产 bundle。虚构数据库保留在忽略的 `.generated/restart-*`，不自动删除。

新增运行时证据补齐 C 的本地业务矩阵：两组近输入上限长文本在冷/热 DO 实际 BPE 计数，与独立 Python 参考一致；第一轮结算后第二轮派发前取消/撤设备不会再请求模型；跨 await 迟到的旧 service 通知不影响新任务；真实 D1 加注入时钟验证指纹与结果各自 24 小时边界；取消后原截止内等待收据实际结算，结果不复活。未真实等待 24 小时，不承诺同步 BPE 可抢占、宿主截止后写入必达或线上 usage 等于本地参考。

插件内 `abortAllDurableObjects` 的原生 Fatal 与定点 `state.abort` 超时仍是未修复的诊断问题；独立进程重启通过不能声称这两个 API 已修复，也不等于云端单 DO 故障保证。证据及复现方式见 [本批交接](../docs/handoffs/2026-09-16-production-do-runtime.md)。正式上线仍需云 CPU/峰值内存、真实 usage、私密配置、清理/恢复调度及手机接入验收；本轮未部署、提交或推送。
