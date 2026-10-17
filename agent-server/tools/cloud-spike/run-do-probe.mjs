import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { disconnectRequest } from './disconnect-client.mjs';
import cases from './.output/tokenizer-cases.json' with {type:'json'};

export async function runDoProbe(request,disconnect,report,wait) {
  let ready = false;
  for(let attempt=0;attempt<4;attempt++) {
    const response = await request('/do/ready');
    if(response.status===200) ready = (await response.json()).protocol === 'do-runtime-v1';
    else if(![401,404,503].includes(response.status)) throw new Error(`就绪检查停止 HTTP ${response.status}`);
    if(ready) break;
    if(attempt<3) await wait(10000);
  }
  assert.ok(ready,'四次检查内未见目标协议');
  const scheduled = ['setup',...[0,1].flatMap(()=>cases.map(value=>`token/${value.name}`)),
    'agent/one','agent/three','agent/disconnect','status/disconnect','agent/receipt-fault','recover','recover'];
  assert.equal(scheduled.length,20);
  let disconnected, recovered = false;
  for(const path of scheduled) {
    assert.ok(report.rows.length<20,'业务请求上限');
    const row = {path:`/do/${path}`,attemptedAt:Date.now()};report.rows.push(row);
    if(path === 'agent/disconnect') {
      row.result = disconnected = await disconnect();
      assert.equal(disconnected.socketDestroyed,true);
      assert.equal(disconnected.socketCloseObserved,true);
      assert.equal(disconnected.responseCompleteBeforeDestroy,false);
      row.cutAt = Date.now();
      await wait(8000);
      continue;
    }
    const response = await request(row.path);
    row.status = response.status;row.cfRay = response.headers.get('cf-ray');
    assert.equal(response.status,200,`业务失败不重试：${path}`);
    row.result = await response.json();
    const item = cases.find(value=>path === `token/${value.name}`);
    if(item) {
      assert.equal(row.result.actualTokens,item.inputTokens);
      assert.equal(row.result.exactIds,true);
      assert.equal(row.result.tokenIdsSha256,item.tokenIdsSha256);
    } else if(path === 'status/disconnect') {
      assert.equal(row.result.taskId,disconnected.taskId);
      assert.equal(row.result.status,'completed');
    } else if(path === 'recover') {
      assert.equal(row.result.receiptsApplied,recovered?0:1);recovered=true;
    } else if(path.startsWith('agent/')) {
      assert.ok(row.result.taskId);
      assert.deepEqual(row.result.measurements.map(value=>value.actualTokens),path==='agent/three'?[397,471,562]:[397]);
      assert.ok(row.result.measurements.every(value=>value.exactIds));
    } else assert.match(row.result.ownerId,/^spike_/);
  }
}

if(process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const token = readFileSync(new URL('./.output/session-token',import.meta.url),'utf8').trim();
  assert.match(token,/^[a-f0-9]{64}$/);
  const base = 'https://liuying-free-spike-20260915.fangran31415927.workers.dev';
  const request = path=>fetch(`${base}${path}`,{method:'POST',headers:{authorization:`Bearer ${token}`},
    redirect:'error',signal:AbortSignal.timeout(25000)});
  const report = {startedAt:Date.now(),rows:[]};
  try {
    await runDoProbe(request,()=>disconnectRequest(`${base}/do/agent/disconnect`,token),report,
      ms=>new Promise(resolve=>setTimeout(resolve,ms)));
  } catch(error) { report.error=error.message;process.exitCode=1; }
  finally {
    report.finishedAt=Date.now();
    writeFileSync(new URL(`./.output/do-cloud-${report.startedAt}.json`,import.meta.url),JSON.stringify(report,null,2),{flag:'wx'});
    console.log(JSON.stringify(report));
  }
}
