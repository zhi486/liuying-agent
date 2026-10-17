# 正式 DO 运行边界实施计划（B/C）

> **执行要求：** 使用 superpowers:executing-plans 逐项执行，配合 test-driven-development、durable-objects、workers-best-practices、wrangler 与 verification-before-completion。沿用已确认的主代理直接实现、子代理只读审查、当前工作区；无需重新选择执行方式。

**目标：** 将已完成的通用计数工厂和真实 Agent 服务接入默认关闭的 Worker/每用户 DO，取得本地真实运行时证据。

**架构：** Worker 有界读取及 D1 鉴权，以可信 owner 路由。DO 负责完整 prepare/run/收尾；D1 保持身份、任务和费用真相。查询及取消先直连 D1，不等待执行槽。正式模块不依赖 spike 的固定输入、许可或云资源。

**技术栈：** 已有 Node 22.22.2、pnpm 11.19.0、TypeScript 5.9.2、Workers 类型 5.20260915.1；正式工具依赖复用已验证版本 Wrangler 4.131.2、Vitest 4.1.11、@cloudflare/vitest-plugin 1.1.9、Vite 7.3.6，固定安装且不升级旧探针依赖。计数依赖与声明补丁沿用切片 A。

**规格：** [正式 DO 与计数设计](../specs/2026-09-16-agent-do-tokenizer-design.md)，尤其第 3–6、9–11 节；前置 [A 交接](../../handoffs/2026-09-16-general-tokenizer.md)。状态：**B 已实现并验证；C 本地业务矩阵及双模式独立强停重启通过，插件 abort 诊断仍跳过，云与手机未验收**。最新证据见 [B/C 交接](../../handoffs/2026-09-16-production-do-runtime.md)。

## 全局约束

- 只实现 B/C，不部署、迁移远程 D1、创建云资源、配置真实密钥或调用 DeepSeek/Fish；Android/React/语音不变。
- 不提交、推送、合并、删除用户文件；保留现有 `codex/five-second-zones` 脏工作区。
- 模型 deepseek-flash、thinking disabled、输出最多 600、输入最多 20,000 token；任务接单起 30 秒，最多 3 次模型和 6 次只读工具。
- 仅现有 6 个业务路由，不新增管理、SQL、通用 RPC、注册或恢复端点；所有响应 no-store、静态错误、不使用通配 CORS。
- 真实凭据/输入/中间上下文不持久化到 DO；DO 只持久化 owner 与协议版本。D1 schema 不搬迁、不复制。
- 默认关闭 workers.dev、preview 和所有公网路由；新正式配置不能引用旧 spike 的 account/database ID。
- 内存峰值与线上 usage 仍为未验收；本地 workerd 成功不冒称真云或全 App $2 封顶。

## 文件与接口边界

| 文件 | 职责 |
| --- | --- |
| `agent-server/src/agent-http.ts` | 6 路由识别、UTF-8/媒体类型/64 KiB 读取、空控制正文、HTTP 静态映射 |
| `agent-server/src/runtime-lifetime.ts` | 单调时间界限、动态后台 Promise 收集与有界 drain；纯逻辑可用 Node 验证 |
| `agent-server/src/agent-runtime.ts` | `LiuyingAgentRuntime extends DurableObject`；owner 绑定、短接单互斥、执行槽、懒计数、控制通知 |
| `agent-server/src/worker.ts` | 真实 Worker 入口与 DO 导出、认证路由、私有 RpcTarget 回执通道、完整 RPC 的 waitUntil |
| `agent-server/src/runtime-config.ts` | 宿主配置到现有 RateCard 的严格映射，缺失/失效拒绝，不默认填价 |
| `agent-server/wrangler.jsonc` | 正式独立名称、SQLite 类 migration、无真实 ID 的本地 D1、关闭态路由、日志隐私配置 |
| `agent-server/worker-configuration.d.ts` | 用本地 Wrangler 生成的绑定类型，不手写重复 Env |
| `agent-server/tools/token-count/prepare-worker-assets.ts` | 调用 A 的 verifyAssets，通过后复制固定词表到忽略的 `.generated/tokenizer.bin` |
| `agent-server/src/worker-assets.d.ts` | 声明二进制模块为 ArrayBuffer，不将词表 JSON 构造成入口模块级对象 |
| `agent-server/test/agent-http.test.ts`、`test/runtime-lifetime.test.ts` | 纯协议与截止/动态收尾红绿测试 |
| `agent-server/runtime-test/`、`vitest.config.ts` | 真 workerd/DO/D1 组合测试、合成设备/账户/费率、拦截真实供应商 fetch |
| package/lock、双 tsconfig、README/交接 | 固定工具依赖、分离 Node 与 Workers 测试类型、可复验命令与证据 |

