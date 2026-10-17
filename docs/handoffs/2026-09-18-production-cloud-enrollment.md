# 正式云创建与真机登记

日期：2026-09-18。用户明确批准“现有 Free 账户新建正式 Worker、D1，并登记这台手机”，随后确认覆盖安装成功。本批不升级套餐，不重开历史隔离服务，不安装供应商 Secret 或调用真实模型。

## 已完成的正式资源

| 项目 | 实际值/结果 |
| --- | --- |
| Cloudflare 账号 | `95a131611fbe1097f7196cfb014ebce3`，沿用既有有限 OAuth，不扩权 |
| Worker | `liuying-agent` |
| 正式 origin | `https://liuying-agent.fangran31415927.workers.dev` |
| 首版版本 ID | `3c7eb331-430c-4777-b807-642c320dcefa` |
| D1 | `liuying-agent-production`，`7840026c-6571-48e9-a041-72d3c40d11ed`，APAC |
| 迁移 | 0001—0004 全部成功，另行 list 确认无待执行迁移 |
| DO | `AGENT_RUNTIME` → `LiuyingAgentRuntime`，首次 SQLite 类迁移 v1，与隔离资源分离 |
| owner | `liuying_personal`，新账户沿用 1,800,000 微美元/月；不代表全 App $2 硬封顶 |
| 设备 ID | `78d2df90-1218-48aa-acc4-e41a34f15761`；只登记一次，无重复签发 |
| 凭据有效期 | 创建 1789730005344，到期 1797506005344（毫秒）；私密原值不写本交接 |

新增 `agent-server/wrangler.production.jsonc`，生产部署必须显式 `--config` 指向它；根配置仍为本地占位。兼容日期 2026-09-18，workers.dev 开启，preview/routes/Cron 关闭，日志和 trace 开启，invocation_logs 关闭。

**当前是设备认证可用阶段，不是在线生成已可用。** 初次部署未提供供应商配置，故生产配置明确 `secrets.required: []`；否则 Wrangler 会拒绝新 Worker 首次部署。没有填假 key/费率，生产 `readRuntimeConfig` 校验未修改；缺失配置仍拒绝新执行。将来用户经私密渠道配置真实两项后，应把 required 恢复为 `DEEPSEEK_API_KEY`、`RATE_CARD_JSON`，但这个数组本身不是模型开关。

## 云端验收证据

- 本地正式配置生成绑定类型（`.generated/production-worker-configuration.d.ts`），正式配置 dry-run 通过，6401.92 KiB／gzip 1902.74 KiB；真实部署返回成功，启动时间报告 3 ms。不能将启动时间当运行 CPU/内存验收。
- 既有本地 workerd suite 本轮重跑：27 passed、1 项既有诊断 skipped；该 suite 沿用原本地配置，不冒充生产 2026-09-18 兼容日期的全面验收。
- D1 登记：register.sql 只执行一次。原始 Bearer 仅在新建当前用户受限 secrets 子目录，数据库仅保存摘要。
- 真正 `--command` 查询返回一行，`registered=1`、设备/owner/有效期均匹配，月限额 1800000、halted=0；云任务数查询为 0。
- 正式 HTTPS 认证 GET：合法新 UUID 的 `/v1/agent/requests/<uuid>`；正确 Bearer → 404 `not_found`，缺失/未登记 Bearer → 401 `unauthenticated`，三者 JSON 形状与 `Cache-Control: no-store` 均匹配。使用已安装 undici ProxyAgent，重定向拒绝、TLS 证书校验保留、15 秒截止，不调用 DO/模型。
- GET 内含本 owner 到期清理，因此不是绝对数据库只读；新库无任务，未发任何 POST。
- 最后只读核对：`secret list` 为 `[]`，唯一部署 ID 为 `2befde49-d742-4899-ac14-713b6353df41`，上述版本流量 100%。独立只读审查确认当前“正式入口＋设备认证”无新增阻断，但不代表模型回答或手机在线全链路通过。

## 本轮修正的运维问题

