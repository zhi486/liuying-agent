import { env } from 'cloudflare:workers';
import { applyD1Migrations, createScheduledController, createExecutionContext,
  waitOnExecutionContext, listDurableObjectIds } from 'cloudflare:test';
import { beforeAll, afterEach, expect, it, vi } from 'vitest';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

it('真实 scheduled 无模型配置也清理到期数据，不发网、不创建 DO', async () => {
  const owner = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const device = await createDeviceAdmin({ db: env.DB }).issue(owner);
  const tasks = createCloudTasks({ db: env.DB });
  const request = { clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(),
    prompt: '合成测试提示，不输出日志', consent: { cloudProcessing: true, runSummaries: false }, summaries: [] };
  const task = (await tasks.accept(`Bearer ${device.token}`, request)).task;
  // 保持原30秒约束，只将合成记录整体移到过去。
  const past = Date.now() - 90000000;
  await env.DB.prepare('UPDATE agent_cloud_tasks SET accepted_at=?,deadline_at=?,updated_at=?,payload_expires_at=? WHERE id=?')
    .bind(past, past + 30000, past, past + 86400000, task.id).run();
  const before = (await listDurableObjectIds(env.AGENT_RUNTIME)).length;
  const fetch = vi.fn(() => { throw new Error('维护不得调用网络'); });
  vi.stubGlobal('fetch', fetch);
  const log = vi.spyOn(console, 'log').mockImplementation(() => {});
  const ctx = createExecutionContext();
  await worker.scheduled(createScheduledController(), { ...env, DEEPSEEK_API_KEY: '', RATE_CARD_JSON: '' }, ctx);
  await waitOnExecutionContext(ctx);
  expect(await env.DB.prepare('SELECT status,payload_digest FROM agent_cloud_tasks WHERE id=?').bind(task.id).first())
    .toEqual({ status: 'timed_out', payload_digest: null });
  expect(fetch).not.toHaveBeenCalled();
  expect((await listDurableObjectIds(env.AGENT_RUNTIME)).length).toBe(before);
  const result = JSON.parse(log.mock.calls.at(-1)[0]);
  expect(Object.keys(result).sort()).toEqual(['event','status','processed','receiptsApplied','closed','uncertain','cursorSaved'].sort());
  expect(result).toMatchObject({ event: 'agent_maintenance', status: 'completed', cursorSaved: true });
});

it('维护失败以静态事件与错误报告，不吞错、不输出原始数据库异常', async () => {
  const log = vi.spyOn(console, 'error').mockImplementation(() => {});
  const ctx = createExecutionContext();
  await expect(worker.scheduled(createScheduledController(), { ...env,
    DB: { prepare() { throw new Error('模拟敏感异常'); } } }, ctx))
    .rejects.toThrow('maintenance_unavailable');
  await waitOnExecutionContext(ctx);
  expect(log.mock.calls).toEqual([[JSON.stringify({ event: 'agent_maintenance', status: 'failed' })]]);
});