内部 RPC 结果使用可序列化的静态判别联合，不假定自定义 Error 的 code 能跨 RPC 保留：

```ts
type RuntimeReply =
  | { ok: true; task: CloudTaskView }
  | { ok: false; error: CloudErrorCode };
// CloudTaskView/CloudErrorCode 复用 cloud-task-contracts.ts；若错误类型未导出，只导出已有联合。
// execute(authorization: string, submission: unknown, receipt: ReceiptTarget): Promise<RuntimeReply>
// interrupt(authorization: string, taskId: string, action: 'cancel'|'withdraw'): Promise<void>
// ReceiptTarget.accepted(task: CloudTaskView): Promise<void>，仅当前 RPC 可调用，非公开端点。
```

## B1：HTTP 契约与关闭态可构建入口

- [x] 在 `test/agent-http.test.ts` 写失败测试：只识别规格 6 路由；GET 和控制 POST 拒绝非空正文；UUIDv4 标识严格；拒绝多余 query、错误方法/路径、压缩、非 JSON/非 UTF-8、实际读取超过 65,536 bytes；错误不回显内容。

```ts
assert.equal((await invalidJsonResponse).status, 400);
assert.equal((await tooLargeResponse).status, 413);
assert.equal((await unsupportedMediaResponse).status, 415);
assert.equal(response.headers.get('cache-control'), 'no-store');
assert.equal(response.headers.has('access-control-allow-origin'), false);
```

- [x] 运行 `node --experimental-strip-types --test test/agent-http.test.ts`，记录缺失导出/行为断言失败，再实现纯解析与响应函数。正文逐块累计，UTF-8 fatal 解码；不能相信 Content-Length 或一次无界 request.json()。提交后继续使用现有 parseSubmission，不复制业务验证。
- [x] 拒绝不支持 Content-Encoding；JSON 仅 application/json 与可选 charset=utf-8。路径仅规范格式，不用 URL 解码隐含扩展路由。错误映射严格遵守规格；未知错误统一 unavailable/503。
- [x] 增加固定工具依赖及二进制资产准备命令。先写缺失资产/篡改资源不能产出有效 build 的测试，再实现验证后复制；二进制文件是生成产物，不入 Git。以 Wrangler schema 确认 Data module 规则。
- [x] 新建独立 `wrangler.jsonc`：正式名 `liuying-agent`，兼容日期 2026-09-16，SQLite 类 `LiuyingAgentRuntime` 和一次新类 migration，DB 使用明确的本地测试占位 UUID，关闭 workers.dev/preview、无 route、无 account_id。占位不是可部署生产绑定。
- [x] 日志/trace 开启但禁止自动 invocation URL 日志；手写日志仅随机关联号、静态阶段/错误、版本和数值耗时。配置缺密钥/费率时只能拒绝执行，不使用测试默认值；已存任务查询不依赖模型密钥。
- [x] 按本地 `wrangler types --help` 生成环境绑定类型并纳入纯 Workers 检查。不要把 Node 类型引入生产 Workers 配置；`cloudflare:workers` 类模块的运行时测试由 C 完成，Node 测试只导入纯边界模块。

## B2：有界动态收尾

