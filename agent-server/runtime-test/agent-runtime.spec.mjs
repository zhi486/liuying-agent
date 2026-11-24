import { env, RpcTarget } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, waitOnExecutionContext,
  listDurableObjectIds, runInDurableObject, evictDurableObject } from 'cloudflare:test';
import { beforeAll, afterEach, expect, it, vi } from 'vitest';
import { Tokenizer } from '@huggingface/tokenizers';
import { longPrompts, longPromptReferences } from './long-text-fixture.mjs';
import worker from '../src/worker.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';
import { recoverFreeExecutions } from '../src/execution-ledger.ts';
import { interceptD1 } from '../test/d1-interceptor.ts';

beforeAll(() => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
// 断言提前失败时也消费已启动 RPC，避免已放开的屏障继续污染下一项测试。
async function settleTestWork(pending) {
  let timer;
  try {
    await Promise.race([Promise.allSettled(pending), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('测试 RPC 收尾超时')), 10000);
    })]);
  } finally { clearTimeout(timer); }
}
const input = () => ({ clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(),
  prompt: '总结本次跑步', consent: { cloudProcessing: true, runSummaries: true },
  summaries: [{ runId: 'run_1', distanceMeters: 4000, durationSeconds: 1440,
    averagePaceSecPerKm: 360, averageHeartRate: 150 }] });
async function device() {
  const owner = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const issued = await createDeviceAdmin({ db: env.DB }).issue(owner);
  return { owner, deviceId: issued.deviceId, header: `Bearer ${issued.token}` };
}
function request(header, path = '/v1/agent/tasks', body = input(), method = 'POST') {
  return new Request(`https://local.invalid${path}`, { method,
    headers: { Authorization: header, 'Content-Type': 'application/json' },
    ...(method === 'GET' ? {} : { body: JSON.stringify(body) }) });
}
const final = () => Response.json({ choices: [{ index: 0, message: { role: 'assistant', content: '本次跑步已完成。' }, finish_reason: 'stop' }],
  usage: { prompt_tokens: 1000, completion_tokens: 20, total_tokens: 1020 } });
function provider(gate) {
  const dispatched = deferred();
  const mock = vi.fn(async (url, init) => {
    expect(url).toBe('https://api.deepseek.com/chat/completions');
    expect(init.method).toBe('POST');
    dispatched.resolve();
    if (gate) await gate.promise;
    return final();
  });
  vi.stubGlobal('fetch', mock);
  return { mock, dispatched };
}

it('未认证提交不创建 DO，未知管理端点不可达', async () => {
  const before = (await listDurableObjectIds(env.AGENT_RUNTIME)).length;
  const ctx = createExecutionContext();
  const response = await worker.fetch(request('invalid'), env, ctx);
  expect(response.status).toBe(401);
  expect(await response.json()).toEqual({ error: 'unauthenticated' });
  expect((await listDurableObjectIds(env.AGENT_RUNTIME)).length).toBe(before);
  expect((await worker.fetch(request('invalid', '/admin'), env, ctx)).status).toBe(404);
  await waitOnExecutionContext(ctx);
});

class Sink extends RpcTarget { async accepted() {} }

it.each(['stale', 'reference'])('超预算且旧停用标记存在时仍执行，%s 费率仅影响估算标签', async rateStatus => {
  const d = await device(); const { mock } = provider(); const body = input();
  await env.DB.prepare('UPDATE agent_budget_accounts SET monthly_limit_micros=1,halted=1 WHERE owner_id=?')
    .bind(d.owner).run();
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  await runInDurableObject(stub, instance => {
    const now = Date.now();
    instance.env = { ...instance.env, RATE_CARD_JSON: rateStatus === 'reference' ? undefined : JSON.stringify({
      version: 'expired_runtime_fixture', model: 'deepseek-flash', inputMicrosPerMillion: 300000,
      outputMicrosPerMillion: 1200000, checkedAtMillis: now - 86400000, expiresAtMillis: now - 1,
    }) };
  });
  const result = await stub.execute(d.header, body, new Sink());
  expect(result.ok).toBe(true);
  expect(result.task.status).toBe('completed');
  expect(result.task.result.cost).toEqual({ currency: 'USD', estimatedMicros: 324,
    inputTokens: 1000, outputTokens: 20, usageComplete: true, rateStatus });
  const repeat = await stub.execute(d.header, body, new Sink());
  expect(repeat.task.id).toBe(result.task.id);
  expect(mock).toHaveBeenCalledTimes(1);
  expect((await stub.execute(d.header, input(), new Sink())).task.status).toBe('completed');
  expect(mock).toHaveBeenCalledTimes(2);
  const row = await env.DB.prepare('SELECT SUM(charged_micros) amount FROM agent_budget_reservations WHERE owner_id=?')
    .bind(d.owner).first();
  expect(row.amount).toBe(648);
});

