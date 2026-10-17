# 首页 Fun-ASR 流式语音输入交接

最新状态：已获准完成真机覆盖安装和正式云端发布，用户确认语音入口可见；真实识别尚待手动测试。见[正式发布交接](2026-09-25-fun-asr-production.md)。下文未发布描述保留为历史记录。

日期：2026-09-25。用户已接受“阿里 Fun-ASR 流式＋热词”方案；本批完成本地实现和回归，尚未发布。此状态取代上一份交接中的“新供应商待选”。

## 后续更新：热词真实创建完成（2026-09-25）

用户完成本地私密填写，并明确授权仅连接百炼创建／查询热词，不上传录音、不调用识别、不部署。已使用用户提供的北京业务空间 `ws-0adi0ev54h37qp4j`，向其官方 `/api/v1/services/audio/asr/customization` 执行3次请求：按 `liuying` 前缀查询（0张）→创建1张→查询详情。全部HTTP 200；词表 `vocab-liuying-37714055ac39423093ce7aa8b7f17091` 状态 `OK`，`target_model=fun-asr-realtime`，8个词及权重全部匹配。未更新／删除其他词表，未自动重试。

词表 ID 已填入被 Git 忽略的 `secrets/fun-asr.local.env`；密钥未输出、未写入文档、未上传 Cloudflare Secret。该结果证明当前凭据可调用热词管理接口，不证明语音识别权限、实际延迟、准确率或Cloudflare网络可达性已通过。正式Wrangler配置仍保持原占位；发布须另获授权并审查前批未发布修改，先安装兼容手机版本，再配套后端。