- [x] 先写 `test/runtime-lifetime.test.ts`：已登记 Promise 完成时又登记新的 Promise，drain 必须等新链；拒绝被消费但不伪装业务成功；永不 settle 时返回截止；截止后新注册只消费拒绝，不延长等待；系统时钟回拨不延长单调时间。

```ts
// 宿主接口供 B3 使用，不替换服务原有 deadline 与检查。
// createRuntimeLifetime(): { waitUntil(p: Promise<void>): void;
//   remaining(): number; setRemaining(ms: number): void;
//   wait<T>(p: Promise<T>): Promise<T>; drain(): Promise<void>; close(): void }
// 构造器立即开始 30 秒单调上限；setRemaining 仅在成功接单后一次使用任务剩余 deadline。
```

- [x] 运行单测先失败，再实现 Set 跟踪与循环等待集合变空，循环每轮共享同一截止；不能每轮重新开始 30 秒。定时器在 finally 清理；迟到 rejected Promise 必须有消费链。
- [x] 接单后将宿主剩余时间设为 `task.deadlineAtMillis - Date.now()`；不能越过该绝对截止。主执行仍遵守 `createAgentService` 自身 preparedAt 的更早单调限制。
- [x] 超时后释放对象执行槽；不把未完成 drain 当作全部账务成功，不直接退款或复活结果。记录静态截止状态，不序列化原错误。
- [x] 定向测试和 Node/Workers 类型检查通过，审阅每个 Promise 的所有权；DO 的 ctx.waitUntil 不用于保活。

## B3：正式 DO 协调与早回执

- [x] 先配置最小真 workerd 测试红例：不同 owner 隔离、直接 RPC owner 不匹配拒绝、首次并发只执行一次、同键异文 conflict、新键 busy 不创建排队孤儿、回执丢失也只执行一次。
- [x] `LiuyingAgentRuntime` 构造器只创建 SQLite 绑定表；`execute` 内先重新 authenticate 原 Bearer，再同步条件插入 owner/version 并验证，不能将 caller 参数 owner 当可信输入。初始化与比较不在外部 I/O 中间分开。
- [x] 初次 prepare 使用短 in-memory 接单门闩，现有执行期间新键先 findByRequest，not_found 转 busy；已存在键经 tasks.accept 的同一正式指纹路径验证，不因键存在直接成功。接单门闩不会覆盖完整 run 或后台 drain，查询/中止不受其限制。
- [x] 服务按本次执行构造并保留引用至收尾，费率快照不得永久缓存。计数工厂 Promise 只在 DO 首次计数时创建，失败不缓存假计数；词表只读复用，不缓存用户输入结果。
- [x] 成功 prepare 后立刻调用 run，独立发 receipt.accepted；两者不可串成先 await 回执再 run。回执失败消费掉，不能重建执行闭包。完整 execute 等主流程和动态 drain，随后按原授权回读持久 DTO；不能直接返回内存答案。

```ts
const work = prepared.run?.({ waitUntil: pending => lifetime.waitUntil(pending) });
// work 先启动；回执失败不阻塞它。两条链均归属本次完整 RPC。
const receiptWork = receipt.accepted(prepared.task).catch(() => {});
// 回执等待只能占原剩余期限，不能阻止已接单执行或扩大 drain。
```

- [x] 迟到 prepare 回包前若 pre-accept 等待已经终止，禁止调用 run；即使 D1 已创建任务也只允许按原键查询、按原截止收敛。finally 仅清理本次槽，不删除后续新执行引用；迟到后台错误继续消费。
- [x] `interrupt` 重新鉴权、比对绑定 owner，使用当前执行同一 service.cancel/withdraw；无当前服务时不伪造 abort 成功。不能新建空 running Map 的 service 来“通知”。

## B4：HTTP → D1/DO 完整连接