it('正式HTTP入口携带本次授权记忆，真实计数后仅派发一次且重放不可换记忆', async () => {
  const d = await device();
  const memory = { id: crypto.randomUUID(), version: 1, content: '偏好晚跑，回答简短😊' };
  const body = { ...input(), consent: { cloudProcessing: true, runSummaries: false }, summaries: [],
    memoryContext: { version: 'selected_memories_v1', approved: true, items: [memory] } };
  const { mock } = provider();
  const tokenize = vi.spyOn(Tokenizer.prototype, 'tokenize');
  const ctx = createExecutionContext();
  const response = await worker.fetch(request(d.header, undefined, body), env, ctx);
  expect(response.status).toBe(202);
  const { task } = await response.json();
  await waitOnExecutionContext(ctx);
  expect(mock).toHaveBeenCalledTimes(1);
  expect(tokenize).toHaveBeenCalledTimes(1);
  const messages = JSON.parse(mock.mock.calls[0][1].body).messages;
  expect(messages.filter(message => message.role === 'user')).toEqual([
    { role: 'user', content: JSON.stringify({ selected_memories_v1: [memory] }) },
    { role: 'user', content: body.prompt },
  ]);
  expect(messages[0].content).not.toContain(memory.content);
  const ctx2 = createExecutionContext();
  const result = await worker.fetch(request(d.header, `/v1/agent/tasks/${task.id}`, undefined, 'GET'), env, ctx2);
  expect((await result.json()).task.status).toBe('completed');
  const repeated = await worker.fetch(request(d.header, undefined, body), env, ctx2);
  expect((await repeated.json()).task.id).toBe(task.id);
  const changed = { ...body, memoryContext: { ...body.memoryContext, items: [{ ...memory, version: 2 }] } };
  expect((await worker.fetch(request(d.header, undefined, changed), env, ctx2)).status).toBe(409);
  const denied = { ...body, memoryContext: { ...body.memoryContext, approved: false } };
  expect((await worker.fetch(request(d.header, undefined, denied), env, ctx2)).status).toBe(400);
  await waitOnExecutionContext(ctx2);
  expect(mock).toHaveBeenCalledTimes(1);
});

it('构造期服务注入仍执行真实认证、计数与结算', async () => {
  const { LiuyingAgentRuntime } = await import('../src/agent-runtime.ts');
  const { createAgentService } = await import('../src/agent-service.ts');
  const d = await device(); const { mock } = provider();
  const injected = vi.fn(options => createAgentService({ ...options,
    fetch: async () => Response.json({ choices: [{ index: 0, finish_reason: 'stop',
      message: { role: 'assistant', content: '隔离传输完成' } }],
      usage: { prompt_tokens: 1000, completion_tokens: 20, total_tokens: 1020 } }) }));
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const result = await runInDurableObject(stub, async (_instance, state) => {
    const local = new LiuyingAgentRuntime(state, env, injected);
    return local.execute(d.header, input(), new Sink());
  });
  expect(result.ok).toBe(true); expect(result.task.result.answer).toBe('隔离传输完成');
  expect(injected).toHaveBeenCalledTimes(1); expect(mock).not.toHaveBeenCalled();
  const row = await env.DB.prepare('SELECT state,charged_micros FROM agent_budget_reservations WHERE task_id=?')
    .bind(result.task.id).first();
  expect(row).toEqual({ state: 'settled', charged_micros: 324 });
});

it('新长文本在冷对象和热对象都实际经过 BPE，计数与独立 Python 参考一致', async () => {
  const a = await device(); const b = await device();
  // 只观察真实库的方法，不替换实现；若 DO 改成查表或跳过计数，此断言会失败。
  const tokenize = vi.spyOn(Tokenizer.prototype, 'tokenize');
  const sent = [];
  vi.stubGlobal('fetch', vi.fn(async (url, init) => {
    expect(url).toBe('https://api.deepseek.com/chat/completions');
    expect(init.method).toBe('POST');
    sent.push(JSON.parse(init.body).messages.find(message => message.role === 'user').content);
    return final();
  }));
  for (const [d, prompt] of [[a, longPrompts[0]], [a, longPrompts[1]], [b, longPrompts[0]]]) {
    const result = await env.AGENT_RUNTIME.getByName(d.owner).execute(d.header, { ...input(), prompt }, new Sink());
    expect(result.ok).toBe(true);
    expect(result.task.status).toBe('completed');
  }
  expect(longPrompts.map(prompt => prompt.length)).toEqual([2000, 1999]);
  expect(sent).toEqual([longPrompts[0], longPrompts[1], longPrompts[0]]);
  // 同时锁定真正进入 BPE 的完整 prompt；独立参考测试防止只照抄待测计数刷新预期。
  const references = [longPromptReferences[0], longPromptReferences[1], longPromptReferences[0]];
  const hashes = await Promise.all(tokenize.mock.calls.map(async ([prompt]) => {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(prompt));
    return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
  }));
  expect(hashes).toEqual(references.map(row => row.promptSha256));
  expect(tokenize.mock.results.map(result => result.value.length)).toEqual(references.map(row => row.inputTokens));
});

