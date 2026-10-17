# Bridge v1 合同样本

本目录是 React 与 Kotlin 共用的 Bridge v1 线协议基准。

2026-09-24：云端结果和手机 `cloud.list` 单项新增可选 `cost`。六字段严格校验：`currency: USD`、非负安全整数 `inputTokens/outputTokens`、布尔 `usageComplete`、`rateStatus: current|stale|reference`、`estimatedMicros`（完整时非负安全整数，未知时只能为 null）。旧无费用结果仍可读取；费用为结果保存时快照，不是供应商账单。撤回时隐藏费用，但不删除账本。旧 APK 拒绝未知结果字段，正式发布必须先更新手机端。月预算超出不关闭功能。

- `fixtures/valid` 中的每个 JSON 都必须同时通过 TypeScript/Zod 与 Kotlin Serialization 校验。
- `fixtures/invalid` 中的每个 JSON 都必须被两端拒绝。
- 信封 ID 使用小写 canonical UUID v4，时间使用带时区的 ISO 8601。
- 合同只允许 `contractVersion: 1`，所有对象都拒绝未知字段。
- 样本不得包含 API key、传感器原始隐私数据或完整历史记录。

## 跑后总结只读详情

- `history.detail` 请求体严格为 `{ "sessionId": "记录标识" }`；标识非空、最长 128 字符、无首尾空白。
- 成功 data 为 `commandId`、`record`（与历史列表记录字段一致）和 `splits`。分段包含 `index`、`endDistanceMeters`（累计公里点）、`durationSeconds`、`averagePaceSecPerKm`、可空 `averageHeartRate`，均按段号排序。
- 只查询已完成记录，不返回坐标或连续传感器序列，不改变任何运动状态。缺失值为 null、缺失分段为空数组，不从概要反推。
- 同包增加可选命令，不改变 ready 的既有能力元组；旧原生拒绝命令时详情页提示更新，不影响历史分页。

## 心率警戒偏好

同包 Web／Native 保持版本 1，并在 ready 能力清单中声明 `heart-rate-alerts.v1`。

- 快照 `settings.hrAlertThresholdBpm` 为 null 或 80–240 的整数；null 表示沿用当前最大心率的 Z5 最低门槛，不代表个体医学评估。
- `settings.hrAlertRepeatIntervalSec` 为 30–300 的整数，旧偏好缺省 120 秒，仅针对高心率重复提醒。
- `settings.update` 可选择更新任一字段；阈值缺失表示不修改，显式 null 表示清空恢复自动。重复间隔不允许 null。非法输入拒绝，不截断。
- 两项设置在新跑步开始时冻结；运行中或暂停时保存仅影响下一次跑步。语音总开关的即时行为不变。

## 独立数据质量

ready 能力清单按顺序为 `snapshot.v1`、`history.pagination`、`settings.v1`、`heart-rate-alerts.v1`、`data-quality.v1`、`deepseek-companion.v1`、`agent.tasks.v1`、`memory.v1`、`agent.cloud.tasks.v1`；原生与同包 Web 同步更新。

- 根 `dataQuality` 是必填严格对象：`gps` 为 `waiting | fresh | stale`；`heartRate` 为 `not_available | waiting | fresh | stale`。两端均拒绝缺失字段、未知枚举及额外字段。
- `waiting` 表示当前会话尚未取得有效样本，有效的零米定位属于已取得样本。曾取得样本后，接收时间满 5 秒为 `stale`。无手环能力时心率为 `not_available`。
- GPS 停更时不增加距离并清空当前配速，HR 停更时清空心率和区间。暂停时 GPS 保留暂停时的观察状态，HR 仍依据现有暂停心率来源更新。
- 新跑和进程恢复重新建立观察基线；恢复的历史距离不能证明 GPS 当前新鲜。
- 数据质量不改变 `wearable.state`，也不覆盖 `safetyState`。只有独立真实 BLE 故障观察才能提交手环中断语音。

## 五秒曲线分页

