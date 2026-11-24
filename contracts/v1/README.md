# Bridge v1 合同样本

本目录是 React 与 Kotlin 共用的 Bridge v1 线协议基准。

## 本机任务增量通知（2026-10-19，本地未发布）

- 握手有效期间，原生可以推送 `event`，payload 严格为 `{ "name": "cloud.changed" }`，不携带聊天正文、任务标识、凭据或工具参数。页面收到后只读取 `cloud.list`；通知不能授权发送、恢复查询或执行工具。
- `cloud.list` 的运行中条目可带可选 `draftAnswer`（1～4,000 UTF-16 单位）。仅 `busy=true`、`status=queued/running`、无正式答案且未撤回时允许；它是暂存片段，不是已完成回答，不写入任务回执或下一轮会话上下文。
- 原生按任务与模型轮次处理有序事件。工具轮／丢弃轮清空片段，任务终止后从既有回执读取正式答案。后台、会话切换、配置／授权变化及控制操作关闭订阅并清除显示；页面隐藏或读取失败也丢弃当前草稿。恢复网络不自动重发问题或重连流，明确恢复只查询同一任务，可用当前进程保存的游标续读。
- 旧原生不发通知、条目不含草稿时，保留原有本机状态刷新方式。新增通知不改变 ready 能力元组；同包前端与原生一起更新。是否实际支持任务 SSE 由云端版本决定，不能以本地协议存在宣称真机流式已验收。

## MCP 设置命令（2026-10-18，本地未发布）

- `mcp.status {}` 只读当前云身份下的本机发现记录，返回 `configured / identityToken / operations`；没有配置时 token 为 null。操作只含原请求、服务、端点、上次状态与固定原因，没有请求正文、会话 ID 或密钥。未知结果不推断为未执行。
- 其余命令均要求本机返回的 `identityToken`；身份变更拒绝旧页面。`mcp.list { identityToken, after }` 查询云端单页，after 为 null 或 UUID；每页最多 20 个服务和一个游标。`mcp.detail { identityToken, id }` 查询完整目录与权限。列表及详情合并本机 `configured / hasCredential / context / endpointMatches`，不返回密钥、密文或配置 generation。
- `mcp.register { identityToken, endpoint }` 仅登记规范公开 HTTPS 地址，不允许查询参数、片段、用户信息或非 443 端口；不连接插件、不自动保存匿名配置、不启用工具。
- `mcp.configure { identityToken, id, revision }` 读取已认证云详情后打开原生私密窗口。插件 Bearer 只能在该窗口填写，默认不回填；空输入不代表匿名授权，匿名需明确选择。保存前再次核对云版本、端点、本机配置 context 和窗口身份，保存本身不启用工具。窗口取消、后台或文档重建清除输入。`mcp.revokePrivate { identityToken, id, context }` 只撤销手机配置，不冒充云端停用或撤销已经发出的外部操作。
- `mcp.discover { identityToken, id, revision, confirmed: true }` 需要设置页本次明确勾选连接与上传工具目录的范围；原生重读云版本及本机端点后调用既有发现同步器。Bridge 管理操作在 8 秒内收敛，预留既有 10 秒命令超时的返回余量；超时保留原操作，不续租、不自动重试 start/take/reply。`mcp.recover { identityToken, requestId }` 仅查询已有原标识；失败后可自动只读本机 status 以展示恢复入口，不能自动查询云端或补发发现。
- `mcp.approve { identityToken, id, revision, selection, confirmed: true }` 只接收 1—64 个唯一 `{ name, mode: read|confirm }`，替换当前服务权限。界面默认全部不授权、审核勾选默认关闭，改变选择清除勾选；远端说明／annotations 不自动授予权限。`mcp.disable { identityToken, id, revision }` 清空全部工具批准。云端提交使用已审核版本条件更新，手机核对返回工具、模式及新版本，不能把错误成功回执当批准完成。
- `mcp.endpoint { identityToken, id, revision, context, endpoint, confirmed: true }` 修改地址；`mcp.remove { identityToken, id, revision, context, confirmed: true }` 移除登记。设置页均默认不确认，地址再编辑清除勾选。原生先读取当前云版本，匹配后按 context 撤销这台手机的旧私密配置、关闭同服务旧窗口，再发送仅有云版本／新地址的固定 POST；本机 context 和 confirmed 不上传。相同地址不提交，不撤销；旧云版本或本机代次拒绝。即使提交失败、取消或回执丢失，也不恢复旧密钥；不能把未知结果宣称为移除完成。地址成功回执必须为新版本、正确新地址、关闭状态、空目录和空权限；移除只接受严格 `{ removed: true }`。移除不删除本机发现记录或其他服务配置，原记录仍只能查询。
- 设置命令只管理插件，不直接执行聊天工具。第二十九批已另接下述本次选择和原生执行确认，本地能力不代表线上可用。旧原生不认识这些命令时明确报错，不能把缺失能力当空列表成功；全部回执仍由宿主补充 `commandId`。