it.each(['cancel', 'revoke'])('首轮已结算、第二轮派发前 %s，不再发送且只释放未发送预留', async action => {
  const d = await device(); const body = input(); const entered = deferred(); const gate = deferred();
  let dispatchGates = 0;
  const mock = vi.fn(async (url, init) => {
    expect(url).toBe('https://api.deepseek.com/chat/completions'); expect(init.method).toBe('POST');
    return Response.json({ choices: [{ index: 0, finish_reason: 'tool_calls',
    message: { role: 'assistant', content: null, tool_calls: [{ id: 'call_1', type: 'function',
      function: { name: 'runs_list', arguments: '{}' } }] } }],
    usage: { prompt_tokens: 1000, completion_tokens: 20, total_tokens: 1020 } });
  });
  vi.stubGlobal('fetch', mock);
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  await runInDurableObject(stub, instance => {
    instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, phase) => {
      if (phase === 'before' && sql.includes("SET dispatch_state = 'may_have_dispatched'") && ++dispatchGates === 2) {
        entered.resolve(); await gate.promise;
      }
    }) };
  });
  const work = stub.execute(d.header, body, new Sink());
  try {
    await entered.promise;
    const tasks = createCloudTasks({ db: env.DB }); const task = await tasks.findByRequest(d.header, body.clientRequestId);
    const rows = async () => (await env.DB.prepare(`SELECT r.state,r.dispatch_state,r.charged_micros,u.applied
      FROM agent_budget_reservations r LEFT JOIN agent_usage_receipts u ON u.reservation_id=r.id
      WHERE r.task_id=? ORDER BY r.request_id`).bind(task.id).all()).results;
    const settled = { state: 'settled', dispatch_state: 'may_have_dispatched', charged_micros: 324, applied: 1 };
    expect(await rows()).toEqual([settled, { state: 'reserved', dispatch_state: 'prepared', charged_micros: null, applied: null }]);
    if (action === 'cancel') await tasks.cancel(d.header, task.id);
    else await createDeviceAdmin({ db: env.DB }).revoke(d.owner, d.deviceId);
    gate.resolve(); await work;
    expect(mock).toHaveBeenCalledTimes(1);
    expect(await rows()).toEqual([settled, { state: 'released', dispatch_state: 'closed_not_dispatched', charged_micros: null, applied: null }]);
    expect(await env.DB.prepare('SELECT status,result_json FROM agent_cloud_tasks WHERE id=?').bind(task.id).first())
      .toEqual({ status: 'cancelled', result_json: null });
  } finally { gate.resolve(); await settleTestWork([work]); }
});

it('已捕获旧 service 的迟到取消通知不能中止同 owner 的新执行', async () => {
  const d = await device(); const a = input(); const b = input();
  const firstEntered = deferred(); const firstGate = deferred();
  const secondEntered = deferred(); const secondGate = deferred();
  const notifyEntered = deferred(); const notifyGate = deferred();
  const pending = [];
  let armed = false; let calls = 0; let secondSignal;
  vi.stubGlobal('fetch', vi.fn(async (url, init) => {
    expect(url).toBe('https://api.deepseek.com/chat/completions'); expect(init.method).toBe('POST');
    if (++calls === 1) { firstEntered.resolve(); await firstGate.promise; }
    else { secondSignal = init.signal; secondEntered.resolve(); await secondGate.promise; }
    return final();
  }));
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  await runInDurableObject(stub, instance => {
    instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, phase) => {
      if (armed && phase === 'before' && sql.includes("SET status = 'cancelled', reason = 'cancelled'")) {
        armed = false; notifyEntered.resolve(); await notifyGate.promise;
      }
    }) };
  });
  try {
    const first = stub.execute(d.header, a, new Sink());
    pending.push(first);
    await firstEntered.promise;
    const tasks = createCloudTasks({ db: env.DB }); const old = await tasks.findByRequest(d.header, a.clientRequestId);
    await tasks.cancel(d.header, old.id);
    armed = true;
    const notify = env.AGENT_RUNTIME.getByName(d.owner).interrupt(d.header, old.id, 'cancel');
    pending.push(notify);
    await notifyEntered.promise;
    firstGate.resolve(); await first;
    const second = env.AGENT_RUNTIME.getByName(d.owner).execute(d.header, b, new Sink());
    pending.push(second);
    await secondEntered.promise;
    notifyGate.resolve(); await notify;
    expect(secondSignal.aborted).toBe(false);
    expect((await tasks.findByRequest(d.header, b.clientRequestId)).status).toBe('running');
    secondGate.resolve();
    const completed = await second;
    expect(completed.task.status).toBe('completed');
    expect(completed.task.result.answer).toBe('本次跑步已完成。');
    expect((await tasks.get(d.header, old.id)).result).toBeNull();
    expect(calls).toBe(2);
  } finally {
    firstGate.resolve(); secondGate.resolve(); notifyGate.resolve();
    await settleTestWork(pending);
  }
});

