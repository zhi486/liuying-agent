# 免费 SQLite DO 隔离云验证

日期：2026-09-16。结论：**已批准的固定探针验证通过，可以进入正式 DO 接入的设计；本轮没有迁移正式后端或手机 App。** 内存峰值仍未测得，不把本轮结果当作生产稳定性保证。

## 范围与收尾

用户批准复用测试 Worker/D1、新增一个 SQLite DO namespace，最多20业务请求、4次就绪检查，不调用真实模型、不付费升级或扩权。执行前用户再次确认 Workers 仍为 Free；账单 API 因既有权限返回403，不能把 `default_usage_model=standard` 当作套餐证明。

- 账户：`95a131611fbe1097f7196cfb014ebce3`。
- 原 Worker/D1：`liuying-free-spike-20260915`；D1 ID `8b159107-a1f8-40d9-a932-c52df81c3dfe`。未执行 D1 迁移。
- 唯一新增 DO：`liuying-free-spike-20260915_LiuyingRuntimeProbe`，ID `a250a3fe68f54f199cb4967ebd69ea5a`，API确认`use_sqlite=true`。
- 实验版本：`7fc507d1-e3d8-4264-80b0-3c48bc7258fd`，startup 41ms；打包gzip约2062KiB。
- 收尾版本：`7ded46af-e999-43cd-9f4d-46af8025fd48`，保留 DO 类、绑定、迁移及测试状态。
- 已读回：workers.dev=false、preview=false、EXPIRES_AT=0；公开URL返回404。observability配置false，API未返回该字段，不能声称API确认其值。
- 没有 Cron、常驻心跳、WebSocket、真实 DeepSeek/Fish 请求；未删除资源、未提交或推送 Git。

## 实际请求和 CPU

执行一次固定序列：setup 1次；6组token用例两遍12次；Agent单轮/三轮2次；断连1次；同任务回查1次；故障1次；恢复2次，合计**20次业务请求**。就绪检查实际1次。没有业务自动重试。

实时 tail 保存41个事件：21个入口事件、20个 DO RPC事件。通过随机probeId逐一关联，DO事件实际包含`executionModel=durableObject`、`entrypoint=LiuyingRuntimeProbe`、`rpcMethod=run`及数值CPU。入口业务CPU全部 **1–2ms**；DO全部 outcome=ok、无异常；断连入口 outcome=canceled符合主动断流，其余入口为ok。

| 固定用例 | tokens | DO首次CPU | 同实例重复CPU |
| --- | ---: | ---: | ---: |
| production-round-1 | 347 | 225ms | 1ms |
| production-round-2 | 421 | 4ms | 5ms |
| production-round-3 | 512 | 3ms | 2ms |
| loop-large-round-1 | 2390 | 16ms | 12ms |
| loop-large-round-2 | 3789 | 18ms | 13ms |
| loop-large-round-3 | 3924 | 16ms | 9ms |

首次225ms包含首次构建tokenizer及完整ID摘要验证，不能称作纯计数耗时。所有用例完整ID的SHA-256都匹配锁定独立参考；同一DO实例标记保持一致，第二遍明确标记seenCaseBefore=true。入口边缘曾在HKG/SIN之间变化，**cf-ray机房不是DO实际驻留机房证据**。

| Agent/恢复用例 | 入口CPU | DO CPU | DO wall time |
| --- | ---: | ---: | ---: |
| 单轮完整Agent | 1ms | 19ms | 277ms |
| 三轮完整Agent | 1ms | 27ms | 478ms |
| 断连中的Agent | 2ms | 13ms | 4165ms |
| 断连后回查 | 2ms | 1ms | 19ms |
| 收据后结算故障 | 1ms | 10ms | 204ms |
| 第一次恢复 | 2ms | 3ms | 566ms |
| 第二次恢复 | 1ms | 1ms | 16ms |

这支持“将本轮计算放入免费DO后，入口低于普通Free Worker的10ms，DO的超10ms调用也实际成功”。**未通过压力测试确认全部CPU上限或生产SLA**，不能用这些小样本断言长期稳定。DO活跃时长而非单纯CPU可能消耗duration额度，包括模型等待；本轮不测实际账单、不估算全App总成本。

## 精确计数边界

