# S2：私密配置与费率预检

日期：2026-09-17。用户明确要求本批实施。主代理编码，子代理独立只读安全复核；保留原脏工作区，未提交或推送。

## 结果

本地预检工具及配置/费率/设备管理操作说明完成；**没有读取或检查用户真实密钥，没有核验某份真实报价，没有安装云端配置或签发真实设备凭据。**

| 项目 | 已完成内容 |
| --- | --- |
| 生产一致性 | 提取既有内部校验；生产与离线报告共用规则，不放宽 key／费率／时间边界 |
| 状态说明 | 密钥缺失/非法/格式通过；费率缺失/非法/未生效/过期/有效 |
| 私密输入 | 仅显式 `--stdin`，拒绝 TTY，16 KiB 原始字节上限、严格 UTF-8、设置 5 秒截止 |
| 脱敏 | 仅白名单状态；密钥、摘要、版本、价格、时间和原始异常均不输出 |
| 操作说明 | 人工核验新报价、到期停止、Secret 部署副作用、可信签发/换机/撤销边界 |

## 文件

- `agent-server/src/runtime-config.ts`：内部纯校验复用；新增 `inspectRuntimeConfig` 只返回状态，`readRuntimeConfig` 仍统一 `CloudError('unavailable')` 并返回独立冻结费率快照。未改预算/结算层、HTTP 路由或 Worker 绑定。
- `tools/config-preflight.mjs`：不扫描 `.env`/`.dev.vars`、不读取配置环境变量、不联网、不写文件、不调用 Wrangler。根 JSON 只允许 `DEEPSEEK_API_KEY` 和字符串 `RATE_CARD_JSON`；错误不回显输入。
- `package.json`：新增 `preflight:config` 命令，无依赖变化。
- `tools/config-preflight.example.json`：只有两个空值，不含有效密钥或费率，不可作为上线配置。
- `test/config-preflight.test.ts`：七项新测试，与原 `runtime-config.test.ts` 组成八项定向测试。
- [使用与运维说明](../../agent-server/tools/config-preflight.md)：真实配置只由用户在私密渠道填写，本阶段不要求提供密钥。

## 验证

先运行新增测试观察缺少导出红例，再实现并通过：

| 检查 | 结果 |
| --- | --- |
| 预检＋原配置定向 | 8/8 |
| `corepack pnpm check` | 固定资产、Node/Workers 双类型通过；235/235 测试通过 |
| `corepack pnpm test:runtime` | 25 通过、1 个既有中断诊断跳过 |
| `corepack pnpm build:worker` | 仅 dry-run，通过；6399.09 KiB／gzip 1901.92 KiB |
| 独立安全复审 | 无阻断；超时口径说明为“设置 5 秒截止”，非精确墙钟 SLA |

覆盖真实子进程 CLI、环境中合成密钥不被自动采用、canary 不出 stdout/stderr、参数/JSON/编码错误静态化、512/513 密钥长度、2048/2049 费率长度、原始字节分块上限、TTY 拒绝、流异常、持续不关闭的真实管道，以及 checked 包含/expiry 排除、24 小时上限、冻结与独立快照。

所有凭据均为合成值；部分测试专门使用非法输入，不能把测试输出当用户实际配置状态。runtime 的合成回执断流异常属于既有注入场景，suite 通过；既有 abort 诊断仍跳过。

## 未完成与下一步

工具成功仅证明本地格式/有效期符合生产规则，不能证明 API 密钥有效、余额充足、官网价格真实、tokenizer 在线计费一致、远端配置已安装或服务可达。费率仍需人工核验且最多有效 24 小时，不会自动给旧报价续期；旧收据仍按原费率快照结算。

实际云写入、设备凭据发放/安全导入、真实模型费用与手机联调另行明确范围。根据本轮读取的 [Cloudflare 官方说明](https://developers.cloudflare.com/workers/configuration/secrets/)，`secret put/delete` 会立即部署，不能作为无副作用预检。没有执行这些命令，现有关闭态未改变。

下一步 S3：Android 独立在线意图持久层和 Room 迁移，保留旧 `agent.tasks.v1` 本地语义，不自动上传历史 blocked。S4 再接 HTTPS/Keystore，S5 接独立在线 Bridge/UI；用户真实配置与安装配合留到相应门禁。
