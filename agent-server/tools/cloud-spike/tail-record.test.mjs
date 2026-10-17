import assert from 'node:assert/strict';
import { test } from 'node:test';
import { tailRecord } from './tail-record.mjs';

test('DO与入口CPU保留实际字段但不保存头、查询、任意日志或异常内容',()=>{
  const record = tailRecord({cpuTime:19,wallTime:300,outcome:'ok',entrypoint:'LiuyingRuntimeProbe',
    executionModel:'durableObject',event:{rpcMethod:'run',request:{url:'https://probe.invalid/do/setup?secret=value',
      headers:{authorization:'secret'}}},exceptions:[{name:'Error',message:'secret'}],
    logs:[{message:['spike-do',JSON.stringify({role:'do',phase:'completed',probeId:'id',path:'setup',secret:'value'})]},
      {message:['other secret']}]});
  assert.equal(record.cpuTime,19);
  assert.equal(record.entrypoint,'LiuyingRuntimeProbe');
  assert.equal(record.rpcMethod,'run');
  assert.equal(record.logs[0].role,'do');
  assert.ok(!JSON.stringify(record).includes('secret'));
  assert.equal(tailRecord({}).cpuTime,undefined);
});
