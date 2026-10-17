import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createRuntimeLifetime } from '../src/runtime-lifetime.ts';

function deferred() {
  let resolve!: () => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<void>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

test('动态收尾会等待旧 Promise 中新登记的链，不只等初始快照', async () => {
  const scope = createRuntimeLifetime();
  const first = deferred();
  const late = deferred();
  scope.waitUntil(first.promise.then(() => { scope.waitUntil(late.promise); }));
  let done = false;
  const draining = scope.drain().then(() => { done = true; });
  first.resolve();
  await new Promise<void>(resolve => setImmediate(resolve));
  assert.equal(done, false);
  late.resolve();
  await draining;
  assert.equal(done, true);
  scope.close();
});

test('永不完成的后台链受同一个原截止约束', async t => {
  let time = 100;
  t.mock.method(performance, 'now', () => time);
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const scope = createRuntimeLifetime();
  scope.waitUntil(new Promise<void>(() => {}));
  time += 29000;
  const result = assert.rejects(scope.drain(), { message: 'unavailable' });
  time += 1000;
  t.mock.timers.tick(1000);
  await result;
  assert.equal(scope.remaining(), 0);
  scope.waitUntil(Promise.reject(new Error('迟到私密异常')));
  await new Promise<void>(resolve => setImmediate(resolve));
  assert.throws(() => scope.setRemaining(30000), { message: 'unavailable' });
});

test('接单后只设置一次剩余期限，后续 drain 不能重新延长', async t => {
  let time = 0;
  t.mock.method(performance, 'now', () => time);
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const scope = createRuntimeLifetime();
  time = 20000;
  scope.setRemaining(5000);
  assert.equal(scope.remaining(), 5000);
  assert.throws(() => scope.setRemaining(30000), { message: 'unavailable' });
  time += 4000;
  scope.waitUntil(new Promise<void>(() => {}));
  const result = assert.rejects(scope.drain(), { message: 'unavailable' });
  time += 1000;
  t.mock.timers.tick(1000);
  await result;
  assert.equal(scope.remaining(), 0);
});

test('接单前等待超过 30 秒后，迟到成功不能重新获得生命周期', async t => {
  let time = 0;
  t.mock.method(performance, 'now', () => time);
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const scope = createRuntimeLifetime();
  const pending = deferred();
  const result = assert.rejects(scope.wait(pending.promise), { message: 'unavailable' });
  time = 30000;
  t.mock.timers.tick(30000);
  await result;
  pending.resolve();
  assert.throws(() => scope.setRemaining(30000), { message: 'unavailable' });
});

test('拒绝被消费但收尾不能假报成功，已关闭实例不可再次等待', async () => {
  const scope = createRuntimeLifetime();
  scope.waitUntil(Promise.reject(new Error('私密错误')));
  await assert.rejects(scope.drain(), { message: 'unavailable' });
  scope.close();
  await assert.rejects(scope.wait(Promise.resolve(1)), { message: 'unavailable' });
});

test('墙上时钟回拨不改变单调等待期限，超时后的已完成 Promise 也拒绝', async t => {
  let time = 0;
  t.mock.method(performance, 'now', () => time);
  const scope = createRuntimeLifetime();
  t.mock.method(Date, 'now', () => 0);
  time = 30001;
  assert.equal(scope.remaining(), 0);
  await assert.rejects(scope.wait(Promise.resolve(1)), { message: 'unavailable' });
});
