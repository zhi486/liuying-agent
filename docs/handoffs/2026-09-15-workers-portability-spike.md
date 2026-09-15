# Workers 计数器与免费层可移植性探针

日期：2026-09-15。承接 [前批本地适配](2026-09-15-token-count-free-adapter.md)，用户“继续下一步”后进行有界可行性验证。主代理运行独立探针，子代理只读核对官方 D1 资料及实现。**不改生产 `src`、不部署、不创建云资源、不调用模型或读取密钥。**

后续进展：两处类型兼容修复已在另批正式落地，并加入持续检查，见 [最新交接](2026-09-15-workers-typecheck.md)。本文保留 Spike 时点的历史证据；“原源码两错、尚未修改”不再代表当前源码状态。

## 结论

JavaScript BPE 候选已找到，146 个固定/合成样本与官方参考完全匹配；但还没有 JavaScript 请求模板，不能称为生产计数器。原后端在纯 Workers 类型下暴露两处容易定位的兼容差异；候选修复只在内存验证，未合入。免费层 CPU、内存和 D1 batch 计量尚未通过真云验收。

| 项目              | 结果                                                   | 边界                                                |
| ----------------- | ------------------------------------------------------ | --------------------------------------------------- |
| JS BPE 差分       | 146/146 token 数与 ID 哈希一致                         | prompt 仍由已锁定 Python 参考生成，不是完整 JS 移植 |
| 官方 Workers 类型 | 原源码 2 个 TS2345；两处内存候选修改后 0 错            | 未修改生产文件，不代表运行时兼容或已部署            |
| 本地性能          | 大 Loop 三轮首次编码约 15/22/15 ms；长输入约 50–164 ms | Node wall time，不是 Workers CPU                    |
| 内存观察          | 构造后 Node heapUsed 约 84.6 MiB，整批后约 132.8 MiB   | 含探针、未回收对象等，不是 Workers isolate 内存计量 |
| D1 Free           | 官方 50 queries/invocation；batch 扣 1 还是 N 仍未证实 | 原三轮 41 次 API/76 SQL 不变，不能报免费已通过      |

## 1. 独立探针与依赖

仅新增 `agent-server/tools/workers-spike/`，明确标记一次性材料，不接入生产脚本或默认计数端口。它依赖相邻独立计数工具已准备的固定词表与 Python 参考，无需另下载模型。

本目录使用 `npm install --ignore-scripts --no-audit --no-fund` 安装两个固定公开包，生成本目录 `package-lock.json`；没有修改后端的 pnpm 依赖或系统工具。复现用 `npm ci --ignore-scripts --no-audit --no-fund`。来源与 SRI：

