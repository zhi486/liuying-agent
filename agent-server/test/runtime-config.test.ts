import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readRuntimeConfig, REFERENCE_RATE } from '../src/runtime-config.ts';
import { CloudError } from '../src/cloud-task-contracts.ts';

const rate = { version: 'fixture', model: 'deepseek-flash', inputMicrosPerMillion: 300000,
  outputMicrosPerMillion: 1200000, checkedAtMillis: 1000, expiresAtMillis: 2000 };
const config = () => ({ DEEPSEEK_API_KEY: 'fixture-only', RATE_CARD_JSON: JSON.stringify(rate) });
const unavailable = (error: unknown) => error instanceof CloudError && error.code === 'unavailable';

test('密钥仍必需，缺失或非法费率使用明确标识的参考估算', () => {
  assert.deepEqual(readRuntimeConfig(config(), 1500), { apiKey: 'fixture-only', rate });
  for (const value of [undefined, null, '', ' ', 'key\nleak', 123])
    assert.throws(() => readRuntimeConfig({ ...config(), DEEPSEEK_API_KEY: value }, 1500), unavailable);
  for (const value of [undefined, null, '', '{}', '[]', '{', JSON.stringify({ ...rate, extra: true }),
    JSON.stringify({ ...rate, model: 'other' }), JSON.stringify({ ...rate, inputMicrosPerMillion: 0 }),
    JSON.stringify({ ...rate, expiresAtMillis: 86401001 })])
    assert.deepEqual(readRuntimeConfig({ ...config(), RATE_CARD_JSON: value }, 1500).rate, REFERENCE_RATE);
  assert.deepEqual(readRuntimeConfig(config(), 2000).rate, rate);
  assert.deepEqual(readRuntimeConfig(config(), 999).rate, REFERENCE_RATE);
  for (const now of [NaN, -1]) assert.throws(() => readRuntimeConfig(config(), now), unavailable);
});