1. 电脑直连 DNS 失败，现有 127.0.0.1:7890 代理可用；仅对子进程配置，无系统网络改动。
2. curl/Windows Schannel 对正式 workers.dev 地址出现握手失败；Node HTTPS 客户端维持严格证书校验后通过。这个结果不保证手机流量/VPN场景已经验收。
3. Wrangler 远程 `--file verify.sql` 走批量导入，只给统计，不返回 SELECT 行；后改 `--command` 精确读回。两次脚本误判包含输出包装/日志过滤原因，没有重新注册或换令牌。`WRANGLER_LOG=error` 会压掉 JSON，不能据此解析。已修正设备登记操作文档。

## 手机与数据保护

- vivo V2241A，USB 授权正常；序列号只用于本机定向命令。
- 安装前无运行服务/未结束运动。新建当前用户受限目录 `build/device-tests/production-enrollment-20260918`，保留原 APK 与跑步数据库 tar，一致性检查 ok；仅本机保存，不上传健康数据。
- 安装前数据库 v2：4 条跑步、0 未结束、7295 路线点；尚无旧 Agent 数据库可备份，没有复制供应商配置。
- `adb install -r` 成功，用户也确认安装成功，无卸载/清数据。
- 安装后启动无本进程 AndroidRuntime 崩溃记录；数据库迁到 v3，4/0/7295 保持一致、integrity ok；新 `no_backup/liuying_agent.db` 已创建。
- 从手机拉回实际 APK，SHA256 与已测包完全一致：`59C68AB323C0D29AB141D104FD1B26D227931FC450B095B059104AEAF56155D4`；新旧签名均 `ebad2b92cff0324810062be3e8116645cfd352780ad79141be61be148774c603`。
- 前后备份、APK及虚构测试残留均保留，未删除；未改语音配置/音量、未播放、未生成跑步记录。

## 手机私密配置保存确认

用户先确认打开手机“在线 Agent 私密配置”窗口，随后回复“配置已保存”。用户通过本机私密文件填写设备凭据，原值未进入聊天或截图。

本轮只读检查 `no_backup/cloud-agent/identity.json`，严格限定四个身份元数据键；origin、ownerId、deviceId 与正式目标全部匹配，credentialRef 对应的密文文件为 97 字节，符合现有封装长度。只检查密文元数据，未读取或解密其内容，也未发手机云请求。此证据确认保存落盘，不证明录入凭据与云摘要一致或手机网络可达。

独立只读复核确认现有 UI 和 instrumentation 没有纯 GET 的手机正式认证探针；不能使用恢复入口冒充探针（可能带控制/确认 POST）。本轮未新增调试入口或测试、未安装或重启 App。

接续：请用户通过私密方式提供新供应商配置，核验当前费率并获准写 Secret；安装有效配置前恢复 required 声明。任何真实模型请求另行明确同意，仅做一次问题＋虚构偏好验证完整答案和原键恢复不重发。之后推进 RAG，不继续扩展计费。没有提交或推送 Git。

## 仅本地填写位置准备

用户随后明确回复“允许准备填写位置”，仅授权准备本地文件，不是写云 Secret 或调用模型的批准。新建 `agent-server/secrets/production-runtime-20260918/agent-runtime.json`，使用现有两字段空模板，不覆盖旧文件。新子目录权限继承关闭，仅当前 Windows 用户有允许访问规则，文件继承该限制；Git 忽略已核对。此文件不是磁盘加密存储，不上传、不加入 Git。

用户已回复“已填写”。重新核对文件访问权限、Git 忽略和未跟踪状态均通过；内容仅通过私密管道交给既有 `config-preflight.mjs --stdin`，输出为“密钥：格式通过；费率：缺失”，完整预检退出 1 是费率按约定留空的预期结果。未打印密钥内容，不把格式通过当成认证成功。

尚未核验当前费率、访问供应商认证端点、上传 Secret 或调用模型。本次用户授权仍仅包含本地填写准备和相应本地核对；下一步须明确获准核验费率并写正式 Worker Secret，模型调用仍单独确认。不要用编辑工具重新输出或重写含密钥的整份 JSON。