it('请求指纹与结果各自保留 24 小时，到点清正文但永久保留原请求标识', async () => {
  const d = await device(); const body = input(); const acceptedAt = Date.now() + 10000;
  let time = acceptedAt;
  const tasks = createCloudTasks({ db: env.DB, now: () => time });
  const accepted = await tasks.accept(d.header, body);
  const execution = await tasks.claim(d.header, accepted.task.id);
  time += 1000;
  const answer = { status: 'completed', answer: '合成保留期验证', actions: [{ kind: 'model_requested', step: 1 }] };
  await tasks.finish(execution, answer);
  const expiry = await env.DB.prepare('SELECT payload_expires_at,result_expires_at FROM agent_cloud_tasks WHERE id=?')
    .bind(accepted.task.id).first();
  expect(expiry).toEqual({ payload_expires_at: acceptedAt + 86400000, result_expires_at: acceptedAt + 86401000 });
  time = expiry.payload_expires_at - 1;
  expect((await tasks.accept(d.header, body)).created).toBe(false);
  await expect(tasks.accept(d.header, { ...body, prompt: '改变输入' })).rejects.toThrow('conflict');
  time++;
  for (const prompt of [body.prompt, '改变输入'])
    await expect(tasks.accept(d.header, { ...body, prompt })).rejects.toThrow('request_expired');
  expect((await tasks.findByRequest(d.header, body.clientRequestId)).result).toEqual(answer);
  expect(await env.DB.prepare('SELECT payload_digest FROM agent_cloud_tasks WHERE id=?').bind(accepted.task.id).first('payload_digest')).toBeNull();
  time = expiry.result_expires_at - 1;
  expect((await tasks.get(d.header, accepted.task.id)).result).toEqual(answer);
  time++;
  const expired = await tasks.findByRequest(d.header, body.clientRequestId);
  expect(expired.status).toBe('completed'); expect(expired.resultState).toBe('expired'); expect(expired.result).toBeNull();
  expect(await env.DB.prepare('SELECT id,client_request_id,deadline_at,result_json FROM agent_cloud_tasks WHERE id=?')
    .bind(accepted.task.id).first()).toEqual({ id: accepted.task.id, client_request_id: body.clientRequestId,
      deadline_at: acceptedAt + 30000, result_json: null });
});

it('收据尚未提交时取消，迟到收据真正结算后也不能复活答案', async () => {
  const d = await device(); const body = input(); const entered = deferred(); const gate = deferred();
  const applied = deferred(); const { mock } = provider();
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  await runInDurableObject(stub, instance => {
    instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, phase) => {
      if (phase === 'before' && sql.includes('INSERT INTO agent_usage_receipts')) { entered.resolve(); await gate.promise; }
      if (phase === 'after' && sql.includes('UPDATE agent_usage_receipts SET applied = 1')) applied.resolve();
    }) };
  });
  const work = stub.execute(d.header, body, new Sink());
  try {
    await entered.promise;
    const tasks = createCloudTasks({ db: env.DB }); const task = await tasks.findByRequest(d.header, body.clientRequestId);
    const receipt = async () => env.DB.prepare(`SELECT u.applied,r.state,r.charged_micros FROM agent_budget_reservations r
      LEFT JOIN agent_usage_receipts u ON u.reservation_id=r.id WHERE r.task_id=?`).bind(task.id).first();
    expect(await receipt()).toEqual({ applied: null, state: 'reserved', charged_micros: null });
    await tasks.cancel(d.header, task.id);
    await env.AGENT_RUNTIME.getByName(d.owner).interrupt(d.header, task.id, 'cancel');
    expect(Date.now()).toBeLessThan(task.deadlineAtMillis);
    gate.resolve(); await applied.promise; await work;
    expect(Date.now()).toBeLessThan(task.deadlineAtMillis);
    expect(await receipt()).toEqual({ applied: 1, state: 'settled', charged_micros: 324 });
    const stored = await tasks.get(d.header, task.id);
    expect(stored.status).toBe('cancelled'); expect(stored.result).toBeNull();
    expect(stored.deadlineAtMillis).toBe(task.deadlineAtMillis);
    expect(mock).toHaveBeenCalledTimes(1);
  } finally { gate.resolve(); await settleTestWork([work]); }
});

