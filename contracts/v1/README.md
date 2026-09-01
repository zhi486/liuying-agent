# Bridge v1 合同样本

本目录是 React 与 Kotlin 共用的 Bridge v1 线协议基准。

- `fixtures/valid` 中的每个 JSON 都必须同时通过 TypeScript/Zod 与 Kotlin Serialization 校验。
- `fixtures/invalid` 中的每个 JSON 都必须被两端拒绝。
- 信封 ID 使用小写 canonical UUID v4，时间使用带时区的 ISO 8601。
- 合同只允许 `contractVersion: 1`，所有对象都拒绝未知字段。
- 样本不得包含 API key、传感器原始隐私数据或完整历史记录。
