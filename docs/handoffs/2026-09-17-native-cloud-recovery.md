# S4：原生 HTTPS、加密凭据与云任务恢复

日期：2026-09-17。承接用户“继续”，按已批准的六批计划完成 S4 本地实现和验收。主代理直接实现，子代理只读核对协议与独立复审；没有 Git 提交或推送。下一批为 S5，不是上线或真实模型验收。

## 交付与边界

| 内容 | 结果 |
| --- | --- |
| HTTPS 客户端 | 六个正式路由、固定规范 origin、身份校验、严格响应解析、取消传播 |
| 防重复发送 | 禁重定向／连接重试／认证回调；POST 一次性正文及每 Call 一次网络发送门禁 |
| 凭据存储 | 独立 Android Keystore AES-GCM，密文文件在 noBackupFilesDir，身份作为 AAD |
| 结果持久化 | AgentDatabase 2→3 增量迁移，独立结果表，事务落库并回读核验后才确认 |
| 恢复与控制 | 原键查询、结果确认重试、取消／撤回先持久化，迟到结果不恢复已撤回正文 |
| 生产接线 | 尚未接入 DI、Bridge、首页或真实配置导入；旧本机任务不自动上传 |

本轮没有读取或使用真实凭据，没有签发设备、部署云服务、调用 DeepSeek/Fish、改真机或改跑步／语音逻辑。测试仅本地 TLS 服务器、合成凭据、隔离模拟器。既有云验收入口关闭状态不变，本轮没有重新查询云状态。手机直连语音仍不在云账本范围内，不能宣称全 App 每月 $2 已硬封顶。

## 实现位置

`app/src/main/java/com/healthyagent/running/agent/cloud/`：

- `CloudTaskResponse.kt`：严格云任务 DTO、状态／原因／正文关系和静态错误代码。
- `CloudTaskHttpClient.kt`：六路由、固定 HTTPS、安全响应读取及取消。
- `CloudDeviceCredentials.kt`：独立设备 Bearer 密文库和原生读取接口。
- `CloudTaskReceipt.kt`：结果实体与纯合并规则。
- `CloudTaskSync.kt`：一次提交、恢复、控制与落库后确认的协调器。
- `CloudTaskIntentDao.kt`：增加事务接纳、结果回读、撤回隐藏正文。

`agent/local/AgentDatabase.kt` 升至版本 3，生产 builder 同时注册 1→2 与 2→3；只增表索引，无破坏性兜底。运行时 Room 仍 2.6.1、OkHttp 仍 4.12.0；仅增加固定版本 `mockwebserver` 与 `okhttp-tls` 测试依赖。

## S5 接线必须遵守

1. 由可信原生配置提供规范 HTTPS origin 与完整身份。客户端构造时拒绝非 HTTPS、路径、用户信息、查询、片段或非规范拼写；显式默认端口 `:443` 不可偷偷归一后改动既有快照。
2. `CloudDeviceCredentials.create(context)` 创建独立凭据库。`storeNew` 的同代次同身份同秘密导入可幂等，不同身份／秘密拒绝覆盖；轮换必须新代次，不复用语音或 DeepSeek 配置。Bearer 只能进入原生请求头，不经过普通 Bridge、URL、日志或 Room。
3. 凭据方法为阻塞方法，必须在 IO 线程调用。AES-GCM 使用 Keystore 生成 IV，AAD 绑定版本、origin、owner、device、credentialRef；写入只有密文，经同步与原子移动提交。损坏、缺失或密钥不可用均静态失败，不回退明文。未承诺 StrongBox／硬件保护。
4. 应用级原生实例组合 DAO、`CloudTaskHttpClient` 与 `CloudTaskSync`。当前没有 DI 注入；同步器内部 Mutex 不替代跨实例事务，生产应保持单实例。Manifest 当前单进程，凭据写入也基于单进程锁；若以后增加进程，需要重新设计同步。
5. 显式在线确认后先保存不可变意图，再 `submit`。CAS 已认领时只恢复；POST 失败后通过原 requestId 查询，404 也不自动清零、换键或重发。未提交意图的恢复和待控制不发网。凭据失效同样不自动重置 CAS。
6. `recover` 优先已知 cloudTaskId，否则原 requestId。服务端没有列表接口，首页列表来自本地意图索引；不扫描旧 blocked 任务作为在线队列。
7. 取消／撤回先持久化待控制再等待同步锁。撤回立即清除本机正文及确认待办，但只有收到云端 withdrawn 才算云端已撤回；取消已完成任务可能仍返回 completed，不伪造 cancelled 或退款。
8. 云响应先验证并在 Room 事务中写入、回读核验，事务完成后才能 acknowledge。确认失败保留正文与待确认状态，恢复再确认；服务端 acknowledged／expired 不删除此前合法缓存的本机结果。撤回例外，始终隐藏正文。