- 新增只读命令 `history.series`，body 严格为 `{ sessionId, offset, limit }`。标识非空、无首尾空白、最多 128 字符；offset 为非负整数，limit 为 1–300 整数。
- 仅已完成记录可读取。返回 `{ commandId, sessionId, offset, totalItems, samples }`；不包含路线或配置。每页最多 300 点，避免长跑序列超过 256 KiB 消息上限。
- 每点包含 `startElapsedSeconds`、`endElapsedSeconds`、`segmentId`、`averagePaceSecPerKm`、`averageHeartRate`、`paceValidSeconds`、`heartRateValidSeconds`。窗口为 1–5 秒活动时间，均值可为空；覆盖秒数不超过窗口时长。有配速时必须完整覆盖；心率可部分覆盖。
- 前端校验同页及跨页时间不重叠、段号不倒退、总数稳定、页号连续、窗口不超出记录用时。读取失败不呈现半条曲线；旧记录返回空数组，不用分段数据补造。
- 连线要求两个相邻窗口均完整覆盖、同段且时间相接；区间分布按有效秒数计算。沿用同包更新与现有严格 ready 能力清单，不改变语音和安全规则。

## DeepSeek 私密配置

- 必填布尔 `settings.deepseekApiKeyConfigured` 仅表示本地有凭据，不表示认证或连接成功；旧 `llmApiKeyConfigured` 不能用于此判断。
- `settings.configureDeepseek` 的 body 必须为 `{}`，只请求 Activity 打开原生遮罩输入框；任何密钥字段或额外字段均拒绝，结果和快照不返回密钥。
- `settings.llmEnabled` 使用独立持久化键 `deepseek_companion_enabled`，默认关闭，不继承旧 `llm_enabled`。未配置不可开启；缺失凭据的旧 true 安全清为关闭，避免后续填写导致意外启用。
- 保存不自动开启、检测或试听；运行／暂停／恢复期间不能配置，配置窗口与开跑互斥。更换已配置密钥保留新开关，关闭开关立即取消旧准备。

## 手机精选知识选择（2026-09-23）

- 同包在线命令的 `cloud.prepare` 增加可选 `knowledge: { version: "curated_knowledge_v1", library: "health" | "firefly", allowSpoilers: boolean }`。仅接受固定库，健康库拒绝剧透 true；拒绝 URL、资料正文等额外字段。
- 准备响应原样回显选择，前端比对后展示；不匹配则丢弃令牌。确认前不得改选，暂不发送后可重选。每次成功发送后重置剧透许可；页面重载默认不选知识库。仅保存仍只保存问题文字。
- 新知识任务使用 `selected_knowledge_v1`，可同时有独立记忆引用。Room v5→v6 只新增 `knowledgeJson TEXT DEFAULT NULL`；旧任务不补知识、不改变旧授权。选择纳入持久快照和服务端请求指纹，重试不得换库或许可。
- completed 回答可选结构化 `sources` 数组（最多3项），每项为 `citationId/title/url/publishedAt/checkedAtMillis`，无命中为空数组；原生与网页都严格校验。允许来源主机仅 WHO 与星穹铁道官网，地址只显示可选中复制，不开放 WebView 外站导航。正文始终纯文本。
- `cloud.list` 条目可选 `knowledge` 与 `sources`，来源和答案同步落盘、重载保留、撤回同时隐藏。展示说明为本次检索来源，不代表模型逐条使用或逐句有据。
- 新后端必须先部署再安装新版真机；旧服务可能拒绝 knowledge 字段，客户端不能静默删除字段重发。代码与本地测试不代表生产部署/模型验收。

## 本机 Agent 任务（M1B）

- `agent.submit` 严格 body 为 `{ clientRequestId, prompt }`，前者 canonical UUIDv4，后者 1—2000 字符且非空白。原生修剪首尾空白后持久去重；同键异文拒绝。
- `agent.list` 严格 body 为 `{ offset, limit }`，offset 非负 Int，limit 1—50；返回 `{ commandId, offset, limit, totalItems, items }`，按创建时间倒序、ID 倒序。
- `agent.detail/cancel` 严格 body 为 `{ taskId }`，canonical UUIDv4；与 submit 一样返回 `{ commandId, task, messages, actions }`。未知或越权 ID 统一 RESOURCE_UNAVAILABLE。
- task 为 `{ id, title, status, reason, createdAtMillis, updatedAtMillis }`，title 最长80；M1B 只接受 blocked/cloud_not_connected 或 cancelled/null。消息仅1条用户原文，行动为 saved_locally 和可选 cancelled；按 sequence 排序。详情不包含 ownerId 或幂等键。
- accepted 表示本机事务完成，不表示模型执行。任务不会因刷新、重启或以后云端接通自动上传/执行。取消幂等、不删除消息、不播放语音。
- 新能力与 APK 内 Web 同步升级；本批不提供原始运动数据、云鉴权或付费接口。