it('首次同时提交同键只执行一次，两个 owner 可以独立执行', async () => {
  const a = await device(); const b = await device(); const body = input(); const { mock } = provider();
  const stub = env.AGENT_RUNTIME.getByName(a.owner);
  const [one, duplicate, other] = await Promise.all([
    stub.execute(a.header, body, new Sink()), stub.execute(a.header, body, new Sink()),
    env.AGENT_RUNTIME.getByName(b.owner).execute(b.header, body, new Sink()),
  ]);
  expect(one.ok && duplicate.ok && other.ok).toBe(true);
  expect(duplicate.task.id).toBe(one.task.id);
  expect(other.task.id).not.toBe(one.task.id);
  expect(mock).toHaveBeenCalledTimes(2);
  const tasks = createCloudTasks({ db: env.DB });
  await expect(tasks.get(a.header, other.task.id)).rejects.toThrow('not_found');
});

it('三轮真实计数与工具历史接线，收据按供应商 usage 结算而非本地计数', async () => {
  const d = await device(); const sent = [];
  vi.stubGlobal('fetch', vi.fn(async (url, init) => {
    expect(url).toBe('https://api.deepseek.com/chat/completions');
    const raw = String(init.body); sent.push(JSON.parse(raw));
    expect(sent.at(-1).model).toBe('deepseek-flash');
    expect(sent.at(-1).thinking).toEqual({ type: 'disabled' });
    if (sent.length === 3) return final();
    return Response.json({ choices: [{ index: 0, finish_reason: 'tool_calls', message: {
      role: 'assistant', content: null, tool_calls: [{ id: `call_${sent.length}`, type: 'function',
        function: sent.length === 1 ? { name: 'runs_list', arguments: '{}' }
          : { name: 'runs_summary', arguments: '{"runId":"run_1"}' } }],
    } }], usage: { prompt_tokens: 1000 + sent.length, completion_tokens: 20, total_tokens: 1020 + sent.length } });
  }));
  const result = await env.AGENT_RUNTIME.getByName(d.owner).execute(d.header, input(), new Sink());
  expect(result.ok).toBe(true); expect(result.task.status).toBe('completed');
  expect(sent).toHaveLength(3);
  expect(sent[0].messages).toHaveLength(2);
  expect(sent[1].messages.at(-1).role).toBe('tool');
  expect(sent[2].messages.at(-1).content).toContain('4000');
  const rows = (await env.DB.prepare(`SELECT r.state, u.input_tokens, u.applied FROM agent_budget_reservations r
    JOIN agent_usage_receipts u ON u.reservation_id=r.id WHERE r.task_id=? ORDER BY r.request_id`).bind(result.task.id).all()).results;
  expect(rows).toEqual([1001, 1002, 1000].map(input_tokens => ({ state: 'settled', input_tokens, applied: 1 })));
  expect((await recoverFreeExecutions({ db: env.DB })).receiptsApplied).toBe(0);
});

it('慢模型时 D1 取消立即返回，同一服务收到 abort，迟到答案不能复活', async () => {
  const d = await device(); const entered = deferred(); const gate = deferred(); let signal;
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => { signal = init.signal; entered.resolve(); await gate.promise; return final(); }));
  const ctx = createExecutionContext(); const body = input();
  const accepted = await worker.fetch(request(d.header, undefined, body), env, ctx);
  const { task } = await accepted.json(); await entered.promise;
  const control = createExecutionContext();
  const response = await worker.fetch(new Request(`https://local.invalid/v1/agent/tasks/${task.id}/cancel`,
    { method: 'POST', headers: { Authorization: d.header } }), env, control);
  expect(response.status).toBe(200); expect((await response.json()).task.status).toBe('cancelled');
  await waitOnExecutionContext(control); expect(signal.aborted).toBe(true);
  gate.resolve(); await waitOnExecutionContext(ctx);
  const stored = await createCloudTasks({ db: env.DB }).get(d.header, task.id);
  expect(stored.status).toBe('cancelled'); expect(stored.result).toBeNull();
  const row = await env.DB.prepare('SELECT state,dispatch_state FROM agent_budget_reservations WHERE task_id=?').bind(task.id).first();
  expect(row).toEqual({ state: 'uncertain', dispatch_state: 'may_have_dispatched' });
});

it('真正拒绝的 transport 保留未知费用，按原请求键恢复不重发', async () => {
  const d = await device(); const body = input();
  const mock = vi.fn(async () => { throw new TypeError('合成断网'); }); vi.stubGlobal('fetch', mock);
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const result = await stub.execute(d.header, body, new Sink());
  expect(result.ok).toBe(true); expect(result.task.reason).toBe('model_unavailable');
  const rows = (await env.DB.prepare('SELECT state,dispatch_state FROM agent_budget_reservations WHERE task_id=?').bind(result.task.id).all()).results;
  expect(rows).toEqual([{ state: 'uncertain', dispatch_state: 'may_have_dispatched' }]);
  expect((await stub.execute(d.header, body, new Sink())).task.id).toBe(result.task.id);
  expect(mock).toHaveBeenCalledTimes(1);
});

