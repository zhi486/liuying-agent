import { env, exports } from 'cloudflare:workers';
import { applyD1Migrations, evictDurableObject, runInDurableObject } from 'cloudflare:test';
import { beforeAll, expect, test } from 'vitest';

const post = path => exports.default.fetch(`https://probe.invalid/do/${path}`, {
  method:'POST', headers:{ authorization:`Bearer ${env.SPIKE_TOKEN}` },
});
beforeAll(async () => { await applyD1Migrations(env.DB, env.TEST_MIGRATIONS); });

test('拒绝未鉴权和任意路径；就绪检查不开户', async () => {
  expect((await exports.default.fetch('https://probe.invalid/do/setup',{method:'POST'})).status).toBe(401);
  expect((await post('unknown')).status).toBe(404);
  expect(await (await post('ready')).json()).toEqual({protocol:'do-runtime-v1'});
  expect(await env.DB.prepare('SELECT count(*) AS n FROM agent_budget_accounts').first('n')).toBe(0);
});

test('预置只开户一次；驱逐后凭据和次数持久化、实例标记改变', async () => {
  const first = await (await post('setup')).json();
  expect(first.ownerId).toMatch(/^spike_/);
  expect(JSON.stringify(first)).not.toContain('lyd_');
  const stub = env.PROBE.getByName('synthetic-runner-20260916');
  await evictDurableObject(stub);
  const next = await (await post('setup')).json();
  expect(next.ownerId).toBe(first.ownerId);
  expect(next.instanceMarker).not.toBe(first.instanceMarker);
  expect(next.calls).toBe(first.calls + 1);
  expect(await env.DB.prepare('SELECT count(*) AS n FROM agent_budget_accounts').first('n')).toBe(1);
});

test('DO 固定精确计数与完整三轮 Agent 保留正常认证和账务', async () => {
  const count = await (await post('token/loop-large-round-3')).json();
  expect(count.actualTokens).toBe(3924);
  expect(count.exactIds).toBe(true);
  const result = await (await post('agent/three')).json();
  expect(result.measurements.map(value=>value.actualTokens)).toEqual([397,471,562]);
  const task = await env.DB.prepare('SELECT status,result_state FROM agent_cloud_tasks WHERE id=?').bind(result.taskId).first();
  expect(task).toEqual({status:'completed',result_state:'available'});
  const receipts = await env.DB.prepare(`SELECT r.state,r.charged_micros,u.applied FROM agent_budget_reservations r
    JOIN agent_usage_receipts u ON r.id=u.reservation_id WHERE r.task_id=?`).bind(result.taskId).all();
  expect(receipts.results).toEqual(Array.from({length:3},()=>({state:'settled',charged_micros:78,applied:1})));
});

test('入口流首帧出现在假供应商等待中，取消流后原任务仍能回查', async () => {
  const response = await post('agent/disconnect');
  const reader = response.body.getReader();
  const first = JSON.parse(new TextDecoder().decode((await reader.read()).value));
  expect(first.transportEntered).toBe(true);
  expect(await env.DB.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?').bind(first.taskId).first('status')).toBe('running');
  await reader.cancel();
  await new Promise(resolve=>setTimeout(resolve,4500));
  const result = await (await post('status/disconnect')).json();
  expect(result.taskId).toBe(first.taskId);
  expect(result.status).toBe('completed');
});

test('收据故障后驱逐重建，恢复只结算一次、不重发模型或改写失败答案', async () => {
  const result = await (await post('agent/receipt-fault')).json();
  const snapshot = ()=>env.DB.prepare('SELECT status,reason,result_state,result_json FROM agent_cloud_tasks WHERE id=?').bind(result.taskId).first();
  const before = await snapshot();
  expect(before.status).toBe('failed');
  const receipt = ()=>env.DB.prepare(`SELECT r.state,r.charged_micros,u.applied FROM agent_budget_reservations r
    JOIN agent_usage_receipts u ON r.id=u.reservation_id WHERE r.task_id=?`).bind(result.taskId).first();
  expect(await receipt()).toEqual({state:'uncertain',charged_micros:null,applied:0});
  await evictDurableObject(env.PROBE.getByName('synthetic-runner-20260916'));
  expect((await (await post('recover')).json()).receiptsApplied).toBe(1);
  expect(await receipt()).toEqual({state:'settled',charged_micros:78,applied:1});
  expect((await (await post('recover')).json()).receiptsApplied).toBe(0);
  expect(await snapshot()).toEqual(before);
  expect(await receipt()).toEqual({state:'settled',charged_micros:78,applied:1});
  expect(await env.DB.prepare(`SELECT count(*) AS n FROM agent_usage_receipts u
    JOIN agent_budget_reservations r ON r.id=u.reservation_id WHERE r.task_id=?`).bind(result.taskId).first('n')).toBe(1);
});

test('固定 Agent 用例不能重发，驱逐也不能清除尝试标记', async () => {
  const before = await env.DB.prepare('SELECT count(*) AS n FROM agent_cloud_tasks').first('n');
  await evictDurableObject(env.PROBE.getByName('synthetic-runner-20260916'));
  expect((await post('agent/three')).status).toBe(409);
  expect(await env.DB.prepare('SELECT count(*) AS n FROM agent_cloud_tasks').first('n')).toBe(before);
});

test('持久化业务上限拒绝第21次；其他对象不共享身份', async () => {
  const stub = env.PROBE.getByName('synthetic-runner-20260916');
  await runInDurableObject(stub, async (_instance, state) => { await state.storage.put('calls',20); });
  expect((await post('setup')).status).toBe(429);
  const other = env.PROBE.getByName('isolated-test-only');
  const identity = await runInDurableObject(other, (_instance,state)=>state.storage.get('identity'));
  expect(identity).toBeUndefined();
});
