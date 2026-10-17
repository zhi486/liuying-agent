# Cloudflare Free 基础真云验证

后续进展见 [精确计数与真实断连云验证](2026-09-15-cloud-tokenizer-disconnect.md)：六组完整 token ID 对照通过，实际 CPU 10–35 ms，不满足免费层稳定性验收；真实客户端断连与收据恢复通过，精确内存峰值未测得。下文为基础阶段记录。

日期：2026-09-15。用户提供 Workers & Pages 截图，显示 `Requests today 0 / 100,000`、Upgrade 按钮及已初始化的 workers.dev 子域名。本轮以这份账户免费档配额证据继续已批准的隔离探针；未扩大 OAuth 权限或升级套餐。订阅账单 API 仍不可读，不把 `usage_model=standard` 当作套餐证明。

## 已完成

| 项目 | 真实结果 |
| --- | --- |
| 隔离资源 | 1 个测试 Worker + 1 个测试 D1，名称均为 `liuying-free-spike-20260915` |
| D1 | `8b159107-a1f8-40d9-a932-c52df81c3dfe`，APAC，关闭读副本 |
| 迁移 | 正常应用后端 0001、0002、0003，未修改迁移内容 |
| 本地检查 | 后端 Node/Workers 类型检查 + 163 项测试通过；新增探针 13 项通过 |
| 独立审查 | 配额入口、鉴权和假模型闭环均无阻塞项；子代理只读，无改动 |
| 打包/部署 | 配额版本 2.85 KiB、启动 11 ms；假模型版本 64.70 KiB、启动 12 ms |
| 收尾 | API 回读确认 `enabled=false`、`previews_enabled=false`，已关闭测试公网入口；资源未删除 |

代码仅新增到 `agent-server/tools/cloud-spike/`，没有修改后端业务代码或 Android/React 旧改动，没有 Git 提交或推送。

## D1 配额对照

每组独立 invocation，固定 SELECT，鉴权和计数不查询 D1，无失败后查询或重试。

| case | 尝试/成功 D1 API 次数 | 成功 SQL 数 | HTTP / 结果 |
| --- | --- | --- | --- |
| seq-50 | 50 / 50 | 50 | 200 / completed |
| seq-51 | 51 / 51 | 51 | 200 / completed |
| batch-51 | 1 / 1 | 51 | 200 / completed |
| mixed-49-2 | 50 / 50 | 51 | 200 / completed |

本轮没有观察到第 51 次独立调用失败，因此不能据此确定 batch 的限额计数口径，也不能宣称没有配额。官方 [D1 限制](https://developers.cloudflare.com/d1/platform/limits/) 仍列 Free 50 queries/invocation；有限成功样本不替代平台承诺。后端三轮仍保持原 41 次 API / 76 条 SQL 的本地结构预算，不趁本次成功扩大执行上限。

缺令牌/错令牌返回 401，非白名单路径返回 404。初始未传播版本返回 503，随后拒绝行为符合预期。原始成功报告：`agent-server/tools/cloud-spike/.output/quota-1789465264532.json`（被忽略的本地产物）。

## 假模型闭环和模拟账务

固定虚构摘要：4 公里、1440 秒、配速 360 秒/公里、平均心率 150。全部使用注入的 fake transport，不执行真实 fetch；token 计数固定 100、供应商 usage 固定 100/40。费率仅为测试夹具。

| case | task ID | 任务状态 | 收据 | 模拟结算（微美元） |
| --- | --- | --- | --- | --- |
| one | `650fcc8b-7c5d-4792-8ff2-b0ffc0926535` | completed | 1 | 78 |
| three | `4d8eb19b-a98d-4495-96ed-63ca02473558` | completed | 3 | 234 |
| background | `a5d3af9c-9be0-442a-968a-840eb7154304` | completed | 1 | 78 |

独立真实 D1 回读并程序断言：恰好三个任务、五笔预留/收据；各任务 result available、reason null、账户 halted=0；每笔 reservation settled、预留 6720、charged 78、input/output 100/40、收据 applied=1、执行关联一致、violation=0、正确 request_id 序号。三轮真实 actions 是 model → runs.list → model → runs.summary → model。

background 首次响应为 202，固定假响应等待两秒后继续，随后独立回读证明任务完成；**这只证明响应后保活，不是实际客户端硬断网试验**。成功客户端报告：`agent-server/tools/cloud-spike/.output/agent-1789465963721.json`。

78/234/78 是测试数据库中的模拟金额，不是实际 API 消费；没有使用真实 DeepSeek/Fish 密钥或手机数据。

## 鉴权传播问题与恢复

初版客户端每次失败退出丢失内存随机 token，下次重启重新设置 secret，会触发新的版本传播。观察到设置成功后 503，以及同一 token 就绪路径 404、下一条业务路径 401 的混合结果。部署/密钥传播是强假设，不能声称已证明所有节点的具体原因。

处理为仅首次通过官方 Wrangler stdin 设置 secret，成功后写到已忽略的 `.output/session-token`，后续复用，不打印、不提交、不自动续期。固定 token 并等待传播后，三组业务请求一次完成。所有此前业务请求均被拒绝，回读证实零任务后才开始最终首轮，因此无重复任务。

就绪检查最多四次，仅访问不存在路径，401/503 时最多十秒间隔；业务请求不自动重试。后续若部分 case 成功，必须先核对报告与 D1，只执行尚未开始的 case，不直接整组重跑。

## 尚未验收与下一步

1. 精确 tokenizer 的真云启动、每请求 CPU 和内存；当前启动 12 ms 不等于 HTTP CPU 12 ms，也不代表分词器通过。[Workers 限制](https://developers.cloudflare.com/workers/platform/limits/) 列 Free 10 ms CPU、128 MB、1 秒启动。
2. 真正的客户端网络中止，以及收据已写入、结算失败、下一 invocation 恢复的故障云测。
3. JS 完整提示模板、真实供应商 usage 对照、生产 HTTP/身份配置和手机接入仍未完成。

下一批继续在同一隔离资源中做独立 tokenizer 探针与故障恢复；不要重做已通过的 OAuth/迁移/基础配额组。公开入口已关闭，恢复前需核对授权、有效期及配置，再显式开启。资源和本地测试产物保留待批准清理，未执行任何删除。
