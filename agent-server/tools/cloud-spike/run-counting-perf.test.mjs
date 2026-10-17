import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runPerformance } from './run-counting-perf.mjs';
import cases from './.output/tokenizer-cases.json' with {type:'json'};

test('旧版本响应不能视为就绪，检查最多四次',async()=>{
  let calls=0,waits=0;
  await assert.rejects(runPerformance('count',async()=>{
    calls++; return Response.json({protocol:'count-perf-v1',mode:'encode'});
  },{rows:[]},async()=>{waits++;}));
  assert.equal(calls,4); assert.equal(waits,3);
});
test('业务失败不重试，保留已尝试用例',async()=>{
  const paths=[];const report={rows:[]};
  await assert.rejects(runPerformance('count',async path=>{
    paths.push(path);
    return path==='/perf/ready' ? Response.json({protocol:'count-perf-v1',mode:'count'}) :
      new Response('error code: 1102',{status:500});
  },report,async()=>{}));
  assert.equal(paths.length,2);assert.equal(report.rows.length,1);
  assert.equal(report.rows[0].platformError,'1102');
});
test('正确版本只执行十二次计数与两次Agent，不重复建任务',async()=>{
  const paths=[];const report={rows:[]};
  await runPerformance('encode',async path=>{
    paths.push(path);
    if(path==='/perf/ready')return Response.json({protocol:'count-perf-v1',mode:'encode'});
    const item=cases.find(row=>path===`/perf/${row.name}`);
    if(item)return Response.json({mode:'encode',actualTokens:item.inputTokens});
    const counts=path==='/agent-perf/one'?[397]:[397,471,562];
    return Response.json({mode:'encode',taskId:crypto.randomUUID(),
      measurements:counts.map(actualTokens=>({actualTokens}))});
  },report,async()=>{});
  assert.equal(report.rows.length,14);
  assert.equal(paths.filter(path=>path==='/agent-perf/one').length,1);
  assert.equal(paths.filter(path=>path==='/agent-perf/three').length,1);
});
