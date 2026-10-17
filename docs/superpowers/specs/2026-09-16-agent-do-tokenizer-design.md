# M1C 正式 DO 执行与通用精确计数设计

日期：2026-09-16。分类：架构整合。状态：**用户已审阅并批准执行；先实施切片 A，随后接入 DO 并做本地运行时验收。云端与真实 API 仍另行授权。**

## 1. 目标、范围与依据

采用轻量 Worker 入口、每用户一个 SQLite-backed Durable Object、D1 持久业务状态的结构，承接已经实现的 `createAgentService`。补齐生产计数器，让当前 Agent 协议内的合法用户文本可以实时计数，不再依赖固定请求哈希查表。

本批先完成后端源码、默认关闭的部署配置及本地验收，不改变手机端、跑步语音、BLE、GPS、既有本机任务或 UI；不增加 RAG、长期记忆、公开注册、自动重试工作流和通用聊天协议。不自动上传旧本机任务，不调用真实 DeepSeek/Fish，不创建或开放云资源，不升级套餐。本批不自动提交或推送 Git，保留工作区原有改动。

沿用已确认的 [个人设备与云任务规格](2026-09-14-agent-device-tasks-design.md)、[应用服务与执行记账规格](2026-09-15-agent-execution-service-design.md) 和 [模型预算规格](2026-09-14-agent-provider-budget-design.md)：个人使用、凭据 90 天、结果最长可查询 24 小时、最多 3 次模型调用及 6 次只读工具、接单起 30 秒期限、输入与中间上下文不持久化、可能派发后不自动重跑或退款。

[DO 隔离验证](../../handoffs/2026-09-16-do-runtime-validation.md) 证明了固定样本的可行性，不是生产验收：入口 CPU 1–2 ms，首次 DO 计数含初始化及 ID 校验 225 ms，假模型任务断开独立 TCP 后可以回查；通用 JS 模板和云端峰值内存尚无完成证据。探针代码、固定身份、固定请求、次数许可和测试密钥均不进入正式运行路径。

## 2. 方案选择与职责

| 方案 | 取舍 | 决定 |
| --- | --- | --- |
| Worker 入口＋每用户执行 DO＋D1 | 将计数与完整 Loop 放在同一执行边界，复用现有任务和费用安全检查 | 采用 |
| 只把 tokenizer 放入 DO | 剩余 Agent 执行仍存在普通 Free Worker CPU 风险，增加每轮跨边界交互 | 不采用 |
| 立即增加工作流或自动重试队列 | 需另行设计输入持久化、重放与计费语义，超出已确认的断线恢复策略 | 不采用 |

| 组件 | 承担的职责 | 不承担的职责 |
| --- | --- | --- |
| HTTP Worker | 有界读取、设备校验、可信路由、任务回执；直连 D1 查询及控制操作 | 分词、模型调用、执行权与账本的第二套实现 |
| `LiuyingAgentRuntime` DO | 同一 owner 的执行协调、一次性 prepare/run、懒加载计数器、在途中止通知 | 持久保存设备令牌、用户 prompt、运动摘要或中间对话 |
| D1 | 设备、任务状态、唯一执行认领、预算、派发标记、用量收据、结果保留 | 自动重发模型或保存可重放的完整输入 |
| 独立 Python 参考工具 | 锁定官方模板和分词资源，生成差分测试期望值 | 生产 HTTP 请求处理或在线计数服务 |

D1 是业务真相源。DO SQLite 只保存最小内部 owner 绑定和协议版本，不复制任务表或账本；内存执行槽、计数器和中止句柄可以丢失，不能当作唯一执行证明。

## 3. 身份、DO 路由与配置

入口只接受 HTTPS 的 `Authorization: Bearer ...`，用现有 `createDeviceAuth.authenticate` 从 D1 得到 owner/device；请求正文不得提供 owner。通过 `getByName` 按服务器确认的 owner 确定性路由，不能按客户端 task ID、临时 UUID 或一枚全局固定名字建执行对象。

