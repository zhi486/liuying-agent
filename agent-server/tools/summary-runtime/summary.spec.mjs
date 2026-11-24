import { env } from 'cloudflare:workers';
import { evictDurableObject, runInDurableObject } from 'cloudflare:test';
import { expect, test } from 'vitest';

const id = '11111111-1111-4111-8111-111111111111';
const input = { version: 'summary_v1', requestId: id, fingerprint: 'a'.repeat(64), approved: true,
  sources: [{ requestId: '22222222-2222-4222-8222-222222222222', prompt: '合成问题', answer: '合成回答' }] };
function call(stub, method, suffix = '', body) {
  return stub.fetch(`https://synthetic.example/v1/agent/summaries${suffix}`, { method,
    headers: { Authorization: 'Bearer synthetic', 'Content-Type': 'application/json' },
    ...(body ? { body: JSON.stringify(body) } : {}) });
}
test('DO驱逐重建后原请求只查询，撤回持久化且不重发', async () => {
  const stub = env.SUMMARY.getByName('recovery');
  const first = await call(stub, 'POST', '', input);
  const marker = first.headers.get('X-Test-Instance');
  expect((await first.json()).cost).toBe('uncertain');
  await evictDurableObject(stub);
  const recovered = await call(stub, 'POST', '', input);
  expect(recovered.headers.get('X-Test-Instance')).not.toBe(marker);
  expect((await recovered.json()).state).toBe('attempted');
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBe(1);
  expect((await (await call(stub, 'POST', `/${id}/withdraw`)).json()).state).toBe('withdrawn');
  await evictDurableObject(stub);
  expect((await (await call(stub, 'GET', `/${id}`)).json()).state).toBe('withdrawn');
  await (await call(stub, 'POST', '', input)).json();
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBe(1);
});
test('DO并发同键仅一次派发，撤回后冲突不改原记录', async () => {
  const stub = env.SUMMARY.getByName('concurrent');
  const results = await Promise.all(Array.from({ length: 8 }, () => call(stub, 'POST', '', input)));
  expect(results.map(result => result.status)).toEqual(Array(8).fill(200));
  await Promise.all(results.map(result => result.json()));
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBe(1);
  await (await call(stub, 'POST', `/${id}/withdraw`)).json();
  const conflict = await call(stub, 'POST', '', { ...input, fingerprint: 'b'.repeat(64) });
  expect(conflict.status).toBe(409);
  await conflict.json();
  await evictDurableObject(stub);
  const restored = await (await call(stub, 'GET', `/${id}`)).json();
  expect(restored.state).toBe('withdrawn');
  expect(restored.fingerprint).toBe(input.fingerprint);
  expect(restored.summary).toBeNull();
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBe(1);
});

test('DO生成期间撤回，迟到结果仅补用量且驱逐后不恢复正文', async () => {
  const stub = env.SUMMARY.getByName('late-result');
  await runInDurableObject(stub, instance => { instance.waitForResult = true; });
  const pending = call(stub, 'POST', '', input);
  try {
    await expect.poll(() => runInDurableObject(stub, instance => Boolean(instance.finishResult))).toBe(true);
    const repeated = await (await call(stub, 'POST', '', input)).json();
    expect(repeated.state).toBe('attempted');
    expect((await (await call(stub, 'POST', `/${id}/withdraw`)).json()).state).toBe('withdrawn');
  } finally {
    await runInDurableObject(stub, instance => {
      instance.finishResult?.({ text: '不得恢复正文', inputTokens: 7, outputTokens: 2 });
    });
  }
  const result = await (await pending).json();
  expect(result.state).toBe('withdrawn');
  expect(result.summary).toBeNull();
  expect(result.remoteFinished).toBe(true);
  expect(result.usage).toEqual({ inputTokens: 7, outputTokens: 2 });
  await evictDurableObject(stub);
  expect(await (await call(stub, 'GET', `/${id}`)).json()).toEqual(result);
  await (await call(stub, 'POST', '', input)).json();
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBe(1);
});

test('DO查询补记期间撤回完成，响应与驱逐恢复均无旧正文', async () => {
  const stub = env.SUMMARY.getByName('withdraw-during-receipt');
  await runInDurableObject(stub, instance => {
    instance.modelResult = { text: '不得交付的旧摘要', inputTokens: 7, outputTokens: 2 };
  });
  const created = await call(stub, 'POST', '', input);
  const marker = created.headers.get('X-Test-Instance');
  expect((await created.json()).state).toBe('completed');
  await runInDurableObject(stub, instance => { instance.waitForReceipt = true; });
  const pending = call(stub, 'GET', `/${id}`);
  try {
    await expect.poll(() => runInDurableObject(stub, instance => Boolean(instance.finishReceipt))).toBe(true);
    const withdrawn = await (await call(stub, 'POST', `/${id}/withdraw`)).json();
    expect(withdrawn.state).toBe('withdrawn');
    expect(withdrawn.summary).toBeNull();
  } finally {
    await runInDurableObject(stub, instance => { instance.finishReceipt?.(); });
  }
  const result = await (await pending).json();
  expect(result.state).toBe('withdrawn');
  expect(result.summary).toBeNull();
  expect(result.usage).toEqual({ inputTokens: 7, outputTokens: 2 });
  await evictDurableObject(stub);
  const restored = await call(stub, 'GET', `/${id}`);
  expect(restored.headers.get('X-Test-Instance')).not.toBe(marker);
  expect(await restored.json()).toEqual(result);
  expect(await (await call(stub, 'POST', '', input)).json()).toEqual(result);
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBe(1);
});

test('DO持久化确认失败不返回派发许可，恢复后同键不重发', async () => {
  const stub = env.SUMMARY.getByName('sync-failure');
  await runInDurableObject(stub, instance => { instance.failSync = true; });
  const failed = await call(stub, 'POST', '', input);
  expect(failed.status).toBe(503);
  await failed.json();
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBeUndefined();
  await runInDurableObject(stub, instance => { instance.failSync = false; });
  const recovered = await (await call(stub, 'POST', '', input)).json();
  expect(recovered.state).toBe('attempted');
  expect(recovered.cost).toBe('uncertain');
  await evictDurableObject(stub);
  expect(await (await call(stub, 'GET', `/${id}`)).json()).toEqual(recovered);
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBeUndefined();
});

test('DO实际写入失败时不派发，损坏记录不重建', async () => {
  const stub = env.SUMMARY.getByName('failure');
  await runInDurableObject(stub, (_i, state) => state.storage.sql.exec(`CREATE TRIGGER reject_insert
    BEFORE INSERT ON summaries BEGIN SELECT RAISE(ABORT, 'synthetic'); END`));
  const failed = await call(stub, 'POST', '', input);
  expect(failed.status).toBe(503);
  await failed.json();
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBeUndefined();
  await runInDurableObject(stub, (_i, state) => {
    state.storage.sql.exec('DROP TRIGGER reject_insert').toArray();
    state.storage.sql.exec('INSERT INTO summaries VALUES (?, ?, ?, ?)', 'synthetic', 'device', id, '{').toArray();
  });
  await evictDurableObject(stub);
  const corrupt = await call(stub, 'POST', '', input);
  expect(corrupt.status).toBe(503);
  await corrupt.json();
  expect(await runInDurableObject(stub, (_i, state) => state.storage.get('calls'))).toBeUndefined();
});
