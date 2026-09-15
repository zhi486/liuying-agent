# 精确 token 计数与免费云端可行性核验

核验日期：2026-09-15。范围：现有 `agent-server`、DeepSeek 当前官方编码资料、Cloudflare Workers/D1 Free。用户本轮要求验证，不是实施、部署或付费调用授权。

后续进展：用户另行授权的独立计数工具与 Free 本地适配已完成，见 [实施交接](2026-09-15-token-count-free-adapter.md)。下文保留核验时点的历史结论；真实 API 对照和云端验收仍未完成。

## 结论

**已完成本地与官方资料核验；精确计数和免费云端上线验收尚未通过。** 找到了完整的官方协议编码路径，但不是现成的 TypeScript Workers 依赖。免费层仍可作为候选；当前维护分页和迟到账务生命周期需要适配，不能原样上线并保证免费稳定运行。

| 项目 | 状态 | 证据 / 缺口 |
| --- | --- | --- |
| 当前模型和完整编码路径 | 已核验 | `deepseek-flash` 当前对应 V4.1-Flash；官方 recipe 支持完整 Chat Completions、工具定义与非思考模式 |
| 文字词表一致性 | 已核验 | 对两份官方 JSON 做内存结构比较；仅图像 added token 不同，其他字段完全一致 |
| 实际 token IDs / API 用量一致性 | 待验证 | 本机没有计数运行库；未调用真实 API。不能把现有测试固定返回 100 当成精确计数 |
| Workers Free 资源条件 | 已核验文档 | 每次 10 ms CPU、128 MB；尚未测量计数器加完整服务的云端开销 |
| D1 调用数量 | 已本地计数 | 1/2/3 轮为 19/30/41 次 API 调用、40/58/76 条 SQL；batch 额度口径待真云核验 |
| 恢复分页 | 有明确适配缺口 | 默认满页 100 条，无待处理收据也至少 101 次 D1 调用，超过单次 50 queries 条件 |
| 迟到账务 | 有明确适配缺口 | 不能仅 `waitUntil(run())` 就宣称已跟踪所有晚到记账 Promise |
| 现有后端基线 | 本轮重新通过 | `corepack pnpm check`：类型检查与 154/154 离线测试通过，0 失败 |

## 1. 精确计数必须覆盖什么

当前 [DeepSeek 适配器](../../agent-server/src/deepseek.ts) 已把即将发送的完整请求 JSON 交给 `countInputTokens`，但没有生产计数器。计数失败、非整数、超限时拒绝发送。现有测试验证这些控制逻辑，不验证 BPE 或线上实际 token 数。

正确路径是：

```text
完整 Chat Completions 请求
→ 官方协议转换（含工具定义、工具调用与结果、thinking disabled）
→ V4.1 模型实际 prompt 模板
→ 匹配版本的 tokenizer 编码 token IDs
→ token IDs 数量
```