合法样本 `command.mcp-*.json` 与七个非法 MCP 样本由 TypeScript／Kotlin 共用；`contracts/mcp-phone-v1/settings.json` 仅是设置页合成状态，不含真实服务或秘密。上述本地能力不代表已部署、已安装手机或已批准实际插件连接。

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

- 同包在线命令的 `cloud.prepare` 增加可选 `knowledge: { version: "curated_knowledge_v1", library: "health" | "firefly" | "both", allowSpoilers: boolean }`。仅接受固定库，健康库拒绝剧透 true；拒绝 URL、资料正文等额外字段。2026-10-29 起默认双库且不允许剧透，选择移至设置并跨会话保留；双库无匹配时允许普通聊天，来源仍仅包含实际检索结果。新版服务须先支持 `both`，客户端不得静默降级重发。
- 准备响应原样回显选择，前端比对后展示；不匹配则丢弃令牌。确认前不得改选，暂不发送后可重选。每次成功发送后重置剧透许可；页面重载默认不选知识库。仅保存仍只保存问题文字。
- 新知识任务使用 `selected_knowledge_v1`，可同时有独立记忆引用。Room v5→v6 只新增 `knowledgeJson TEXT DEFAULT NULL`；旧任务不补知识、不改变旧授权。选择纳入持久快照和服务端请求指纹，重试不得换库或许可。
- completed 回答可选结构化 `sources` 数组（最多3项），每项为 `citationId/title/url/publishedAt/checkedAtMillis`，无命中为空数组；原生与网页都严格校验。允许来源主机仅 WHO 与星穹铁道官网，地址只显示可选中复制，不开放 WebView 外站导航。正文始终纯文本。
- `cloud.list` 条目可选 `knowledge` 与 `sources`，来源和答案同步落盘、重载保留、撤回同时隐藏。展示说明为本次检索来源，不代表模型逐条使用或逐句有据。
- 2026-10-30 本地服务端改为按需 `knowledge.search { query }`：闲聊不强制检索，库与剧透范围仍绑定本次快照；多次查询共享三片段上限，来源仅来自实际检索结果。内部检索不新增客户端动作枚举，保持旧结果解析兼容；无匹配返回空观察，不伪造来源。尚未部署或真实模型验收。
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

以上记忆接口后续扩展见本文末尾“自动记忆授权与版本管理”，不再以 M3 首批的“不用于回答”描述为当前行为。

## 聊天本次插件选择（2026-10-18，第二十九批本地未发布）

