import { test } from 'node:test';
import assert from 'node:assert/strict';
import { selectProfile } from './profile.mjs';

test('仅固定新旧验收配置可用，目标与证据目录严格隔离',()=>{
  const prior=selectProfile();const current=selectProfile('entry-20260917');
  assert.equal(prior.target,'liuying-agent-verify-20260916');
  assert.equal(current.target,'liuying-agent-entry-20260917');
  assert.notEqual(prior.output,current.output);assert.notEqual(prior.main,current.main);
  assert.equal(current.deliveryBarrier,false);assert.equal(prior.deliveryBarrier,true);
  for(const invalid of ['',null,'production','../cloud-verify','entry-20260918'])
    assert.throws(()=>selectProfile(invalid));
});