DO 再次验证原授权，并将该 owner 与本对象的持久 owner 绑定比较。初始化绑定只允许在验证成功后执行，并保证并发首次初始化不会覆盖已有绑定。`createAgentService` 内的实时鉴权、原设备检查、执行权和 SQL 派发门禁均保留，DO 路由不能代替它们。

DO 的创建器只做最小初始化；不把整段执行或外部 fetch 放进 `blockConcurrencyWhile`。计数器只在 DO 执行路径首次需要时构建，Worker 入口不构建 BPE。字典、模板版本和资源摘要由构建锁定；用户输入不能选择模型、供应商 URL、DO 类、费率或分词配置。

DeepSeek 密钥来自私密宿主配置，不放在普通 vars、代码、DTO 或日志。设备令牌仅在本次请求和执行闭包内传递，D1 继续仅保存令牌摘要。有效费率按原规则校验，过期停止，不自动延长 24 小时有效期、不使用陈旧缓存继续派发；费率刷新与实际私密配置属于上线验收项。

## 4. HTTP 契约

仅新增后端协议，不改现有 `agent.tasks.v1`。所有任务响应包装为 `{ task: CloudTaskView }`；只返回持久任务 DTO，不传递 run 闭包、execution token、原授权、原始模型响应或内部推理。

| 方法与路径 | 行为 |
| --- | --- |
| `POST /v1/agent/tasks` | 接收现有 `parseSubmission` 的五个字段：clientRequestId、localTaskId、prompt、consent、summaries |
| `GET /v1/agent/tasks/:taskId` | 按已认证 owner 查询任务 |
| `GET /v1/agent/requests/:clientRequestId` | 在提交回执丢失时按原请求键恢复 |
| `POST /v1/agent/tasks/:taskId/cancel` | 持久取消，再尽力通知在途执行中止 |
| `POST /v1/agent/tasks/:taskId/withdraw` | 撤回本次资料授权，并按现有规则清除可查询结果 |
| `POST /v1/agent/tasks/:taskId/acknowledge` | 手机确认已落本机库后，清除云结果；服务端不代替手机发起确认 |

提交接口只接收 UTF-8 JSON，按实际读取字节限制为 64 KiB，不能只信 Content-Length；拒绝压缩正文和非法 UTF-8。控制操作为空正文；方法、路径、标识和字段严格校验，不增加通用 RPC/SQL/管理路由。所有结果与错误使用 `Cache-Control: no-store`，不开放浏览器通配 CORS，不接受 query/cookie/body 中的认证凭据。

queued/running 回执使用 HTTP 202，已存在的终态任务使用 HTTP 200；查询和控制成功使用 200。静态错误映射：invalid_request→400、unauthenticated→401、not_found→404、conflict/busy/inactive→409、request_expired→410、unavailable→503。正文超限使用 413 及静态 invalid_request；不支持的正文媒体类型使用 415 及静态 invalid_request。不返回供应商正文、SQL、堆栈或输入片段。返回 503 不表示“尚未接单”，客户端仍须按原请求键查询，不能生成新键自动重提。

未认证调用不能创建 DO 或消耗模型额度。设备发放、撤销、调额、费用恢复和部署管理不属于这些公开业务路由。

## 5. 接单回执、执行与生命周期

一次提交只有一个完整 DO 执行 RPC。入口在等待接单回执前，将完整 RPC 的 Promise 注册到 Worker `ctx.waitUntil`；DO 内部完成 `prepare → run → 持久终态读取`，不拆成“先接单再从另一条 RPC 找回闭包”。

DO 通过仅供本次调用使用的内部回执通道向入口返回已持久化的任务 DTO，入口据此尽早返回 202/200。回执只表示任务存在，不表示模型成功、结果已保存或不会超时。取得首次 run 后立即启动执行，回执独立发送，不先 await 回执再执行。入口竞争“接单回执”和“完整 RPC 提前结束”：回执前 RPC 失败时返回静态错误，RPC 成功却没有回执时返回 unavailable。回执发送不得阻塞 RPC 收尾；失败或迟到拒绝必须被消费，不能阻止已接单执行或触发第二次 prepare/run。