it.each(['cancel', 'revoke'])('计数后派发门禁前 %s，不能发送模型请求', async action => {
  const d = await device(); const body = input(); const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const entered = deferred(); const gate = deferred(); const { mock } = provider();
  await runInDurableObject(stub, instance => {
    instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, phase) => {
      if (phase === 'before' && sql.includes("SET dispatch_state = 'may_have_dispatched'")) {
        entered.resolve(); await gate.promise;
      }
    }) };
  });
  const work = stub.execute(d.header, body, new Sink());
  await entered.promise;
  const tasks = createCloudTasks({ db: env.DB }); const task = await tasks.findByRequest(d.header, body.clientRequestId);
  if (action === 'cancel') await tasks.cancel(d.header, task.id);
  else await createDeviceAdmin({ db: env.DB }).revoke(d.owner, d.deviceId);
  gate.resolve(); await work;
  expect(mock).not.toHaveBeenCalled();
  expect(await env.DB.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?').bind(task.id).first('status')).toBe('cancelled');
});

it('结果确认、撤回和到期沿用 D1 语义，不回传原输入或身份', async () => {
  const d = await device(); provider(); const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const tasks = createCloudTasks({ db: env.DB });
  const result = await stub.execute(d.header, input(), new Sink());
  expect((await tasks.acknowledge(d.header, result.task.id)).resultState).toBe('acknowledged');
  expect((await tasks.get(d.header, result.task.id)).result).toBeNull();
  const second = await stub.execute(d.header, input(), new Sink());
  expect((await tasks.withdraw(d.header, second.task.id)).resultState).toBe('withdrawn');
  const third = await stub.execute(d.header, input(), new Sink());
  await env.DB.prepare('UPDATE agent_cloud_tasks SET result_expires_at=? WHERE id=?').bind(Date.now() - 1, third.task.id).run();
  const expired = await tasks.get(d.header, third.task.id);
  expect(expired.resultState).toBe('expired'); expect(expired.result).toBeNull();
  expect(JSON.stringify(expired)).not.toContain(d.header);
});

it('取消时继续等已开始的收据落库，再幂等恢复，不复活答案', async () => {
  const d = await device(); const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const entered = deferred(); const gate = deferred(); const body = input(); provider();
  await runInDurableObject(stub, instance => {
    instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, phase) => {
      if (phase === 'after' && sql.includes('INSERT INTO agent_usage_receipts')) {
        entered.resolve(); await gate.promise;
      }
    }) };
  });
  let finished = false;
  const work = Promise.resolve(stub.execute(d.header, body, new Sink())).then(reply => { finished = true; return reply; });
  await entered.promise;
  const tasks = createCloudTasks({ db: env.DB }); const task = await tasks.findByRequest(d.header, body.clientRequestId);
  await tasks.cancel(d.header, task.id);
  await env.AGENT_RUNTIME.getByName(d.owner).interrupt(d.header, task.id, 'cancel');
  expect(finished).toBe(false);
  gate.resolve(); await work;
  const receipt = await env.DB.prepare(`SELECT u.applied, r.state FROM agent_usage_receipts u
    JOIN agent_budget_reservations r ON r.id=u.reservation_id WHERE r.task_id=?`).bind(task.id).first();
  expect(receipt).toEqual({ applied: 1, state: 'settled' });
  expect((await tasks.get(d.header, task.id)).result).toBeNull();
  expect((await recoverFreeExecutions({ db: env.DB })).receiptsApplied).toBe(0);
});

// 插件中断 API 的已知诊断红例，仍不计为通过；进程强制退出恢复另由 test:restart 独立验证。
it.skipIf(!env.RUN_UNSAFE_ABORT_REPRO)('待验：实例被中断后不重建 run，未应用收据由可信恢复恰好结算一次', async () => {
  const d = await device(); const body = input(); const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const entered = deferred(); provider();
  await runInDurableObject(stub, instance => {
    instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, phase) => {
      if (phase === 'after' && sql.includes('INSERT INTO agent_usage_receipts')) {
        entered.resolve(); await new Promise(() => {});
      }
    }) };
  });
  const work = Promise.resolve(stub.execute(d.header, body, new Sink())).catch(() => null);
  await entered.promise;
  const task = await createCloudTasks({ db: env.DB }).findByRequest(d.header, body.clientRequestId);
  await runInDurableObject(env.AGENT_RUNTIME.getByName(d.owner), (_instance, state) => state.abort('合成实例中断'))
    .catch(() => {});
  await work;
  const replacement = env.AGENT_RUNTIME.getByName(d.owner);
  const duplicate = await replacement.execute(d.header, body, new Sink());
  expect(duplicate.ok).toBe(true); expect(duplicate.task.id).toBe(task.id);
  expect(duplicate.task.deadlineAtMillis).toBe(task.deadlineAtMillis);
  const first = await recoverFreeExecutions({ db: env.DB });
  expect(first.receiptsApplied).toBe(1);
  expect((await recoverFreeExecutions({ db: env.DB })).receiptsApplied).toBe(0);
  expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  const stored = await runInDurableObject(replacement, (_instance, state) => state.storage.sql.exec('SELECT * FROM runtime_owner').toArray());
  expect(stored).toEqual([{ singleton: 1, owner: d.owner, version: 1 }]);
});

