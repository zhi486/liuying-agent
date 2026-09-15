# M1C-1 模型适配与持久预算实施计划

> 执行技能：executing-plans；主代理直接实施，子代理只读审查，沿用用户已声明的当前工作树偏好。

**目标：** 形成带真实 SQL 费用约束、可接入现有有界循环的 DeepSeek 适配层，不部署、不发真实付费请求。

**架构：** 新模块位于独立 agent-server，保持 ModelPort/BudgetPort 及 runAgent 的既有契约。HTTP 与 D1 为外部边界，测试仅替代 transport 和 D1 宿主，业务映射和 SQL 保持真实。

**技术栈：** Node 22.22.2、TypeScript、内置 fetch/SQLite 测试、Cloudflare D1 结构接口；不增加生产依赖。

**规格：** [M1C-1 设计](../specs/2026-09-14-agent-provider-budget-design.md)。

## 全局约束

- 保护现有 dirty/untracked 修改；不改 Android/React、不给任务自动上传，不提交/推送/安装/部署。
- 月上限最多 1,800,000 微美元；费率必须显式配置并未过期。仅覆盖本模块，不能承诺全 App $2。
- 供应商 endpoint 与模型固定；counter 缺失/超限不发请求；响应最多 128 KiB；无重试、无内部推理输出。
- 基线：agent-server 类型检查及 56 项测试已通过。

## Task 1：有界 DeepSeek 适配

文件：新增 `src/deepseek.ts`、`test/deepseek.test.ts`。

接口：

```ts
createDeepseekModel(options: {
  apiKey: string;
  countInputTokens: (serializedRequest: string) => Promise<number>;
  fetch?: typeof globalThis.fetch;
}): ModelPort;
```

计数器接收将实际发送的完整序列化请求；可信实现必须应用相同模型的聊天/工具模板。字符串不是 token 计数结果。

- [x] 测试先通过动态 import 检查尚不存在的工厂，确认功能缺失的断言失败。
- [x] 用独立手写响应验证真实请求 JSON：固定模型、关闭 thinking、600 输出上限、系统指令/用户消息/工具消息角色与下划线名称。
- [x] 验证非法工具 JSON/名称、length 完成、内部 reasoning、usage 缺失/不一致；响应无合法决策时不能假成功。
- [x] 测试 401/429/500、重定向策略、网络异常、超长响应、预取消、输入计数失败/超限无 HTTP；实现后定向 GREEN。
- [x] 用真实 runAgent 验证两轮工具选择→观察→答复，未知费用保持 uncertain。

示例期望：

```ts
assert.equal(body.model, 'deepseek-flash');
assert.deepEqual(body.thinking, { type: 'disabled' });
assert.equal(body.max_tokens, 600);
assert.equal(body.messages[2].tool_calls[0].function.name, 'runs_summary');
assert.equal(body.messages[3].tool_call_id, 'call_1');
assert.deepEqual(reply.usage, { inputTokens: 100, outputTokens: 40 });
```

## Task 2：D1 原子预留和结算

文件：新增 `src/budget.ts`、`src/d1.ts`、`migrations/0001_agent_budget.sql`、`test/sqlite-d1.ts`、`test/budget.test.ts`。

接口：

```ts
createD1Budget(options: {
  db: BudgetDatabase;
  ownerId: string;
  rate: RateCard;
  now?: () => number;
  newId?: () => string;
}): BudgetPort;
// RateCard: version, model='deepseek-flash', input/outputMicrosPerMillion,
// checkedAtMillis, expiresAtMillis；无默认生产费率。
```

- [x] 先写真实 SQLite 连接、加载迁移的测试工具（只在 test 目录），并观察尚无预算实现的断言失败。
- [x] 账户预置月限额 13,440 微美元，三个并发请求每笔 6,720，只允许两个；同键重复不增行；不存在账户拒绝。
- [x] 真实 SQL 验证：usage 100/40 收费估算 78 微美元、未知保留 6,720、not_dispatched 释放、迟到消息不退款、相同结算幂等、冲突结算拒绝。
- [x] 增加所有者隔离、费率过期、旧费率快照、跨月、关闭文件库后重开、结算超过预留持久熔断测试；用 SQLite 触发器制造第二条 SQL 失败，验证 batch 整体回滚。
- [x] 实现单条条件 INSERT 与事务结算，再运行定向 GREEN；SQL 不存 prompt、摘要或秘密。

示例期望（手算）：

```ts
// 20,000 × 0.30 + 600 × 1.20 = 6,720 微美元。
assert.equal(reservations.filter(Boolean).length, 2);
assert.equal(row.reserved_micros, 6720);
// 100 × 0.30 + 40 × 1.20 = 78 微美元。
assert.equal(row.charged_micros, 78);
```

## Task 3：组合回归与交接

- [x] 用真实适配器＋SQLite 账本＋runAgent 做两轮离线完整工具循环，检查真实持久费用和最后回答；不使用假 BudgetPort 替代账本。
- [x] 运行 `corepack pnpm check`；用前端已有 Prettier 格式化本批 TypeScript 后重跑。
- [x] 只读复审实际代码，所有有效问题先补失败测试再修复；复审与完整测试通过后才报告完成。
- [x] `git diff --check`，更新 README、总体计划、交接与项目记忆。明确 tokenizer/认证/远程 D1/手机连接仍未完成；本批没有 APK 改动，不重复安装或播放声音。

## 验收结果

2026-09-14：类型检查通过；完整测试 86/86（原 56＋本批 30，含嵌套子测试）；本批 TypeScript 格式检查通过。独立只读复审覆盖适配器、SQL 预算和组合路径，未发现 P1/P2。测试过程观察到模块缺失、工具映射缺失和预算未实现的 RED，再完成 GREEN；复审建议的迟到响应、30 秒截止及并发结算边界均已固化。

测试使用的 ReadableStream 最初默认预取会提前关闭流，导致无法观察取消回调；已用独立最小复现确认原因，并将该测试的 highWaterMark 设为 0，使其验证尚未关闭正文的取消行为，没有更改生产边界来迁就测试。Node SQLite / MockTimers 实验警告保留。验收只适用于 M1C-1 离线模块，后续门禁见交接。