- `@huggingface/tokenizers 0.2.0`：`sha512-LidMHe1FpcSYH4vcSjXooda34pC0M8m1gmDOv7SQi6Iv+ib1zX7J3sHBCC9/3FPCkn2k1b55FneVqMRxrr99pg==`。[官方仓库](https://github.com/huggingface/tokenizers.js)、[官方包版本](https://www.npmjs.com/package/@huggingface/tokenizers/v/0.2.0)
- `@cloudflare/workers-types 5.20260915.1`：`sha512-3Lw2wkyVcEDdD0W6rNBmannaAkUsWGsO5TjbyHflWIDlhxtFRDFnxqHoDlg4YiVxinsIcBl1j9jW1kdruUhZpg==`。[官方包版本](https://www.npmjs.com/package/@cloudflare/workers-types/v/5.20260915.1)

网页搜索入口本轮仍 HTTP 404；资料来自官方 npm 元数据、已安装固定发布包和官方 HTTPS 原文。原生 Node fetch 一度连接超时，PowerShell HTTPS 与 npm 获取成功；没有关闭证书校验、改系统代理或使用第三方镜像。

## 2. BPE 差分范围

`reference-cases.py` 先检查前批 7 组 golden，再生成 146 个仅含虚构数据的完整 prompt 与 ID 哈希。`tokenizer-probe.mjs` 再校验词表大小/SHA，构造官方 JS Tokenizer，显式 `add_special_tokens:false`，比较 ID 按 little-endian uint32 编码后的 SHA-256。

- 原 7 组 golden：三轮短 system 为 347/421/512，其他包含空文本、特殊标记和转义。
- 3 组大 Loop 请求：真实 `runAgent` 使用 2,000 字提示、20 条长 ID/数值摘要，真实协议适配器在计数预检处导出即停止；模型和预算是虚构端口。两步工具为 list、summary，第三轮 final；参考计数 **2,390 / 3,789 / 3,924**。这不是穷尽的生产最大上下文证明。
- 8 组边界：Unicode 空白、数字、组合字符/emoji/扩展汉字、角色标记、长 ASCII、长中文、单段长字符、接近 20,000 token 的输入。最大请求 65,185 字节；超 token 上限样本仍精确计出 26,008，不截断。
- 128 组固定种子 Unicode 随机文本；每次复现相同语料。

没有移植官方消息/工具模板。JS 构造时不加载远程 tokenizer_config，使用本地词表与空额外配置；当前样本匹配仅支持本轮 BPE 路线的可行性，不能推论任意协议、模型或线上模板一致。模型别名/词表更新后重新认证。

## 3. 性能与免费层风险

记录的一轮 Windows x64 / Node 22.22.2 结果如下，数值随宿主负载、GC、缓存变化；不是性能 SLA：

| 样本            | 参考 tokens | 首次编码 wall ms | 热缓存 10 次中位 wall ms |
| --------------- | ----------: | ---------------: | -----------------------: |
| 短样本第 1 轮   |         347 |             6.65 |                     0.73 |
| 短样本第 2 轮   |         421 |             2.02 |                     0.84 |
| 短样本第 3 轮   |         512 |             2.09 |                     1.30 |
| 大 Loop 第 1 轮 |        2390 |            15.04 |                     6.05 |
| 大 Loop 第 2 轮 |        3789 |            22.22 |                     8.92 |
| 大 Loop 第 3 轮 |        3924 |            14.93 |                     9.83 |

其他压力样本单次约 50–164 ms。词表 JSON 解析和构造约 383 ms；Node heapUsed 构造前 6.5、后 84.6、整批结束 132.8 MiB，RSS 结束约 213.6 MiB。RSS 含 Node 本体，heap 含夹具和未回收对象，不能据此宣布 Worker 超出 128 MB；同样不能拿热缓存小样本宣称满足免费要求。

Windows `process.cpuUsage()` 结果存在 0/16/31 ms 等量化，且包含进程其他线程 CPU，**不用它与 10 ms Free CPU 直接比较**。计时不含 Python 模板、I/O、网络、账务与后端逻辑。官方 Free 限制为每次 10 ms CPU；等待 I/O 不计，但完整调用中的解析、验证、模板、分词、数据库响应处理仍需一起计量。初始化有独立启动约束，不能简单并入/排除后就宣布合格。[Workers 限制](https://developers.cloudflare.com/workers/platform/limits/)

推论：纯 JS 候选值得保留，但目前没有足够余量证据，不建议先完整移植并默认上线。WASM 仍只是备选，未安装 Rust 或验证 WASM，不应承诺它一定更省。

## 4. Workers 类型结果

`tsconfig.json` 仅加载 ES2022 和固定官方 Workers 类型；编译全部生产 `src` 与 `D1Database -> BudgetDatabase` / `ExecutionContext -> WaitUntil` 赋值探针。没有 Node/DOM 混入、`as any` 或 `skipLibCheck`。

原始编译 exit 2，且只发现：

1. `agent-service.ts` 的 `clearTimeout(timer)`：本地 timer 类型含 undefined，官方参数为 `number | null`。候选为 `if (timer !== undefined) clearTimeout(timer)`。
2. `deepseek.ts` 的 `new TextDecoder('utf-8', {fatal:true})`：当前官方 `TextDecoderConstructorOptions` 要求显式 `ignoreBOM`。候选为 `ignoreBOM:false`，保留现有默认 BOM 行为，不改成 true。

`types-hypothesis.mjs` 用 CompilerHost 在内存精确替换这两个唯一位置，再编译 0 错；运行后原文件不变。**这只是候选修复验证，原生产编译差异仍在。** D1 结构赋值本轮通过，但没有验证真实绑定、事务、时间语义或运行时取消。

宿主应注入原始主库 D1Database；不要因为 Session 结构可兼容就切读副本，权限/预算查询需要看到其他请求刚提交的撤销。包装 statement 时，原生 batch 必须接收解包后的原生对象。[D1 Session 指引](https://developers.cloudflare.com/d1/worker-api/d1-database/#guidance-3)

## 5. D1 独立核验与后续选择

独立核验官方文档及 workerd 固定提交 `edd9aaa8679dc35ce101eb3d50acf21e3050017a`：batch 只使用一次底层 transport，不代表生产额度只扣一次；公开实现没有明确给出 D1 50 queries 限额扣数路径。Workers 的内部服务 1,000 次说明不能替代 D1 专项 50 次条件。[D1 限制](https://developers.cloudflare.com/d1/platform/limits/)、[batch 契约](https://developers.cloudflare.com/d1/worker-api/d1-database/#batch)、[官方实现](https://github.com/cloudflare/workerd/blob/edd9aaa8679dc35ce101eb3d50acf21e3050017a/src/cloudflare/internal/d1-api.ts#L277)

当前正常三轮 76 SQL 不变。不减权限的候选优化：对已 settled、用量/费用完全一致、无违例且未超预留的再次 settle，读取后直接幂等返回；理论三步省 9 SQL 至 67。私有收据路径再复用已验证行，理论可至 64。**只是静态推算，未改代码或重测；仍不足以按内部语句口径证明小于 50。** 不删除设备、owner、执行许可、数据库时间或派发检查来凑额度。

建议下一批先落地两处类型兼容修复，并把纯 Workers 编译提升为持续检查；再经单独授权做最小 Free 真云探针，不急于引入完整 JS 模板或做大规模 SQL 重构：

- 不调用供应商、只用虚构数据，比较 51 次独立 `SELECT 1`、一个 51 语句 batch、49 次独立加一个 2 语句 batch，确定配额实际单位。
- 观察真实 CPU、内存/启动和完整大输入，假模型响应仍走真实账务。创建资源、凭据配置、部署需用户另行授权；不自动升级付费套餐。
- 另行授权真实 DeepSeek 小额 usage 对照；仅在所有门禁通过后接 HTTPS 与手机云端链路。

免费托管不等于 DeepSeek/Fish 免费，全入口 $2 封顶仍未完成。

## 6. 复现与收尾

命令、环境与限制见 [探针说明](../../agent-server/tools/workers-spike/README.md)。本轮不提交、不推送，不修改 Android/React，也不清理前批工具环境。探针依赖在本目录 `node_modules`，已由 `.gitignore` 排除。

独立复审重新执行 146/146 ID 对照、原始两处类型错误和内存候选归零，未发现 Critical/Important。第二次机器测量初始化约 403 ms、长输入约 59–206 ms、最终 heapUsed 132.9 MiB/RSS 213.7 MiB，进一步说明前述时长只是一次样本而非上限。记录的是采样时点而不是进程峰值；没有测量真实 Worker 的存活峰值。

原后端 `corepack pnpm check` 再次 exit 0：类型检查与 161/161 离线测试通过；该 Node 基线不抵消本报告中的 Workers 类型差异。