只有 `prepare` 返回非空 run 的首次提交可以执行；同键同内容只返回原任务，同键异文拒绝。同 owner 的不同新任务若已有活动任务则拒绝 busy。DO 不建立隐形等待队列；同一实例旧执行仍在收尾时，也不让新意图占用第二个执行槽。槽忙时先按 owner 查询请求键：未知键必须在可能创建任务的 prepare 之前拒绝，已存在键仍走正式指纹核验。并发首次 prepare 使用短接单互斥标记协调，不能先产生两份新闭包再判 busy；不能把整个执行包进互斥区。重复提交不能仅凭键存在跳过正文指纹。

30 秒采用现有接单绝对截止时间，不因 HTTP 回执、重连、DO 重建、计数、认证回查或 SQL 等待重置。入口读正文及接单前鉴权尚未创建任务，不把它们伪称已纳入数据库任务期限。各阶段仍使用剩余时限，超时禁止新的模型或工具派发。

宿主将应用服务注册的后台 Promise 收集起来，包括模型适配器、结算与终态写入；DO 完整 RPC 在宿主截止内等待主流程及已登记收尾，不是只等待一个提前结束的 race。收集器覆盖收尾过程中新增的 Promise，不能只对最初数组执行一次 `Promise.all`。接单前宿主等待上限为 RPC 入场起单调时钟 30 秒；若等待已结束，迟到的 prepare 不能再调用 run，已有记录仅供按原键查询收敛。接单后收尾等待不晚于该任务原 deadline，不给 drain 重新分配 30 秒；主执行仍受服务内更早的单调时钟期限约束。

截止后停止等待并释放执行槽，持续消费迟到拒绝，禁止新派发，不把尚未完成的后台 Promise 标为成功；未完成收据/结算按已有持久证据保留或恢复。测试必须包含永不 settle 的 D1/transport Promise。DO 的 `ctx.waitUntil` 不能用于保活，回执等待也不能延长上述截止。

Worker 的 waitUntil 只有响应完成或客户端断开后最多 30 秒的延长窗口，不是持久任务保证。已收到的合法 usage 仍尝试入库；平台终止、收据写入失败或未取得完整 usage 时继续保留未知费用，不宣称所有结算都能完成。DO 驱逐/重建不重建 run 闭包、不恢复 prompt、不抢占旧执行；查询按原截止收敛，恢复流程只处理状态和账务。

## 6. 取消、撤回和结果保留

查询、cancel、withdraw、acknowledge 由入口直接调用 D1 任务库，不能排在 DO 的 tokenizer 或模型等待之后。取消/撤回必须先成功落 D1，再尽力通知所属 DO；通知失败不回滚已成功的取消。

DO 控制通知单独处理，不受执行槽 busy 限制；验证身份及归属后，使用当前执行对应的同一应用服务实例调用现有 cancel/withdraw 以触发 AbortController。不要每次通知都创建一个没有 running Map 的新服务。通知可以再次执行幂等数据库控制操作；不因此新增无鉴权的强制中止能力。

执行服务及有效费率快照按执行需要构造，在途服务引用仅保留到本次完整 RPC 收尾。不能为方便取消而永久缓存过期费率。跨实例的即时 abort 无法保证，D1 检查负责阻止下一次派发、撤回后的工具使用及迟到答案复活；已经通过派发门禁的请求仍可能计费。

沿用 24 小时结果/指纹规则，以及手机成功写入 Room 后才 acknowledge 的规则。输入、运动摘要和模型中间上下文不进入 DO 持久状态。查询到期即隐藏正文；物理清理不能只依赖查询碰巧发生。

本批复用现有 `purge` 与每页最多 5 条的 `recoverFreeExecutions`，不开放公网恢复端点，不默认增加 Cron/alarm。实际定时物理清理、恢复调度、失败告警与费率更新必须在正式公网启用前另行配置和验收；未完成时只能标记为“本地后端接入完成”，不能标记生产上线。

## 7. 精确计数的定义和支持域

计数器保留现有注入签名 `countInputTokens(serializedRequest: string): Promise<number>`。输入是 `deepseek.ts` 实际发送的同一份 JSON body。计算对象不是用户一句话，也不是 HTTP JSON 的字符，而是该 body 经官方消息模板生成的完整模型输入。

