# M1C-2A 个人设备认证与云任务库实施计划

> 执行技能：superpowers:executing-plans。沿用用户偏好：主代理直接实现，子代理只读审查；当前工作树内开发，不提交推送。

**目标：** 离线验证真实凭据校验和真实 SQL 云任务状态，不派发模型或开放 HTTP。

**架构：** device-auth 负责身份与私密管理，cloud-task-contracts 负责严格内容/结果校验，cloud-tasks 负责持久状态；复用既有 D1 结构接口和预算 owner。所有秘密仅在可信进程内流转。

**技术栈：** Node 22.22.2、TypeScript 5.9.2、Web Crypto、D1 SQL、内置 SQLite 测试；无新增运行时依赖。

**规格：** [已确认 M1C-2 设计](../specs/2026-09-14-agent-device-tasks-design.md)。

## 全局约束

- 仅实现 M1C-2A；不改 Android/React、不上传旧任务、不联网请求模型、不部署或自动管理真实凭据。
- 设备凭据：lyd_＋64 位小写 hex、32 字节密码学随机、SHA-256 落库、90 天有效；普通认证工厂没有管理方法。
- owner 绑定来自设备记录；每次业务请求重新认证。SQL 写入/查询继续包含当前设备有效性门禁，不仅依赖先前内存身份。
- 接单到截止固定 30 秒；只认领一次、不续租、不重跑；每 owner 最多一个 queued/running。
- 输入不单独落库；指纹 24 小时后清除；结果自终态写入起最多 24 小时可取回，确认后立即清空；幂等墓碑保留。
- 所有待认证错误、数据库错误对外使用静态代码，不暴露原始异常；不修改现有核心 StopReason。
- 基线 `typecheck` 与 86 项测试通过；SQLite/MockTimers 的实验警告保留。

## Task 1：严格契约与设备认证

新增 `src/cloud-task-contracts.ts`、`src/device-auth.ts`、`migrations/0002_agent_device_tasks.sql`、`test/device-auth.test.ts`；扩展测试宿主 `test/sqlite-d1.ts` 加载第二次迁移。

接口定稿：

```ts
createDeviceAuth({ db, now? }).authenticate(authorization: unknown): Promise<Principal>
// Principal: { ownerId: string; deviceId: string }
createDeviceAdmin({ db, now? }).issue(ownerId: string): Promise<{ deviceId: string; token: string; expiresAtMillis: number }>
createDeviceAdmin({ db, now? }).revoke(ownerId: string, deviceId: string): Promise<void>
// CloudError.code: unauthenticated | unavailable | invalid_request | not_found |
// conflict | busy | inactive | request_expired
```

- [x] RED：动态导入尚不存在的认证模块；未知 token 的消费者结果应为 unauthenticated 而不是 undefined，观察断言失败，再用最小拒绝实现转 GREEN。
- [x] 写真实 SQL 发放/校验测试，检查原值未落库、同 owner 两设备身份与预算不变、错误 owner/畸形凭据/90 天边界/撤销/存储失败。运行观察尚无发放等行为失败，再实现。
- [x] 第二次迁移新增 `agent_devices`：id、owner_id、token_hash、created_at、expires_at、revoked_at，外键绑定原预算账户；同迁移声明云任务表，供撤销事务使用。
- [x] 私密撤销使用 D1 batch 同时撤销指定设备和取消其活跃任务；复核无公开注册/管理 HTTP、无读取真实 key。

手写验收：

```ts
assert.equal(identity.ownerId, 'owner_1');
assert.equal(device.expiresAtMillis, timestamp + 7776000000);
assert.equal(databaseText.includes(device.token), false);
await admin.revoke('owner_1', device.deviceId);
await assert.rejects(auth.authenticate(header), /unauthenticated/);
```

## Task 2：真实云任务状态库

新增 `src/cloud-tasks.ts`、`test/cloud-tasks.test.ts`；契约与迁移由本任务补齐实际状态约束。

接口定稿：

```ts
createCloudTasks({ db, now? })
// 普通业务方法，每次接收并重新校验 authorization：
accept(authorization, input): Promise<{ task: CloudTaskView; created: boolean }>
get(authorization, taskId): Promise<CloudTaskView>
findByRequest(authorization, clientRequestId): Promise<CloudTaskView>
cancel(authorization, taskId): Promise<CloudTaskView>
withdraw(authorization, taskId): Promise<CloudTaskView>
acknowledge(authorization, taskId): Promise<CloudTaskView>
// 以下只供可信执行器/维护流程，禁止做成面向手机的公开路由：
claim(authorization, taskId): Promise<{ taskId: string; token: string }>
finish(execution, result: unknown): Promise<void>
purge(): Promise<void>
```

