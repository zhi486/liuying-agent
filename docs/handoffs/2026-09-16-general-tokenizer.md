# 通用精确计数切片 A 交接

日期：2026-09-16。用户已批准 [正式 DO 与计数规格](../superpowers/specs/2026-09-16-agent-do-tokenizer-design.md)。本交接只覆盖 A，**不表示正式 DO、workerd、云端或手机接入已完成**。

## 已实现

- `agent-server/src/token-count/`：严格协议解析、锁定官方完整纯文本/工具模板、BPE 计数与资源 profile。输入是实际供应商 body，新文本不查表；输出仅数量，无请求内容缓存。
- `src/plain-text.ts` 与 `tools.ts`：孤立代理和两类特殊图片标记在任务接单前拒绝，普通 emoji/控制字符/ASCII image 标签保留。任务原有 trim、2,000 code units 和摘要数量限制不变。
- `tools/token-count/verify-assets.ts`：只读验证模板、字典大小/hash 和生产 profile；缺失不下载、不覆盖。已加入 `pnpm check` 最前方。
- 生产依赖固定 `@huggingface/tokenizers 0.2.0`。发布包声明缺少 NodeNext 扩展名，使用可复现 pnpm 补丁修复 62 个 `.d.ts` 的 163 处相对路径；不改分词算法，不启用 skipLibCheck，不切换模块解析模式。
- 资源初始化复制并校验同一字节快照，之后调用公开 `tokenize(..., {add_special_tokens:false}).length`。DO 宿主的懒构建/复用属于后续 B。

## 验证与审查

- 初始基线：双类型与 163 项测试通过；新入口的 6 个非法输入测试先红后绿，解析器/模板/资源门禁均先观察实现缺失失败。
- 7 组冻结 golden 未重写；146 组原有请求和 **1,418 组新增 Unicode/added-token 输入**逐一对照独立官方 Python 模板及完整 ID 摘要，而非仅比较数量。
- 真实三轮 `createAgentService`＋计数器＋SQLite＋假 HTTP：计数与发送使用同一 body；三轮均与独立 Python 比对；三份收据与云任务 ID 关联。第二轮合成供应商用量故意比本地多 17 token，费用按供应商值结算，不混淆预检与账单。
- 真正超过 20,000 token 但未超过 64 KiB 的文本零派发；非法文本接单前零新增任务；计数等待中取消，释放后真实计数完成仍零发送、预留释放、结果不复活。
- Python 独立参考 16 项通过。交付前重跑 `corepack pnpm check`：资产门禁、Node/Workers 双类型、207 项测试全部通过，0 失败；`git diff --check` 无空白错误。SQLite/MockTimers 实验性警告保留。
- 两项只读审查未发现阻塞缺陷。模板审查另做 240 组合成多轮完整 prompt 差分，240/240 一致；此额外只读实验不是持久 CI 测试。资源/集成审查的 P3“供应商与本地用量不同”建议已纳入回归。

这些证据限定于锁定官方 profile 与当前支持域，不数学证明所有字符串，也不证明线上 API 隐式模板、usage 或云端 CPU/峰值内存。旧云探针仍关闭，没有本轮云调用。

## 差异与工作区

资源门禁从计划中的 `.mjs` 改为 `.ts`，以直接复用强类型生产 profile，随 Node 类型检查检查；执行用项目既有 `--experimental-strip-types`。已有 146 组生成器仅新增显式 `--include-requests` 模式，默认输出不变，不破坏旧云证据。

pnpm 的本地补丁编辑副本保留在 `agent-server/.tokenizer-type-patch/` 并已忽略，未擅自删除；可审查/复现补丁在 `agent-server/patches/`。未改 Android/React/跑步语音，没有提交或推送 Git，保留原脏工作区。

## 下一步

按批准规格实施 B/C，见 [下一批实施计划](../superpowers/plans/2026-09-16-production-do-runtime.md)（已写，未执行）：正式轻入口、每 owner 的 SQLite DO、早回执与完整 RPC 保活、有界动态收尾、直达 D1 查询和持久取消，之后真实 workerd 验收。资产以二进制模块进入 DO，不能在入口把 JSON 字典解析成对象。正式配置与关闭的 spike 分离，不复用其身份、次数或固定请求计数。

上线仍需另行批准的正式资源、私密凭据/费率、定时清理/账务恢复、受限云测、真实 API 对照及手机适配；本批无需用户连接手机或提供密钥。
