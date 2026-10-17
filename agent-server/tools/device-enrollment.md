# 正式手机设备登记

`device-enrollment.mjs` 只准备本地文件。**没有远程 apply、部署或模型请求动作**；不能把生成草稿当作设备已经登记。它不打开旧云验收 session，不读取 OAuth 或供应商密钥，不新增公网管理员接口。

## 先核对目标

1. 取得用户对正式 Worker、D1、手机登记的明确批准，不复用隔离验收资源。
2. 以 `wrangler whoami` 核对当前账号；使用正式部署配置核对 `account_id`、Worker `liuying-agent`、`DB` 的数据库 UUID，并完成迁移 0001—0004。默认 `wrangler.jsonc` 仍是不可上线的本地占位。
3. 首次使用选一个稳定 ownerId；以后换机继续用这个 owner，不创建新账户绕过旧预算或熔断。

## 本地准备

在 `agent-server` 下运行，尖括号均须替换成已核对的非秘密标识：

```powershell
node --experimental-strip-types tools/device-enrollment.mjs prepare --account <Cloudflare账户ID> --database <正式D1的UUID> --origin https://liuying-agent.<已核对的子域>.workers.dev --owner <稳定ownerId>
```

首版只接受上述正式 workers.dev origin，不接受测试 Worker、路径、尾斜杠、自定义域或供应商 API 地址。新建 `secrets/device-<UUID>/`，不覆盖已有目录；失败留下的部分文件不自动删除或重建身份。

| 文件 | 用途 |
| --- | --- |
| `phone-private.json` | 私密交付，包含 origin/ownerId/deviceId/token；不要在聊天、日志或截图中展示 |
| `register.sql` | 账户与设备登记，只含 token 的 SHA-256，不含原始 Bearer |
| `verify.sql` | 只读核验，返回登记是否精确匹配和预算，不返回原始 Bearer 或摘要 |

Windows 写秘密前，将**新建草稿子目录**关闭权限继承并仅给当前用户完整访问；失败则不写秘密，不修改父目录权限。其他平台使用 0700 目录和 0600 文件。它不是磁盘加密，不能阻止管理员、同一用户进程或备份软件读取；应只在受信任个人电脑运行。`secrets/` 被 Git 忽略，仍禁止强制添加或上传。

## 经批准后的登记与读回

生成的 SQL 注释记录目标，但 SQL 本身不能阻止管理员对错库执行。**每次执行前重新核对正式配置的账号与 DB UUID，不能只看名称，也不能用根占位配置。** 使用项目固定版本的 Wrangler 和明确的生产配置：

```powershell
node node_modules/wrangler/bin/wrangler.js d1 execute DB --config <经核对的正式配置> --remote --file <本次草稿目录>/register.sql
$deviceVerificationQuery = Get-Content -LiteralPath '<本次草稿目录>/verify.sql' -Raw -Encoding utf8
node node_modules/wrangler/bin/wrangler.js d1 execute DB --config <同一正式配置> --remote --command $deviceVerificationQuery --json
```

以上是人工审核后的远程命令，不由准备工具自动执行。[Cloudflare 官方 D1 命令说明](https://developers.cloudflare.com/d1/wrangler-commands/)说明 `--remote` 操作远端数据库，`--file` 执行指定 SQL 文件，`--command` 执行查询。正式验收发现本版本的远程 `--file` 使用批量导入流程，SELECT 也只返回统计，不能用它读回登记字段；核验必须使用 `--command`。查询只含摘要，不含原始 Bearer。

解析自动化输出时注意：代理提示可能出现在 JSON 前；`WRANGLER_LOG=error` 又会压掉正常 JSON，不能用这种方式获得“干净 JSON”。没有读到有效结果就保持未确认状态，不重新签发。

必须读回**恰好一行、`registered=1`、owner/device/到期时间与本次草稿相符**，才可向手机交付。空结果或 0 均不算登记成功；CLI 退出 0 也不能代替此检查。远程超时或中断后保留同一草稿，先查询再决定是否重试同一 SQL，不重新生成 Bearer。

新 owner 预算沿用现有上限 1,800,000 微美元/月；已有账户的上限、用量、熔断完全不变。这不是全 App（包括手机直连 Fish）的 $2 硬封顶。设备有效期仍为 90 天；重复 SQL 不续期、不换令牌、不解除撤销，已熔断账户不发新凭据。数据库实际执行时也检查时间，过期草稿不能登记。

## 手机导入与验收

用户在 App 首页“配置在线 Agent”的原生安全窗口填写：`target.origin`、`target.ownerId`、`deviceId`、`token`。最后一项是设备 Bearer，**不是 DeepSeek/Fish API key**。只由用户在本机私密查看文件，不通过网页 Bridge、聊天或命令参数传递原值。

配置完成只表示本机保存，不能据此声称已联网。供应商 Secret、当前费率和正式入口就绪后，另行取得一次真实模型请求授权；用一个非敏感问题与一条虚构偏好测试单次授权、完整回答、重启恢复和不重复派发。

工具不实现撤销 CLI；需要撤销时使用既有可信 `createDeviceAdmin.revoke` 流程，先持久撤销并取消待完成任务，再核验旧凭据失效，不能只删本地文件假装云端已撤销。退出验收或删除草稿需先取得用户批准。
