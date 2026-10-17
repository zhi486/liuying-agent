# 免费执行环境：下一批验证建议

后续更新：用户已批准该隔离范围并完成云验证，当前结论见[DO云验证](2026-09-16-do-runtime-validation.md)。下文保留提出建议时的历史状态，不再表示仍待批准。

日期：2026-09-16。当前仅完成只读调查与已有本地基准的CPU采样；**未修改实现、安装依赖、创建云资源、重开公网、调用真实模型或提交Git**。新增Durable Objects（DO）隔离探针尚待用户批准，不能把本文当作部署授权或已采用的新生产架构。

## 本轮实际进展

1. 恢复[上一批云测](2026-09-15-tokenizer-counting-performance.md)：固定输入精确性通过，但轻量计数2–21ms、完整假模型Agent19/28ms，不满足普通Free Worker的10ms稳定性门槛。该结论不代表所有免费Cloudflare运行产品均不可行。
2. 对既有`benchmark-counting.mjs`运行Node22.22.2的`--cpu-prof`，未改脚本。六例×两模式×51次计数均通过断言。计数调用栈采样中，`BPE.add_node`自耗时占约37%；此函数包含JSON字符串构建和合并rank查询。不能把37%全归因于JSON，也不能把本地采样比例当作云CPU比例。
3. 源码显示BPE缓存仅缓存长度小于256的分片。三个大输入按整段prompt辅助预分词均出现长度6000的分片，而小输入没有。它解释了一个可能的重复计算来源，但辅助切分不是实际encode各section调用次数，未调整缓存上限。
4. 独立只读审查确认：旧探针每次额外创建账户和设备，增加两条SQL及随机凭据/SHA-256。正常prepare/run不执行该管理操作。未经实测，不能从19/28ms直接扣减推测成本。

本地诊断证据在被Git忽略的`agent-server/tools/cloud-spike/.output/`：`counting-20260916.cpuprofile`、`counting-local-1789521842362.json`。采样混合encode/count，不含真实用户文本；构造成本与计数调用栈分别分析。没有新的云性能或内存峰值测量。

## 候选路线

| 路线 | 判断 |
| --- | --- |
| 优先验证SQLite-backed DO承载完整Agent执行 | 官方明确Free可用；可保留JS分词和D1账务，避免只优化分词但遗漏整体开销；需要新增执行边界并重验断连 |
| 官方Rust tokenizers薄WASM封装 | 备用；需要构建、正则一致性与Workers初始化适配；未证明更快，不能直接采用原生N-API Node包 |
| 继续微调JS合并查询/缓存 | 已找到可测热点，但收益不足以推断完整Agent能低于10ms；先不修改库内部实现 |

## 官方资料：可支持与不可支持的结论

- [普通Workers限制](https://developers.cloudflare.com/workers/platform/limits/)仍列Free HTTP10ms CPU；等待网络/D1不计CPU。
- [DO定价](https://developers.cloudflare.com/durable-objects/platform/pricing/)明确Free支持SQLite-backed DO，100,000请求/日、13,000 GB-s/日；超额对应操作失败，而非自动切换Paid。Duration按活跃wall time计量，**模型等待仍可能消耗DO duration**，不能用CPU时间代替额度估算。
- [DO限制](https://developers.cloudflare.com/durable-objects/platform/limits/)专用表及脚注列默认30秒CPU；但同页开头/FAQ又泛称适用Workers计划限制，存在不够一致的表述。没有取得明确说明调用方Worker和DO完全独立CPU计限的官方证明；必须在该Free账户实测入口和DO两侧，不能凭30秒一行就宣布通过。
- [RPC生命周期](https://developers.cloudflare.com/workers/runtime-apis/rpc/lifecycle/)指出客户端断连可能结束执行上下文。引入DO不等于天然获得无限后台执行；必须按新边界重新验收真实TCP中断与已存收据恢复。
- [官方Rust tokenizers Cargo](https://github.com/huggingface/tokenizers/blob/v0.23.2/tokenizers/Cargo.toml)提供`default-features=false`、`unstable_wasm`路线，但fancy-regex与默认Oniguruma语义需重做完整ID/Unicode对照。[官方Node绑定](https://github.com/huggingface/tokenizers/blob/v0.23.2/bindings/node/package.json)是N-API，不可直接部署Workers。WASM内存和速度均未知。

这些是当前官方文档与源码证据，不是对全App每月2美元、长期可靠性或当前账户DO权限的保证。

## 建议的隔离探针范围（未批准）

1. 只新增一个明确命名的SQLite-backed DO测试命名空间，复用现有测试Worker/D1；不迁移D1账本，不改生产库或手机协议。
2. 入口只做受保护分发；由DO执行固定精确计数与现有完整fake-Agent。合成账户/设备单独预置，计时请求仍保留认证、撤销、截止时间、幂等、预算预留与先存收据后结算。
3. 本地先回归146＋3组完整ID与任务/账务，再做有界真云验证：最多20个业务请求；就绪检查另限4次，业务失败不自动重试。分别记录入口和DO实际CPU/outcome/机房/首次重复状态，以及D1只读验收。
4. 新执行边界重验真实客户端TCP断连和账务恢复；不声称手机飞行模式/UI恢复已通过。不得因迁移运行容器而重发旧任务。
5. 不真实模型、不付费、不扩权、不设Cron/常驻心跳/WebSocket；固定短有效期，收尾关闭公网并保留测试资源和证据。正式接入另走架构设计/审查，不自动把探针当生产实现。

批准前不部署，也不因Cloudflare MCP缺失重复OAuth。当前Ravenclaw可用但无流萤项目条目，继续使用本地交接/记忆；Cloudflare MCP未出现在本轮工具清单，网页检索工具返回404，官方Markdown通过只读HTTPS获取。后续真云执行前再检查Wrangler权限、实际Free账户及资源状态。
