import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync,writeFileSync } from 'node:fs';

const [clientName,tailName,stateName] = process.argv.slice(2);
for(const name of [clientName,tailName,stateName]) assert.match(name,/^[a-z0-9-]+\.json$/);
const read = name=>JSON.parse(readFileSync(new URL(`./.output/${name}`,import.meta.url),'utf8'));
const client=read(clientName),tail=read(tailName),state=read(stateName);
assert.equal(client.error,undefined);assert.equal(client.rows.length,20);
const owner=client.rows[0].result.ownerId;
assert.match(owner,/^spike_[a-f0-9-]{36}$/);
const sql=`SELECT id,status,reason,result_state,result_json,accepted_at,updated_at FROM agent_cloud_tasks WHERE owner_id='${owner}';
SELECT r.task_id,r.state,r.charged_micros,r.input_tokens,r.output_tokens,r.violation,u.applied,
u.input_tokens AS receipt_input,u.output_tokens AS receipt_output,
(r.execution_hash=t.execution_hash) AS execution_matches FROM agent_budget_reservations r
LEFT JOIN agent_usage_receipts u ON r.id=u.reservation_id JOIN agent_cloud_tasks t ON t.id=r.task_id WHERE r.owner_id='${owner}';
SELECT count(*) AS pending_count FROM agent_budget_reservations r LEFT JOIN agent_usage_receipts u ON u.reservation_id=r.id
WHERE r.execution_hash IS NOT NULL AND (r.state IN ('reserved','uncertain') OR u.applied=0);`;
const d1=JSON.parse(execFileSync(process.execPath,['node_modules/wrangler/bin/wrangler.js','d1','execute',
  'liuying-free-spike-20260915','--remote','--json','--command',sql],{
  encoding:'utf8',timeout:60000,env:{...process.env,WRANGLER_SEND_METRICS:'false'},
}));
const evidence={checkedAt:Date.now(),clientName,tailName,stateName,d1};
writeFileSync(new URL(`./.output/do-evidence-${evidence.checkedAt}.json`,import.meta.url),JSON.stringify(evidence,null,2),{flag:'wx'});
for(const query of d1) {assert.equal(query.success,true);assert.equal(query.meta.rows_written,0);}
const tasks=d1[0].results,receipts=d1[1].results;
assert.equal(tasks.length,4);assert.equal(receipts.length,6);assert.equal(d1[2].results[0].pending_count,0);
assert.deepEqual(state.subdomain.value,{enabled:false,previews_enabled:false});
assert.equal(state.settings.value.bindings.find(value=>value.name==='EXPIRES_AT').text,'0');
assert.equal(state.namespaces.value.length,1);
assert.equal(state.namespaces.value[0].use_sqlite,true);
assert.equal(state.settings.value.bindings.find(value=>value.name==='PROBE').namespace_id,state.namespaces.value[0].id);
const metrics=[];
for(const row of client.rows) {
  const probeId=row.result.probeId;
  const events=tail.rows.filter(value=>value.logs.some(log=>log.probeId===probeId));
  const entry=events.filter(value=>value.executionModel==='stateless');
  const durable=events.filter(value=>value.executionModel==='durableObject');
  assert.equal(entry.length,1);assert.equal(durable.length,1);
  assert.equal(entry[0].outcome,row.path==='/do/agent/disconnect'?'canceled':'ok');
  assert.equal(durable[0].outcome,'ok');
  for(const event of [...entry,...durable]) {
    assert.deepEqual(event.exceptionNames,[]);
    assert.ok(Number.isInteger(event.cpuTime)&&event.cpuTime>=0);
  }
  metrics.push({path:row.path,entryCpuMs:entry[0].cpuTime,doCpuMs:durable[0].cpuTime,
    doWallMs:durable[0].wallTime,edge:row.cfRay?.split('-').at(-1),seenCaseBefore:row.result.seenCaseBefore});
  if(!row.path.startsWith('/do/agent/')) continue;
  const task=tasks.find(value=>value.id===row.result.taskId);
  const fault=row.path.endsWith('receipt-fault');
  assert.equal(task.status,fault?'failed':'completed');assert.equal(task.result_state,'available');
  if(fault) assert.equal(task.reason,'model_unavailable');
  const result=JSON.parse(task.result_json);
  const rounds=row.path.endsWith('/three')?3:1;
  if(!fault) {
    assert.match(result.answer,/四公里/);
    assert.equal(result.actions.filter(value=>value.kind==='model_requested').length,rounds);
    assert.equal(result.actions.filter(value=>value.kind==='tool_completed').length,rounds-1);
  }
  const taskReceipts=receipts.filter(value=>value.task_id===task.id);
  assert.equal(taskReceipts.length,rounds);
  for(const receipt of taskReceipts) {
    assert.equal(receipt.state,'settled');assert.equal(receipt.applied,1);assert.equal(receipt.charged_micros,78);
    assert.equal(receipt.violation,0);assert.equal(receipt.execution_matches,1);
    assert.equal(receipt.input_tokens,100);assert.equal(receipt.receipt_input,100);
    assert.equal(receipt.output_tokens,40);assert.equal(receipt.receipt_output,40);
  }
}
console.log(JSON.stringify({metrics,tasks:tasks.map(({result_json,...task})=>task),
  receipts:receipts.length,chargedMicros:receipts.reduce((sum,value)=>sum+value.charged_micros,0),
  realModelCalls:0,cloudMemoryPeak:'not-measured',publicAccessClosed:true},null,2));
