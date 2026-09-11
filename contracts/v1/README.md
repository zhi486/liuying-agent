# Bridge v1 合同样本

本目录是 React 与 Kotlin 共用的 Bridge v1 线协议基准。

- `fixtures/valid` 中的每个 JSON 都必须同时通过 TypeScript/Zod 与 Kotlin Serialization 校验。
- `fixtures/invalid` 中的每个 JSON 都必须被两端拒绝。
- 信封 ID 使用小写 canonical UUID v4，时间使用带时区的 ISO 8601。
- 合同只允许 `contractVersion: 1`，所有对象都拒绝未知字段。
- 样本不得包含 API key、传感器原始隐私数据或完整历史记录。

## 心率警戒偏好

同包 Web／Native 保持版本 1，并在 ready 能力清单中声明 `heart-rate-alerts.v1`。

- 快照 `settings.hrAlertThresholdBpm` 为 null 或 80–240 的整数；null 表示沿用当前最大心率的 Z5 最低门槛，不代表个体医学评估。
- `settings.hrAlertRepeatIntervalSec` 为 30–300 的整数，旧偏好缺省 120 秒，仅针对高心率重复提醒。
- `settings.update` 可选择更新任一字段；阈值缺失表示不修改，显式 null 表示清空恢复自动。重复间隔不允许 null。非法输入拒绝，不截断。
- 两项设置在新跑步开始时冻结；运行中或暂停时保存仅影响下一次跑步。语音总开关的即时行为不变。

## 独立数据质量

ready 能力清单按顺序为 `snapshot.v1`、`history.pagination`、`settings.v1`、`heart-rate-alerts.v1`、`data-quality.v1`、`deepseek-companion.v1`；原生与同包 Web 同步更新。

- 根 `dataQuality` 是必填严格对象：`gps` 为 `waiting | fresh | stale`；`heartRate` 为 `not_available | waiting | fresh | stale`。两端均拒绝缺失字段、未知枚举及额外字段。
- `waiting` 表示当前会话尚未取得有效样本，有效的零米定位属于已取得样本。曾取得样本后，接收时间满 5 秒为 `stale`。无手环能力时心率为 `not_available`。
- GPS 停更时不增加距离并清空当前配速，HR 停更时清空心率和区间。暂停时 GPS 保留暂停时的观察状态，HR 仍依据现有暂停心率来源更新。
- 新跑和进程恢复重新建立观察基线；恢复的历史距离不能证明 GPS 当前新鲜。
- 数据质量不改变 `wearable.state`，也不覆盖 `safetyState`。只有独立真实 BLE 故障观察才能提交手环中断语音。

## DeepSeek 私密配置

- 必填布尔 `settings.deepseekApiKeyConfigured` 仅表示本地有凭据，不表示认证或连接成功；旧 `llmApiKeyConfigured` 不能用于此判断。
- `settings.configureDeepseek` 的 body 必须为 `{}`，只请求 Activity 打开原生遮罩输入框；任何密钥字段或额外字段均拒绝，结果和快照不返回密钥。
- `settings.llmEnabled` 使用独立持久化键 `deepseek_companion_enabled`，默认关闭，不继承旧 `llm_enabled`。未配置不可开启；缺失凭据的旧 true 安全清为关闭，避免后续填写导致意外启用。
- 保存不自动开启、检测或试听；运行／暂停／恢复期间不能配置，配置窗口与开跑互斥。更换已配置密钥保留新开关，关闭开关立即取消旧准备。