## 可管理的本机长期记忆（M3 首批）

- `memory.create` body 为 `{ memoryId, content }`；`memory.update` 为 `{ memoryId, version, content }`；`memory.delete` 为 `{ memoryId, version, confirmed: true }`；`memory.list` 为 `{ offset, limit }`。均严格拒绝额外字段。
- ID 为小写 UUIDv4；正文 1—500 个 UTF-16 单元，拒绝孤立代理字符；两端按 JavaScript trim 空白集合修剪并拒绝空文。版本是 1—2147483646 整数，分页 offset 为非负 Int、limit 为 1—50。
- 创建／编辑返回 `{ commandId, memory }`；memory 为 `{ id, content, source: "user_explicit", createdAtMillis, updatedAtMillis, version }`。来源、归属与时间由原生决定；更新使用原版本，成功版本加一。
- 列表返回 `{ commandId, offset, limit, totalItems, items }`，仅含活动记忆，按更新时间倒序、ID 倒序。删除返回 `{ commandId, memoryId, deleted: true }`。
- 同键创建、同结果编辑及删除支持重试。过时版本拒绝为 SESSION_CONFLICT；缺失／越权 ID 不提供正文。删除清空正文，仅留删除标记，旧请求不能复活；不是 SQLite/WAL 历史字节的安全擦除承诺。
- 使用 noBackupFilesDir 下 AgentDatabase v4 增表迁移，无破坏性回退，不自动备份；数据库未加密，不适合密钥。此批只管理用户显式输入，不自动提取、不上传、不检索注入 Agent 回答，不修改跑步和语音行为。

### 本次记忆选择与预览（后续批）

- 同包 `memory.v1` 增加 `memory.preview`，body 严格为 `{ memoryRefs: [{ id, version }] }`，1—5 个唯一 ID，版本为 1—2147483647。拒绝网页传正文、owner、approved 或其他字段。
- 原生在同一 Room 事务中按选择顺序读取；任一不存在、非当前归属、已删除、非手动来源或版本不符，整次拒绝为 SESSION_CONFLICT，不回传部分内容。此操作不写数据。
- 返回 `{ commandId, scope: "local_preview", items }`；items 沿用记忆记录。首页同时核对数量、顺序、ID、版本及正文；刷新、换页、收起或断桥清空选择，问题变化废弃预览，迟到回包不恢复它。
- 预览不是上传授权，不创建云任务或长效令牌。“仅保存”仍只带输入文字；在线执行另走下方单次授权协议，不可把缓存预览直接当 HTTP 正文发送。
- 云端可选 `memoryContext` 协议与 Agent Loop 接入见 `agent-server/README.md`；原生 `CloudTaskIntent.prompt_only_v1` 含义保持不变，不能借此授权发送记忆。

## 首页单次语音输入（2026-09-25，流式本地实现待发布）

