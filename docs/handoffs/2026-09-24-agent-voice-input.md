# 首页语音输入本地实现

日期：2026-09-24。范围：用户要求参考 chatbot 项目的语音输入，继续开发流萤 App。当前是本地实现，不是已上线声明。

## 后续更新：私密复用许可与速度优化（2026-09-24）

用户已允许通过本地私密流程复用 chatbot 的百度配置，随后反馈百度识别准确率、速度不理想。已在本地 Node 内存读取 `chatbot/localmodel/.env`，仅核对 `BAIDU_APP_ID`、`BAIDU_API_KEY`、`BAIDU_SECRET_KEY` 三字段的唯一性、非空和可打印格式，全部通过；只输出布尔结果，未显示、复制、保存或上传密钥，未请求真实鉴权或识别。字段齐全不等于百度权限、有效性与余额已验证。

已修复新链路每次重复获取供应商访问令牌的额外网络往返：`baidu-token-cache.ts` 有界保存一份应用令牌，绑定两项密钥的 SHA-256 指纹。依 `expires_in` 提前一分钟过期、最多一小时，缺失有效期不缓存；不是保存音频／用户转写／请求 Promise，也不是跨实例持久缓存。每次仍做设备认证；取消或失败仅使下一次用户主动请求重新鉴权，不自动重发音频；旧请求的失败不能清掉新令牌。冷启动及并发首次请求可能各自取令牌，未引入跨请求 I/O Promise 合并。

本轮289项后端单元测试和 Node／Workers 类型检查通过；本地workerd32通过、1既有跳过，正式入口连续两次识别的供应商请求数量从4次降至3次。证明缓存命中省一次鉴权请求，不代表已测出真机延迟收益；不能据此诊断原 chatbot 慢的原因，也不能提高模型本身准确率。本轮未改手机代码，沿用下方上一批 APK／手机回归证据；未重新部署或安装，未提交／推送Git。