S5 应明确区分“仅保存”和“在线执行”，显示上传范围和真实状态；当前授权固定只发文本，无跑步摘要。可信配置导入入口与真实设备发放需要单独接线，不能让网页任意提供 origin／Bearer。本轮没有新增后台轮询或 WorkManager。

## 协议与竞态保护

- 响应只接收约定的八字段任务封装、合法状态关系、安全整数时间和固定 30 秒截止；正文和行动记录字段严格限定。错误仅静态白名单，无原始网络／SQL 异常原因。
- 解码后的响应正文上限 64 KiB，严格 UTF-8；所有响应关闭。默认 TLS 信任和主机名验证保持启用，测试专属自签证书只注入测试客户端。
- 不能只依赖 `retryOnConnectionFailure(false)`：同时禁重定向／认证，POST 正文声明一次性，并用网络拦截器阻止同一 Call 的第二次发送。测试覆盖 301/302/307/308/401/408/421/503 等路径。
- `updatedAtMillis` 不是唯一版本：同毫秒完成、确认及到期可发生，不能只按时间判断新旧，也不以手机时钟推断服务端到期。
- 原 cloudTaskId、接单／截止时间和已观察终态不可冲突；旧 queued/running 不覆盖终态，已关闭结果不能被旧 available 恢复。撤回待办下的迟到正文不能重新出现。
- Room 2.6.1 的 Upsert 遇非主键唯一冲突可能不写入却不抛错。本轮新增事务内回读一致性校验，失败回滚并停止确认，不把方法返回等同于实际落库。

凭据文件加密不代表整个 App 数据库加密：Room 仍为 noBackupFilesDir 内普通 SQLite，任务文本与合法缓存答案保存在本机。失败时可能留下密文 pending 文件；本轮没有添加自动删除用户文件的行为。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Android JVM 全量 | 787/787，83 个 suite，失败／错误／跳过均 0 |
| 云任务 JVM 定向 | 15/15，含 S3 原有 4 项及本批 11 项 |
| 前端完整门禁 | 160/160，lint／格式／类型／构建／Bundle 通过；本批无前端源码变更 |
| 主包及测试包 | 离线完整构建成功，保留原项目签名 |
| 隔离模拟器仪器测试 | 23/23，2.925 秒：同步 7、Keystore 3、在线意图／迁移 9、旧任务库 4 |
| 独立审查 | 协议和传输／凭据两个只读审查无剩余阻断 |

关键实测包括本地 TLS 六路由、默认信任拒绝自签证书、超限／非法 UTF-8／截断响应、底层 Call 取消；真实 AndroidKeyStore 加解密、重建实例读取、身份换绑／篡改拒绝；真实 Room 写入失败不确认、唯一云 ID 冲突、确认失败后文件库关闭重开、撤回与迟到查询竞争、1→3／2→3 迁移保留旧任务与在线待办。

两处行为先观察失败后修复：终态冲突未拒绝、Upsert 唯一冲突未证明落库。测试搭建另修复了 JUnit 表达式方法返回值，以及本地 TLS 证书缺 IP SAN；未放宽生产 TLS 或隐藏测试失败。后端 235 项／运行时 25 项与 1 项既有跳过是 S2 历史结果，本轮未改后端源码或重跑后端。

本轮恢复证据是故障注入和 SQLite 文件关闭重开，**不是**真机飞行模式、真实进程被杀、实际 Cloudflare 断网或收费供应商验收。

## 复现与产物

环境：JDK `C:/Users/fangr/.jdks/ms-17.0.20`，`ANDROID_USER_HOME` 指向项目 `.android-user`，Gradle 使用项目 `.gradle-local`。完整构建：

```powershell
./gradlew.bat --offline --gradle-user-home .gradle-local :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

在全新独立 userdata 的 Android 34 模拟器 `emulator-5556` 运行四个上述仪器测试类；使用无窗口／无音频／不保存快照模式。测试目录 `build/device-tests/s4-c8d02f7a775143868c5fb4c1d90b392b` 保留，本轮已正常关闭模拟器，没有操作真实手机。

- 主 APK：`app/build/outputs/apk/debug/app-debug.apk`
- SHA-256：`2B8B3F12BA4217338476DD33C566E21FCDFBA6AB139A7CDA140650A2A9CC03DF`
- 原 debug 证书 SHA-256：`ebad2b92cff0324810062be3e8116645cfd352780ad79141be61be148774c603`

只读依据：[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[固定 OkHttp 4.12.0 跟随与重试实现](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)。网页工具不可用时以官方文档／固定源码只读请求核对，没有执行远程脚本。

下一步：S5 独立 `agent.cloud.tasks.v1` Bridge 与首页显式在线入口；先合成数据测试状态、恢复、取消与撤回。真实 origin、私密导入、费率／tokenizer 适用性与有限真实模型验收留 S6，需先确认范围和预算，当前无需用户提交密钥。
