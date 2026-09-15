# 独立计数验证与免费层适配实施计划

> 执行方式：主代理依 executing-plans / test-driven-development 直接实施，子代理只读复审。沿用用户确认的当前工作区，不提交、推送、部署或请求真实 API。

**目标：** 交付能实际运行的官方参考计数工具，以及可单独验收的 Free 维护分页和请求级保活接口。

**架构：** 计数工具与生产 TypeScript 完全隔离，使用固定版本官方 Python 编码参考及 tokenizers，将实际请求先转换成模型 prompt 再编码。后端保留原有截止、权限及账务语义，只增加 Free 单页入口与请求级可选保活回调。

**技术栈：** Node 22.22.2、TypeScript 5.9.2、Python 3.12、tokenizers 0.23.2、现有 SQLite 测试宿主。

**依据：** [核验报告](../../handoffs/2026-09-15-tokenizer-workers-feasibility.md)；用户明确“做独立计数验证工具，再处理免费层适配”。本批是这两项已确认路线的有界实现，不建设新的云端 HTTP 服务。

## 全局约束

- 保留旧后端 154 项回归、原有 30 秒截止与三轮上限，不删除任何权限或派发检查。
- 只支持当前服务的纯文本、非思考请求；未知协议字段拒绝，不能静默忽略后宣称精确。
- 编码参考和词表固定提交及 SHA-256；明确准备资源时才联网。计数、测试不联网、不读密钥、不打印原提示词。
- 本地数字标记为官方参考计数，不等于已验证 API 账单或 Workers CPU。
- 每次 Free 恢复只做一页、最多 5 条；不在一个 invocation 内循环翻页。
- 不安装系统级依赖，不改手机和前端；Python 依赖只放工具目录的隔离环境。

## 任务 1：独立计数工具

**文件：** 新建 `agent-server/tools/token-count/{count_tokens.py,test_count_tokens.py,prepare-assets.mjs,assets.lock.json,requirements-win.txt,README.md,LICENSE.deepseek}` 与虚构固定对照样本；新增该目录 `.gitignore`。

**接口：** `render_request(request: dict) -> str`、`count_request(request: dict) -> dict`。命令 `python count_tokens.py --request <JSON>` 只输出计数、版本与摘要哈希；`--self-test` 对照固定样本，失败返回非零。`node prepare-assets.mjs` 仅下载锁定资源，先校验哈希，已有异文拒绝覆盖。

- [x] RED：真实测试覆盖消息/工具模板、非思考生成头、工具结果顺序、中文/emoji/转义、错误模型/未知字段/图片/重复 JSON 键拒绝。
- [x] GREEN：严格映射当前请求，将顶层工具放入首个 system，调用已审阅官方 `encode_messages(thinking_mode='chat')`，再以 `add_special_tokens=False` 编码；不对原 JSON 直接分词。
- [x] 资源准备：固定官方源码/词表哈希，校验后存工具缓存；以带哈希的 Windows wheel 在局部 venv 安装 tokenizers，不依赖完整模型或 Rust 编译。
- [x] 数值验收：以官方参考得到可审查的固定样本；保存 token 数、prompt/token IDs 哈希，运行回归并通过真实生产适配器导出的虚构请求检查协议兼容。
- [x] 文档：写一键准备、离线使用、版本/输入边界、API 对照尚未完成的明确说明；子代理只读审查后再进入任务 2。

关键断言示例：

```python
self.assertEqual(render_request(simple_request),
    '<｜begin▁of▁sentence｜><｜System｜>只读助手<｜User｜>你好<｜Assistant｜></think>')
with self.assertRaises(ValueError):
    render_request({**simple_request, 'thinking': {'type': 'enabled'}})
```

验证：工具目录 `python -B -m unittest -v`、`python -B count_tokens.py --self-test`；后端 `corepack pnpm check`。

## 任务 2：Free 恢复页与请求级保活

**文件：** 修改 `src/execution-ledger.ts`、`src/loop.ts`、`src/deepseek.ts`、`src/agent-service.ts`；按需要新增小型 `src/background.ts`；扩展对应 Node 测试。只改这批行为，保留通用恢复入口兼容性。

**接口：** `recoverFreeExecutions({db,now?,cursor?})` 固定调用通用恢复页 `limit:5`；私密 `run({waitUntil?})` 的回调仅属于当前请求，逐层传递，不能存入跨请求共享配置。

- [x] RED：构造至少 6 条 pending receipt，按真实 SQL 统计第一页 41 条、处理 5 条且留下游标；下一次调用处理余页，无重复费用。
- [x] GREEN：增加 Free 单页入口，通用 `recoverExecutions` 的 1–100 能力不变；文档禁止同次循环翻页。
- [x] RED：取消/超时后 `run` 已返回，宿主仍收到未完成的原始 operation、晚到预留释放和结算链；完成 deferred 后费用只入账一次、不增加 HTTP、不恢复撤回内容。
- [x] GREEN：可选请求级 `waitUntil` 注册只返回 void 的清洗副本，保留原错误处理；注册 Loop 晚到释放/结算、适配器底层 operation、服务底层 operation，不延长 timer、不等待无界 drain、不自动重试。
- [x] 全量回归与只读复审；更新 README、交接和项目记忆。仍不将本地回归称为免费真云验收。

关键断言示例：

```ts
const page = await recoverFreeExecutions({ db: countedDb, now });
assert.equal(page.processed, 5);
assert.equal(sqlCount, 41);
assert.notEqual(page.nextCursor, null);
```

验证：对应 Node 测试、`corepack pnpm check`、格式/空白检查。未授权提交；交付后由用户决定 Git 和云端探针。
