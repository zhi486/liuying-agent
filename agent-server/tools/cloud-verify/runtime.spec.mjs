import { env } from 'cloudflare:workers';
import { applyD1Migrations, createExecutionContext, waitOnExecutionContext } from 'cloudflare:test';
import { beforeAll, expect, it, vi } from 'vitest';
import { createDeviceAdmin } from '../../src/device-auth.ts';
import { fixture } from './fixtures.mjs';
import schema from './schema.sql?raw';
import production from '../../src/worker.ts';

beforeAll(async()=>{
  await applyD1Migrations(env.DB,env.TEST_MIGRATIONS);
  for(const sql of schema.split(';').filter(value=>value.trim())) await env.DB.prepare(sql).run();
  const now=Date.now();
  await env.DB.prepare('INSERT INTO verify_limits VALUES (1,?,?,0)').bind(now,now+1800000).run();
});

it('无屏障入口返回原 JSON Response，不读取响应或轮询派发，执行仍在后台',async()=>{
  const { default: worker }=await import('./entry-worker.mjs');
  const owner=crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const device=await createDeviceAdmin({db:env.DB}).issue(owner);
  const headers={'Content-Type':'application/json','X-Verify-Token':'local-test-token',Authorization:`Bearer ${device.token}`};
  let originalResponse;let polls=0;
  const original=production.fetch;
  const spy=vi.spyOn(production,'fetch').mockImplementation(async(...args)=>{
    originalResponse=await original(...args);return originalResponse;
  });
  const db=new Proxy(env.DB,{get(target,key){
    if(key==='prepare')return query=>{
      if(query.startsWith('SELECT round FROM verify_transport'))polls++;
      return target.prepare(query);
    };
    const value=Reflect.get(target,key);return typeof value==='function'?value.bind(target):value;
  }});
  const ctx=createExecutionContext();
  try {
    const response=await worker.fetch(new Request('https://fixture.invalid/v1/agent/tasks',{
      method:'POST',headers,body:JSON.stringify(fixture(5))}),{...env,DB:db},ctx);
    expect(response).toBe(originalResponse);expect(response.bodyUsed).toBe(false);
    expect(response.status).toBe(202);expect(response.headers.get('Content-Type')).toContain('application/json');
    expect(polls).toBe(0);
    const {task}=await response.json();
    const status=await env.DB.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?').bind(task.id).first('status');
    expect(['queued','running']).toContain(status);
    await waitOnExecutionContext(ctx);
    expect(await env.DB.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?').bind(task.id).first('status')).toBe('completed');
  } finally {spy.mockRestore();await waitOnExecutionContext(ctx);}
});

it('隔离入口真实全链执行，只走模拟传输，重复键不重派且凭据仍必需',async()=>{
  const { default: worker }=await import('./worker.mjs');
  const owner=crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const device=await createDeviceAdmin({db:env.DB}).issue(owner);
  const submit=(body=fixture(0),header=`Bearer ${device.token}`)=>new Request('https://fixture.invalid/v1/agent/tasks',{
    method:'POST',headers:{'Content-Type':'application/json','X-Verify-Token':'local-test-token',Authorization:header},body:JSON.stringify(body)});
  const ctx=createExecutionContext();
  const response=await worker.fetch(submit(),env,ctx);
  expect(response.status).toBe(202);
  const {task}=await response.json(); await waitOnExecutionContext(ctx);
  const stored=await env.DB.prepare('SELECT status,result_json FROM agent_cloud_tasks WHERE id=?').bind(task.id).first();
  expect(stored.status).toBe('completed');
  expect(JSON.parse(stored.result_json).answer).toBe('合成云验收完成，不是健康建议。');
  const second=createExecutionContext();
  expect((await worker.fetch(submit(),env,second)).status).toBe(200); await waitOnExecutionContext(second);
  expect(await env.DB.prepare('SELECT COUNT(*) n FROM verify_transport WHERE task_id=?').bind(task.id).first('n')).toBe(1);
  expect(await env.DB.prepare('SELECT COUNT(*) n FROM verify_admissions WHERE owner=?').bind(owner).first('n')).toBe(1);
  const denied=createExecutionContext();
  expect((await worker.fetch(submit(fixture(0),'Bearer invalid'),env,denied)).status).toBe(401);
  expect((await worker.fetch(submit({...fixture(0),prompt:'私人输入'}),env,denied)).status).toBe(400);
  expect((await worker.fetch(submit({...fixture(0),prompt:'合成验收：不同文本'}),env,denied)).status).toBe(409);
  await waitOnExecutionContext(denied);
});

it('外层密钥或时间不满足时在正式任务接单前拒绝',async()=>{
  const { default: worker }=await import('./worker.mjs');
  const request=new Request('https://fixture.invalid/__verify_ready',{headers:{'X-Verify-Token':'wrong'}});
  const before=await env.DB.prepare('SELECT requests FROM verify_limits').first('requests');
  expect((await worker.fetch(request,env,createExecutionContext())).status).toBe(401);
  expect(await env.DB.prepare('SELECT requests FROM verify_limits').first('requests')).toBe(before);
  expect((await worker.fetch(request,{...env,VERIFY_EXPIRES_AT:'0'},createExecutionContext())).status).toBe(503);
});

it('取消屏障确认派发后仍使用正式控制路径，未知用量不退款',async()=>{
  const { default: worker }=await import('./worker.mjs');
  const owner=crypto.randomUUID();
  await env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const device=await createDeviceAdmin({db:env.DB}).issue(owner);
  const headers={'Content-Type':'application/json','X-Verify-Token':'local-test-token',Authorization:`Bearer ${device.token}`};
  const ctx=createExecutionContext();
  const response=await worker.fetch(new Request('https://fixture.invalid/v1/agent/tasks',{
    method:'POST',headers,body:JSON.stringify(fixture(6))}),env,ctx);
  expect(response.status).toBe(202);
  const {task}=await response.json();
  expect(await env.DB.prepare('SELECT COUNT(*) n FROM verify_transport WHERE task_id=?').bind(task.id).first('n')).toBe(1);
  const control=createExecutionContext();
  const cancelled=await worker.fetch(new Request(`https://fixture.invalid/v1/agent/tasks/${task.id}/cancel`,{
    method:'POST',headers}),env,control);
  expect(cancelled.status).toBe(200);
  await Promise.all([waitOnExecutionContext(control),waitOnExecutionContext(ctx)]);
  const stored=await env.DB.prepare('SELECT status,result_json FROM agent_cloud_tasks WHERE id=?').bind(task.id).first();
  expect(stored).toEqual({status:'cancelled',result_json:null});
  const reservation=await env.DB.prepare('SELECT state,reserved_micros,charged_micros FROM agent_budget_reservations WHERE task_id=?').bind(task.id).first();
  expect(reservation).toEqual({state:'uncertain',reserved_micros:6720,charged_micros:null});
});
