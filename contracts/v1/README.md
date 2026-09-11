# Bridge v1 合同样本

本目录是 React 与 Kotlin 共用的 Bridge v1 线协议基准。

- `fixtures/valid` 中的每个 JSON 都必须同时通过 TypeScript/Zod 与 Kotlin Serialization 校验。
- `fixtures/invalid` 中的每个 JSON 都必须被两端拒绝。
- 信封 ID 使用小写 canonical UUID v4，时间使用带时区的 ISO 8601。
- 合同只允许 `contractVersion: 1`，所有对象都拒绝未知字段。
- 样本不得包含 API key、传感器原始隐私数据或完整历史记录。

## 心率警戒偏好

同包 Web／Native 保持版本 1，并在 ready 能力清单末尾声明 `heart-rate-alerts.v1`。

- 快照 `settings.hrAlertThresholdBpm` 为 null 或 80–240 的整数；null 表示沿用当前最大心率的 Z5 最低门槛，不代表个体医学评估。
- `settings.hrAlertRepeatIntervalSec` 为 30–300 的整数，旧偏好缺省 120 秒，仅针对高心率重复提醒。
- `settings.update` 可选择更新任一字段；阈值缺失表示不修改，显式 null 表示清空恢复自动。重复间隔不允许 null。非法输入拒绝，不截断。
- 两项设置在新跑步开始时冻结；运行中或暂停时保存仅影响下一次跑步。语音总开关的即时行为不变。
