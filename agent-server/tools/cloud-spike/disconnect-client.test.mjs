import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { disconnectRequest } from './disconnect-client.mjs';

// 若客户端完整读完响应而非关闭真实 socket，此测试应失败。
test('收到任务编号后关闭尚未结束的真实连接', async (t) => {
  const server = createServer((req, res) => {
    res.writeHead(200, { 'content-type':'application/x-ndjson' });
    res.write(JSON.stringify({ taskId:'fixture-task', transportEntered:true }) + '\n');
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => server.close());
  const result = await disconnectRequest(`http://127.0.0.1:${server.address().port}/`, 'fixture');
  assert.equal(result.taskId, 'fixture-task');
  assert.equal(result.responseCompleteBeforeDestroy, false);
  assert.equal(result.socketDestroyed, true);
  assert.equal(result.socketCloseObserved, true);
});

test('拒绝没有任务首帧的已结束响应，不声称断网成功', async (t) => {
  const server = createServer((req, res) => { res.end('{}'); });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => server.close());
  await assert.rejects(disconnectRequest(`http://127.0.0.1:${server.address().port}/`, 'fixture'));
});