- `cloud.prepare` 可附 `mcpServices: [serviceId]`，1—8 个唯一小写 UUIDv4，必须同时有当前 `conversationId`。只接 ID，不接 webpage 自报的 revision、generation、端点、工具或 approved；无插件时省略而非空数组。合同样例 `command.cloud-prepare-mcp.json` 与非法 `cloud-mcp-forged-authority.json` 由 TS／Kotlin 共用。
- 原生按当前身份及前台代际读取已启用云目录与匹配本机配置，冻结完整快照。返回可选 `mcpServices: [{ id, endpoint, tools: [{ name, mode: read|confirm }] }]`，最多八服务、总计六十四工具，不返回密钥／本机代次。只读取目录，不访问插件或模型、不持久化任务。
- `cloud.confirm` 重读并比较版本、批准和本机代次，变化拒绝旧 token；成功后仅持久保存服务版本／代次引用，由既有首次发送链路处理。`cloud.send` 不允许非空插件范围，普通文字持续授权不扩大成插件许可。界面刷新、换身份／会话、成功提交清空本次选择，旧异步结果不恢复它。
- confirm 模式由原生安全窗口展示实际服务、工具及完整参数；Web 没有返回批准的 Bridge 命令。当前窗口对象只能回答一次，取消、过期、后台或文档重载均不批准。插件密钥只在手机原生网络边界读取；实际参数／结果经云端参与模型处理。恢复仍只查原任务，不重新执行插件。
- 本地单测、合成浏览器及隔离模拟器已验证，原生与实际后端联合、正式部署和真机仍待验收，不能把此节当作真实插件调用授权。

## 首页单次语音输入（2026-09-25，流式本地实现待发布）

- `voice.begin {sessionId, approved:true}`：先展示本次音频实时经配置的云端交由阿里云 Fun-ASR 识别的说明，再请求 Android 麦克风权限。`sessionId` 为小写 UUIDv4。`voice.status/finish/cancel {sessionId}` 不接受额外字段、音频、地址或密钥。
- 同包 APK/Web 升级；返回 `{commandId,sessionId,state,text,preview,error}`。状态为 `permission/connecting/recording/transcribing/completed/failed/idle`；仅 `completed` 的 `text` 带最终正文，其余为空。`preview` 是可修订的中间文字，独立显示，不追加草稿；取消、失败、完成后清空。旧会话和迟到权限回调不能影响新会话。
- 最长30秒、16kHz/单声道/16bit PCM，仅在原生内存中录制。退后台、页面重载、取消会停止录音或请求并清空本机会话。已经发送至供应商的音频无法通过取消撤回。
- 原生通过已登记的 HTTPS origin 将 `/v1/agent/voice/stream` 升级为 WebSocket，携带设备认证与 `X-Voice-Consent: fun-asr-v1`；供应商确认任务就绪后才开麦分帧发送。Bearer 不进入 Web，禁止重定向和自动重试。识别结果最多2000字符，追加至草稿，超出总长度则保留原草稿；不自动保存任务或执行 Agent。旧百度入口保留兼容，不作为新手机的自动回退。
- 录音期间不能编辑、提交草稿或切换在线配置。该功能不负责 TTS、跑步播报、常驻监听、蓝牙麦克风路由或离线识别。ASR 费用暂未接入现有模型 token 账本，不显示为零。

## 手机在线任务与单次授权（S5，命令详情）