精确性声明限定为：**在支持域内，相对于锁定的官方模板及 tokenizer 的计数一致性**。线上 API 别名可能调整模板或增加处理，供应商 `usage.prompt_tokens` 才是实际用量依据；本地一致性和线上账单一致性必须分别报告。

沿用 `tools/token-count/assets.lock.json` 的 profile `deepseek-v41-reference-chat-v1`、模型 revision `dba1be0a40aa45a94ad051997016db3960a90277`、资源字节数与 SHA-256；独立参考使用 Python tokenizers 0.23.2，JS 候选固定 `@huggingface/tokenizers` 0.2.0。构建验证资产和依赖完整性；失败阻止构建，不在线下载或回退到别的分词器。

支持域固定为当前生产适配器的纯文本请求：deepseek-flash、thinking disabled、非 stream、输出限制 1–600、请求不超过 64 KiB、system/user 文本以及现有 runs_list/runs_summary 的工具调用和结果；工具仅允许当前两个 schema 的限定结构及 0–2 个不重复组合。任务输入继续遵守现有非空、不超过 2,000 UTF-16 code units、trim、最多 20 条授权摘要等产品限制，不借计数器移除这些限制。底层计数器的差分输入域与任务入口不同：支持协议内的空文本或长文本可用于 ≤64 KiB 的计数测试，不要求这些夹具通过任务提交端点。

合法文本包括中文扩展字符、英文、emoji/ZWJ、组合字符、换行及 JSON 可表示的控制字符；计数器不额外 trim、NFC 归一化、HTML 转义或改写空白。孤立 UTF-16 代理字符明确拒绝，不能让 TextEncoder 静默替换后继续发送。沿用当前 Python 参考对保留图像标记 `<｜deepseek_image｜>`、`<｜image｜>` 的拒绝；这些特例不伪称“任意字符串都支持”。任务入口在 prepare 接单前执行共享纯文本校验，返回静态非法输入错误；计数器仍保留防御检查，不能仅在深层计数失败后归为 model_unavailable。有兼容性测试，不修改本机已保存任务。

不支持图像、多模态、thinking、未知模型、未知工具/schema、未知字段及不完整工具历史。协议保持服务器构造；不新增面向手机的任意 chat-completions 透传端点。计数器要求外层 body 满足 `JSON.stringify(JSON.parse(raw)) === raw` 并进行严格形状校验；assistant 的 function.arguments 字符串单独做同样的标准序列化校验，避免嵌套重复 JSON 键漏过。该检查不代替 Unicode 校验；tool.content 作为普通字符串原样保留，不执行解析重写。不满足本服务标准序列化格式的请求拒绝处理，这不妨碍当前适配器生成的新用户文本。

## 8. JS 模板与 BPE 的实现要求

按职责分离协议检查、官方模板渲染和 BPE 封装，复用一份锁定资源，不复制第二套 Agent 或供应商请求构造器。

1. 将请求顶层工具定义附到官方模板要求的 system 部分，计入工具说明和 schema；明确保持现有工具字段和顺序。
2. 移植支持域内 Python `json.dumps(ensure_ascii=False)` 的模板序列化语义，包括逗号/冒号后的空格、Unicode 和控制符转义、数组及对象顺序，不能用紧凑 JSON.stringify 冒充。当前 schema 无任意数值及数字键，不扩展成通用 Python JSON 实现。
3. 按官方规则编码 assistant 工具调用、DSML 参数、角色边界、BOS/EOS 和 chat 模式生成标记。runs_list 参数为空对象，runs_summary 仅 runId 字符串。
4. 合并连续工具结果，并按前一 assistant 的调用顺序排列；工具 content 已是实际 body 中的 JSON 字符串，保持原样，不解析后重新格式化其中的运动数值。
5. 正式运行只返回 token 数量，不保存 prompt 或完整 ID；完整 prompt 字节和 ID 摘要仅用于合成数据的离线差分测试。禁止用固定 request SHA、fixture 或固定 token 数作为运行时答案。
6. tokenizer 懒初始化并复用，缓存有界；不建立随用户请求无限增长的 prompt/body 结果缓存，不将缓存持久化。失败不返回估算值或默认值。

