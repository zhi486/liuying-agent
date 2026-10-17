import { test } from 'node:test';
import assert from 'node:assert/strict';
import { analyzeEvidence,validateEvidenceSource } from './evidence.mjs';
const reference={rows:[{case:0,round:1,bodyHash:'abc',inputTokens:393}]};
const event={executionModel:'durableObject',cpuTime:42,wallTime:100,outcome:'ok',exceptionNames:[],logs:[
  {role:'do',phase:'begin',case:0,requestId:'request0',instance:'first'},
  {role:'do',phase:'count',tokens:393,bodyHash:'abc'},
  {role:'do',phase:'end',case:0,status:'completed'},
]};
test('计数按用例轮次与正文摘要双重对应，CPU 缺失不能伪造为零',()=>{
  const correct=analyzeEvidence([event],reference,[0]);
  assert.equal(correct.countsMatched,true);assert.equal(correct.cpuMeasured,true);
  assert.equal(correct.memory,'not-measured');
  assert.equal(analyzeEvidence([{...event,cpuTime:undefined}],reference,[0]).cpuMeasured,false);
  const wrong=structuredClone(event);wrong.logs[1].tokens=394;
  assert.equal(analyzeEvidence([wrong],reference,[0]).countsMatched,false);
  assert.equal(analyzeEvidence([],reference,[0]).countsMatched,false);
});
test('重复键零次计数允许，重复运行同一用例计数不允许',()=>{
  const replay={...event,logs:[event.logs[0],event.logs[2]]};
  assert.equal(analyzeEvidence([event,replay],reference,[0]).countsMatched,true);
  assert.equal(analyzeEvidence([event,event],reference,[0]).countsMatched,false);
});

test('ready 不能代替业务入口 CPU，需按用例与请求键关联',()=>{
  const ready={executionModel:'stateless',cpuTime:1,wallTime:2,outcome:'ok',exceptionNames:[],logs:[]};
  assert.equal(analyzeEvidence([event,ready],reference,[0]).entryCpuMeasured,false);
  const entry={...ready,logs:[{role:'entry',phase:'begin',kind:'submit',case:0,requestId:'request0'}]};
  assert.equal(analyzeEvidence([event,entry],reference,[0]).entryCpuMeasured,true);
  entry.logs[0].requestId='other';
  assert.equal(analyzeEvidence([event,entry],reference,[0]).entryCpuMeasured,false);
  assert.equal(analyzeEvidence([],reference,[]).sameInstanceColdHot,false);
});

test('只有专用断流入口 canceled 可接受，DO 或其他入口 canceled 仍失败',()=>{
  assert.equal(analyzeEvidence([{...event,outcome:'canceled'}],reference,[0]).failures.length,1);
  const entry={executionModel:'stateless',cpuTime:1,wallTime:2,outcome:'canceled',exceptionNames:[],logs:[
    {role:'entry',phase:'begin',kind:'submit',case:5},
  ]};
  assert.equal(analyzeEvidence([entry],reference,[]).failures.length,0);
  entry.logs[0].case=0;
  assert.equal(analyzeEvidence([entry],reference,[]).failures.length,1);
});

test('无屏障样本超10ms、缺失CPU或canceled不能性能通过',()=>{
  const options={deliveryBarrier:false};
  const entry={executionModel:'stateless',cpuTime:10,wallTime:100,outcome:'ok',exceptionNames:[],logs:[
    {role:'entry',phase:'begin',kind:'submit',case:0,requestId:'request0'},
  ]};
  assert.equal(analyzeEvidence([event,entry],reference,[0],options).entryWithin10ms,true);
  assert.equal(analyzeEvidence([event,{...entry,cpuTime:11}],reference,[0],options).entryWithin10ms,false);
  assert.equal(analyzeEvidence([event,{...entry,cpuTime:undefined}],reference,[0],options).entryWithin10ms,false);
  assert.equal(analyzeEvidence([event,{...entry,outcome:'canceled'}],reference,[0],options).failures.length,1);
  const incomplete=analyzeEvidence([], {rows:[]}, [0],options);
  assert.equal(incomplete.countsMatched,false);assert.equal(incomplete.entryWithin10ms,false);
});

test('新profile拒绝旧目标、旧窗口和错来源tail，不借用旧报告',()=>{
  const profile={name:'entry-20260917',target:'new-target'};
  const tagged={target:profile.target,profile:profile.name};
  const tail={...tagged,rows:[{scriptName:profile.target,eventTimestamp:150}]};
  const run={...tagged,startedAt:100,finishedAt:200};
  const window={...tagged,started:90,expires:250};
  assert.doesNotThrow(()=>validateEvidenceSource(profile,tail,tagged,run,window));
  assert.throws(()=>validateEvidenceSource(profile,{...tail,target:'old-target'},tagged,run,window));
  assert.throws(()=>validateEvidenceSource(profile,{...tail,rows:[{scriptName:'old-target',eventTimestamp:150}]},tagged,run,window));
  assert.throws(()=>validateEvidenceSource(profile,{...tail,rows:[{scriptName:profile.target,eventTimestamp:50}]},tagged,run,window));
});
