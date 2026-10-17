# 精确 tokenizer 与真实客户端断连：真云验证

日期：2026-09-15，约 18:38–18:48（UTC+8）。延续已批准的 Cloudflare Free 隔离验证，复用唯一 Worker/D1 `liuying-free-spike-20260915`。没有真实模型调用、真实跑步数据、付费升级、权限扩张、系统/VPN 网络修改或资源删除。

## 结论

| 验收项 | 状态 | 证据与限制 |
| --- | --- | --- |
| 六条固定输入的精确 BPE | 通过 | 347–3924 tokens，数量与完整 ID SHA-256 均与锁定 Python/Rust 参考一致 |
| 真云请求 CPU | 已测，免费层稳定性不通过 | 10–35 ms；六条中五条高于 Free 标称 10 ms，不把 HTTP 200 当作符合 CPU 配额 |
| 真云启动 | 通过本次部署验证 | 平台返回 206 ms；这是启动验证时间，不是请求 CPU |
| 真云内存峰值 | 未测得 | 六条请求均完成，未观察到 exceededMemory；不能据此报告精确 MB 或证明所有负载均安全 |
| 客户端真实 TCP 中断 | 通过限定实验 | 在响应未完整时关闭独立 socket，后续独立 D1 回读确认任务完成且结果保留 |
| 收据故障恢复 | 通过 | 已存收据、未结算 → 后续请求补结算一次；第二次恢复零变更，不重派模型 |
| 手机断网/杀进程后的 UI 恢复 | 未测试 | 本轮是电脑测试客户端与真实 Cloudflare 的连接，不是手机飞行模式测试 |

当前不能把 JS tokenizer 原样纳入“免费层稳定生产运行”验收。完整 JS 消息模板、真实 API usage 对照、生产 HTTP/身份及手机接入仍待完成。不要因本次诊断自动升级付费或信任客户端自行上报的 token 数来放宽预算。

## CPU 和计数证据

锁定 `@huggingface/tokenizers@0.2.0`；官方 tokenizer.json 为 6,367,257 字节，SHA-256 `c90dfa01249db1be4245780a052ede752e1361c612ac6d08e2bdada7d599476b`。由既有 `reference-cases.py` 生成六条虚构规范 prompt，完整模板仍由独立 Python 参考生成。本实验是精确 BPE，不是已经完成生产模板移植。

同一 Worker 暂时切换到独立 tokenizer 入口，不同时运行 Agent 账务。模块初始化一次创建 tokenizer；每个请求只处理一个固定用例，不接受任意文本、重复次数或 SQL。门禁包括密钥、固定有效期、POST、路径白名单、禁止查询参数。

| 用例 | tokens | CPU ms | wall ms | ID 哈希 | HTTP/outcome |
| --- | ---: | ---: | ---: | --- | --- |
| production-round-1 | 347 | 33 | 34 | 一致 | 200 / ok |
| production-round-2 | 421 | 26 | 27 | 一致 | 200 / ok |
| production-round-3 | 512 | 10 | 11 | 一致 | 200 / ok |
| loop-large-round-1 | 2390 | 29 | 36 | 一致 | 200 / ok |
| loop-large-round-2 | 3789 | 35 | 36 | 一致 | 200 / ok |
| loop-large-round-3 | 3924 | 29 | 35 | 一致 | 200 / ok |

CPU/wall 来自真实 `wrangler tail --format json` 返回的顶层 `cpuTime`/`wallTime`，不是客户端计时或 `performance.now()`。虽然当前 Wrangler 类型没有声明这两个字段，实际响应包含；无需增加 Observability 权限。每个用例只有一条计时样本，不能宣称得到 p95、稳定冷热启动分布或长期稳定性。

CPU 是整个探针请求（鉴权、BPE、ID 哈希、少量日志/响应）的开销，不是单独 BPE 函数；就绪空路径另测 1 ms，未擅自相减。没有与真实 Agent 循环合并，因此生产累计 CPU 还未验收。云端传输压缩包 2043.62 KiB，未压缩 8531.19 KiB，启动验证 206 ms。分词版本 ID：`516f4d5e-00ef-4aeb-9d5d-133a5d104ace`。