模型适配器在调用计数器后仍检查截止、AbortSignal、输入 token 上限 20,000 及持久派发许可。计数失败、超限、计数中取消或截止时 transport 必须未调用；未派发预算按现有 prepared 封闭证明处理，不能改变 may_have_dispatched 的保守记账。

## 9. 文件边界与交付切片

| 切片 | 拟新增/精准修改范围 | 完成定义 |
| --- | --- | --- |
| A：通用计数 | `agent-server/src/token-count/`、锁定资源构建步骤、参考工具/差分测试；必要的纯文本输入验证与依赖锁 | 任意支持域文本不查表，模板字节与完整 token ID 对照通过，失败不派发 |
| B：正式运行边界 | `agent-server/src/worker.ts`、`agent-server/src/agent-runtime.ts`、HTTP 契约/测试、宿主后台 Promise 收集；必要的类型与关闭态配置 | 真实模块组合，可信按 owner 路由，早回执、查询/取消不依赖执行槽，账务恢复不重发 |
| C：本地运行时验收 | 真实 workerd/DO/D1 绑定测试、构建/类型/全量回归及独立只读审查 | 冷/热/长输入、并发、断流、重建、权限竞争均有可复核证据 |
| D：后续云与手机门禁 | 正式资源隔离、私密配置、清理/恢复调度、受限云测与获准真实 API 对照，之后单独原生接入 | 另行授权、另行验收，不在 A–C 完成时冒称通过 |

A 先于 B 的真实计数接线，C 使用正式模块而不是固定探针实现。沿用主代理直接实现、子代理只读核对/审查。不改 Android/React，不重构无关后端，不迁移现有 D1 账本到 DO。

## 10. 验收矩阵

| 类别 | 必须观察到的结果 |
| --- | --- |
| 资源锁定 | 模板/字典/依赖版本正确；篡改 hash 或资源缺失导致构建失败 |
| 模板差分 | 同一请求分别由独立 Python 官方源码与 JS 渲染，完整 UTF-8 字节一致；不把 Python prompt 直接喂 JS 冒充模板验证 |
| BPE 差分 | 完整 token ID 数组或其 uint32 little-endian SHA-256 一致，不能只比较长度 |
| 非固定输入 | 升级现有 146＋3 组并增加新种子 Unicode、逐字段变化、跨消息边界、三轮真实适配器请求；运行时无样本哈希分支 |
| 特殊边界 | 空白、转义、emoji、组合字符、长中文/ASCII、保留标记、孤立代理、乱序工具结果；64 KiB 和 20,000 token 两侧 |
| 发送一致性 | 计数 body 与 transport body 相同；每一轮都包含最新工具结果；错误/超限/取消时 HTTP 调用数为 0 |
| HTTP 与身份 | 未认证不能建对象；未知字段/非法编码/超限拒绝；跨 owner 的 ID 与不存在同样不可读；直接 RPC 的 owner 不匹配也拒绝 |
| 回执与断流 | 回执之前 D1 已有任务；回执丢失后按原键找回；回执通道断开不创建第二次执行；入口保持完整 RPC |
| 去重及并发 | 同键同文一次执行，同键异文冲突；不同新键 busy；查询/取消不受执行槽 busy 影响 |
| 取消与撤权 | 先 D1 后尽力 abort；通知失败仍是 cancelled/withdrawn；计数后、派发前和结算后各边界不复活答案 |
| 生命周期 | 单轮、三轮、冷/热计数、截止及晚到 usage；DO 重建后不重派，原截止不变，未知费用不退款 |
| 恢复与保留 | 收据恢复一次再零次；may_have_dispatched 保留；24 小时到期/ack/withdraw 隐藏与清除规则不退化 |
| 资源与回归 | Node/Workers 类型、已有后端回归、新 workerd 测试、关闭态构建/配置、差异检查及独立审查通过 |

