# 通用精确计数实施计划

> **执行要求：** 使用 superpowers:executing-plans 逐项实施；沿用用户明确选择的主代理直接实现、子代理只读审查，不重复询问执行方式。

**目标：** 交付能处理当前协议内新用户文字的生产计数器，以独立官方 Python 参考证明模板字节和完整 token ID 的样本一致性。

**架构：** 共享纯文本验证、严格请求解析、固定官方模板渲染、锁定 JS BPE 四个职责。现有供应商适配器对同一 body 计数和发送的接口不变。完成此独立切片后，继续写正式 DO 接入计划；本计划不覆盖已另列的云部署或手机接入。

**技术栈：** Node 22.22.2、TypeScript 5.9.2、pnpm 11.19.0、Python tokenizers 0.23.2、@huggingface/tokenizers 0.2.0、Web Crypto。

**规格：** [2026-09-16-agent-do-tokenizer-design.md](../specs/2026-09-16-agent-do-tokenizer-design.md) 第 7–11 节，切片 A。

## 全局约束

- 模型 deepseek-flash，thinking disabled，非 stream；完整请求 ≤64 KiB，输入上限 20,000 token，输出限制 1–600。
- 任务入口仍要求非空、≤2,000 UTF-16 code units、trim、最多 20 条授权摘要；计数器底层夹具不套用任务入口长度。
- 锁定 profile deepseek-v41-reference-chat-v1 及 revision dba1be0a40aa45a94ad051997016db3960a90277；禁止字符估算、固定请求查表、在线资源回退。
- 保留 7 组冻结 golden；已有 146 组生成器的默认输出不变，避免改动旧探针证据。
- 两种特殊图像标记和孤立代理拒绝；ASCII `<image>文字</image>` 仍是普通文字，不能主动调用官方未使用的 parse_tagged_text。
- 直接在当前 codex/five-second-zones 工作区实施，保留已有改动，不自动提交/推送，不删除用户文件，不读密钥，不联网调用供应商或部署。

## 文件与接口

| 文件 | 职责/接口 |
| --- | --- |
| `src/plain-text.ts` | `isSupportedText(value: unknown): value is string`；纯字符串、Unicode scalar、无两种保留图像标记 |
| `src/token-count/request.ts` | `parseCountingRequest(raw: string): CountingRequest`；标准 JSON、形状/schema/工具历史/64 KiB 校验 |
| `src/token-count/template.ts` | `renderInputPrompt(raw: string): string`；解析后生成完整锁定 chat 模板 |
| `src/token-count/counter.ts` | `createInputTokenCounter(asset: Uint8Array): Promise<(raw: string) => Promise<number>>`；校验资源、构建 BPE、返回纯计数函数 |
| `src/token-count/profile.ts` | 固定词表大小/hash/model/profile，与资源 lock 通过行为测试核对 |
| `tools/token-count/verify-assets.ts` | 构建前只读核验现有模板和字典；不存在或 hash 错误非零退出，不下载 |
| `tools/workers-spike/reference-cases.py` | 新增显式 `--include-requests` 输出模式，默认输出不变 |
| `test/token-count.test.ts` | 真实模板、BPE、协议拒绝、资源完整性及 Python 差分 |
| `test/token-count-integration.test.ts` | 真实供应商适配器和服务＋假 HTTP，验证三轮新输入、超限/取消不派发 |
| `test/token-reference.ts` | 仅测试用的 Python 子进程与完整 ID 哈希辅助，不进入生产 bundle |
| `src/tools.ts`、任务/服务现有测试 | 接单前共享文字校验；只改与新排除规则相关部分 |
| `package.json`、`pnpm-lock.yaml`、README | 正式固定依赖、验证命令、如实报告精确性范围 |

## 任务 A1：入口与计数请求验证

**消费：** 现有 `TOOL_DEFINITIONS` 与 `parseSubmission`；**产出：** isSupportedText、parseCountingRequest、CountingRequest/WireMessage/WireTool 类型。

- [x] 写拒绝测试：未知字段/模型、重复 JSON 键、内层 arguments 重复键、非法类型、无穷数溢出、孤立代理、特殊图像 token、未知 schema、缺失/重复工具结果、64 KiB 两侧；合法 emoji、ASCII image 标签和新用户文字必须接受。
- [x] 在尚未导出实现时执行 `node --experimental-strip-types --test test/token-count.test.ts`，确认新能力缺失的失败；入口已有函数测试应因非法文字被接受而失败。
- [x] 实现最小标准 JSON 校验及白名单类型，保留对象字段顺序：

```ts
const value: unknown = JSON.parse(raw);
if (JSON.stringify(value) !== raw) throw new Error('输入计数不可用');
```

  arguments 字符串独立执行该检查；tool.content 不解析。请求首两条为 system/user；后续 assistant 调用 ID 唯一且名字在声明工具内，tool 必须匹配 pending，末条为 user/tool 且无 pending。
- [x] `isSupportedText` 用逐 UTF-16 配对检查拒绝孤立代理，不拒绝合法 surrogate pair；任务 `parseTask` 在 trim/接单前共享调用。
- [x] 重新执行定向测试及 `corepack pnpm check`，确认旧协议正常输入不退化。

示例验收断言：

```ts
assert.equal(isSupportedText('流萤🏃‍♀️<image>文字</image>'), true);
assert.equal(isSupportedText('\ud800'), false);
assert.throws(() => parseCountingRequest(raw.replace('"stream":false', '"stream":false,"stream":false')));
```

