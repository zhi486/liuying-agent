# 正式 Worker / DO 接入：本地与隔离云检查点

日期：2026-09-16。用户已批准实施并要求继续；主代理直接编码，子代理只读审查。沿用 `codex/five-second-zones` 的现有脏工作区，HEAD 仍为 `74549137be63aa1cb0571dd526e9cca75c68cec6`，未提交或推送。

## 状态

| 阶段 | 结果 |
| --- | --- |
| A：通用精确计数 | 前序已完成，本轮不重做资源/参考差分 |
| B：正式 HTTP / 每 owner DO | 代码已实现，本地类型/构建及主体集成通过 |
| C：本地真实运行时 | 本地业务矩阵已补齐：最新 23 项通过；1 项插件中断 API 诊断仍跳过 |
| 本地真实 HTTPS 断连 | 已通过：无回执、按原键恢复、一次模型/结算 |
| 本地 workerd 强制退出并重启 | 两模式通过：有收据结算一次；无收据保持 uncertain；原键不重跑、原截止不延长 |
| 受控隔离云 | 用户批准后完成 10 任务／35 HTTP；计数、Loop、断流和账务核对通过；入口已关闭 |
| 真实 API 与手机 | 未调用、未改 App、未安装；峰值内存未知，Free 性能保留断流入口 17 ms 例外 |

权威规格：`docs/superpowers/specs/2026-09-16-agent-do-tokenizer-design.md`。实施计划：`docs/superpowers/plans/2026-09-16-production-do-runtime.md`。本批没有放宽原来的云/费用/手机授权边界。

最新补测（2026-09-17）见 `docs/handoffs/2026-09-17-no-barrier-entry-validation.md`：无人工交付屏障的 33 个入口样本 CPU 最大 7 ms，10 任务／34 HTTP 的受控证据通过并已关闭；本轮未重测断网，内存仍未知。上表和 `docs/handoffs/2026-09-16-formal-do-cloud-acceptance.md` 保留前次带屏障的历史结果。下方“未云部署／等待确认”段落记录更早阶段，不再作为最新待办；正式产品仍未上线。

## 实现

- `agent-http.ts`：六路由、HTTPS、真实字节 64 KiB、fatal UTF-8、JSON 类型、禁止压缩/多余字段/非空控制正文，静态错误/no-store/无通配 CORS。读正文中断也释放 reader。
- `worker.ts`：先 D1 认证，以可信 owner 找 DO；完整 execute RPC 先注册 Worker waitUntil，再竞争私有回执。查询/取消/撤回/确认直连 D1；取消通知不阻塞成功响应。
- `agent-runtime.ts`：构造器仅建 SQLite owner/version 表，认证后事务绑定。短接单门闩、不建隐藏执行队列；忙时新键在可能建任务前拒绝，旧键仍经 `tasks.accept` 正式指纹核验。旧键重放不依赖当前模型配置。
- 首次 run 立即启动，回执独立发送并消费拒绝，不参与账务 drain。执行槽按本次对象身份清理，旧调用不能清掉后续槽；迟到 prepare 不启动 run。
- `runtime-lifetime.ts`：RPC 入场单调 30 秒，接单后原任务剩余期限，动态 Set 收尾共享截止。保留现有 Agent 服务的更早期限、派发门禁和 AbortController。
- `runtime-config.ts`：严格配置、费率有效期最多 24 小时、缺失/过期拒绝新意图，不伪造免费价格。服务/费率按执行构造，取消使用当前同一服务。
- 词表以生成的 Data 模块打包；构建前验证两个固定资源，输出同一份已验词表快照；DO 首次计数时再初始化真实 BPE，仅缓存固定工厂。
- 新独立 `wrangler.jsonc` 不含真实 account/database ID，公网全部关闭；日志禁 invocation、去掉 query。类型由 Wrangler 生成，Workers 使用严格 ESNext/Bundler；Node 原 NodeNext 不变。

## 红绿与审查修复

HTTP、动态收尾、配置、资产门禁均先观察缺失实现红例再实现。真实 workerd 初始入口缺失红例之后补齐正式 Worker/DO。