保留现有 7 组冻结 golden，不随 JS 实现自动刷新。新请求生成器输出原始 request，独立交给 hash 校验后的官方 Python 模板和待测 JS 模板；期望值不经 JS 模板或双方共用的待测序列化函数。模板字节与完整 ID 两层均须核对。

随机差分与完整 ID 一致不能数学证明所有输入；必须同时审阅模板分支和支持域，不把通过样本数夸大为线上账单认证。测试数据全部合成，不上传真实轨迹或连续心率。

## 11. 免费层、资源和上线门禁

用户确认当前套餐仍为 Free，继续免费优先、月预算目标不超过 $2。该目标不是本批已经做到的全 App 硬封顶；手机直连 DeepSeek/Fish 尚在本账本之外。既有按 20,000/600 上限预留与实际 usage 结算策略不因计数变准确而放宽。

正式云验收必须分别关联 Worker 与 DO CPU/wall time，包含冷初始化、未见过的长输入、重复输入、并发对象与接近截止场景。不能用入口 CPU 代替 DO CPU，也不能把固定样本缓存命中当作任意文本性能。真实鉴权与查询/取消路径需要单独测量，不能沿用探针廉价认证的 1–2 ms 作结论。

内存目前无可靠云端峰值数据。本地堆采样只作诊断，workerd 的非真实字段及“没有 OOM”不能证明云端余量；记录字典体积、输入边界、缓存界限和真实资源失败。若无法取得可信峰值，报告保留“未知”，以有界输入和受控负载证据作为风险说明，不伪造量化通过。

正式服务须与已关闭的 spike 分开命名及配置；不直接重开已用完 20 次许可的探针，不覆盖或删除其 DO 类/迁移/状态。A–C 不填写真实云资源 ID，不执行迁移/部署/secret put，不开放 workers.dev、preview 或自定义路由。

日志采用字段白名单，仅保留随机关联号、静态阶段/错误类别、版本与数值耗时；不记录 Authorization、prompt、摘要、模板正文、原始模型响应或带凭据的 URL。运行时无原始请求转储接口，测试完整内容只使用本地合成数据。

真实 API 对照仅在用户另行批准的请求数和费用范围内进行，使用合成输入；比较本地计数与 usage，出现差异则停止上线并调查，不随意加固定偏移。模型别名、模板、工具协议或资源版本变动后重新验证。物理清理、账务恢复调度、费率更新、凭据私密配置、正式资源与云运行证据未就绪时，不启用生产公网入口。

## 12. 官方依据与审阅状态

本书依据已直接读取的官方文档，以及上述本地代码与隔离证据。平台行为在实际部署前再次核对：

- [DO 设计规则](https://developers.cloudflare.com/durable-objects/best-practices/rules-of-durable-objects/)：按协调实体划分、确定性路由、异步并发边界。
- [Durable Object State](https://developers.cloudflare.com/durable-objects/api/state/)：DO waitUntil 不延长生命周期，blockConcurrencyWhile 不应用于整段外部调用。
- [Worker Context](https://developers.cloudflare.com/workers/runtime-apis/context/#waituntil)：HTTP 结束/断连后的保活窗口最多 30 秒。
- [DO 生命周期](https://developers.cloudflare.com/durable-objects/concepts/durable-object-lifecycle/)：对象可能重建，没有可靠退出钩子；内存不等于持久完成。
- [锁定的 DeepSeek 模板源码](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/dba1be0a40aa45a94ad051997016db3960a90277/encoding/encoding.py)：支持域内模板移植的参考，资产 hash 以本地 lock 为准。

本书已完成占位项、引用及范围自检，并经运行边界、tokenizer 两项独立只读审阅。已补齐回执/执行竞争、忙碌时接单顺序、有界收尾、嵌套 JSON 标准化、入口非法文本校验与冻结参考样本的要求。文档检查通过不等于代码或运行时测试通过；本轮未运行后端回归，也未改变任何云端状态。

用户随后回复“批准执行”。按独立可验收的切片编写并执行计划，首批见 [通用计数实施计划](../plans/2026-09-16-general-tokenizer.md)；不把实施许可写成源码已完成或云上线已获批准。
