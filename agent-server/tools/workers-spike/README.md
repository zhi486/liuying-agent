# Workers 一次性本地可行性探针

此目录仅用于验证，不是生产代码或部署入口。禁止把本地耗时、类型通过或 BPE 对照当成完整 Workers 免费层验收。没有真实密钥或用户数据；不部署、不调用模型。

固定官方 `@huggingface/tokenizers 0.2.0` 和 `@cloudflare/workers-types 5.20260915.1`，依赖仅装在本目录，禁用安装脚本。计数样本复用邻接 `token-count` 的已校验官方参考。

首次在本目录运行 `npm ci --ignore-scripts --no-audit --no-fund`；不要在后端根目录执行。需要已准备好的相邻 `token-count/.venv` 与 `.assets`，Node 为 22.22.2。包版本及完整性固定在 `package-lock.json`，不下载模型权重。

持续类型检查已迁入后端正式 `corepack pnpm typecheck:workers`，不再依赖此目录。BPE 探针仍可独立复现（本目录）：

```powershell
node tokenizer-probe.mjs
```

历史 `types-probe.ts` / `tsconfig.json` / `types-hypothesis.mjs` 保留为修复前取证材料，不纳入正式回归。两处修复现已写入源文件，`types-hypothesis.mjs` 的“原源码必须两错”前提不再成立；不要再对当前版本运行它，也不要为使它通过而回退生产修复。最新状态见 [类型兼容交接](../../../docs/handoffs/2026-09-15-workers-typecheck.md)。

上述命令从 Python 参考生成仅含合成内容的 prompt，对比 JS BPE 的 token 数与完整 ID 序列 SHA-256：7 组固定样本、3 组真实 Loop 大输入请求、8 组边界、128 组固定种子 Unicode 随机样本。任何 ID 差异返回非零。`loop-cases.mjs` 的固定 usage 仅驱动虚构测试端口，真正的参考计数仍由 Python 重新计算；不发送 HTTP。

探针输出 Node 本进程的耗时和内存诊断，不包含原始 prompt/ID。计时不含 Python 参考生成、文件 I/O 或生产请求模板转换；初始化仅计 JSON 解析和 Tokenizer 构造。重复编码是在已运行完整语料后的热缓存上测量。Windows `process.cpuUsage()` 有明显量化误差，不能用其 0/16 ms 等数字判定 Free 的 10 ms CPU 是否通过。

RSS 包含 Node 本体，heap 包含探针、夹具、未回收对象及分词器；不是 isolate 的真实内存限额测量。大输入/随机样本并非全部能由生产 Loop 生成，也不是穷尽的协议证明。提示词仍在 Python 渲染，**没有完成 JS 模板移植、在线 usage 对照、WASM 测试或真云验收**。

探针的结果不改变现有生产逻辑；研究结束后保留标记为一次性材料，任何生产接入需另行设计和验证。

详见 [本轮可行性报告](../../../docs/handoffs/2026-09-15-workers-portability-spike.md)。
