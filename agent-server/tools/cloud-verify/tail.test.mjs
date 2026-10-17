import assert from 'node:assert/strict';
import { test } from 'node:test';

test('云日志只保留数值和静态关联字段，不保存请求头、正文及异常消息',async()=>{
  const { sanitizeTail }=await import('./tail.mjs');
  const row=sanitizeTail({cpuTime:7,wallTime:123,executionModel:'durableObject',entrypoint:'VerificationRuntime',outcome:'ok',
    event:{rpcMethod:'execute',request:{url:'https://example.invalid/?token=PRIVATE',headers:{Authorization:'PRIVATE'},body:'PRIVATE'}},
    exceptions:[{name:'Error',message:'PRIVATE',stack:'PRIVATE'}],logs:[{message:[JSON.stringify({message:'verify-runtime',role:'do',phase:'count',tokens:1683,secret:'PRIVATE'})]}]});
  assert.equal(row.cpuTime,7);assert.equal(row.logs[0].tokens,1683);
  assert.ok(!JSON.stringify(row).includes('PRIVATE'));
  assert.equal(sanitizeTail({}).cpuTime,undefined);
});
