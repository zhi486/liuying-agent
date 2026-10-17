import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import cases from './.output/tokenizer-cases.json' with { type:'json' };
import { waitReady } from './readiness.mjs';

const base = 'https://liuying-free-spike-20260915.fangran31415927.workers.dev';
const token = readFileSync(new URL('./.output/session-token',import.meta.url),'utf8').trim();
assert.match(token,/^[a-f0-9]{64}$/);
const options = { method:'POST', headers:{ authorization:`Bearer ${token}` }, redirect:'error' };
await waitReady(async () => (await fetch(`${base}/ready-not-a-route`, {
  ...options, signal:AbortSignal.timeout(10000),
})).status);
const report = { startedAt:Date.now(), rows:[] };
try {
  for (const item of cases) {
    const response = await fetch(`${base}/tokenizer/${item.name}`, { ...options,
      signal:AbortSignal.timeout(20000) });
    const row = { case:item.name, status:response.status, cfRay:response.headers.get('cf-ray') };
    report.rows.push(row);
    if (response.status !== 200) {
      // 错误只记录平台状态与通用错误编号，不保存可能包含连接信息的 HTML。
      const body = await response.text();
      row.platformError = body.match(/error code:\s*(\d+)/i)?.[1] ?? null;
      console.log(JSON.stringify(row));
      throw new Error(`固定用例 ${item.name} 未完成`);
    }
    row.result = await response.json();
    console.log(JSON.stringify(row));
    assert.equal(row.result.actualTokens,item.inputTokens);
    assert.equal(row.result.tokenIdsSha256,item.tokenIdsSha256);
  }
} catch (error) { report.error = error.message; console.error(error.message); process.exitCode=1; }
finally {
  report.finishedAt = Date.now();
  writeFileSync(new URL(`./.output/tokenizer-cloud-${report.startedAt}.json`,import.meta.url),
    JSON.stringify(report,null,2),{flag:'wx'});
}
