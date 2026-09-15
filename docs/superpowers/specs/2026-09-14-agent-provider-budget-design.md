# M1C-1 模型适配与持久预算设计

状态：承接已确认的 Agent 总体设计及用户“继续下一步”，作为 M1C 的后端接入切片实施；不是完整云端上线。

## 切片与取舍

先交付独立可测试的 DeepSeek ModelPort 和 D1 BudgetPort，再由 M1C 后续应用服务连接身份、摘要授权、持久任务执行与手机。保留现有 M1A/M1B；本批不改 Android/React，不部署、不调用真实供应商、不自动上传本机任务。沿用主代理直接实现、子代理只读复审和当前工作树，不提交/推送。

使用原生 fetch 和 D1 的最小结构接口，不引入 Agent 框架或运行时依赖。预算选择实际 SQL 语句而非生产内存实现；Node 内置 SQLite 只作测试边界替身，不能冒充已完成 Cloudflare 免费层/远程事务验收。

## DeepSeek 适配

- 固定 `https://api.deepseek.com/chat/completions`、模型 `deepseek-flash`，非流式、显式关闭 thinking；禁止重定向、自动重试、任意 endpoint 和模型切换。
- 核心工具 `runs.list/runs.summary` 映射为兼容供应商名称 `runs_list/runs_summary`；assistant 工具 ID 与 tool_call_id 保持一致，观察始终是 tool 角色，不提升为 system。
- 密钥仅从可信服务构造参数注入，不读取仓库/手机秘密，不进入错误或日志。输出上限由核心传入且最多 600；请求正文最多 64 KiB、响应正文最多 128 KiB，超限取消读取。单次网络最长 30 秒，并接受核心更短的 AbortSignal。
- 输入 token 精确计数必须由可信模型匹配计数器注入，对完整消息及工具模板计数；无计数器、计数错误或超过 20,000 时不发 HTTP。官方字符比例是估算，不能替代硬上限。本批不提供未经校验的默认 tokenizer；配置生产计数器仍是后续上线门禁。
- HTTP 异常不返回供应商正文。只有合法完成原因、单一 assistant choice、合法白名单工具/参数才归一化。有效 usage 配非法决策仍返回 usage，让核心记账后拒绝；用量缺失/不一致不补零。内部 reasoning 不进入结果。
- 延续现有核心保守语义：进入 ModelPort 后的预检失败仍记 uncertain，不擅自退款；这可能占用额度，但不会制造免费调用。精细鉴权/限流 UI 错误映射属于后续应用协议。

## 持久费用账本

- D1 表为 `agent_budget_accounts` 与 `agent_budget_reservations`；ownerId 来自已认证的可信服务，不接受模型输入。未预置账户时拒绝预留，不提供自动匿名开户。
- 账户月上限为整数微美元，允许范围 1—1,800,000；超过门槛停止，不自动加额。该额度目前仅约束本端口，不代表全 App $2 封顶。
- 单条条件 INSERT 原子检查本 UTC 月已收费估算＋未结清预留与上限；唯一 `(owner_id, request_id)` 永久拒绝重复调用，包括释放后重试。不同 owner 不共享请求或余额。
- 每笔绑定 taskId、月份、模型、费率版本、输入/输出单价和预留金额。费率由可信构造参数传入，必须是已核实且有效期不超过 24 小时的正整数费率；过期、未知和账户 halted 都拒绝。
- 金额按 BigInt 乘法后向上取整到微美元，不使用浮点美元。结算依照本笔费率快照，不能用事后涨跌的费率改旧账。当前 Usage 无缓存/峰谷拆分，因此按已核实的保守上限估算，不声称等于供应商账单。
- `reserved → uncertain` 保留预留；只有 reserved 的可信 not_dispatched 可释放。已结算费用不会被迟到 uncertain/not_dispatched 清零。相同 usage 重复结算无变化；冲突 usage 拒绝并保留既有费用。
- 有效 usage 可更新 reserved/uncertain/released；真实费用超过预留，或释放后又报告正费用，仍完整入账并持久熔断账户。更新费用及设置熔断在同一 D1 batch 事务，避免中间窗口再次消费。
- 旧月请求保留旧月归属；跨月不清除未知账。取消、进程崩溃或过期时间不自动退款。对账/解除熔断没有自动入口，由后续可信运维协议处理。

## 验收与剩余门禁

HTTP 在测试中使用假 transport，消息转换、响应归一化、无重试、取消、上限由真实适配代码执行；SQL 使用真实 SQLite，验证并发请求、重复键、回滚、重开恢复、所有者隔离、跨月、费用快照、迟到结算和熔断。再将真实适配器＋真实 SQL 账本＋现有 runAgent 组合进行离线完整循环。

后续仍需：生产身份与摘要授权、已验证 tokenizer、D1 远程/免费层验证、任务幂等及崩溃恢复、账户/费率私密配置、手机执行状态和真实网络小额联调。没有这些不能宣布 M1C 已完成。

## 官方依据（2026-09-14）

- [Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion)：thinking 默认开启、max_tokens、角色、finish_reason 与 usage。
- [Tool Calls](https://api-docs.deepseek.com/guides/tool_calls)：模型返回函数调用、应用执行工具并提交结果；程序仍需校验参数。
- [Token 用量](https://api-docs.deepseek.com/quick_start/token_usage)：字符比例仅估算，实际用量以 usage 为准，另提供离线 tokenizer。
- [价格](https://api-docs.deepseek.com/quick_start/pricing)：Flash 高峰未命中输入 $0.30/百万 token、输出 $1.20/百万 token；本批仅用作测试费率依据，没有永不过期的生产默认值。
- [D1 Database](https://developers.cloudflare.com/d1/worker-api/d1-database/)：batch 顺序执行并整体事务回滚；普通写操作在主库执行。

本轮 web 检索工具返回 404，已改为直接读取上述官方页面内容；未读取任何私密账户数据。