- [x] 先写真运行时红例：未认证 POST 不建对象；回执前任务已在 D1；Worker 已持有完整 RPC 的 waitUntil；RPC 早失败不挂等回执；返回 503 后可按原 clientRequestId 找回。
- [x] `worker.ts` 只把已鉴权 owner 用于 `env.AGENT_RUNTIME.getByName(owner)`。回执 RpcTarget 用本次闭包，只接收 task DTO；完整 execute Promise 在竞争回执之前注册当前 ctx.waitUntil，不能只保活提前返回的 race。
- [x] 收到 queued/running 回执 202，终态 200；完整 RPC 无回执却正常结束按 unavailable，静态失败按映射返回。RPC target/stub 的生命周期绑定完整 pending，不能在 HTTP 早返回时显式 dispose 仍在使用的通道。
- [x] GET/控制直接调用真实 createCloudTasks。取消/撤回持久成功后尽力 notify DO，通知挂起或失败不能阻塞/回滚控制响应；通知 Promise 受有界等待并注册当前 Worker 生命周期。
- [x] 无 owner/body/query/cookie 凭据透传认证、无输入日志、无管理路径。控制成功返回最新持久 DTO；acknowledge 仅显式请求触发。

## C1：真实运行时矩阵

- [x] `vitest.config.ts` 直接指定生产 main 和本地 Miniflare 绑定，remoteBindings:false；为避免加载私密文件，不读取 wrangler.configPath/.dev.vars。正式配置另以 dry-run/生成类型核对。测试使用合成 key、有效费率及独立 owner，禁止从旧 spike 读取秘密。
- [x] 仅在测试拦截官方供应商 fetch，并核对请求正文包含真实计数后的三轮工具历史；断网测试让 transport 真正拒绝/悬挂，不能以假 final 代替。生产源码不加 /test、测试模式开关或样本查表。
- [x] 验证首次冷计数/同对象热计数/新长文字、三轮工具、完整收据；重复同键、异文、两个 owner、新键 busy；回执断流与执行继续、真实 HTTP 客户端断开后按原键恢复。
- [x] 在计数后/派发前/结算后取消或撤设备：不得派发下一步、不得复活结果，may_have_dispatched 不退款；新通知与旧服务引用隔离。取消在慢模型期间可返回；同步 BPE 无法被 JS 抢占的限制如实报告。
- [x] 模拟实例重建，检查 SQLite owner/version 保留、无 prompt/摘要/令牌；D1 任务原 deadline 不变，模型不重发。恢复函数有收据一次结算再零次，没有收据保持 uncertain。
- [x] 永不 settle 的 prepare/D1/fetch/收据场景都在原边界结束；迟到 prepare 禁止 run，取消后原截止内的迟到 usage 完成结算也不复活结果。用注入时钟和真实 D1 验证 24 小时边界，ack/withdraw 保留语义仍成立。
- [x] 本地运行时的文件隔离、辅助 Worker、D1 migration 和重建 API 以当前插件 Test APIs 文档及安装类型为准；测试配置绝不设置 remote:true，不执行远程命令。

### C1 复合条目细分（2026-09-16 后续）

- [x] 短文本冷/热计数、三轮工具、并发/冲突/busy、早回执、HTTPS socket 真实断开。
- [x] 计数后派发前 cancel/revoke、慢模型取消、收据提交后取消；D1 结果不复活。
- [x] 有收据未结算时强停 workerd，同目录新进程恢复 owner、原 id/deadline，执行窗口内重放不派发，恢复结算 1→0 次。
- [x] 挂起 prepare/transport/收据的原 30 秒限制，迟到 prepare 不启动；ack/withdraw/已过期结果隐藏。
- [x] 真 workerd 的 2000/1999 code units 新长文本，冷/热/新对象 BPE 得到 1683/1479/1683，与独立 Python 参考一致；同步计数不可抢占限制继续保留。
- [x] 无收据时强停重启＋恢复仍 uncertain，6720 微美元预留不退款，模型只有一次。
- [x] 一轮结算后下一轮派发前 cancel/revoke；第二轮未派发预留释放；跨 SQL await 的迟到旧 service 通知不影响新执行。
- [x] 真实 D1 + 注入时钟验证指纹/结果各自 24 小时起点的前 1 ms 与到点；取消后等待真实 applied=1 和完整 RPC，费用一次、结果不复活。未真实等待 24 小时，不保证原截止后后台存活。