- `cloud.configure {}` 仅请求原生私密窗口。地址、ownerId、deviceId 和设备 Bearer 不由 Web 设置；Bearer 仅在 Keystore 加密文件中，配置排除备份。保存不检测、不授权、不调用模型；同值保存幂等，新代次不换绑旧任务。
- `cloud.list {}` 只查询本机状态，返回 `commandId/configured/configuring/origin/items`。每条含 `requestId/prompt/busy/attempted/status/resultState/reason/answer/pendingControl/confirmedControl/error/memoryRefs`；不返回凭据或复制的记忆正文。最近至多 10 条，并按完整记录 JSON 字节预算限制到 220000 字节以内，不截断回答。
- `cloud.prepare {prompt,memoryRefs}` 只接受 0—5 个唯一 ID/版本；原生核对并返回 `commandId/token/origin/prompt/memories`，每条 memory 仅含 `id/version/content`。准备不落在线意图；默认不发网，附本次插件选择时仅按上节读取云目录。token 绑定完整原生身份和输入，最长两分钟，页面重载或原生退到后台作废。
- `cloud.confirm {token,approved:true}` 消费本次 token，重新核对身份、版本及正文后，持久化独立在线意图并启动有界异步操作；`cloud.discard {token}` 只废弃准备。发送前记忆复核与唯一 POST 许可在同一 Room 事务内完成。旧本机任务绝不转换成上传队列。
- 2026-10-17 增加普通文字持续授权：`cloud.list` 可附带 `chatAuthorized/chatConsentContext`；旧原生未返回时视为关闭。`cloud.chatConsent {identityToken,enabled}` 仅写本机、排除备份的原子授权记录，校验当前身份指纹（含服务、账号、设备、凭据代际），更换配置失效。2026-10-29 起尚无授权记录时默认开启；已有显式关闭保留，损坏记录或已存身份不匹配仍关闭。用户可在设置管理，不授予录音、记忆写入或插件权限。
- `cloud.send {token}` 在持续授权有效时消费已准备的普通文字快照，仍复核身份、两分钟有效期和当前会话上下文；允许 `prompt_only_v1` 或 `selected_knowledge_v1` 且有会话 ID，知识库沿用设置，不允许选中记忆、跑步摘要或 MCP 工具。私密数据与工具仍走 `cloud.confirm` 单次确认；原生拒绝后不自动重试或降级确认。关闭授权会废弃尚未消费的预览，不撤回已受理任务，网络恢复仍只查询原任务。
- 新 `selected_memories_v1` 意图只保存记忆 ID/版本；HTTP 发网时生成独立 `memoryContext`。记忆正文不另存快照；已生成的回答仍可能引用它。Room v4→v5 只新增可空引用字段，不改旧授权含义。
- `cloud.recover/cancel/withdraw {requestId}` 以原生固定身份处理；恢复永不补发 POST。控制请求先落盘，撤回先隐藏本机回答，`confirmedControl` 才代表云端确认。正在发网时新增控制请求不丢弃，最多合并一次显式后续恢复。
- Bridge 操作快速返回本机状态；原生单次工作最长两分钟，正常执行中有界查回执，网络失败即停止。Web 两秒轮询仅 `cloud.list`，刷新/重载不自动联系云端。回答以纯文本呈现，并列出授权的记忆版本，不声称模型采用每条。
- 当前为手机端代码接线与本地假云验收；真实设备配置、生产部署及付费模型验收须单独执行，不能用模拟结果替代。

## 自动记忆授权与版本管理（2026-10-17，本地实现待发布）

