# M1B 原生任务库与首页接口

状态：执行用户已确认的 Agent 总体计划；2026-09-14 用户明确要求推进原生任务库与首页接口。

## 范围与选择

新增独立原生 Room 任务库和 `agent.submit/list/detail/cancel`，首页保存、列表、详情与取消连接真实本机记录。本批不接云服务、不产生模型回复、不读跑步摘要、不上传文字、不发语音。首页保留既有 C 布局与四 Tab，不再重做设计。

采用独立数据库，而非把聊天表并入运动数据库；采用查询接口与操作后刷新，而非新增事件推送或定时轮询。这使本批可独立验证，又不改变运动快照和核心控制链路。数据库放在应用 `noBackupFilesDir`，不让健康相关自由文本进入系统自动备份；本批无加密存储承诺。

## 存储与语义

- `agent_tasks`：ID、可信原生 ownerId、clientRequestId、标题、状态、原因、创建/更新时间。唯一索引 `(ownerId, clientRequestId)`，ID 由原生生成；ownerId 不接受网页参数。目前原生固定本机所有者，不表示已有云账号认证。
- `agent_messages`：taskId、sequence、role、text、创建时间。第一版只保存用户原文，不插入冒充助手的系统提示。
- `agent_actions`：taskId、sequence、kind、时间。记录本地保存和取消，不记录提示原文/供应商诊断。
- 每次保存任务、首条消息和保存行动在同一事务中提交。同一所有者、幂等键和规范化文本返回现有任务；同键不同文本拒绝。相同文本使用新键表示用户有意新建，不按文本全局去重。
- 新任务为 `blocked/cloud_not_connected`，明确已存本机、云端尚未接通。取消变为 `cancelled/null`，重复取消不增加行动或改时间。后续接云端不能自动执行本批历史任务，须重新授权并显式发起。
- 已提交记录不依赖 WebView 生命周期；页面卸载前尚未提交的事务可取消/回滚。没有后台云 worker，不伪造 queued/running。后续执行状态/远端恢复协议在 M1C 扩展。
- 查询均以可信 ownerId 限定；别人的 ID 与不存在的 ID 返回相同“未找到任务”。数据库与 owner 字段不是服务端认证，M1C 仍需真实身份及授权隔离。

## Bridge 协议

新增能力 `agent.tasks.v1`，同步原生/前端的严格 ready 契约和 fixtures。保留现有 10 秒传输超时；accepted 仅代表原生事务成功，不代表模型执行成功。

| 命令 | 请求 body | accepted.data（另含 commandId） |
| --- | --- | --- |
| agent.submit | clientRequestId: UUIDv4，prompt: 1—2000 字符且非空白 | task、messages、actions |
| agent.list | offset: 非负整数，limit: 1—50 | offset、limit、totalItems、items（任务摘要） |
| agent.detail | taskId: UUIDv4 | task、messages、actions |
| agent.cancel | taskId: UUIDv4 | task、messages、actions |

task 只含 `id/title/status/reason/createdAtMillis/updatedAtMillis`；不泄露 ownerId、幂等键。列表按创建时间倒序、ID 倒序稳定排序；取消不改变创建顺序。详情消息/行动按 sequence 升序。所有外部 body/result 精确校验字段、长度、整数、ID 和状态组合。

## 首页行为

- Bridge 未就绪：不查询、不保存，明确只能在 App 内使用。
- 主按钮叫“保存任务”，说明“仅存本机，不会自动上传；云端尚未接通”。草稿不写 Web 存储。保存成功后清空草稿并显示真实详情；失败保留草稿。
- 保存中的同步锁防连点。同一草稿的结果未知重试复用 clientRequestId，修改内容则使用新键。刷新后只查询记录，不自动重发草稿或任务。
- 分页加载、空状态、错误重试与详情关闭；切换选择、取消、保存或卸载后丢弃旧查询响应。保存/取消请求不能被列表请求序号互相覆盖。
- 取消前不乐观改状态，成功后采用原生结果；取消失败仍显示已知状态并可重试。不存在用户可点的“生成成功”占位。
- 原生冷启动入口改为 `#/agent`；明确 `#/run` 仍进入跑步页，保留活动跑步返回入口。
- 小屏实现调整：输入框放在最近任务之前，避免新增任务列表挤走输入入口；保持既有主题、文案和四 Tab。

## 验收

JVM 验证命令校验、状态/消息生成、幂等冲突、分页及 owner 隔离；Room 仪器测试验证实际 SQL、事务原子性、并发重复提交、关闭重开恢复、取消幂等。前端验证真实边界请求、草稿保留、禁止伪回复、迟到回包、离线和刷新恢复。完整前端检查、Android 单测与 APK 构建均回归；不自动真机安装、云部署、付费请求或 Git 提交。
