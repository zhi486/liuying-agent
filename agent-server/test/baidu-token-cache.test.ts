import assert from 'node:assert/strict';
import { test } from 'node:test';
import { baiduCredentialId, createBaiduTokenCache } from '../src/baidu-token-cache.ts';

test('令牌缓存有界、提前到期，缺失或异常有效期不缓存', () => {
  let now = 1000;
  const cache = createBaiduTokenCache(() => now);
  const entry = cache.put('first', 'synthetic-token', 2592000, now);
  assert.equal(cache.get('first'), entry);
  now += 3540000;
  assert.equal(cache.get('first'), undefined);
  for (const expires of [undefined, null, '3600', 0, 60, -1, Infinity, NaN]) {
    cache.put('first', 'synthetic-token', expires, now);
    assert.equal(cache.get('first'), undefined);
  }
});

test('密钥切换不复用旧令牌，迟到失败不清除较新缓存', async () => {
  const first = await baiduCredentialId('key', 'first-secret');
  const next = await baiduCredentialId('key', 'next-secret');
  assert.notEqual(first, next);
  assert.match(first, /^[a-f0-9]{64}$/);
  const cache = createBaiduTokenCache(() => 1000);
  const old = cache.put(first, 'old-token', 3600, 1000);
  assert.equal(cache.get(next), undefined);
  const current = cache.put(next, 'new-token', 3600, 1000);
  cache.invalidate(old);
  assert.equal(cache.get(next), current);
  cache.invalidate(current);
  assert.equal(cache.get(next), undefined);
});