下方“没有创建真实热词／待填写”是首次本地实现阶段，已被本节更新。官方依据：[热词 HTTP API](https://help.aliyun.com/zh/model-studio/vocabulary-http-api)。下一步是获准配套发布、安装手机及单次真实识别；本次未执行这些操作。

## 已实现

- 首页单次告知：录音期间实时经已登记的 Worker 转发至阿里云 Fun-ASR，可能产生费用，不保存录音、不自动发送给 Agent。
- 原生等待供应商 `task-started` 后才开启麦克风，16kHz／单声道／16bit PCM，约100毫秒一帧、每帧最多4096字节、最长30秒；不再等完整录音结束才上传。
- `connecting` 与 `recording` 区分；可修订的 `preview` 独立显示，最终文字才追加草稿。取消／失败清除预览，原草稿不动。
- 新 `/v1/agent/voice/stream` 使用设备 Bearer 与 `X-Voice-Consent: fun-asr-v1`；供应商域名、模型、协议由服务端固定。拒绝浏览器 Origin、查询参数、无效音频帧和超长结果。
- 鉴权在握手前后、就绪、结束、返回最终结果时检查；会话活动期间至多约5秒间隔重新检查。无自动重连或供应商回退；取消断流停止录音和上游连接，已发送音频无法撤回。
- 有界事件队列、55秒会话上限、2000字符结果上限；供应商最终消息与紧随的关闭／错误事件按已接收顺序处理，避免定稿鉴权期间被误中断。
- 旧百度接口保留兼容，不由新 APK 自动调用。跑步、B5、Fish 播报不改；没有新增语音回答或常驻监听。

## 热词和私密配置：待用户配合

仓库只包含非私密的 `agent-server/tools/fun-asr-hotwords.json`（流萤、开拓者、配速、心率、轻松跑、间歇跑、热身、拉伸）。权重为初始候选，尚未经识别准确率对照验证。不上传用户记忆或聊天正文作为热词。

Fun-ASR 使用已创建的 `vocabulary_id`，不是直接在识别参数传即时词数组。后续需在同账号、地域、业务空间下创建适配 `fun-asr-realtime` 的热词表，并确认可用状态；目前没有创建真实热词资源。

| 配置 | 位置与要求 |
| --- | --- |
| `FUN_ASR_API_KEY` | 服务端 Secret；通过本地私密流程填写，不能发聊天或写入 Git |
| `FUN_ASR_WORKSPACE_ID` | 与 API Key 对应的业务空间 ID；当前占位空值 |
| `FUN_ASR_REGION` | `cn-beijing` 或 `ap-southeast-1`；默认北京仅为占位选择，真实发布前需核对 |
| `FUN_ASR_VOCABULARY_ID` | 同模型、同账号／地域的热词表 ID；当前占位空值 |

缺失配置返回不可用，不发起真实识别、不影响文字任务。不要直接部署占位配置覆盖现有服务端设置。百度密钥不能用于阿里云。

## 验证证据

| 检查 | 结果 | 本地证据 |
| --- | --- | --- |
| 后端 Node／Workers 类型与单元测试 | 293通过 | `build/fun-asr-server-check.log` |
| 本地 workerd／D1／WebSocket | 36通过、1既有诊断跳过 | `build/fun-asr-runtime.log` |
| Android JVM | 819通过 | `build/fun-asr-android.log`、JUnit XML |
| 前端 | 226通过；lint／格式／类型／构建通过 | `build/fun-asr-frontend.log` |
| 隔离模拟器 | 33通过 | `build/fun-asr-device.log` |
| APK与正式配置本地打包 | 通过，仅 dry-run | `build/fun-asr-apk.log`、`build/fun-asr-production-dry-run.log` |

新增测试覆盖中间句替换／最终去重、供应商紧随关闭、帧上限、无授权／无热词不发网、用户取消、设备撤销、Android 录音中断流释放、草稿不被预览修改。workerd 日志仍包含既有负向用例“合成回执断流”，不是本批失败。

模拟器使用本轮独立目录 `build/device-tests/fun-asr-9e11747a6eff47d59dc4d6224d62d58d`、合成识别服务与真实 WebView／Bridge；原生 AudioRecord 测试通过。目视检查 `phone-voice-consent.png`、`phone-voice-preview.png`，360 CSS 像素宽度无横向溢出，原草稿与预览各自显示。不能用合成转写证明真实准确率或端到端延迟。

APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA256 `F5615297D46DA12351D75900FF8CA9F842E24157DAFCD672CA5D845D63F1F85F`。

## 未完成与下一步

1. 用户提供地域、工作空间信息，并通过私密流程配置阿里云 API Key；确认现有权限／服务可用性，不擅自开通付费服务。
2. 经用户许可创建热词表并核验同模型适配；填好配置后再批准配套手机安装及正式 Worker 发布。当前工作区还含上一批费用仅估算／记账等未发布代码，不能把发布当成仅语音变更；先审查发布集合，并先更新兼容 `cost` 字段的手机端。
3. 单独授权一次真实短语音识别，记录首字延迟、停止到定稿延迟和转写错误；核验 Beats 实际输入路由、户外流量＋VPN、断网释放。Free 真云 CPU／内存与服务可达性尚未验证。
4. ASR 费用目前未接入模型 token 账本，不能显示成零；后续只做估算展示与记账，不引入预算硬停功能。

本轮未部署、未上传／读取新供应商秘密、未改线上数据、未安装真机、未调用真实 ASR／DeepSeek／Fish、未提交／推送 Git。保留既有工作区修改。

## 官方依据与技能应用

按 Workers best practices 的请求生命周期、有界资源与取消原则处理流式会话；通过 Wrangler 技能生成 Env 类型并仅执行本地生产 dry-run，没有发布。

- [Fun-ASR WebSocket 地址和鉴权](https://help.aliyun.com/zh/model-studio/fun-asr-realtime-websocket-api)
- [客户端 run-task／finish-task 与词表参数](https://help.aliyun.com/zh/model-studio/fun-asr-client-events)
- [服务端分句、修订与结束事件](https://help.aliyun.com/zh/model-studio/fun-asr-server-events)
- [热词表适配条件](https://help.aliyun.com/zh/model-studio/improve-asr-accuracy)
- [Cloudflare WebSocket 生命周期和二进制类型](https://developers.cloudflare.com/workers/runtime-apis/websockets/)
