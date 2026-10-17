// 离线读取脱敏证据；缺失测量必须标为缺失，不能替代为零或墙钟时间。
import { readFile,readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { output,artifact,profile,target,requireExplicitProfile } from './control.mjs';

export function validateEvidenceSource(profile,tail,reference,run,window){
  for(const value of [tail,reference,run,window]){
    assert.equal(value.target,profile.target);assert.equal(value.profile,profile.name);
  }
  assert.ok(Number.isFinite(run.startedAt)&&Number.isFinite(run.finishedAt)&&run.finishedAt>=run.startedAt);
  assert.ok(window.started<=run.startedAt&&run.finishedAt<=window.expires);
  assert.ok(tail.rows.length>0);
  for(const row of tail.rows){
    assert.equal(row.scriptName,profile.target);
    assert.ok(Number.isFinite(row.eventTimestamp)&&row.eventTimestamp>=run.startedAt&&row.eventTimestamp<=run.finishedAt);
  }
}
export function analyzeEvidence(rows,reference,required=Array.from({length:10},(_,i)=>i),{deliveryBarrier=true}={}){
  const executions=[];const entry=[];const failures=[];
  for(const row of rows){
    const begin=row.logs.find(log=>log.role==='do'&&log.phase==='begin');
    const counting=row.logs.filter(log=>log.role==='do'&&log.phase==='count');
    if(begin)executions.push({case:begin.case,requestId:begin.requestId,instance:begin.instance,
      cpu:row.cpuTime,wall:row.wallTime,model:row.executionModel,entrypoint:row.entrypoint,rpcMethod:row.rpcMethod,
      outcome:row.outcome,counts:counting.map((value,i)=>({round:i+1,tokens:value.tokens,bodyHash:value.bodyHash}))});
    if(row.executionModel==='stateless')entry.push({cpu:row.cpuTime,wall:row.wallTime,outcome:row.outcome,
      kind:row.logs.find(log=>log.phase==='begin')?.kind,case:row.logs.find(log=>log.phase==='begin')?.case,
      requestId:row.logs.find(log=>log.phase==='begin')?.requestId});
    const expectedDisconnect=deliveryBarrier&&row.executionModel==='stateless'&&row.outcome==='canceled'
      &&row.logs.some(log=>log.role==='entry'&&log.phase==='begin'&&log.kind==='submit'&&log.case===5);
    if(row.exceptionNames.length || (row.outcome!=='ok'&&!expectedDisconnect))
      failures.push({outcome:row.outcome,exceptions:row.exceptionNames});
  }
  const comparison=required.map(index=>{
    const actual=executions.filter(value=>value.case===index).flatMap(value=>value.counts);
    const expected=reference.rows.filter(value=>value.case===index);
    return {case:index,actual,expected:expected.map(value=>({round:value.round,tokens:value.inputTokens,bodyHash:value.bodyHash})),
      matches:expected.length===(index===4?3:1)&&actual.length===expected.length && actual.every((value,i)=>value.round===expected[i].round
        && value.tokens===expected[i].inputTokens && value.bodyHash===expected[i].bodyHash)};
  });
  const numeric=value=>Number.isFinite(value)&&value>=0;
  const entryCoverage=required.map(index=>({case:index,measured:entry.some(value=>value.case===index&&value.kind==='submit'
    &&numeric(value.cpu)&&numeric(value.wall)&&executions.some(run=>run.case===index&&run.requestId===value.requestId))}));
  const cold=executions.find(value=>value.case===0)?.instance;
  const hot=executions.find(value=>value.case===1)?.instance;
  const entryCpuMeasured=entryCoverage.every(value=>value.measured)&&entry.length>0
    &&entry.every(value=>numeric(value.cpu)&&numeric(value.wall));
  return {memory:'not-measured',cpuScope:'完整调用，包含验收哈希与观察写入；不是纯 tokenizer CPU',
    countsMatched:comparison.every(value=>value.matches),comparison,executions,entry,failures,
    cpuMeasured:executions.length>0 && executions.every(value=>numeric(value.cpu)&&numeric(value.wall)),
    entryCoverage,entryCpuMeasured,entryWithin10ms:entryCpuMeasured&&entry.every(value=>value.cpu<=10&&value.outcome==='ok'),
    entryMaxCpuMs:entryCpuMeasured?Math.max(...entry.map(value=>value.cpu)):null,
    overLimitEntries:entry.filter(value=>numeric(value.cpu)&&value.cpu>10),
    runtimeIdentityMatched:executions.length>0 && executions.every(value=>value.model==='durableObject'
      && value.entrypoint==='VerificationRuntime' && value.rpcMethod==='execute'),
    sameInstanceColdHot:typeof cold==='string'&&cold.length>0&&cold===hot};
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
  requireExplicitProfile();
  const files=(await readdir(output)).filter(name=>/^tail-\d+\.json$/.test(name));
  if(files.length!==1)throw new Error('需要唯一一次 tail 证据');
  const tail=JSON.parse(await readFile(new URL(files[0],output),'utf8'));
  const reference=JSON.parse(await readFile(new URL('reference.json',output),'utf8'));
  let run;
  if(!profile.deliveryBarrier){
    run=JSON.parse(await readFile(new URL('run-report.json',output),'utf8'));
    const window=JSON.parse(await readFile(new URL('opened-once.json',output),'utf8'));
    validateEvidenceSource(profile,tail,reference,run,window);
  }
  const result={target,profile:profile.name,...analyzeEvidence(tail.rows,reference,undefined,profile)};
  result.tailParseError=tail.parseError;
  result.evidencePassed=!tail.parseError&&result.countsMatched&&result.cpuMeasured&&result.entryCpuMeasured
    &&result.runtimeIdentityMatched&&result.failures.length===0;
  result.passed=result.evidencePassed&&(profile.deliveryBarrier||
    (result.entryWithin10ms&&run.passed&&run.closed&&!run.tailLost&&!run.tailCaptureFailed));
  await artifact('metrics-evidence.json',result);
  console.log(JSON.stringify(result));
  if(!result.passed)process.exitCode=1;
}
