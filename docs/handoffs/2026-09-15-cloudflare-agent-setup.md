# Cloudflare 官方 Agent 开发环境配置

日期：2026-09-15。用户明确要求读取并执行 https://developers.cloudflare.com/agent-setup/prompt.md 的适用配置，选择 Codex 分支。

**最新进展：** 用户已提供账户每日 100,000 次免费档配额及子域名截图，基础真云探针已完成。测试 D1/Worker 已创建，4 组配额实验、3 组假模型任务及 5 份收据通过真实回读；公网测试入口已关闭，资源保留。本文以下等待截图/尚未建资源的内容是此前阶段记录，最新状态以 [基础真云验证](2026-09-15-cloud-smoke.md) 为准。精确 tokenizer CPU/内存和真实断连/故障恢复仍未验收。

## 已完成与验证

- 从官方 `cloudflare/skills` 固定提交 `b052c32bab7dd493513260228a36c88294f343f1` 安装全部 14 个技能，目录 `C:/Users/fangr/.codex/skills/`。按 Codex 技能安装流程使用系统安装器，没有覆盖已有技能，也没有执行下载技能中的脚本。14 个 `SKILL.md` 的 Git blob hash 与官方树全部一致；未声明全体文件安全审计完成。
- 在 `C:/Users/fangr/.codex/config.toml` 注册并启用 `cloudflare`、`cloudflare-docs`、`cloudflare-bindings`、`cloudflare-builds`、`cloudflare-observability` 五个官方 HTTPS MCP。
- 原配置已备份到 `C:/Users/fangr/.codex/config.toml.before-cloudflare-20260915-162826.bak`，备份时文件哈希一致。CLI 注册时省略了原 `ravenclaw.type="stdio"`、`node_repl.args=[]`；发现后已补回。移除新增五项后的 TOML 解析结果与原配置完全相同。
- 只读独立网络检查：Docs MCP 匿名 initialize 200，服务器 `docs-ai-search`；其余四项 401 且公开 OAuth discovery 200，支持授权码/PKCE S256。401 是未授权，不是不可达。
- 上一轮 Wrangler OAuth 命令本轮收尾返回 `Successfully logged in.`；这只代表 Wrangler 登录，不等于 MCP 登录或 Free 套餐已核验。

## 主 MCP 授权已成功，工具已重载并完成部分只读核验

用户确认浏览器显示 `Authentication complete` 后，本机同次登录命令返回 `Successfully logged in to MCP server 'cloudflare'.`，退出码 0；新鲜 `codex mcp list --json` 确认主服务 `enabled=true`、`auth_status=o_auth`。先前数轮误操作/超时只影响当次 OAuth，未重装技能或扩大权限。

授权页面从默认 194 项只读权限收窄为 8 项：三个页面必需项（账户读取、用户读取、后台/离线访问），加 D1 读/写、Workers Scripts 读/写、Workers Tail 读；最终确认页显示 8 项及用户选择的单一账户。没有选择完全访问权限或无关产品权限。具体 API 能力仍需重载后通过只读调用核验，不得因权限不足自动全选或扩大授权。

`cloudflare-bindings`、`cloudflare-builds`、`cloudflare-observability` 三个专用服务仍为 `not_logged_in`，仅在确有需要时再单独 OAuth。Docs 无需登录，CLI 的 `unsupported` 不代表初始化失败。

用户重载后，本轮已发现并调用主 MCP 的 `search` / `execute` 及 Docs 搜索工具。当前没有运行中的主服务登录命令；无需重新 OAuth。

本轮主 MCP 对用户选定账户的只读结果：

| 检查 | 结果 |
| --- | --- |
| `GET workers/account-settings` | 200，`default_usage_model=standard`；此字段不证明付费或免费套餐 |
| `GET workers/scripts` | 200，空列表 |
| `GET d1/database` | 200，空列表 |
| `GET subscriptions` | API 10000 Authentication error；其他调用成功，不是整个登录失效，不能自动扩大权限 |
| `GET workers/subdomain` | API 10007，尚未初始化 workers.dev 子域名 |

Docs 工具调用没有报鉴权错误，但两次定向搜索返回空结果，因此本轮限制依据改为直接读取 Cloudflare 官方 Markdown 文档。内置浏览器访问套餐页跳转到登录页，不共享用户 Chrome 登录；没有输入凭据、修改权限或操作升级。已请用户在已登录 Chrome 的 Workers & Pages 中查看套餐并提供 Workers Free 截图；首次进入若要求初始化子域名，可由用户保留默认名称。

仅做了只读云检查，尚未创建任何测试资源。没有把 `standard` 使用模型、空资源列表或登录成功当作 Workers Free 的证明。本轮 `corepack pnpm check` 退出 0：Node/Workers 双类型检查及 163 项测试通过，0 失败；仅有 Node SQLite/MockTimers 已知实验性警告。

## 云探针接入审查

独立只读审查确认 `createAgentService` 可直接注入真实 D1 和假供应商传输，无需预先修改业务代码。不能直接打包 Node 测试夹具；云端需采用实际 `Date.now()`，避免固定夹具时钟与数据库时间冲突。

本地内存 SQLite 的生产路径计数：单轮 `prepare+run+get` 为 21 次 D1 API / 46 条 SQL；三轮 `prepare+run` 为 41 次 API / 76 条 SQL。初始化、设备签发、限次与验收回读不要混入配额对照。固定 token=100 仅能验证记账，不属于精确 tokenizer 验收。

确认 Free 后，先做 50/51 次独立查询、51 条 batch、49 次独立加 2 条 batch 的有界对照，再验证假模型闭环、断连/收据恢复与独立 tokenizer 云性能。所有测试需隔离、鉴权、短期有效；没有真实密钥、模型调用或用户数据。运行结果仍待真实云测，不得把本地计数代替验收。

本轮读取的官方依据：[Workers 限制](https://developers.cloudflare.com/workers/platform/limits/)、[D1 限制](https://developers.cloudflare.com/d1/platform/limits/)。前者列 Free 10 ms CPU、128 MB、1 秒启动；后者列 Free 每 invocation 50 次查询，但 batch 实际计费口径仍需上述对照。

## 范围

未部署 Worker、创建 D1、上传跑步数据、调用真实模型、推送 Git 或升级付费。此前仅 Free、虚构数据、假供应商云探针的范围授权继续有效；真实账户的 Workers Free 套餐、D1/CPU/内存和断连记账仍待验收。

恢复时先查看用户提供的 Workers Free 套餐证据，再核验 workers.dev 初始化状态，最后继续已批准的虚构数据真云探针。不必重做已经成功的主 MCP 授权，也不要把专用 MCP 尚未登录或订阅读取权限不足当作主服务失败。
