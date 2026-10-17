# 一次性真云探针

仅用于已授权的 Cloudflare Free 可行性验证，不是生产 HTTP 入口。业务代码未改动；所有任务、设备、用量和模型响应均为虚构。不得用于上传真实跑步摘要或提供真实 API 密钥。

## 本轮环境

最新批次为2026-09-16 SQLite DO探针；配置已切到`do-worker.mjs`且公网关闭、有效期0。保留唯一DO类/绑定/迁移，**不要直接恢复不导出DO类的旧入口**。以下9月15日步骤是历史工具说明，不是重新执行授权。

- 固定 Wrangler `4.131.2`，本目录 `package-lock.json` 锁定依赖；安装禁用脚本。
- Worker / D1 名称均为 `liuying-free-spike-20260915`，D1 ID `8b159107-a1f8-40d9-a932-c52df81c3dfe`。
- `wrangler.jsonc` 是被忽略的本轮环境配置，不能省略数据库 ID 让工具自动创建资源。
- 新 Worker 使用 `2026-09-15` 与 `nodejs_compat`；无 Cron、无真实模型绑定。
- 本轮测试完成后关闭 workers.dev 和 Preview URL。资源保留待后续验证，未删除。

## 本地检查

```powershell
npm ci --offline --ignore-scripts --no-audit --no-fund
New-Item -ItemType Directory -Force .output | Out-Null
node prepare-tokenizer.mjs
node --experimental-strip-types prepare-agent-counting.mjs
npm test
npm run test:do
node node_modules/wrangler/bin/wrangler.js deploy --dry-run --outdir .output/bundle --no-autoconfig
```

41项Node测试检查鉴权、过期、固定路由、查询形状、失败不重试、部署就绪上限、真实SQLite假模型/收据闭环、真实本地TCP中断、146组轻量完整ID对照及六条云输入/三条实际Agent请求；7项Cloudflare Vitest/workerd测试验证DO状态/驱逐/完整RPC/防重派/结算恢复。它们不模拟或证明云平台配额。分词测试复用兄弟目录已锁定的`workers-spike` npm依赖和`token-count` Python环境、官方资产；先完成这些既有工具的准备，再生成参考输入。

## 云端执行边界

真实部署前重新核验授权、账户免费档、单一目标资源、到期时间和已部署状态。不要因为本文件包含命令就自动部署或延长有效期。三个后端迁移只通过 Wrangler 迁移记录执行，不在请求中重跑 ALTER。

`run-quota.mjs prepare` 仅设置一次测试 secret，通过官方 CLI stdin 传递；成功后保存到已忽略的 `.output/session-token`。后续运行复用它，不反复轮换导致部署传播。不得打印、提交或上传此文件；本轮所有响应报告均不含令牌。

`run-quota.mjs quota` 发出拒绝访问检查和四个配额 case；每个 case 独立 HTTP 请求，只执行指定 SQL。无 D1 鉴权查询、配额记录查询或重试。50 次控制组失败即停止其余对照。

`run-quota.mjs agent` 创建单轮、三轮和响应后保活三个虚构任务。**不能在失败后直接整组重跑**：先检查 `.output/agent-*.json` 和真实 D1，辨别哪些 case 已开始。程序每次生成新 UUID，重跑会创建新任务；不要把它当作生产幂等客户端。

`.output/` 中的报告是本轮执行产物，保留初始失败记录，不把它们覆盖成成功。就绪检查最多四次，仅访问不存在的路径，401/503 时最多间隔十秒重查；404 只证明该次请求的当前 token 可用，不能保证全网部署同步。业务请求不自动重试。

## 已知结论与边界

本轮四组配额 case 均完成，包括 51 次独立调用；不能据此宣称没有限制或断言 batch 的计数单位。三个假模型任务及五份收据已从真实 D1 独立回读并严格校验。

固定计数 100 和 usage 100/40 仅验证模拟账务，不验证生产精确 token 计数或真实供应商账单。background 是返回 202 后保活，不是客户端硬断网。

后续独立验证：`run-network.mjs disconnect` 只创建一条任务，读到首帧即销毁未结束的真实 HTTPS socket；`receipt-fault` 只创建一条收据已存但结算故障任务，`recover` 每次只恢复一页，不重派模型。必须独立查 D1，不因客户端 200 宣称任务成功。不要重复运行创建任务的命令。

`tokenizer-worker.mjs`是历史独立入口；`prepare-tokenizer.mjs`校验锁定资产并生成六条虚构Python参考，`run-tokenizer.mjs`每例仅发一个请求、失败停止。`capture-tail.mjs`采样90秒，只保存白名单字段，原始请求头不落盘。9月16日新增DO后，收尾须保留DO类/绑定/迁移，关闭公网及有效期，不再直接恢复旧轻量包；命令不是自动授权。

精确 BPE 六组云对照通过，但实际请求 CPU 10–35 ms，不符合 Free 稳定性验收；启动206 ms不等于请求CPU。没有受支持的真云内存峰值 API，不把 Node/零值占位内存当真云结果。真实 TCP 断连后的任务持久化与后续收据恢复通过，手机飞行模式/UI恢复仍未测试。

轻量对照使用独立`tokenizer-perf-worker.mjs`，encode/count须分两次部署；`run-counting-perf.mjs encode|count`验证协议和目标模式才执行每组14条请求，无业务重试。不能复用旧入口的404就绪判定。`benchmark-counting.mjs`仅测本地Node，`verify-counting-evidence.mjs`只读核验本轮固定证据，不新发网络请求。

最新报告见`docs/handoffs/2026-09-16-do-runtime-validation.md`：20次固定DO探针通过，入口CPU1–2ms、DO单/三轮Agent19/27ms，真实TCP断流恢复和账务通过，公网已关闭。云峰值内存仍未知，通用JS模板计数器尚未实现。

`run-do-probe.mjs`仅用于已批准的固定20次序列；DO对象已用尽20次准入，不能自动重开或重跑。`verify-do-evidence.mjs`读取报告并通过CLI只读查询D1，不发送业务；`read-cloud-state.mjs`仅用既有Wrangler OAuth读取同一账户的固定资源并输出脱敏状态，不打印凭据。包与版本保持锁定，不更新CLI。

普通Worker历史性能见`2026-09-15-tokenizer-counting-performance.md`；真实断连历史见`2026-09-15-cloud-tokenizer-disconnect.md`；基础配额历史见`2026-09-15-cloud-smoke.md`。这些历史数据不等同免费DO能力。
