import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import cases from './.output/tokenizer-cases.json' with {type:'json'};

export async function runPerformance(mode,request,report,wait) {
  assert.ok(mode === 'encode' || mode === 'count');
  let ready = false;
  for (let attempt=0;attempt<4;attempt++) {
    const response = await request('/perf/ready');
    if (response.status === 200) {
      const body = await response.json();
      ready = body.protocol === 'count-perf-v1' && body.mode === mode;
    } else if (![401,404,503].includes(response.status)) {
      throw new Error(`就绪检查停止：HTTP ${response.status}`);
    }
    if (ready) break;
    if (attempt<3) await wait(10000);
  }
  assert.ok(ready,'目标版本未在四次检查内就绪');
  const scheduled = [0,1].flatMap(pass=>cases.map(item=>({path:`/perf/${item.name}`,pass,
    expected:[item.inputTokens]})));
  scheduled.push({path:'/agent-perf/one',expected:[397]},
    {path:'/agent-perf/three',expected:[397,471,562]});
  for (const item of scheduled) {
    const row = {path:item.path,pass:item.pass,attemptedAt:Date.now()};
    report.rows.push(row);
    const response = await request(item.path);
    row.status = response.status;
    row.cfRay = response.headers.get('cf-ray');
    if (response.status !== 200) {
      const body = await response.text();
      row.platformError = body.match(/error code:\s*(\d+)/i)?.[1] ?? null;
      throw new Error(`业务请求停止：${item.path} HTTP ${response.status}`);
    }
    row.result = await response.json();
    assert.equal(row.result.mode,mode,'响应部署模式不一致');
    const counts = item.path.startsWith('/agent-perf/') ?
      row.result.measurements.map(value=>value.actualTokens) : [row.result.actualTokens];
    assert.deepEqual(counts,item.expected,'响应计数不一致');
    if (item.path.startsWith('/agent-perf/')) assert.ok(row.result.taskId);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const mode = process.argv[2];
  assert.ok(mode === 'encode' || mode === 'count','必须显式指定 encode 或 count');
  const token = readFileSync(new URL('./.output/session-token',import.meta.url),'utf8').trim();
  assert.match(token,/^[a-f0-9]{64}$/);
  const base = 'https://liuying-free-spike-20260915.fangran31415927.workers.dev';
  const request = path=>fetch(`${base}${path}`,{method:'POST',
    headers:{authorization:`Bearer ${token}`},redirect:'error',signal:AbortSignal.timeout(25000)});
  const report = {mode,startedAt:Date.now(),rows:[]};
  try { await runPerformance(mode,request,report,ms=>new Promise(resolve=>setTimeout(resolve,ms))); }
  catch (error) {
    // 不保存原始响应或请求头；失败的业务请求绝不自动重试。
    report.error = error.message; process.exitCode = 1;
  } finally {
    report.finishedAt = Date.now();
    writeFileSync(new URL(`./.output/counting-cloud-${mode}-${report.startedAt}.json`,import.meta.url),
      JSON.stringify(report,null,2),{flag:'wx'});
    console.log(JSON.stringify(report));
  }
}
