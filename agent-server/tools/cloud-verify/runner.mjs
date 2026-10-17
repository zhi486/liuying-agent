// 一次性固定合成验收：不重发任务、不改代理或网络、不使用真实模型。
import assert from 'node:assert/strict';
import https from 'node:https';
import { readFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { session,output,artifact,sql,closeCloud,target,profile,requireExplicitProfile } from './control.mjs';
import { fixture,cases } from './fixtures.mjs';
import { captureTail } from './tail.mjs';

requireExplicitProfile();
const base=`https://${target}.fangran31415927.workers.dev`;
let data,opened;
const report={startedAt:Date.now(),target,profile:profile.name,deliveryBarrier:profile.deliveryBarrier,
  attempts:0,http:[],checks:[],tasks:[],realProviderCalls:0,
  memory:'not-measured',disconnect:profile.deliveryBarrier?null:'not-tested'};
let tail;
let phase='启动';
const headers=owner=>({'Content-Type':'application/json','X-Verify-Token':data.token,
  Authorization:`Bearer ${data.devices[owner].token}`});
function permit(){
  assert.ok(++report.attempts<=100,'请求上限');
  assert.ok(Date.now()<opened.expires,'窗口到期');
}
async function request(path,owner=0,body,overrides={}){
  permit();
  const observation={attempt:report.attempts,method:body===undefined?'GET':'POST',
    kind:path==='/__verify_ready'?'ready':path==='/v1/agent/tasks'?'submit':path.split('/').at(-1).length===36?'query':'control'};
  report.http.push(observation);
  const started=performance.now();
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{...headers(owner),...overrides},
    ...(body!==undefined?{body:body===null?'':JSON.stringify(body)}:{}),redirect:'error',signal:AbortSignal.timeout(20000)});
  const reader=response.body.getReader();const decoder=new TextDecoder();let text='';
  try {while(true){const item=await reader.read();if(item.done){text+=decoder.decode();break;}text+=decoder.decode(item.value,{stream:true});assert.ok(text.length<65536);}}
  finally {await reader.cancel().catch(()=>{});reader.releaseLock();}
  let value;try{value=JSON.parse(text);}catch{value={error:'non_json'};}
  Object.assign(observation,{status:response.status,elapsedMs:Math.round(performance.now()-started),
    protocol:response.headers.get('X-Verify-Protocol'),contentType:response.headers.get('Content-Type')});
  return {status:response.status,value,protocol:observation.protocol,contentType:observation.contentType};
}
const record=(name,value=true)=>report.checks.push({name,value});
async function query(index){
  const result=await request(`/v1/agent/requests/${fixture(index).clientRequestId}`,cases[index].owner);
  assert.equal(result.status,200,`查询用例 ${index}`);return result.value.task;
}
async function finish(index,expected='completed',tries=5){
  let task;
  for(let attempt=0;attempt<tries;attempt++){
    await delay(attempt?2000:1000);task=await query(index);
    if(!['queued','running'].includes(task.status))break;
  }
  assert.equal(task.status,expected,`用例 ${index} 终态`);
  assert.equal(task.deadlineAtMillis-task.acceptedAtMillis,30000);
  report.tasks.push({case:index,id:task.id,status:task.status,reason:task.reason,resultState:task.resultState,
    accepted:task.acceptedAtMillis,deadline:task.deadlineAtMillis,actions:task.result?.actions});
  return task;
}
async function submit(index){
  phase=`提交用例 ${index}`;
  const result=await request('/v1/agent/tasks',cases[index].owner,fixture(index));
  assert.equal(result.status,202,phase);assert.ok(result.contentType?.includes('application/json'));
  return result.value.task;
}
async function disconnect(){
  phase='断流屏障';permit();
  return new Promise((resolve,reject)=>{
    let first='',observed=false,ended=false,taskId,socket,incomplete=false;
    const call=https.request(base+'/v1/agent/tasks',{method:'POST',agent:false,headers:headers(0)},response=>{
      if(response.statusCode!==202){response.resume();call.destroy();reject(new Error('断流未接单'));return;}
      response.setEncoding('utf8');response.on('end',()=>{ended=true;});
      response.on('data',chunk=>{
        if(observed)return;
        first+=chunk;
        if(first.length>65536){call.destroy();reject(new Error('断流回包过大'));return;}
        if(!first.includes('\n'))return;
        try{
          const barrier=JSON.parse(first.split('\n')[0]);
          assert.equal(barrier.transportEntered,true);taskId=barrier.taskId;
          assert.equal(ended,false);assert.equal(response.complete,false);
          incomplete=true;observed=true;call.destroy();
        }catch{call.destroy();reject(new Error('断流屏障无效'));}
      });
      response.on('error',()=>{});
    });
    call.on('socket',value=>{
      socket=value;
      socket.once('close',()=>{
        if(observed && socket.destroyed && !ended && incomplete)
          resolve({taskId,transportEntered:true,socketDestroyed:true,socketCloseObserved:true,responseCompleteBeforeClose:false});
        else reject(new Error('未取得实际 socket 中途关闭证据'));
      });
    });
    call.on('error',()=>{if(!observed)reject(new Error('断流连接失败'));});
    call.setTimeout(20000,()=>{call.destroy();reject(new Error('断流连接超时'));});
    call.end(JSON.stringify(fixture(5)));
  });
}

