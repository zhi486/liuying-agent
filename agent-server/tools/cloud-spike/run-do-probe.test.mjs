import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runDoProbe } from './run-do-probe.mjs';
import cases from './.output/tokenizer-cases.json' with {type:'json'};

test('只执行4次就绪检查，旧协议永不派发业务',async()=>{
  let calls=0;
  await assert.rejects(runDoProbe(async()=>{calls++;return Response.json({protocol:'old'});},
    ()=>assert.fail('不得断连'),{rows:[]},async()=>{}));
  assert.equal(calls,4);
});
test('业务失败保存尝试且不重试',async()=>{
  const paths=[],report={rows:[]};
  await assert.rejects(runDoProbe(async path=>{
    paths.push(path);return path.endsWith('ready') ? Response.json({protocol:'do-runtime-v1'}) : new Response(null,{status:500});
  },()=>assert.fail(),report,async()=>{}));
  assert.deepEqual(paths,['/do/ready','/do/setup']);
  assert.equal(report.rows.length,1);
});
test('完整业务序列恰好20次，回查同一断连任务，恢复为1再0',async()=>{
  const paths=[],report={rows:[]};let recover=0;
  await runDoProbe(async path=>{
    paths.push(path);
    if(path.endsWith('ready')) return Response.json({protocol:'do-runtime-v1'});
    const item=cases.find(value=>path.endsWith(`/token/${value.name}`));
    return Response.json(item ? {actualTokens:item.inputTokens,exactIds:true,tokenIdsSha256:item.tokenIdsSha256} :
      path.endsWith('recover') ? {receiptsApplied:recover++===0?1:0} :
      path.endsWith('status/disconnect') ? {taskId:'cut',status:'completed'} :
      path.endsWith('/setup') ? {ownerId:'spike_fixture'} :
      {taskId:'task',measurements:(path.endsWith('/three')?[397,471,562]:[397]).map(actualTokens=>({actualTokens,exactIds:true}))});
  },async()=>({taskId:'cut',socketDestroyed:true,socketCloseObserved:true,responseCompleteBeforeDestroy:false}),report,async()=>{});
  assert.equal(report.rows.length,20);
  assert.equal(paths.length,20);
  assert.equal(recover,2);
});
