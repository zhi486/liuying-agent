import assert from 'node:assert/strict';
import { test } from 'node:test';
import { retainBackground } from '../src/background.ts';

test('宿主保活副本不暴露内部返回值或原始错误，原链保持原结果', async () => {
  const held: Promise<void>[] = [];
  const waitUntil = (pending: Promise<void>) => {
    held.push(pending);
  };
  const value = Promise.resolve({ privateToken: '只留在原链' });
  const failure = Promise.reject(new Error('不向宿主暴露原始内容'));
  retainBackground(value, waitUntil);
  retainBackground(failure, waitUntil);
  assert.deepEqual(await Promise.all(held), [undefined, undefined]);
  assert.deepEqual(await value, { privateToken: '只留在原链' });
  await assert.rejects(failure, /不向宿主暴露/);
});

test('宿主拒绝注册时返回静态账务错误，不泄露回调异常', () => {
  assert.throws(
    () =>
      retainBackground(Promise.resolve(), () => {
        throw new Error('PRIVATE-CONTEXT');
      }),
    /^Error: budget_unavailable$/,
  );
});
