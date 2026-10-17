# 正式 DO：无人工屏障入口补测

日期：2026-09-17（Asia/Singapore）。承接用户“补测无人工屏障的正式入口／继续”。主代理直接实施，两个子代理独立只读复核；未提交、推送、改手机或调用真实模型。

## 结论

本轮捕获的 **33 个无人工交付屏障入口样本 CPU 均不超过 10 ms，最大 7 ms**，受控补测通过。10 个正式业务任务的提交入口全部有证据；不能扩大成全部请求、长期负载或完整产品已经通过 Free 验收。

| 项目 | 本轮结果 |
| --- | --- |
| 合成任务 | 10 个：6 完成、2 取消、1 原截止超时、1 模拟网络失败 |
| HTTP | 34 次客户端尝试，D1 有效外层令牌门禁计数 33 |
| 精确计数 | 12 轮正文 SHA-256、token 数逐项匹配本轮独立 Python 参考 |
| Agent Loop | 三轮计数 407／481／572，两次实际工具调用，三份唯一收据 |
| 入口 CPU | 捕获 33 个样本，0–7 ms；所有业务提交有覆盖，无超限或平台异常 |
| 取消／撤回／截止 | 确认派发后控制；结果不复活，原截止不延长，未知费用不退款 |
| 断网与内存 | 本轮断网 `not-tested`；峰值内存 `not-measured` |
| 收尾 | 独立读回确认 workers.dev=false、preview=false、环境及 D1 有效期均为 0 |

## 改动范围

只改 `agent-server/tools/cloud-verify/` 与交接资料，不改本轮之前的生产源码和依赖版本。

- 新固定 profile `entry-20260917`，Worker、D1、证据目录、创建凭证与一次性标记独立；实际操作必须显式选择 profile，缺失或拼错即拒绝。
- `entry-worker.mjs` 在源码中固定关闭人工屏障，直接返回正式 `production.fetch` 的原 Response：不克隆/读取响应、不轮询、不延迟回执、不包装 NDJSON。保留认证、总量和有效期安全门禁，不提供远程切换开关。
- case 5 改为普通 JSON 回执与同键查询。取消/撤回由电脑侧只读 D1 确认派发后发起；合成 transport 最长等待改为 15 秒，仍受原任务 30 秒截止约束，不增加入口轮询。
- 离线报告核对 profile、目标 Worker、逐条 scriptName、本轮时间窗口、任务覆盖和 CPU 字段；功能证据与入口 10 ms 判据分别记录，不用缺失值填零，不重跑筛选结果。
- 关闭流程先关闭云入口，再等待日志采集收尾。旧 `barrier-20260916` 证据及一次性状态保留，未重开。

## CPU 与响应证据

47 条脱敏 tail：33 个 stateless 入口、12 个 execute（含两次同键核验且不重新计数）、2 个 interrupt。全部 outcome=ok，无解析错误。客户端两次成功 ready 检查只捕获一次 ready；正式业务在 tail 就绪后开始，所以不能写“全部 34 次 CPU 均不超过 7 ms”。无效外层令牌的 0 ms 是平台原值，不是缺失值补零。

| 调用 | CPU | 说明 |
| --- | --- | --- |
| 全部已捕获入口 | 最大 7 ms | 所有业务提交有覆盖；安全门禁仍计入 |
| case 5 普通提交 | 4 ms | 客户端 264 ms 收到 JSON 202，后台继续执行 |
| DO 首见实例，短请求 | 345 ms | 包含真实 BPE 首次初始化 |
| 同一 DO 热短／热长请求 | 18／29 ms | 与入口 CPU 分开统计 |
| 第二个首见 DO 实例 | 638 ms | 不冒充热缓存结果 |
| 三轮 DO Loop | 36 ms | 三轮真实计数与工具调用 |
| case 9 新实例 | 589 ms | 不能假设缓存永远常驻 |

case 5 平台入口 wall 为 4413 ms，包含后台 waitUntil；不能用它代替客户端回执耗时或 CPU。CPU 是完整 invocation，包含测试哈希、门禁及观察写入，不是纯 tokenizer 微基准，也不是完全去除测试包装的生产基准。

