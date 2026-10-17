# 正式 DO 的一次性受控云验收

本目录只用于已批准的隔离验收，不是生产部署入口。固定 profile `barrier-20260916` 对应 `liuying-agent-verify-20260916`；`entry-20260917` 对应无人工交付屏障的 `liuying-agent-entry-20260917`。两者 Worker/D1、输出目录、私密会话与一次性标记均独立，不重用旧 spike 或正式 `liuying-agent`。所有模型传输为内存合成替身，正式认证、模板/BPE、Loop、任务及账本仍实际执行。

## 边界

- 单次最多 30 分钟；D1 原子计数最多 100 个持有效外层令牌的 HTTP 请求、20 个不同接单键。客户端另外限制全部尝试不超过 100 次，包含无效令牌反例。
- 实际清单只有 10 个合成任务、两个合成 owner。清单外正文不进入正式服务，不能使用本工具处理真实跑步资料。
- 本地私密会话只在已忽略的 `.generated/cloud-verify/` 或 `.generated/cloud-entry-20260917/`；不打印、不提交，不复制进手机。云上只有外层令牌摘要及设备凭据摘要。
- 创建后所有 D1 写入都核对 `created.json` 的不可变 UUID；不删除资源、不改套餐、不扩大 OAuth 权限。
- `open`、`runner` 均一次性标记，不自动重开、刷新窗口或重跑。失败保留证据；排查先只读，不绕过标记。
- 收尾依次尝试关闭 workers.dev/preview、D1 到期归零、关闭态部署，再独立核验。任何一种失败不跳过其他措施。

## 本地检查

在 `agent-server` 中运行：

```powershell
$env:LIUYING_VERIFY_PROFILE = 'entry-20260917'
node --experimental-strip-types --test tools/cloud-verify/*.test.mjs
corepack pnpm exec vitest run --config tools/cloud-verify/vitest.config.mjs
```

正式回归另见 `pnpm check`、`test:runtime`、`test:disconnect`、`test:restart` 和 Python 参考测试。固定工具版本不因验收自动升级。

## 云步骤（不能无授权照抄重跑）

`control.mjs prepare` 生成私密会话与关闭态配置；`reference.mjs` 本地构造真实协议并由独立 Python 参考生成逐轮摘要/计数。两者只生成一次，不覆盖已有证据。

用户确认范围且本地检查通过后，才依次执行 `control.mjs create`、`seed`、`open`、`runner.mjs`。runner 无论成功或失败均调用 `closeCloud`；必要时单独 `control.mjs close` 再核验 `state`。所有命令使用 `node --experimental-strip-types`。

实际操作命令（包括参考生成及离线报告）必须显式设置 `LIUYING_VERIFY_PROFILE` 为上述枚举值，缺失或拼错即拒绝；不要清除一次性文件来重开已结束的云窗口。各次工具都核对相同目标、创建凭证和证据目录。

`evidence.mjs` 只分析本地脱敏 tail 与独立参考，不发网。数据库证据只查询任务元数据、账本与收据，不导出正文/凭据。计数按用例、请求键、轮次、正文 SHA-256 与参考数量对应，不能只按 hash 混合冷/热任务。

## 结论限制

CPU 来自平台实际入口/DO invocation，包含验收哈希、D1 门禁与观察写入，不是纯 tokenizer CPU。缺失 CPU 不填零，不用墙钟时间替代。云端只比对同正文计数，没有输出完整 token IDs；独立参考中 IDs 摘要不代表云 IDs 已对照。

断流用例由测试入口等待合成 transport 进入后，保持 NDJSON 响应未完成；独立 HTTPS 客户端确认 `response.complete=false` 后销毁 socket，等待 socket `close`，再用新连接按原键恢复。取消/撤回也使用有界派发屏障。这是实际 socket 中断，但不是手机物理断网或自然网络故障。

以上屏障只适用于 `barrier-20260916`。`entry-20260917` 在源码中固定关闭屏障，不从 HTTP/env 选择：不克隆/读取正式响应、不轮询、不延迟回执、不封装 NDJSON，直接返回原 Response。case 5 改为普通 JSON 回执与按原键回查，本轮明确不测试断流；取消/撤回由客户端独立 D1 只读观察派发后再发送控制，不增加 Worker 轮询。两个模拟等待为 15 秒，真实任务仍受原 30 秒截止。

新 profile 的 `entryWithin10ms` 要求完整入口覆盖、全部入口 CPU/wall 有效、outcome=ok 且 CPU≤10ms。它与 `evidencePassed`（功能及证据完整）分开记录；存在超限样本就保留并判性能未通过，不重跑刷结果。新报告还验证 profile、target、逐条 scriptName 和本轮时间窗口，不能复制旧 tail 冒充新结果。即便通过，也仅证明本轮有限样本，不保证长期负载。

峰值内存仍标记 `not-measured`。不把未见内存超限、bundle 大小、模拟 usage 或用户 Free 确认推导成精确内存、真实模型费用或全 App 每月 $2 硬封顶证明。
