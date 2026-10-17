# 正式云与手机登记准备

日期：2026-09-18。承接用户“继续下一步，需要我配合积极沟通”。手机单次授权闭环已在上一批完成；本批只读核对远端状态并补本地登记工具，**未生产部署、未登记真实设备、未改手机或调用模型**。

## 当前事实

- 最新只读 `wrangler whoami` 确認原 OAuth 仍有效；直连 DNS 超时，通过本机已有 127.0.0.1:7890 代理恢复。仅为子进程设 HTTPS_PROXY，未改变系统代理。未重登或扩展权限；Wrangler 对其他产品 scope 的提示不需要全部补齐。
- 正式 Worker `liuying-agent` 的 deployments 查询返回 Cloudflare 10007（不存在）；D1 清单只有此前三套隔离资源，无正式数据库。根 `wrangler.jsonc` 仍是占位且入口/Cron关闭，不能直接部署。
- 当前会话无可用 Cloudflare MCP；Wrangler 可替代必要操作。Ravenclaw 在线但未登记 healthy-agent 项目，没有借用其他项目或新建外部记录。
- 多次 `adb devices -l` 为空。已请用户连接手机并允许 USB 调试；截至本批收尾未收到已连接确认。
- 已明确询问用户是否允许在现有 Free 账户新建正式 Worker 和 D1、登记手机。**截至本批收尾尚未收到批准**，不推断旧隔离验证授权涵盖正式上线。真实供应商请求另行确认。

## 已完成

| 内容 | 结果 |
| --- | --- |
| 本地设备登记工具 | `agent-server/tools/device-enrollment.mjs`，只有 prepare，无云 apply/联网/密钥读取 |
| 私密交付 | 新建忽略目录，私密 JSON 与只含摘要的 register/verify SQL；Windows 先限制新子目录 ACL，成功后才写秘密 |
| 数据保护 | 重复 SQL 不覆盖预算/熔断/令牌/到期/撤销；读回 registered=1 才算登记成功 |
| 竞态修复 | 独立审查发现异步摘要期间可改 owner；已在两入口首次 await 前复制，SQL 与私密文件使用同源快照 |
| 文档 | 新增工具说明，修正后端 README 顶部和记忆段落中“手机在线未接线”的旧状态 |

没有修改生产 src、迁移、Workers 配置、Android/前端或依赖。工具首版限定正式 `liuying-agent.<子域>.workers.dev`；SQL 中账号/数据库只是注释，不是执行绑定，人工执行前必须核对正式配置和数据库 UUID。工具不实现撤销 CLI，沿用既有可信库流程。

## 验证

- 新工具先取得缺模块失败基线，最终专项 10/10 通过；使用真实 SQLite 与生产设备认证函数。
- `corepack pnpm check`：固定资产、Node/Workers 双类型及后端 **252/252** 通过，无失败/跳过。
- 全量后再补文件级权限与时间篡改断言，专项 10/10 重跑通过。Windows 目录及私密文件仅当前用户 Allow FullControl，目录禁止继承。
- 第一版权限测试因旧 PowerShell 无法加载 Get-Acl 模块失败，未放宽断言；改用 .NET 直接读取后通过，不是实际权限泄漏。
- 独立定向复审确认竞态闭合，未发现新增明显阻断；`git diff --check` 通过，保留既有换行提醒与脏工作树，未提交/推送。
- 所有新凭据都是虚构测试数据，测试目录保留在 `agent-server/.generated/enrollment-test-*`，未擅自删除。没有生成真实 secrets 草稿。
- 上一批 800 JVM／212 前端／47 模拟器是历史结果，本批未重跑或重建 APK。现有 APK 仍见[手机闭环交接](2026-09-18-phone-cloud-loop.md)。

## 接续

1. 先收用户正式资源/设备登记批准，以及 USB 连接确认；不再扩展计费工程。
2. 按 Cloudflare 技能核对正式目标，批准后创建新 D1、迁移、配置正式 Worker/SQLite DO；不重开或借用旧验收身份。修改 DO 配置前读取 durable-objects 技能。
3. 用户在私密入口填写新 DeepSeek 配置，核对现价后形成有效费率，预检并经批准部署 Secret。仍有最长24小时费率有效期，不暗中续期或更改门禁。
4. 用本工具生成真实私密草稿，审核正式目标后执行登记并读回；不把 Bearer/供应商密钥输出到工具日志、聊天、截图或交接。
5. 原签名覆盖安装，保留运动数据与语音设置，用户在原生安全窗口配置设备；准备好后另行批准一次非敏感“问题＋虚构偏好”请求，验收回答与原键恢复不重发。
6. 真机闭环之后推进小语料 RAG；不要把目前本地工具或模拟器证据当作真实在线验收。

操作说明见 [设备登记](../../agent-server/tools/device-enrollment.md)。本轮命令已结合固定 Wrangler 4.131.2 帮助与 [Cloudflare 官方 D1 文档](https://developers.cloudflare.com/d1/wrangler-commands/)核对；网页检索工具不可用，官方文档通过现有代理只读获取。
