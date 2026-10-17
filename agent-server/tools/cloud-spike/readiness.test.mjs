import assert from 'node:assert/strict';
import { test } from 'node:test';
import { waitReady } from './readiness.mjs';

test('只有当前令牌访问不存在路径得到404才判定新版本已就绪', async () => {
  const statuses = [503, 401, 404];
  assert.equal(await waitReady(async () => statuses.shift(), async () => {}), 3);
});
test('部署不就绪最多检查四次，到期或其他错误直接停止', async () => {
  let calls = 0;
  await assert.rejects(waitReady(async () => { calls++; return 503; }, async () => {}), /尚未就绪/);
  assert.equal(calls, 4);
  calls = 0;
  await assert.rejects(waitReady(async () => { calls++; return 410; }, async () => {}), /410/);
  assert.equal(calls, 1);
});