证据：`build/voice-token-reuse-check.log`、`build/voice-token-reuse-runtime.log`。依据：[百度令牌有效期](https://ai.baidu.com/ai-doc/REFERENCE/Ck3dwjhhu)、[Cloudflare隔离实例生命周期](https://developers.cloudflare.com/workers/reference/how-workers-works/)。遵循Workers技能，只共享应用级令牌数据，不缓存用户请求状态或请求所属I/O。

建议下一阶段对照[阿里Fun-ASR实时版](https://help.aliyun.com/zh/model-studio/fun-asr-realtime)的流式识别与热词能力，目标为边说边出字、减少专有名词错误。当前仅核对官方能力，尚未接入、开通、获取新密钥或发送录音；不声称其在用户设备网络下必然更准更快。已向用户询问是试做新流式方案，还是继续百度优化／真机测试；等待选择，不自动变更供应商或扩大音频接收范围。下方“未读配置”指首次实现阶段，已被本节的受控检查更新。

## 行为与边界

- 首页“语音输入”先展示本次上传说明，确认后请求麦克风权限；不把麦克风加入启动／跑步权限集合。
- 点击录音、手动结束或到30秒自动结束，识别后追加至原草稿；可编辑并沿用“仅保存／在线执行”的单次授权。绝不自动创建任务或执行 Agent。
- 原生直录16kHz、单声道、16bit PCM，不落音频文件、不需要 ffmpeg；内存字节数组在结束时清零。取消、退后台或 WebView 重载取消会话，迟到结果不能回填新会话。不是内存所有副本的安全擦除承诺。
- HTTP 经已经登记的 Worker 和设备 Bearer，再请求百度普通话 ASR（dev_pid1537）。供应商密钥只在 Worker，设备凭据仍在原生加密存储，不传入网页。
- 接口认证先于音频读取，PCM限3200—960000字节；供应商响应限16KiB。无自动重试、重定向；25秒服务端取消期限和30秒手机识别期限。已发送的音频不能通过取消撤回。
- 不修改跑步、Fish、固定流萤音频、音乐压低恢复；没有新增语音回复、常驻监听、离线识别或蓝牙麦克风路由控制。真机实际输入路由待验收。
- 费用策略沿用上一批：不按预算关闭功能。ASR 费用尚未加入现有模型 token 账本，不应把当前模型估算当成含 ASR 的总账单。

## 参考与实现

只读参考 `D:/fangr/PycharmProjects/chatbot/localmodel/modules/asr.py` 及 `localmodel/webui/js/chat.js`：百度16kHz识别、停止／取消、识别结果只写输入框。没有读取 `.env` 或复制任何实际密钥，没有修改 chatbot。

协议参考：[百度短语音识别文档](https://ai.baidu.com/ai-doc/SPEECH/Jlbxdezuf)。原生参考本机 Android SDK 的 `AudioRecord` 源码与非阻塞读取契约。实现改为原生 PCM 直录，避免 WebM/Opus 转码。

主要入口：

- `app/.../agent/voice/VoiceInputService.kt`：单次许可和会话状态；`AndroidVoiceRecorder.kt`：PCM及资源释放。
- `app/.../agent/cloud/CloudVoiceHttpClient.kt`：HTTPS单次上传、有界响应、取消。
- `MainActivity`、`NativeWebBridge`、`WebPreviewHost`、`AppModule`：权限、生命周期和受限 Bridge。
- `frontend/src/features/agent/AgentVoiceInput.tsx`、`AgentPage.tsx`、`frontend/src/bridge/voiceInput.ts`：说明、录音控制、状态轮询、草稿追加和操作锁定。
- `agent-server/src/voice-input.ts`、`worker.ts`：`POST /v1/agent/voice`，复用设备鉴权，不写 D1/DO、不创建模型任务。
- `contracts/v1/README.md`：严格命令和状态协议；APK和Web资源必须同步更新。

## 验证

| 检查 | 本地结果 |
| --- | --- |
| 后端 Node／Workers 类型和单元测试 | 286通过 |
| 本地 workerd 正式入口 | 32通过、1项既有诊断跳过；外网均为模拟响应 |
| 前端 lint、格式、类型、全量测试 | 225通过；最终样式调整后相关25项再次通过并重新构建 |
| Android JVM 全量 | 814通过、0失败、0错误 |
| APK与测试APK | 离线构建通过；前端已单独回归、构建，Gradle仅跳过重复checkFrontend |
| 生产类型生成与dry-run | 通过；未deploy、未上传Secret |
| 隔离模拟器最终回归 | 33通过；真实AudioRecord停止／取消后再录、WebView语音草稿及原有记忆／RAG／费用恢复均通过 |

第一轮32项模拟器测试有1项重载时序失败：旧文档尚未卸载时等待回答误判完成，随后读费用节点返回null。增加文档标记等待真实换页，未放宽生产逻辑或断言。语音草稿追加与未自动发任务的断言已在首轮通过。

最终33项全部通过（`OK (33 tests)`，57.776秒）。360 CSS像素宽度下检查 `phone-voice-consent.png` 和 `phone-voice-draft-final.png`：说明、操作和原草稿／转写可读，无横向溢出；按钮已适配原首页样式。关闭宿主音频的全新只读模拟器启动，临时显示密度从420改480，测试结束恢复并关闭。没有清除原模拟器或真机数据；测试目录保留供追溯。

本地日志：`build/voice-input-backend.log`、`voice-input-runtime.log`、`voice-input-frontend.log`、`voice-input-android-unit.log`、`voice-input-apk.log`、`voice-input-production-dry-run.log`；模拟器证据位于 `build/device-tests/voice-5e31b555c75a43beb12ac9b8d5ef3372/`，合成草稿／回答，不含真实凭据。

最终主APK SHA256：`DDDB37CC9B76F59E64F25CFEDB257288DC40257EA113A6B35FEA48A9237DCBD6`。

## 下一步需要用户配合

1. 经私密渠道配置 `BAIDU_ASR_API_KEY`、`BAIDU_ASR_SECRET_KEY`；不要发到聊天。需要明确同意后才读取已有配置或上传云端Secret。当前只添加绑定名称，未验证百度账户余额、权限或密钥有效性。
2. 经授权覆盖安装配套APK，再发布正式Worker（包含上一批可选cost字段和预算仅记账变更）；发布前复核最新工作区与现有云状态，不能把本地通过当成已经部署。
3. 单独批准一次真实识别：手机输入一句话、核对草稿、取消、退后台、断网、30秒上限和Beats输入路由；确认没有自动发送给DeepSeek。语音回复如需新增另定范围。
4. Free云CPU／内存和真实ASR延迟尚未测；本地测试与dry-run不证明云端30秒最大录音负载已通过。

本轮没有提交／推送Git，没有更新正式Worker、D1数据或手机，没有真实百度、DeepSeek或Fish请求。保留原工作区所有历史未提交修改。