it('挂起 prepare、transport 和收据都受原 30 秒约束；迟到 prepare 不执行', async () => {
  const cases = await Promise.all(['prepare', 'transport', 'receipt'].map(async phase => ({
    phase, d: await device(), body: input(), entered: deferred(), gate: deferred(),
  })));
  const dispatched = [];
  vi.stubGlobal('fetch', vi.fn(async (_url, init) => {
    const body = JSON.parse(init.body); const phase = body.messages.find(item => item.role === 'user').content;
    dispatched.push(phase);
    const item = cases.find(item => item.phase === phase);
    if (phase === 'transport') { item.entered.resolve(); await item.gate.promise; }
    return final();
  }));
  for (const item of cases) {
    item.body.prompt = item.phase;
    item.stub = env.AGENT_RUNTIME.getByName(item.d.owner);
    if (item.phase === 'transport') continue;
    await runInDurableObject(item.stub, instance => {
      instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, phase) => {
        if (phase === 'after' && sql.includes(item.phase === 'prepare' ? 'INSERT INTO agent_cloud_tasks' : 'INSERT INTO agent_usage_receipts')) {
          item.entered.resolve(); await item.gate.promise;
        }
      }) };
    });
  }
  const started = performance.now();
  const works = cases.map(item => Promise.resolve(item.stub.execute(item.d.header, item.body, new Sink())));
  await Promise.all(cases.map(item => item.entered.promise));
  const replies = await Promise.all(works);
  expect(performance.now() - started).toBeLessThan(34000);
  expect(replies).toEqual(cases.map(() => ({ ok: false, error: 'unavailable' })));
  expect(dispatched.sort()).toEqual(['receipt', 'transport']);
  for (const item of cases) item.gate.resolve();
  for (const item of cases) {
    const task = await createCloudTasks({ db: env.DB }).findByRequest(item.d.header, item.body.clientRequestId);
    expect(task.status).toBe('timed_out');
    expect((await env.AGENT_RUNTIME.getByName(item.d.owner).execute(item.d.header, item.body, new Sink())).task.id).toBe(task.id);
  }
  expect(dispatched.sort()).toEqual(['receipt', 'transport']);
}, 40000);

it('早回执对应 D1，完整 RPC 保活至结果落库，重复键只派发一次', async () => {
  const d = await device(); const body = input(); const gate = deferred();
  const { mock, dispatched } = provider(gate);
  const ctx = createExecutionContext();
  const response = await worker.fetch(request(d.header, undefined, body), env, ctx);
  expect(response.status).toBe(202);
  const { task } = await response.json();
  expect(await env.DB.prepare('SELECT id FROM agent_cloud_tasks WHERE id=?').bind(task.id).first('id')).toBe(task.id);
  await dispatched.promise;
  let drained = false;
  const drain = waitOnExecutionContext(ctx).then(() => { drained = true; });
  const ctx2 = createExecutionContext();
  const duplicate = await worker.fetch(request(d.header, undefined, body), env, ctx2);
  expect((await duplicate.json()).task.id).toBe(task.id);
  expect(drained).toBe(false);
  const busy = await worker.fetch(request(d.header), env, ctx2);
  expect(busy.status).toBe(409);
  expect(await busy.json()).toEqual({ error: 'busy' });
  const conflict = await worker.fetch(request(d.header, undefined, { ...body, prompt: '另一句话' }), env, ctx2);
  expect(await conflict.json()).toEqual({ error: 'conflict' });
  gate.resolve(); await drain; await waitOnExecutionContext(ctx2);
  expect(mock).toHaveBeenCalledTimes(1);
  const result = await worker.fetch(request(d.header, `/v1/agent/tasks/${task.id}`, undefined, 'GET'), env, ctx2);
  expect((await result.json()).task.status).toBe('completed');
});