## 任务 A2：官方模板逐字节移植

**消费：** parseCountingRequest；**产出：** renderInputPrompt。

- [x] 写字面预期：无工具完整 prompt、空调用 DSML 换行、连续 user、乱序工具结果、control/emoji 内容与工具 schema 顺序。
- [x] 运行新测试，确认缺少模板导出或实际字符串不匹配。
- [x] 实现固定工具说明文本（保留原 MIT 归属）、支持域内 Python JSON 空格语义、角色标记、user/tool 合并排序及 assistant DSML。只复制必要官方常量和规则，不复制多模态、thinking、namespace 支持。

```ts
assert.equal(renderInputPrompt(plain), '<｜begin▁of▁sentence｜><｜System｜>只读助手<｜User｜>你好<｜Assistant｜></think>');
```

- [x] Python reference 增加显式请求输出模式，再以每个 request 的 `JSON.stringify` body 交 JS；比较完整 prompt UTF-8 字节，保留 7 golden 的原 SHA。
- [x] 补独立随机种子和字段变更，包含 schema 中转义描述、连续 user、工具混排、字面特殊标记、ASCII image 标签和长输入。
- [x] 定向测试全绿后检查模板所有支持分支，无任何 body hash 查表。

## 任务 A3：生产 BPE 封装与资源门禁

**消费：** renderInputPrompt、锁定字典 bytes；**产出：** createInputTokenCounter。

- [x] 写资源损坏/缺失失败、7 golden 计数与完整 ID、146 请求及新增输入差分测试；完整 ID 由同版本 JS 库的 tokenize/token_to_id 取得并对照独立 Python IDs 摘要。
- [x] 运行红测试；安装正式依赖 `corepack pnpm add --save-exact @huggingface/tokenizers@0.2.0 --ignore-scripts`（优先已缓存固定版本）。
- [x] 实现异步构造器：复制输入 bytes，校验大小及 Web Crypto SHA-256，fatal UTF-8 解码并构造 `new Tokenizer(data, {})`；返回函数使用 `tokenize(prompt, {add_special_tokens:false}).length`，不返回 ID 或原文。
- [x] 增加只读 `verify-assets.ts`，从现有 lock 读取两个固定本地文件并验证大小/hash；构建验证不自动下载、不覆盖异文。
- [x] 对整个参考集同时核对模板与完整 ID，并确认生产计数等于其长度；损坏资源与超限请求不能返回默认值。

```ts
const count = await createInputTokenCounter(assetBytes);
assert.equal(await count(JSON.stringify(golden[0].request)), 9);
await assert.rejects(createInputTokenCounter(new Uint8Array([1, 2, 3])));
```

## 任务 A4：真实适配器联接、审查与交接

**消费：** createInputTokenCounter、createAgentService；**产出：** 正式可注入计数器和完整离线验收结果。

- [x] 捕获真实三轮 Agent 序列化请求，使用新 prompt，不重放旧 SHA 夹具；供应商仅用假 transport，保留真实模板、Loop、SQLite 账本和收据逻辑。
- [x] 比较计数前 body 与 fetch body 的逐字节一致性；每轮与独立 Python 参考对照。用真实超 20,000 token 文本和非法协议证明 fetch 次数为 0，不靠假计数返回 20,001。
- [x] 对计数等待中取消保留现有 D1 门禁测试，并使用真实计数器完成迟到工作，确认不派发/不复活结果。
- [x] 执行资产验证、Python 固定测试、全部差分、`corepack pnpm check`、`git diff --check`。
- [x] 两项只读审查分别核对模板精确性和接入/输入失败行为，按证据处理反馈。
- [x] 更新 README、交接和计划状态，明确 A 已完成不代表 DO/云端/手机已接通；下一项进入 B 的正式 DO 运行边界实施计划。

## 验证和范围自检

测试脚本不读任何真实请求或密钥，不请求模型；Python `.venv` 与 `.assets` 已存在，复用固定版本。Node SQLite/MockTimers 的实验性警告是既有宿主提示，记录但不当作业务失败。本轮基线已运行：双类型检查与 163 项测试通过。任务中的提交步骤遵循用户本批“不自动提交/推送”的范围，完成后保留可审查差异。

规格 A 的协议、模板、BPE、资源、失败不派发、完整差分和文档均对应 A1–A4；B/C 的 HTTP/RPC/生命周期/DO 构建与 workerd 集成另成顺序计划，不用 A 的测试结果替代。

## 执行结果（2026-09-16）

A1–A4 已完成。最终 `corepack pnpm check`：固定资产门禁、Node/Workers 双类型与 **207 项测试通过，0 失败**；独立 Python 16 项通过，`git diff --check` 无空白错误。7 golden＋146 请求＋1,418 新输入完整模板/ID 差分通过，两项只读审查无阻塞项；P3 用量差异建议已补入回归。门禁采用 `.ts` 以直接复用生产 profile，运行命令沿用 Node 类型擦除。pnpm 补丁仅修复类型声明的 ESM 扩展名，没有放宽严格检查。

这只完成 A，不代表 DO/云/手机完成。后续进入 [B/C 正式 DO 运行边界计划](2026-09-16-production-do-runtime.md)，该计划尚未执行；具体源码/证据见 [A 交接](../../handoffs/2026-09-16-general-tokenizer.md)。保留当前分支与工作区，不自动提交或推送。