[Workers 官方限制](https://developers.cloudflare.com/workers/platform/limits/)列出 Free 10 ms CPU、128 MB/isolate、1 秒启动；偶发超时容忍不能作为部署承诺。本轮超过 10 ms 仍成功，不代表限制已取消。

内存没有受支持的精确云端度量：与本地 workerd 版本对应的 [process 实现](https://github.com/cloudflare/workerd/blob/925464ba9fe5751e4468626ce77f7a5810df274f/src/node/internal/public_process.ts#L284)中 memoryUsage 是零值占位；未拿它或本地 Node heap 冒充真云数据。内存限制针对 isolate，超限时平台可能让现有请求完成后替换 isolate，因此“未观察到超限”不能证明峰值已精确小于 128 MB。

## 真实断连与恢复

### 客户端中断

任务：`295d83e3-ccc3-4e07-8665-605e833c00d4`。

1. 真实服务接受虚构任务，fake transport 已进入并等待 4 秒；完整任务交给 `ctx.waitUntil`。
2. Worker 发出未结束的 NDJSON 首帧，客户端解析 taskId。
3. Node `https.request` 使用独立、非 keep-alive socket；记录 `response.complete=false` 后执行 destroy，等待 socket close，记录 destroyed=true。
4. 客户端报告完成时间 `1789468730573`，云任务 updated_at `1789468736073`；两端时钟记录差为 5500 ms，仅作时序辅助，不作为精确网络延迟。
5. 后续独立真实 D1 查询确认 completed、result available、正确答案及一次 model_requested；唯一预留 settled、收据 applied=1、模拟 charged=78，执行关联一致。

没有重发任务，也没有依靠 HTTP 202 假装断网。此实验验证已接单后客户端 HTTP/TCP 连接中断时后台结果可持久化；不证明手机 UI 已接通恢复、不证明 Worker 被强制终止后会继续生成、不覆盖真实供应商网络故障。

### 已有收据、结算失败

任务：`24c6e97a-6f25-47da-b9a9-5eb4aa1a7fa0`。故障仅注入本请求的 D1 包装器：确认 INSERT receipt 已完成，再在首次 settled 更新之前抛错。没有修改云数据库触发器或生产实现。

| 观察点 | 任务 | 预留 | 收据 | 模拟费用 |
| --- | --- | --- | --- | --- |
| 故障后独立回读 | failed / model_unavailable | uncertain | applied=0，usage 100/40 | null |
| 下一请求恢复后 | 原失败结果完全不变 | settled | applied=1 | 78 微美元 |
| 再次恢复 | 不重新生成答案 | settled | applied=1 | 仍为 78 微美元 |

第一次 `recoverFreeExecutions`：processed=1、receiptsApplied=1；第二次均为 0；两次 nextCursor=null、closed=0、uncertain=0。恢复只修复账务，不把失败任务伪装成已成功生成。78 是虚构费率算出的测试值，不是实际 API 消费。

## 本地验证与证据

- 后端 Node/Workers 两套类型检查通过，163 项测试通过。
- 隔离探针 24 项测试通过，包含真实本地 TCP 中断、SQLite 账务、门禁与完整 token ID 对照。
- 新增云路由测试先观察 404 失败，再实现通过；未修改后端业务源文件或 Android/React 旧改动。
- 独立程序核对 6 条客户端结果与 6 条平台 CPU 日志、D1 两任务各只结算一次、恢复幂等及关闭公网状态。
- 所有 `.output` 文件被 Git 忽略；原始 tail 请求头未保存，只有白名单指标/固定虚构日志落盘。

本地原始证据目录 `agent-server/tools/cloud-spike/.output/`：

- `tokenizer-cloud-1789469141443.json`、`tail-1789469124834.json`
- `network-disconnect-1789468728618.json`
- `network-receipt-fault-1789468756191.json`
- `network-recover-1789468794652.json`、`network-recover-1789468796896.json`
- `network-final-evidence.json`（最终真实 D1 与公网关闭证据）
- `receipt-recovery-snapshots.json`（本轮工具回执中恢复前/后的真实 D1 快照，已核对原失败结果完全一致）

## 收尾与下一步

已恢复轻量探针版本 `5a15cdd1-2537-49ef-a85c-8fedc56c6fb3`，部署配置关闭临时 observability；GET subdomain 明确回读 enabled=false、previews_enabled=false。settings API 未返回 observability 字段，不把缺字段写成“API 明确返回 false”。有效期仍为原先 2026-09-15 19:30（UTC+8），未延期。Worker/D1 和证据保留，未删除。

下一步优先研究降低精确计数 CPU 的免费适配，再决定生产计数架构；不要重做 OAuth、迁移或整组假模型创建。没有 Git 提交/推送；本轮代码仅在 `agent-server/tools/cloud-spike/`。

后续已完成轻量计数对照，最新状态见[性能报告](2026-09-15-tokenizer-counting-performance.md)：146＋3组完整ID通过，候选完整Agent CPU19/28ms，仍未通过Free稳定性门槛；本次新增有效期和收尾版本以新报告为准，以上为本批历史记录。