- `voice.begin {sessionId, approved:true}`：先展示本次音频实时经配置的云端交由阿里云 Fun-ASR 识别的说明，再请求 Android 麦克风权限。`sessionId` 为小写 UUIDv4。`voice.status/finish/cancel {sessionId}` 不接受额外字段、音频、地址或密钥。
- 同包 APK/Web 升级；返回 `{commandId,sessionId,state,text,preview,error}`。状态为 `permission/connecting/recording/transcribing/completed/failed/idle`；仅 `completed` 的 `text` 带最终正文，其余为空。`preview` 是可修订的中间文字，独立显示，不追加草稿；取消、失败、完成后清空。旧会话和迟到权限回调不能影响新会话。
- 最长30秒、16kHz/单声道/16bit PCM，仅在原生内存中录制。退后台、页面重载、取消会停止录音或请求并清空本机会话。已经发送至供应商的音频无法通过取消撤回。
- 原生通过已登记的 HTTPS origin 将 `/v1/agent/voice/stream` 升级为 WebSocket，携带设备认证与 `X-Voice-Consent: fun-asr-v1`；供应商确认任务就绪后才开麦分帧发送。Bearer 不进入 Web，禁止重定向和自动重试。识别结果最多2000字符，追加至草稿，超出总长度则保留原草稿；不自动保存任务或执行 Agent。旧百度入口保留兼容，不作为新手机的自动回退。
- 录音期间不能编辑、提交草稿或切换在线配置。该功能不负责 TTS、跑步播报、常驻监听、蓝牙麦克风路由或离线识别。ASR 费用暂未接入现有模型 token 账本，不显示为零。

## 手机在线任务与单次授权（S5，命令详情）

- `cloud.configure {}` 仅请求原生私密窗口。地址、ownerId、deviceId 和设备 Bearer 不由 Web 设置；Bearer 仅在 Keystore 加密文件中，配置排除备份。保存不检测、不授权、不调用模型；同值保存幂等，新代次不换绑旧任务。
- `cloud.list {}` 只查询本机状态，返回 `commandId/configured/configuring/origin/items`。每条含 `requestId/prompt/busy/attempted/status/resultState/reason/answer/pendingControl/confirmedControl/error/memoryRefs`；不返回凭据或复制的记忆正文。最近至多 10 条，并按完整记录 JSON 字节预算限制到 220000 字节以内，不截断回答。
- `cloud.prepare {prompt,memoryRefs}` 只接受 0—5 个唯一 ID/版本；原生核对并返回 `commandId/token/origin/prompt/memories`，每条 memory 仅含 `id/version/content`。准备不落在线意图、不发网；token 绑定完整原生身份和输入，最长两分钟，页面重载或原生退到后台作废。
- `cloud.confirm {token,approved:true}` 消费本次 token，重新核对身份、版本及正文后，持久化独立在线意图并启动有界异步操作；`cloud.discard {token}` 只废弃准备。发送前记忆复核与唯一 POST 许可在同一 Room 事务内完成。旧本机任务绝不转换成上传队列。
- 2026-10-17 增加普通文字持续授权：`cloud.list` 可附带 `chatAuthorized/chatConsentContext`；旧原生未返回时视为关闭。`cloud.chatConsent {identityToken,enabled}` 仅写本机、排除备份的原子授权记录，校验当前身份指纹（含服务、账号、设备、凭据代际），更换配置失效。默认关闭，设置页主动勾选后开启，可直接关闭；不授予录音、记忆写入或插件权限。
- `cloud.send {token}` 在持续授权有效时消费已准备的普通文字快照，仍复核身份、两分钟有效期和当前会话上下文；只允许 `prompt_only_v1` 且有会话 ID，不允许选中记忆、知识库或跑步摘要。额外数据仍走 `cloud.confirm` 单次确认；原生拒绝后不自动重试或降级确认。关闭授权会废弃尚未消费的预览，不撤回已受理任务，网络恢复仍只查询原任务。
- 新 `selected_memories_v1` 意图只保存记忆 ID/版本；HTTP 发网时生成独立 `memoryContext`。记忆正文不另存快照；已生成的回答仍可能引用它。Room v4→v5 只新增可空引用字段，不改旧授权含义。
- `cloud.recover/cancel/withdraw {requestId}` 以原生固定身份处理；恢复永不补发 POST。控制请求先落盘，撤回先隐藏本机回答，`confirmedControl` 才代表云端确认。正在发网时新增控制请求不丢弃，最多合并一次显式后续恢复。
- Bridge 操作快速返回本机状态；原生单次工作最长两分钟，正常执行中有界查回执，网络失败即停止。Web 两秒轮询仅 `cloud.list`，刷新/重载不自动联系云端。回答以纯文本呈现，并列出授权的记忆版本，不声称模型采用每条。
- 当前为手机端代码接线与本地假云验收；真实设备配置、生产部署及付费模型验收须单独执行，不能用模拟结果替代。