Cloudflare 当前官方 [CPU 限制说明](https://developers.cloudflare.com/workers/platform/limits/#cpu-time) 将普通 Free HTTP Worker 标称上限定为 10 ms，并区分 CPU 与等待时间；本报告将入口与 DO 独立统计，不将 DO CPU 套入入口阈值。文档于本轮核对，后续套餐或限制变化需重新确认。

此前带人工断流屏障入口 17 ms 的结果仍保留。本轮是独立日期、资源和实例的补测，不是证明差值完全来自屏障的受控 A/B 实验，不改写历史失败或例外。

## 计数、账本与边界

12 轮云端实际正文摘要及数量均与新生成的独立参考一致；云端没有导出完整 token IDs，因此不是云 IDs 逐项差分。三轮业务实际调用 `runs.list`、`runs.summary`，其余合成场景各计数一轮。

按任务 ID 独立核对 10 条任务、12 条预留/派发、8 份唯一 applied 收据。前六个完成任务共 8 条 settled，每份合成 usage 为 1000/20、记账 324 微美元，总计 2592；取消、撤回、截止、模拟网络失败各一条 uncertain，charged=null、无收据，每条保留 6720，总计 26880。上述金额不是实际 API 消费。

模型只有固定内存替身；真实 API 调用为零的依据是固定注入与本地拒绝出站测试，不是供应商账单审计。没有读取或部署真实 DeepSeek/Fish 密钥。

本轮没有真实销毁 socket、手机飞行模式、VPN 切换、杀 App 或云 DO 崩溃测试。之前的真实 socket 恢复证据单独保存在 2026-09-16 交接，不由本轮重复证明。峰值内存、长期负载、真实模型与手机云接入仍未验收；每月 $2 仍是目标而非整套 App 的硬封顶证明。

## 资源与安全收尾

- Worker／D1 名称：`liuying-agent-entry-20260917`。
- D1 UUID：`bf0fab8d-6187-47cf-93fb-cff7f9e42399`。
- SQLite DO namespace：`23199dd1ded34000840c3284fdd76440`，类 `VerificationRuntime`。
- 一次性窗口 15:40:31 开启，runner 于 15:40:50–15:42:36 执行及收尾；15:43:47 独立读回确认关闭。没有续期或重跑。
- 独立 API 均 HTTP 200/success，workers.dev 与 previews_enabled=false；Worker `VERIFY_EXPIRES_AT=0`，D1 `expires_at=0`，绑定与创建凭证一致。
- 资源保留、没有删除；旧服务仍关闭，正式 `liuying-agent` 未生产上线。沿用用户确认的 Free，未升级套餐或扩展权限。

## 本地检查与独立复核

| 检查 | 结果 |
| --- | --- |
| 验收工具 Node 测试 | 15/15 |
| 新 profile 真实 workerd suite | 4/4，含原 Response 直接返回与后台继续执行 |
| 隔离 dry-run | 6406.80 KiB／gzip 1904.48 KiB，通过 |
| 离线证据分析 | `evidencePassed=true`、`entryWithin10ms=true`、`passed=true` |
| 两项云后只读复核 | 原始 tail／参考、逐任务账本、独立关闭均无阻断 |
| `git diff --check` | 通过；既有 CRLF 提醒保留 |

本轮未重跑正式生产全量 221 项与原 runtime suite；这些是前次交接中的历史结果，不能冒充今天的新结果。分支原有 Android、前端及后端未提交改动均保留。

## 证据与下一步

证据在已忽略的 `agent-server/.generated/cloud-entry-20260917/`：`run-report.json`、`database-evidence.json`、`reference.json`、`tail-1789630956830.json`、`metrics-evidence.json`、`closure-evidence.json`。私密 `session.json`、`seed.sql` 不提交、不转储；关闭后不得为补报告重开资源或重跑云任务。

下一步可设计真实供应商配置/费率刷新、维护调度和手机 HTTPS 接入，再明确一次真实模型与设备验收的范围。当前无须用户操作手机；若要部署、重开云窗口或调用真实模型，先明确授权范围。