独立审查发现并已验证修复：

1. 回执永不 settle 时原来会阻塞 drain；现在不等待回执才能释放执行槽。
2. 费率失效时原来破坏旧键同文/异文语义；现在旧键仍核验指纹，新意图才要求配置。

本地实际 bundle/native fetch 还发现纯 transport mock 漏掉的兼容差异：`redirect: 'error'` 在 Workers 发网前即抛异常。改为 `manual`，原 `readResponse` 对全部非 2xx 拒绝并取消正文；301/302/303/307/308 不跟随、不转发 Authorization。Node 明确红绿，真实 HTTPS 断连随后通过，独立复审无阻塞。

测试夹具曾跨调用复用旧 DO stub，遇到 `Cannot perform I/O on behalf of a different Durable Object`；改为后续调用重新获取 stub 后相关测试通过。生产 Worker 原本就是每请求获取 stub，未为此改生产业务。

## 可复验证据

在 `agent-server`：

| 命令 | 本轮最终结果 |
| --- | --- |
| `corepack pnpm check` | 221/221；资源、Node 和 Workers 类型通过 |
| `tools/token-count/.venv/Scripts/python.exe -B -m unittest discover -s tools/token-count -v` | 16/16 |
| `corepack pnpm test:runtime` | 22 通过 / 1 插件诊断显式跳过；最后全量 51.49 秒，包含迟到结算完成后的截止断言 |
| `corepack pnpm test:disconnect` | dry-run + 真实回环 HTTPS 断开/恢复通过 |
| `corepack pnpm test:restart` | dry-run + 有收据/无收据两个独立持久化 workerd 强停重启场景通过 |
| `wrangler types --include-runtime false --strict-vars false --check` | 声明无漂移 |
| `git diff --check` | 无空白错误；有既有 Windows 换行提醒 |

Runtime 覆盖：未认证不建 DO、两个 owner 隔离、首次并发同键一次执行、异文冲突、新键 busy、早回执对应 D1、完整 waitUntil、回执失败/悬挂、旧键在费率过期后恢复、三轮真实计数及工具历史/供应商 usage 收据、慢模型取消与同实例 abort、计数后派发前取消/撤设备、transport 真正拒绝、收据后取消、结果确认/撤回/到期、优雅驱逐后不重发及未应用收据幂等结算。

真实 30 秒测试同时挂起 prepare、transport、收据写入，按原界限结束；迟到 prepare 无模型派发；原键只恢复超时任务。所有模型均为合成替身，未消耗真实额度。

Bundle：约 6394.34 KiB，gzip 1900.85 KiB，含约 6.37 MB 的词表二进制；不是内存峰值证明。打包入口不解析词表/BPE。Wrangler 4.131.2、Vitest 4.1.11、插件 1.1.9、Vite 7.3.6、Miniflare 5.20260911.1-alpha 固定版本，未升级旧云探针。

独立 HTTPS 工具采用 Miniflare v5 官方 `convertV4MiniflareOptions` 和显式模块清单；`cf:false`、无遥测、无远程绑定，出站只允许固定合成供应商。测试 wrapper 延迟回执网络交付，先确认接单/模型已进入，再真实销毁 socket；不是生产自然丢包或云端测试。

## 插件内中断诊断：仍未修复，不等同于业务恢复失败

挂起收据时，`abortAllDurableObjects()` 两次稳定触发 workerd 原生 `Promise callback destroyed itself`。改成定点 `runInDurableObject(...state.abort...)` 后出现 outputGateBroken 并在 45 秒测试上限超时。不能断言前者仅仅误杀测试 Runner，也不能把定点版本算通过；没有用业务假结果掩盖失败、没有升级锁定运行时。

该诊断用例默认显式跳过，不计入通过数。只有要隔离调查这个平台边界时才单独运行；正式进程中断恢复已有下面的独立证据：

```powershell
$env:LIUYING_RUN_ABORT_REPRO = '1'
try {
  corepack pnpm exec vitest run -t '待验：实例被中断后'
  $testExit = $LASTEXITCODE
} finally {
  Remove-Item Env:LIUYING_RUN_ABORT_REPRO
}
exit $testExit
```

