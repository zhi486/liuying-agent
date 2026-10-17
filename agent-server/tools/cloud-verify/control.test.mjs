import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateTarget, attemptClosures, closePublic, target, account,requireExplicitProfile,profile } from './control.mjs';

test('远程写入只接受不可变创建凭证中的唯一 DB',()=>{
  const cfg={name:target,account_id:account,d1_databases:[{binding:'DB',database_name:target,database_id:'new-id'}]};
  const created={target,databaseId:'new-id'};
  assert.doesNotThrow(()=>validateTarget(cfg,created));
  assert.throws(()=>validateTarget(cfg,{target,databaseId:'other-id'}));
  assert.throws(()=>validateTarget({...cfg,d1_databases:[...cfg.d1_databases,...cfg.d1_databases]},created));
  assert.throws(()=>validateTarget(cfg,{target:'other-worker',databaseId:'new-id'}));
});

test('公网状态读取抛异常仍尝试关闭 POST',async()=>{
  const methods=[];
  await closePublic(async method=>{methods.push(method);if(method==='GET')throw new Error('模拟超时');return {success:true};});
  assert.deepEqual(methods,['GET','POST']);
});

test('一种关闭措施失败不会跳过其他措施，错误只保留静态标识',async()=>{
  const calls=[];
  const result=await attemptClosures({public:async()=>{calls.push(1);throw new Error('SECRET');},
    database:async()=>{calls.push(2);},deployment:async()=>{calls.push(3);}});
  assert.deepEqual(calls,[1,2,3]);
  assert.deepEqual(result,{public:false,database:true,deployment:true});
  assert.ok(!JSON.stringify(result).includes('SECRET'));
});

test('实际运行入口缺少或拼错profile时拒绝，不默选旧云任务',()=>{
  const previous=process.env.LIUYING_VERIFY_PROFILE;
  try{
    delete process.env.LIUYING_VERIFY_PROFILE;assert.throws(()=>requireExplicitProfile());
    process.env.LIUYING_VERIFY_PROFILE='wrong';assert.throws(()=>requireExplicitProfile());
    process.env.LIUYING_VERIFY_PROFILE=profile.name;assert.doesNotThrow(()=>requireExplicitProfile());
  }finally{
    if(previous===undefined)delete process.env.LIUYING_VERIFY_PROFILE;
    else process.env.LIUYING_VERIFY_PROFILE=previous;
  }
});