本地146组固定参考＋3组真实Agent请求参考完整ID对照通过。云端12次token对照及Agent各轮397/471/562计数与完整ID摘要通过。

仍是“原始请求SHA绑定独立Python模板产出的固定canonical prompt”探针；未知请求失败。**尚未实现任意真实用户输入的完整JS模板渲染器**，不能把这套固定查表探针接到生产当作通用精确计数器。性能包含完整ID验证开销，不与昨日仅count长度探针做严格A/B归因。

## 真正断连和账务证据

客户端只销毁本次请求独立TCP socket，不改电脑/手机/VPN网络。收到带任务编号的运行中首帧时响应未结束，随后记录socketDestroyed=true、socketCloseObserved=true。8秒后使用新连接回查**同一任务**：

`e1b45ac1-1cc8-4e77-8390-78db11acfc1c` → completed / available，有保存的四公里总结，仅1份已应用收据。

入口用ctx.waitUntil保持完整RPC；DO明确await任务与结果落库，没有依赖无效的DO waitUntil。这只证明本轮约4秒假供应商等待的客户端断流场景；**不等于手机飞行模式、VPN黑洞、杀App、服务器中途崩溃或超过30秒恢复已验证**。本地驱逐测试证明已保存身份/尝试标记可重建，不证明执行中的任务能跨崩溃自动恢复。

远程D1预检待恢复数为0。独立只读验收4个新任务：单轮、三轮、断连任务均completed/available；人工故障任务`47f0d3a2-7ea4-40ae-9ba0-efec5fea9a92`保持failed/model_unavailable/available。恢复首次应用1份收据，再次0份，不重派模型。6份收据最终全部settled/applied=1、执行hash匹配、violation=0，每份虚构计费78 micros，总468 micros；**这是测试账务数字，真实模型调用为0，不是真实消费**。最终全库待恢复数0，验收查询rows_written=0。

## 内存仍有证据缺口

这批部署/调用未出现平台OOM或超资源异常，但没有取得可靠云端峰值内存字段；不能把本地Node内存、workerd桩值或“没有OOM”写成精确峰值/充分余量。生产正式接入仍需限制输入大小、实例数量，并验证冷启动、并发与长任务边界。

## 本地测试与审查

- 探针Node：41通过，包括146＋3组完整ID对照、原探针与新runner/日志白名单行为。
- Cloudflare Vitest真实workerd DO：7通过，覆盖预置身份、驱逐重建、不同对象隔离、20次硬上限、禁止重派、取消流、收据恢复金额及失败结果内容不变。
- 正式Agent后端：Node/Workers类型检查通过，163项测试通过。
- 新测试先看到失败后实施；无生产源码改动。
- 安装时npm10.9.7在嵌套Vite8可选peer图抛edgesOut异常；将Vite统一锁定7.3.6后安装成功。Vitest4.1.11、Cloudflare plugin1.1.9均复用原Wrangler4.131.2/Miniflare5.20260911.1-alpha，没有升级部署CLI。
- 上云前独立只读审查通过；审查代理未能在其沙箱复跑，未冒称独立测试通过。其提出的D1基线与恢复断言缺口均已核对/补强。
- 收尾后第二次独立证据审查已将20条客户端记录与40条入口/DO事件、D1证据及关闭状态交叉核对，通过；未再次调用业务端点。

## 证据及复核命令

以下文件均在Git忽略的`agent-server/tools/cloud-spike/.output/`，无会话密钥或原始请求头：

- `do-cloud-1789543287824.json`：客户端固定20次报告。
- `tail-1789543247663.json`：41条脱敏事件，含两侧CPU。
- `cloud-state-1789543470383.json`：收尾真实开关和唯一SQLite DO。
- `do-evidence-1789543554711.json`：独立只读D1任务/账务证据。

在`agent-server/tools/cloud-spike`执行下列命令会再次只读查询D1，不发送业务/模型请求：

```powershell
node verify-do-evidence.mjs do-cloud-1789543287824.json tail-1789543247663.json cloud-state-1789543470383.json
```

## 下一步

先设计正式“轻入口＋每用户DO执行＋D1账务”的接口、授权和生命周期，继续保持撤销/截止/预算/幂等检查；同时解决通用精确模板计数，而不是将固定探针直接产品化。正式云端接入及手机联调另按设计验收，不自动扩大本轮授权。