开关只在测试配置，不进入正式代码/部署绑定。默认 suite 的 22 项通过不表示这项也通过。没有升级依赖、修补 workerd 或改变生产业务架构。官方 [测试 API](https://developers.cloudflare.com/workers/testing/vitest-integration/test-apis/#durable-objects) 区分强制 abort 与优雅 eviction，不能用后者冒充前者。

## 后续推进：独立进程强制重启验证

新增 `agent-server/tools/verify-runtime-restart.mjs`，由主代理编写、子代理只读审查。本轮只改变测试工具/命令和进度文档，不改生产源码、依赖版本、手机、真实云或 Git 分支/提交。

固定 Miniflare 5.20260911.1-alpha 的 `Miniflare.dispose` 在初始化/配置 mutex 结束后调用 `Runtime.dispose`，其实现直接 `runtimeProcess.kill('SIGKILL')`，随后等待进程退出；不会等待挂起业务排空。测试先完成 ready、不并发改配置，在原任务截止前强停，不使用 Vitest runner 内的 abort API。

测试专用 subclass 复用真实 `interceptD1`。默认 `receipt` 模式在收据的真实 D1 batch 完成后发回本地信号，再在 workerd 内永久等待，永不放行结算。`no-receipt` 模式在适配器已解析有效 usage、但收据 batch 尚未执行时永久等待。Node 独立查询确认任务 running、预留 reserved、派发状态 may_have_dispatched，并分别确认收据 applied=0 或确实不存在，再调用 dispose。最后完整双模式复跑强停分别为 40/37 ms，旧监听地址不可连接。

新 Miniflare 使用同一新建目录与相同 Worker/DO/DB 身份，不重做迁移、账户或设备预置。断言：

- DO 内存实例随机标识改变，SQLite 仍仅保存原 owner/version。
- 有收据模式的 D1 未应用收据完整保留；两种模式在原执行窗口内提交同一请求仍为 running，id/deadline 均不变，供应商调用仍只有一次。
- 有收据时，真实 `recoverFreeExecutions` 第一次 receiptsApplied=1，第二次=0；合成费率下实际记账始终为 324 微美元（1000×0.3 + 20×1.2），不重复记账。
- 无收据时，截止前恢复不释放；等待原截止后连续恢复两次仍为 uncertain，charged_micros=null，全额保留 6720 微美元预留，不将未知账当免费。
- 两种模式等待真实原截止后状态 timed_out、结果 null，再次原键提交仍 timed_out。任务与预留各一条；有收据模式收据一条，无收据模式零条。

首跑暴露测试桥惰性代理跨关闭后读取会 poisoned；关闭前转为 JSON 纯数据快照后通过。独立审查补上重放必须仍在执行窗口内、45 秒完整场景上限，以及超时收尾取消 Node 延迟/禁止迟到新建运行时。所有辅助 RPC、永久等待和合成配置都只存在工具内，未进入生产 bundle。

真实外部供应商全部由本地出站替身替代。工具保留 `.generated/restart-*` 内的虚构数据库，不读取旧资源/秘密、不自动删除。它证明固定本地运行时进程强停后有收据可恢复、无收据保留未知费用，不证明云端单个 DO 的实际驱逐或故障语义。

## C 矩阵补齐（本轮）

以下四组本地证据现已补齐。本轮只新增/完善运行时测试、重启工具、命令和交接，不改生产源码：

1. **长文本冷/热 BPE：** 两组独立合成长文本为 2000/1999 UTF-16 code units。真实 DO 首次、同对象热调用、另一新对象首次调用原始 BPE，得到 1683/1479/1683 token，并直接核对发送的 user.content 保持原文。独立 Python 参考与只读审查均复算一致；不替换 tokenizer 返回值，也不等于线上供应商 usage 已验收。完整请求的计数/发送一致性另由切片 A 的 Node 集成测试覆盖。同步 BPE 无法被 JS 抢占的限制仍在。
2. **无 usage 收据的进程强停：** 上述 `no-receipt` 场景用真实 D1 与原 30 秒截止，证明未知预留不退款、不重派。
3. **结算后取消与旧通知隔离：** 第一轮工具请求先结算 324 微美元，第二轮在持久派发许可写入前暂停，再分别取消任务/撤销设备。两例都只发一次模型请求，第二笔以 closed_not_dispatched 释放，旧结果不复活。另一例将旧任务已捕获的 service 取消通知卡在 SQL await，待同 owner 新任务正在执行后再放行；新任务的 signal 未被中断且正常完成。
4. **24 小时边界与迟到 usage：** 真实 D1 上仅推进正式工厂注入的测试时钟，分别验证接单起指纹 24 小时、终态写入起结果 24 小时的前 1 ms 与到点，不直接修改到期列、不声称真实等待 24 小时。取消发生在收据 INSERT 前，随后放行有效 usage，等待真实 applied=1 提交及完整 RPC 后断言结果仍 null、费用只结算一次。该迟到结算在原 30 秒内完成，不保证宿主结束后的写入存活。

新增六项定向测试及完整 22 项运行时测试通过，两项只读独立审查未发现阻塞问题；门闩测试在 finally 放行并有界等待残留 RPC，避免失败路径污染后续用例。C 的本地业务矩阵完成；插件 abort 诊断和下列上线门禁不因此变成通过。

## 上线门禁仍在

正式云 CPU、峰值内存、线上 `usage.prompt_tokens`、有效私密配置/费率刷新、定期 purge/恢复调度与告警、手机 HTTPS 协议及真实跑步闭环均未验收。旧隔离 spike 维持关闭；不能自动重开、创建资源、迁移远程库或调用付费 API。$2 是既定目标，不是全 App 已实现硬封顶。

## 后续受控云验收：只读预检查

2026-09-16 用户提出“继续受控云验收”。本次先完成只读检查，尚未创建、部署或开放新服务：

- 固定 Wrangler 4.131.2 的 `whoami` 成功，现有最小 OAuth 登录可用；没有为 CLI 的可选权限警告重新登录或扩权。当前工具列表缺 Cloudflare MCP，改用已配置 CLI。
- 旧 `liuying-free-spike-20260915` 控制面确认 workers.dev=false、preview=false、EXPIRES_AT=0，原 SQLite DO 保留。脱敏状态证据在忽略目录 `agent-server/tools/cloud-spike/.output/cloud-state-1789564534786.json`。
- 远程 D1 清单仅有旧探针库；正式 `liuying-agent` 的部署查询返回明确的 Worker 不存在（10007），不是登录失败。不能直接部署带全零 D1 占位的正式配置。
- 账单接口仍 403；沿用用户最近确认的 Free，不把 default_usage_model=standard 当套餐证据，不扩充账单权限。
- 正式 DO 当前未注入模拟 transport，直接调用会落到真实 fetch；填假密钥也不能保证零真实 API 请求。旧 tail/runner 固定旧资源和已耗尽的 20 次许可，不可直接复跑。

拟新增独立 `liuying-agent-verify-20260916` Worker、同名隔离 D1 和一个 SQLite DO namespace；保留旧探针与正式关闭态配置。先补构造期模拟 transport 接线与独立限时/持久总量门禁，复用真实认证、通用计数、Loop、账本与六个业务路由，不让公网请求选择模拟或真实供应商。

提交用户确认的边界：最多 20 个新合成任务、最多 100 次验收 HTTP 请求（含查询/控制/就绪检查），测试窗口最多 30 分钟，不自动续期/重跑；本地回归和只读复审通过后才创建隔离资源并应用现有三份迁移。没有真实模型密钥或请求、真实运动资料、套餐升级、手机改动或 Git 提交。结束关闭公网和预览、有效期归零，保留测试证据及资源，不自动删除。

证据分别采集入口和 DO 的真实 CPU/wall time、关联标记、独立 D1 回读以及运行中 socket 真断开/原键恢复；不能把旧探针结果或同键重放当新计数性能。云峰值内存无可靠字段仍标未知。该小范围隔离验收设计与新资源范围等待确认；尚未开始实现和云端业务请求。