`input` 精确字段为 clientRequestId/localTaskId（canonical UUIDv4）、prompt、consent、summaries；parseTask 复用校验后按固定字段序列化，不使用客户端指纹。`CloudTaskView` 仅含 id/status/reason/acceptedAtMillis/deadlineAtMillis/updatedAtMillis/resultState/result；result 为严格 AgentResult 或 null。resultState 为 none/available/acknowledged/expired/withdrawn，不返回 owner、请求指纹、设备凭据或执行权。

云表字段：id/owner_id/device_id/client_request_id/payload_digest/payload_expires_at/status/reason/accepted_at/deadline_at/updated_at/execution_hash/result_json/result_expires_at/result_state。唯一 `(owner_id, client_request_id)`；部分唯一索引 owner_id WHERE status IN ('queued','running')。状态、理由、JSON 有效性和结果可用状态由 CHECK 约束。

- [x] RED：不存在模块时接单结果应为 queued 而非 undefined。随后最小接单与查询实现转 GREEN。
- [x] 分批 RED→GREEN：同键并发/异文冲突、多设备共享活跃任务限制、跨 owner 隔离、旧记录不复活；固定 30 秒/一次认领/错误执行权/撤销与到期不能认领。
- [x] 分批 RED→GREEN：终态完成与重复结果、取消与完成竞态、撤销/授权撤回、24 小时结果与指纹清理、确认幂等、文件库重开、事务失败回滚。
- [x] 请求/结果校验覆盖非法字段、结果状态/理由组合、4,000 字符/9 行动限制、合法工具名/步骤范围。SQL 执行和权限校验是真实代码，不用假存储结果替代。

关键条件更新：

```sql
UPDATE agent_cloud_tasks SET status = 'running', execution_hash = ?
WHERE id = ? AND owner_id = ? AND device_id = ? AND status = 'queued'
  AND deadline_at > ? AND EXISTS (SELECT 1 FROM agent_devices d
    WHERE d.id = agent_cloud_tasks.device_id AND d.revoked_at IS NULL AND d.expires_at > ?);
```

同样约束用于完成写入，并绑定 execution_hash。正常查询先按 owner 收敛超时/清理，再读取不含秘密的 DTO；执行用量结算属于 M1C-2B，不在此阶段伪造派发。

手写验收：

```ts
assert.equal(accepted.task.status, 'queued');
assert.equal(accepted.task.deadlineAtMillis, timestamp + 30000);
assert.equal(duplicates.filter(x => x.created).length, 1);
await tasks.cancel(header, accepted.task.id);
await assert.rejects(tasks.claim(header, accepted.task.id), /inactive/);
assert.equal((await tasks.get(header, accepted.task.id)).status, 'cancelled');
```

终态结果已清除时，迟到 finish 返回 inactive，不再接受无法验证的内容相同声明，不恢复已清除正文；终态和费用记录不变。确认只能作用于终态，撤回可作用于任何状态但保留已有终态；这是清理语义，不是重新执行或删除幂等键。

## Task 3：复审与交接

- [x] 独立只读复审认证和状态库；修复有效问题前增加失败测试，完整回归后再确认结果。
- [x] 使用前端现有 Prettier 仅格式化本批文件；执行 `corepack pnpm check`，检查完整退出码和测试数；检查本批所有未跟踪文件空白，而非只检查 tracked diff。
- [x] 更新 README、总体计划、规格状态、交接和记忆。只报告离线库完成；手机协议、付费派发、真实令牌管理和定时清理部署均未完成。
- [x] 保留当前分支与未提交修改，不 stage/commit/push/安装。后续 M1C-2B 才接应用服务与 Loop。

## 执行结果（2026-09-15）

类型检查及 118 项全量离线测试通过（新增认证 6、任务 26，共 32）；格式与本批未跟踪文件空白检查通过。独立复审修复了 SQL NULL 组合、数据库排队时间快照和测试毫秒格式问题，并全部通过失败复现→修复→回归。上述 SQL 仅说明认领条件，最终实现额外采用数据库执行毫秒时钟与应用时间较晚者检查截止；读取时记录数据库时间，返回前隐藏过期结果。

交接：[M1C-2A](../../handoffs/2026-09-15-agent-device-tasks.md)。保留 `codex/five-second-zones` 与现有工作区；M1C-2B 另按规格细化接续，不把离线库算作手机云端闭环。