不是估算中文字数，也不是直接给整个请求 JSON 分词。模型、输出上限等传输字段不应被原样当成 prompt，消息分隔符、工具 schema 和生成头却不能遗漏。供应商最终计费依据仍是返回的实际 `usage`。[DeepSeek token 说明](https://api-docs.deepseek.com/quick_start/token_usage)

### 当前官方版本和可用实现

- 官方 2026-09-10 发布 V4.1-Flash，当前 `deepseek-flash` 映射至该版本。别名会演进，不能永久绑定旧模板。[发布公告](https://api-docs.deepseek.com/news/news260910)、[模型与价格](https://api-docs.deepseek.com/quick_start/pricing)
- 最新模型不提供 Jinja chat template；`encoding/` 提供独立 Python 格式参考，`deepseek-recipe` 提供完整协议转换和 prompt/token IDs 编码。需要工具 schema 的请求，应使用这个完整路径。[模型编码说明](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/dba1be0a40aa45a94ad051997016db3960a90277/encoding/README.md)、[recipe 计数文档](https://github.com/deepseek-ai/deepseek-recipe/blob/8cadfede7063c896b944e7bae05daa3549ae97ea/docs/tokenizer.md)
- API 文档附带的旧 demo ZIP 只演示 `AutoTokenizer.encode`，其中模板没有处理顶层工具定义，不能单独据此认证本产品完整请求。ZIP SHA-256：`e7310d1dafe0a86d8a5629fe78a7c763760f651db9b8682718a1781dcd6fe495`。[官方 demo ZIP](https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip)
- recipe 当前公开的是 Rust/Python 接口；固定提交中没有现成 JS/WASM 绑定。原生依赖包含 tokenizer、正则及并行相关组件，需要另行验证裁剪/绑定，不能宣称原生库直接可跑 Workers，也不能据此断言 WASM 不可行。[recipe 固定版本](https://github.com/deepseek-ai/deepseek-recipe/tree/8cadfede7063c896b944e7bae05daa3549ae97ea)
- 本机有 Python 3.12.14，没有 `deepseek_recipe`、`tokenizers`，PATH 未发现 Rust 工具链。核验时 PyPI `deepseek-recipe 0.1.1` 发布文件仅 Linux/macOS wheel，没有 Windows wheel；未安装依赖或搭建编译环境。[PyPI 发布元数据](https://pypi.org/pypi/deepseek-recipe/json)

### 已执行的词表对照

通过 Node 内置 `fetch`、`crypto`、`util.isDeepStrictEqual`，只在内存下载、解析、比较以下固定来源；没有执行下载代码或下载模型权重。

| 来源 | 固定版本 | 文件字节 | SHA-256 |
| --- | --- | ---: | --- |
| 官方 V4.1-Flash tokenizer | `dba1be0a40aa45a94ad051997016db3960a90277` | 6,367,257 | `c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b` |
| recipe v41 tokenizer | `8cadfede7063c896b944e7bae05daa3549ae97ea` | 6,367,247 | `81f64d1248a68ce3663e07ab3ee48b851e5df0e32d27cb98e4c9a268151e8d99` |

两者均为 BPE，128,000 项词表、127,741 条合并规则；顶层只有 `added_tokens` 不同，`model` 所有字段一致。唯一差异为 ID 129264：模型文件是 `<｜deepseek_image｜>` / `special=false`，recipe 是 `<｜image｜>` / `special=true`。当前服务不支持图片；不过用户自由文本也可能包含特殊标记，不能凭普通文字词表相同就宣布所有输入计数一致。[模型 tokenizer](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/dba1be0a40aa45a94ad051997016db3960a90277/tokenizer.json)、[recipe tokenizer 来源说明](https://github.com/deepseek-ai/deepseek-recipe/blob/8cadfede7063c896b944e7bae05daa3549ae97ea/static/tokenizers/v41/README.md)

尚缺：运行官方参考计数、建立固定 token IDs 对照样本、验证生产移植版，以及经授权与真实 API `usage.prompt_tokens` 做零差值对照。没有这些证据，本轮不提供虚构的“精确 token 数”。

## 2. 免费云端硬条件

截至核验日，Cloudflare 文档列出的主要条件：

| 服务 | Free 条件 | 对本项目的意义 |
| --- | --- | --- |
| Workers CPU | HTTP/Cron 每次 10 ms；等待外部 I/O 不计 CPU | 30 秒模型等待不等于 30 秒 CPU；tokenizer、JSON 和校验仍耗 CPU |
| Workers 规模 | 每日 100,000 请求、128 MB 内存、启动 1 秒 | 个人请求量不大也不能替代冷启动和最坏输入测试 |
| Worker 脚本 | 未压缩 64 MiB；目前无压缩大小上限 | 不能再用旧 3 MB gzip 限额判失败；磁盘文件大小也不等于运行内存 |
| D1 单次调用 | 每次 Worker invocation 最多 50 queries；单查询/整批最长 30 秒 | 需要将整个请求及晚到记账的数据库操作一起核算 |
| D1 日用量 | 读 500 万行、写 10 万行 | 扫描及索引写入也计费率用量，必须观察真实行数 |
| D1 存储 | 最多 10 库、每库 500 MB、账户共 5 GB | 清理策略和实际用量仍需测试 |

来源：[Workers Limits](https://developers.cloudflare.com/workers/platform/limits/)、[D1 Limits](https://developers.cloudflare.com/d1/platform/limits/)、[D1 Pricing](https://developers.cloudflare.com/d1/platform/pricing/)。超免费配额可能拒绝执行，不等于业务能够无条件继续，也不意味着会自动获得付费保障。

### SQL 调用探针

独立只读核验用真实应用服务、SQLite 测试宿主及假 HTTP，分别统计 `first/all/run/batch` 的 API 调用次数，以及 batch 内的 SQL 语句数。账户配置和迁移不计入这些正常请求数字；它们也不是云端计量结果。

| 模型步数 | prepare + run 的 D1 API 调用 | prepare + run 的 SQL 语句 | 单独 run 的 API 调用 / SQL |
| --- | ---: | ---: | --- |
| 1 | 19 | 40 | 17 / 33 |
| 2 | 30 | 58 | 28 / 51 |
| 3 | 41 | 76 | 39 / 69 |

官方文档没有明确解释 batch 对这项 50 queries 限额究竟按整批还是内部语句计数。因此不能凭 41 次调用宣称三轮可通过，也不能只凭 76 条语句宣称三轮一定失败；需要实际 Cloudflare 探针。在实测前宜按更保守的内部语句口径留余量。[batch 文档](https://developers.cloudflare.com/d1/worker-api/d1-database/#batch)、[batch 限制](https://developers.cloudflare.com/d1/platform/limits/#batch-limits)

相比之下，[recoverExecutions](../../agent-server/src/execution-ledger.ts) 默认 `limit=100` 是明确问题：满页无收据时也至少 1 次读取 + 100 次 batch = 101 次 API 调用；待应用收据还会增加。未来免费宿主必须采用更小分页并限制整次调用总量，不能只限制返回行数。

### 生命周期与运行时

- 断连或响应结束后，`ctx.waitUntil` 最多共享额外 30 秒，不是新调用。应按仍共享 CPU / 查询额度设计，而非重置额度；这是根据调用生命周期所作的保守设计推论。[waitUntil](https://developers.cloudflare.com/workers/runtime-apis/context/#waituntil)
- 当前 `run()` 完成不保证所有账务 Promise 都结束：`loop.ts` 存在晚到预留释放，`deepseek.ts` 的取消 race 可能早于已开始的用量记账返回。未来宿主需要显式追踪这些 Promise；只写 `waitUntil(run())` 不能作为断连记账验收证据。进程直接退出、没有拿到 usage 等情况仍需保留未知费用并对账，不能靠 Promise 永不丢失的假设。
- 本地测试使用 `node:sqlite`，生产源码没有依赖它。Workers 的该模块是非功能 stub；现有 Node 类型检查不等于 Workers 类型或 D1 兼容验证。当前官方兼容日期规则已经变化，应按目标日期配置并验证。[Node compatibility](https://developers.cloudflare.com/workers/runtime-apis/nodejs/)
- Free 可配置 5 个 Cron；UTC 调度，配置传播可能约 15 分钟。Cron 不会放宽 10 ms CPU 与 D1 单次查询限制；维护仍需分页、游标和失败告警。[Cron](https://developers.cloudflare.com/workers/configuration/cron-triggers/)

## 3. 建议的后续验收顺序（尚未实施）

1. **先做独立计数验证工具**：固定官方协议实现与词表版本，覆盖中文、英文、emoji、特殊标记、JSON 转义、空内容、两个工具定义及 1–3 轮工具历史；输出 token IDs 的数量和固定样本对照结果。不要先把估算器塞入生产接口。
2. **验证 Workers 可移植性**：为纯文本范围验证 JS/WASM 路线和真实 Workers 类型；测量冷启动、完整 64 KiB 请求及接近 20,000 token 边界时的 CPU/内存。无法满足免费条件就报告结果，不自动升级付费套餐或引入第三方计数服务。
3. **再实施必要适配**：缩小并约束 D1 恢复分页，明确晚到记账的宿主追踪接口；以回归覆盖取消、超时、分页续跑及未知费用不误退。不能为减少 SQL 去掉权限/派发检查。
4. **经用户授权后做最小云端探针**：只建 Free 测试资源，用虚构数据和假供应商验证迁移、batch 计量、真实 CPU、断连与定时维护；不上传旧手机任务。另经小额真实调用授权，使用虚构文本对照 API 用量。任何计数不一致先停止认证，不用任意安全系数冒充精确。

以上是核验建议，不是已批准实施计划，也不代表资源已创建。只有这些门槛通过后，才适合继续 HTTPS / 手机在线闭环。

## 4. 本轮操作与边界

- 本轮重新运行 `agent-server` 的 `corepack pnpm check`，exit 0；154 tests、154 pass、0 fail。原有 SQLite / MockTimers ExperimentalWarning 保留。
- 读取公开官方网页和 JSON；网页搜索工具本轮返回 HTTP 404，已改用直接官方 HTTPS GET 核验，不以搜索摘要代替原文。未发送密钥、真实跑步记录或用户对话。
- 仅更新核验交接文档与相关记忆；未实现或更换生产计数器，未修改 Android/React，未安装依赖/APK、部署、提交或推送。
- **免费托管不等于 DeepSeek/Fish 免费。** 手机现有直连入口仍未统一到后端账本；本轮不能保证全 App 月费在 $2 内。
