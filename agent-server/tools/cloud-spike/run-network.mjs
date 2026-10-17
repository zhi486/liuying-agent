import { readFileSync, writeFileSync } from 'node:fs';
import { disconnectRequest } from './disconnect-client.mjs';
import { waitReady } from './readiness.mjs';

const name = process.argv[2];
if (!['disconnect', 'receipt-fault', 'recover'].includes(name)) throw new Error('请选择固定网络探针');
const base = 'https://liuying-free-spike-20260915.fangran31415927.workers.dev';
const token = readFileSync(new URL('./.output/session-token', import.meta.url), 'utf8').trim();
if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('隔离密钥格式错误');
const options = { method:'POST', headers:{ authorization:`Bearer ${token}` }, redirect:'error' };
await waitReady(async () => (await fetch(`${base}/ready-not-a-route`, {
  ...options, signal:AbortSignal.timeout(10000),
})).status);
const report = { case:name, startedAt:Date.now() };
try {
  if (name === 'disconnect') report.result = await disconnectRequest(`${base}/agent/disconnect`, token);
  else {
    const response = await fetch(`${base}/agent/${name}`, { ...options, signal:AbortSignal.timeout(20000) });
    report.status = response.status;
    if (response.status !== 200) throw new Error(`探针状态码 ${response.status}`);
    report.result = await response.json();
  }
  console.log(JSON.stringify(report));
} catch (error) {
  report.error = error.message;
  process.exitCode = 1;
  console.error(error.message);
} finally {
  report.finishedAt = Date.now();
  writeFileSync(new URL(`./.output/network-${name}-${report.startedAt}.json`, import.meta.url),
    JSON.stringify(report, null, 2), { flag:'wx' });
}
