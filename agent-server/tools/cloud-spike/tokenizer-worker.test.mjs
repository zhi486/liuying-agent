import assert from 'node:assert/strict';
import { test } from 'node:test';
import worker from './tokenizer-worker.mjs';
import cases from './.output/tokenizer-cases.json' with { type:'json' };

const token = 'fixture-only-not-a-secret-32-characters';
const env = () => ({ SPIKE_TOKEN:token, EXPIRES_AT:String(Date.now()+60000) });
const request = (path, given=token, method='POST') => new Request(`https://spike.invalid${path}`,
  { method, headers:{ authorization:`Bearer ${given}` } });

// 删除门禁或放开任意输入应失败；不要在测试里推导期望 token 数/hash。
test('tokenizer入口拒绝未授权、过期、未知用例和任意查询参数', async () => {
  assert.equal((await worker.fetch(request('/tokenizer/production-round-1','wrong'),env())).status,401);
  assert.equal((await worker.fetch(request('/tokenizer/production-round-1'),{...env(),EXPIRES_AT:'0'})).status,410);
  assert.equal((await worker.fetch(request('/tokenizer/anything'),env())).status,404);
  assert.equal((await worker.fetch(request('/tokenizer/production-round-1?repeat=100'),env())).status,404);
});
for (const item of cases) test(`云入口完整token ID对照独立参考：${item.name}`, async () => {
  const response = await worker.fetch(request(`/tokenizer/${item.name}`),env());
  assert.equal(response.status,200);
  const result = await response.json();
  assert.equal(result.actualTokens,item.inputTokens);
  assert.equal(result.tokenIdsSha256,item.tokenIdsSha256);
  assert.equal(result.exactIds,true);
});