try {
  data=await session();assert.equal(data.target,target);
  opened=JSON.parse(await readFile(new URL('opened-once.json',output),'utf8'));
  if(!profile.deliveryBarrier){assert.equal(opened.target,target);assert.equal(opened.profile,profile.name);}
  await artifact('runner-once.json',{target,profile:profile.name,startedAt:report.startedAt});
  tail=await captureTail();
  phase='就绪';let ready=false;
  for(let n=0;n<4;n++){
    const result=await request('/__verify_ready');
    await delay(5000);
    assert.ok(tail.isAlive(),'云日志进程未存活');
    if(result.status===404 && result.protocol==='formal-do-v1' && result.value.error==='verification_ready'
      && tail.rows.some(value=>value.scriptName===target&&value.logs.some(log=>log.role==='entry'&&log.phase==='ready'))){ready=true;break;}
  }
  assert.ok(ready,'隔离入口未就绪');record('ready');
  phase='认证及清单';
  assert.equal((await request('/__verify_ready',0,undefined,{'X-Verify-Token':'invalid'})).status,401);
  assert.equal((await request('/v1/agent/tasks',0,fixture(0),{Authorization:'Bearer invalid'})).status,401);
  assert.equal((await request('/v1/agent/tasks',0,{...fixture(0),prompt:'清单外合成输入'})).status,400);record('auth-manifest');
  await submit(0);const first=await finish(0);
  const replay=await request('/v1/agent/tasks',0,fixture(0));assert.equal(replay.status,200);assert.equal(replay.value.task.id,first.id);
  assert.equal((await request('/v1/agent/tasks',0,{...fixture(0),prompt:'合成验收：不同文本'})).status,409);
  assert.equal((await request(`/v1/agent/tasks/${first.id}`,1)).status,404);record('idempotency-owner-isolation');
  await submit(1);await finish(1);
  await Promise.all([submit(2),submit(3)]);await Promise.all([finish(2),finish(3)]);
  await submit(4);const three=await finish(4);
  assert.equal(three.result.actions.filter(value=>value.kind==='model_requested').length,3);
  assert.equal(three.result.actions.filter(value=>value.kind==='tool_completed').length,2);record('three-round-loop');
  if(profile.deliveryBarrier){
    report.disconnect=await disconnect();const recovered=await finish(5);
    assert.equal(recovered.id,report.disconnect.taskId);record('real-socket-recovery');
  } else {
    const accepted=await submit(5);const completed=await finish(5);
    assert.equal(completed.id,accepted.id);record('normal-json-receipt-and-query');
  }
  for(const [index,action] of [[6,'cancel'],[7,'withdraw']]){
    const accepted=await submit(index);
    if(profile.deliveryBarrier)await delay(1500);
    phase=`${action} 控制`;
    if(!profile.deliveryBarrier){
      assert.match(accepted.id,/^[0-9a-f-]{36}$/);
      const observed=await sql(`SELECT t.status,COUNT(v.round) rounds FROM agent_cloud_tasks t
        LEFT JOIN verify_transport v ON v.task_id=t.id WHERE t.id='${accepted.id}' GROUP BY t.id`);
      const rows=observed[0].results;assert.equal(rows.length,1);
      assert.equal(rows[0].status,'running');assert.equal(rows[0].rounds,1);
      record(`${action}-client-readonly-dispatch-observation`);
    }
    const controlled=await request(`/v1/agent/tasks/${accepted.id}/${action}`,0,null);assert.equal(controlled.status,200);
    const done=await finish(index,'cancelled');assert.equal(done.result,null);
    if(action==='withdraw')assert.equal(done.resultState,'withdrawn');
    record(action);
  }
  const deadline=await submit(8);phase='原截止等待';
  await delay(31500);
  const timed=await finish(8,'timed_out',2);assert.equal(timed.deadlineAtMillis,deadline.deadlineAtMillis);record('original-deadline');
  await submit(9);await finish(9,'failed');
  phase='确认结果';
  assert.equal((await request(`/v1/agent/tasks/${first.id}/acknowledge`,0,null)).status,200);
  const cleared=await query(0);assert.equal(cleared.resultState,'acknowledged');assert.equal(cleared.result,null);record('acknowledgement');
  phase='独立 D1 核对';
  const snapshot=await sql(`SELECT id,client_request_id,status,reason,accepted_at,deadline_at,result_state FROM agent_cloud_tasks;
    SELECT id,task_id,request_id,state,dispatch_state,reserved_micros,charged_micros,input_tokens,output_tokens FROM agent_budget_reservations;
    SELECT reservation_id,input_tokens,output_tokens,applied FROM agent_usage_receipts;
    SELECT task_id,round FROM verify_transport;
    SELECT started_at,expires_at,requests FROM verify_limits;
    SELECT COUNT(*) n FROM verify_admissions;`);
  await artifact('database-evidence.json',snapshot);
  assert.ok(snapshot.every(value=>value.success && value.meta?.rows_written===0));
  const [tasks,reservations,receipts,transports,limits,admissions]=snapshot.map(value=>value.results);
  assert.equal(tasks.length,10);assert.equal(admissions[0].n,10);
  assert.equal(reservations.length,12);
  assert.ok(limits[0].requests<=report.attempts && limits[0].requests<=100);
  for(const item of tasks)assert.equal(item.deadline_at-item.accepted_at,30000);
  for(const item of report.tasks){
    const stored=tasks.find(value=>value.id===item.id);assert.ok(stored);
    assert.equal(stored.client_request_id,fixture(item.case).clientRequestId);
    assert.equal(stored.status,item.status);assert.equal(stored.reason,item.reason);
    assert.equal(stored.result_state,item.case===0?'acknowledged':item.resultState);
    assert.equal(stored.deadline_at,item.deadline);
    const dispatches=transports.filter(value=>value.task_id===item.id);
    assert.equal(dispatches.length,item.case===4?3:1,`派发次数 ${item.case}`);
    const bills=reservations.filter(value=>value.task_id===item.id);
    assert.equal(bills.length,item.case===4?3:1);
    for(const bill of bills){
      assert.equal(bill.state,item.case<6?'settled':'uncertain');
      const received=receipts.filter(value=>value.reservation_id===bill.id);
      assert.equal(received.length,item.case<6?1:0);
    }
  }
  const settled=reservations.filter(value=>value.state==='settled');
  assert.equal(settled.length,8);assert.equal(receipts.length,8);
  for(const item of settled)assert.equal(item.charged_micros,324);
  for(const item of receipts){assert.equal(item.applied,1);assert.equal(item.input_tokens,1000);assert.equal(item.output_tokens,20);}
  const unknown=reservations.filter(value=>value.state==='uncertain');assert.equal(unknown.length,4);
  for(const item of unknown){assert.equal(item.charged_micros,null);assert.equal(item.reserved_micros,6720);assert.equal(item.dispatch_state,'may_have_dispatched');}
  record('persistent-ledger');
  phase='日志收尾';await delay(3000);report.passed=true;
} catch {
  report.passed=false;report.failedPhase=phase;process.exitCode=1;
  // 不转储网络异常或整个断言对象，避免请求配置中的凭据进入报告。
  console.error(`受控验收未通过：${phase}`);
} finally {
  // 先关闭入口；日志进程收尾不能阻挡公网和持久到期保护。
  try{await closeCloud();report.closed=true;}catch{report.closed=false;report.passed=false;process.exitCode=1;}
  if(tail)try{
    if(!tail.isAlive()){report.tailLost=true;report.passed=false;process.exitCode=1;}
    const captured=await tail.stop();report.tailEvents=captured.rows.length;report.tailParseError=captured.parseError;
    if(captured.parseError){report.passed=false;process.exitCode=1;}
  } catch{report.tailCaptureFailed=true;report.passed=false;process.exitCode=1;}
  report.finishedAt=Date.now();await artifact('run-report.json',report);
  console.log(JSON.stringify(report));
}