it('DO 独立鉴权和持久 owner 绑定，回执失败不阻止执行，重建不重发', async () => {
  const a = await device(); const b = await device(); const { mock } = provider();
  const stub = env.AGENT_RUNTIME.getByName(a.owner); const body = input();
  class LostReceipt extends RpcTarget { async accepted() { throw new Error('合成回执断流'); } }
  const result = await stub.execute(a.header, body, new LostReceipt());
  expect(result.ok).toBe(true); expect(result.task.status).toBe('completed');
  expect(await stub.execute(b.header, input(), new LostReceipt())).toEqual({ ok: false, error: 'unauthenticated' });
  const stored = await runInDurableObject(stub, (_instance, state) => state.storage.sql.exec('SELECT * FROM runtime_owner').toArray());
  expect(stored).toEqual([{ singleton: 1, owner: a.owner, version: 1 }]);
  await evictDurableObject(stub);
  expect((await stub.execute(a.header, body, new LostReceipt())).task.id).toBe(result.task.id);
  expect(mock).toHaveBeenCalledTimes(1);
});

it('永不响应的回执不能阻塞完整 RPC 或占住已完成的执行槽', async () => {
  const d = await device(); const { mock } = provider();
  class HungReceipt extends RpcTarget { async accepted() { await new Promise(() => {}); } }
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const result = await stub.execute(d.header, input(), new HungReceipt());
  expect(result.ok).toBe(true);
  expect(result.task.status).toBe('completed');
  expect((await stub.execute(d.header, input(), new HungReceipt())).ok).toBe(true);
  expect(mock).toHaveBeenCalledTimes(2);
}, 10000);

it('费率失效后原请求仍核验指纹返回结果，新意图正常执行并标注旧费率', async () => {
  const d = await device(); const { mock } = provider(); const body = input();
  class Sink extends RpcTarget { async accepted() {} }
  const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const first = await stub.execute(d.header, body, new Sink());
  expect(first.ok).toBe(true);
  vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 7200000);
  expect((await stub.execute(d.header, body, new Sink())).task?.id).toBe(first.task.id);
  expect(await stub.execute(d.header, { ...body, prompt: '改变内容' }, new Sink()))
    .toEqual({ ok: false, error: 'conflict' });
  const next = await stub.execute(d.header, input(), new Sink());
  expect(next.ok).toBe(true);
  expect(next.task.status).toBe('completed');
  expect(next.task.result.cost.rateStatus).toBe('stale');
  expect(mock).toHaveBeenCalledTimes(2);
});

it('RPC 早失败和完成但无回执不会挂等，Worker 只返回静态错误', async () => {
  const d = await device();
  for (const reply of [{ ok: false, error: 'busy' }, { ok: true, task: {} }]) {
    const ctx = createExecutionContext();
    const isolated = { ...env, AGENT_RUNTIME: { getByName: () => ({ execute: async () => reply }) } };
    const response = await worker.fetch(request(d.header), isolated, ctx);
    expect(response.status).toBe(reply.ok ? 503 : 409);
    expect(await response.json()).toEqual({ error: reply.ok ? 'unavailable' : 'busy' });
    await waitOnExecutionContext(ctx);
  }
});

it('收据已持久化但结算故障，优雅重建后可信恢复恰好应用一次', async () => {
  const d = await device(); const body = input(); const stub = env.AGENT_RUNTIME.getByName(d.owner);
  const { mock } = provider();
  await runInDurableObject(stub, instance => {
    instance.env = { ...instance.env, DB: interceptD1(env.DB, async (sql, phase) => {
      if (phase === 'after' && sql.includes('INSERT INTO agent_usage_receipts')) throw new Error('合成收据后故障');
    }) };
  });
  const result = await stub.execute(d.header, body, new Sink());
  expect(result.ok).toBe(true); expect(result.task.status).toBe('failed');
  await evictDurableObject(stub);
  const replay = await env.AGENT_RUNTIME.getByName(d.owner).execute(d.header, body, new Sink());
  expect(replay.task.id).toBe(result.task.id);
  expect(replay.task.deadlineAtMillis).toBe(result.task.deadlineAtMillis);
  // 测试库可能已有别的未知项，只取本次收据游标，不把管理恢复暴露给 HTTP。
  const row = await env.DB.prepare('SELECT id FROM agent_budget_reservations WHERE task_id=?').bind(result.task.id).first();
  const { applyUsageReceipt } = await import('../src/usage-receipts.ts');
  expect(await env.DB.prepare('SELECT applied FROM agent_usage_receipts WHERE reservation_id=?').bind(row.id).first('applied')).toBe(0);
  await applyUsageReceipt({ db: env.DB, ownerId: d.owner, reservationId: row.id });
  await applyUsageReceipt({ db: env.DB, ownerId: d.owner, reservationId: row.id });
  expect(await env.DB.prepare('SELECT applied FROM agent_usage_receipts WHERE reservation_id=?').bind(row.id).first('applied')).toBe(1);
  expect(mock).toHaveBeenCalledTimes(1);
});