插件 abort 的历史红例默认显式跳过，不纳入通过数；独立 test:restart 替代其业务恢复验证，不宣称修复插件 API。

## C2：构建、复审与交接

- [x] 运行 `corepack pnpm check`（221 项）、Python 16 项、默认 runtime suite（22 通过/1 诊断跳过）、独立 disconnect 与有/无收据双模式 restart；本地 `wrangler deploy --dry-run` 验证关闭态 bundle，确认入口没有初始化词表 JSON/BPE。C1 本地业务矩阵通过，不将插件诊断、云或手机未验收项纳入完成范围。
- [x] 用本地 schema 与生成类型核对 bindings/class/migration，构建检查不触发自动资源 provision。检查打包体积、静态错误和日志白名单；不写入任何真实云 ID。
- [x] 两项只读独立审查：身份/HTTP/去重/取消，生命周期/后台链/计数/构建。修复阻塞项后重跑受影响测试及全量。
- [x] 更新 README、交接和项目记忆：分别标记代码/Node/workerd完成与云 CPU/峰值内存/线上 usage/清理调度/手机未验收。保留工作区，按本批约定不自动 Git 提交或推送。

## 自检与官方依据

规格 3/4 映射 B1/B3/B4；规格 5 的 RPC、早回执、忙碌与截止映射 B2–B4/C1；规格 6 的取消/结果/维护映射 B4/C1；规格 7–8 已由 A 交付，B3 负责真实接线；规格 10–11 的本地验证/关闭态/隐私映射 C1/C2。定时维护、私密配置、云与真实 API 是明确的上线门禁而非本计划漏项，不在本次暗中执行。

2026-09-16 直接读取官方：[DO State](https://developers.cloudflare.com/durable-objects/api/state/)（DO waitUntil 不保活）、[RPC Lifecycle](https://developers.cloudflare.com/workers/runtime-apis/rpc/lifecycle/)（execution context 与 target/stub 生命周期）、[当前 Vitest 插件配置](https://developers.cloudflare.com/workers/testing/vitest-integration/write-your-first-test/)（插件 1.x、Vitest ≥4.1）。实现相关调用前继续读取对应 API，不套用旧 pool 配置。

## 2026-09-16 执行检查点

- B1–B4 已实现；221 项 Node、双类型/资产门禁、Python 16 项通过，正式 bundle dry-run 通过，生成类型无漂移。
- workerd 22 项通过，1 项插件中断诊断显式跳过；独立进程有/无收据两模式强停重启通过。C1 本地业务细分矩阵补齐；本轮只改测试/工具/命令及交接，未改生产实现。
- 真实本地 HTTPS 客户端在测试 wrapper 延迟回执交付时销毁 socket，原键恢复、一次模型/结算通过；不是云端链路验收。
- 原生 fetch 发现 redirect:error 不受 Workers 支持，已最小改 manual + 非 2xx 拒绝，补 301/302/303/307/308 回归并复审。
- 挂起 RPC 全局 abort 稳定触发 workerd Fatal；定点 state.abort 仍测试超时，默认跳过、未冒称修复。新工具在真实收据提交后永久挂起，在截止前强停底层进程，再从磁盘启动新实例；原键不重跑、收据结算一次、原 deadline 收敛已验证。
- 工具依赖固定，额外显式列出同版 Miniflare 5.20260911.1-alpha。Data 声明由 Wrangler 生成；Workers 使用 ESNext/Bundler 接受生成路径，NodeNext 与严格类型保持。
- 无云部署/远程迁移/真实 API/手机修改/提交推送；下一步是受控云验收的范围与授权，实际云操作须另行批准。保留当前分支和工作区。