- `cloud.list` 条目可附带 `memorySaved:true`，仅由原生依据不可变任务绑定及本机工具保存回执生成。必须仍存在同一归属、当前有效的相同记忆版本；无回执、已修改／删除或待撤回／已撤回时省略。旧原生缺少字段不显示提示，不接受模型的动作声明或回答作为成功证明。即使最终回答失败，已经原子保存的记忆仍可提示；点击只进入 `/memories` 管理，不调用模型、不改变授权。关闭自动记忆不会删除已经保存的内容。
- 同包增加 `cloud.memoryAuthorization {}`，仅查询本机，返回 `{ commandId, configured, enabled, context }`。未配置时关闭且 context 为 null；配置后 context 为 64 位小写十六进制状态指纹，包含当前身份与授权代次，不是供应商密钥。
- `cloud.memoryConsent { identityToken, enabled }` 只修改独立、排除备份的本机授权。必须携带当前 context，enabled 严格布尔；启停生成新代次，写后返回最新状态。默认关闭，不继承聊天持续授权；界面启用前需明确勾选读写及发送范围，失败只允许显式重新读取，不自动重试。
- 开启后，模型可按需调用受原任务及原设备绑定的记忆搜索／写入；证据来自原生不可变问题，不接受网页或模型指定授权。当前自动保存仅支持单条中文显式偏好／目标声明；失败不得冒充已保存。用于回答的条目发往云端及模型供应商。关闭阻止后续调用，不删除已存内容、不能撤回已发出的资料；身份／服务／设备／凭据变化使旧授权失效。
- `memory.history { memoryId, offset, limit }` 只读当前归属的活动记忆；分页沿用 offset 非负 Int、limit 1—50。返回 `{ commandId, memoryId, currentVersion, offset, limit, totalItems, items }`，按版本倒序。每个 item 为 `{ version, content, changedAtMillis, changeSource, turnId }`；来源 manual／agent／restored／legacy，仅 agent 带非空 UUIDv4 turnId。正文沿用 500 UTF-16 单元上限，版本最大 2147483647。
- `memory.document { offset, limit }` 只读文档分页，offset 非负 Int、limit 1—20。返回 `{ commandId, generatedAtMillis, offset, limit, totalItems, items }`；每个 item 为 `{ memory, changeSource, sourceTurnId, category }`，memory 沿用当前有效记录结构。来源为 manual／agent／restored／legacy 或 null；仅 agent 可带消息标识及 preference／goal 分类，缺失来源和分类不能推测。每页从原生同一查询快照取正文与该版本来源，翻页会重新读取，不承诺跨页是同一历史时刻；界面显示本页读取时间并提供刷新。分页不截断总体记录数量，不返回已删除或历史正文，不授予上传、自动记忆或文件写入权限；此命令本身不是文件导出。
- `memory.exportBegin { exportId, confirmed:true }` 显式请求一次原生系统文件选择；`memory.exportStatus/cancel { exportId }` 只查询／取消同一操作。exportId 为小写 UUIDv4；所有命令拒绝额外字段，不能传 owner、URI、文件名或正文。返回 `{ commandId, exportId, phase, totalItems, generatedAtMillis }`，phase 为 choosing／writing／saved／cancelled／failed／interrupted；只有 saved 带非空条目数及快照时间，其余两字段为 null。重复 begin 同一当前标识只读状态，不重开窗口；未返回的旧选择器或未结束的写入阻止新操作。
- 宿主使用 [Android 系统文件选择器](https://developer.android.com/training/data-storage/shared/documents-files)，创建 text/markdown 新文档，默认“流萤长期记忆.md”，不申请广泛存储权限、不保留长期 URI 授权。URI 仅由原生结果回调提供，不进入网页／日志／云端。begin 立即返回状态，不等待用户选择而占用 Bridge 的十秒请求窗口；选择取消时不读取记忆正文。确认目标后从原生权威库读取完整有效快照，用 UTF-8 写入，关闭流成功后才能报告 saved，不拼接当前网页分页。
- 页面收起／断连尽力取消本次操作；文档重载或宿主销毁会失效当前状态并取消写入，迟到选择不读取正文或打开目标。写入已经开始时不保证原子撤销，失败／中断／进程退出可能留空文件或部分副本，不能假报未写入，也不自动删除、重做或续写。仅保存最近操作的内存状态，重启后查询拒绝，不推断成功／失败。应用不上传副本；用户选择云存储提供方时，该方可能同步。副本不随应用内编辑／删除同步，也不自动导入或成为权威库。当前已接本地宿主，实际系统选择器／提供方及真机仍待验收。
- `memory.restore { memoryId, version, targetVersion, confirmed: true }` 只接受严格当前版本 1—2147483646、目标版本 1—version-1；原生从历史重读正文，禁止网页提供替换正文。成功返回 `{ commandId, memory }`，另存为当前版本加一且来源 restored，原历史保留；过期版本冲突不会覆盖当前内容。界面先展示目标内容和新版本号，显式确认后才调用。
- 删除清空正文及全部版本历史，保留无正文标记；history／restore 均不能复活已删除记录。来源字段 user_explicit 表示用户明确提供的事实，不等于总是手工点击保存；操作来源以版本记录为准。已有记录迁移只补当前活动版本，不虚构旧历史。
- 修改、恢复或删除后，后续发送会重新核对引用版本；已失效的旧回答不再作为会话上下文来源，但本机历史消息仍保留显示，已经发出的请求不承诺撤回。上述代码和合成回归不等于 APK／设备／云端验收。
