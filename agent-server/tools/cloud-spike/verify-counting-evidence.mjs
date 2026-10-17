import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = name=>JSON.parse(readFileSync(new URL(`./.output/${name}`,import.meta.url),'utf8'));
const evidence = read('counting-final-evidence.json');
assert.equal(evidence.d1.success,true);
for (const query of evidence.d1.result) {
  assert.equal(query.success,true); assert.equal(query.meta.rows_written,0);
}
const tasks = evidence.d1.result[0].results;
const receipts = evidence.d1.result[1].results;
assert.equal(tasks.length,4); assert.equal(receipts.length,8);
assert.deepEqual(evidence.finalState.subdomain.result,{enabled:false,previews_enabled:false});
assert.equal(evidence.finalState.settings.success,true);
assert.ok(!evidence.finalState.settings.bindings.some(row=>row.name==='PERF_MODE'));
const deployments = [
  ['encode','counting-cloud-encode-1789475824142.json','tail-1789475809428.json'],
  ['count','counting-cloud-count-1789475978587.json','tail-1789475950212.json'],
];
const markerSets = [];
for (const [mode,clientFile,tailFile] of deployments) {
  const client = read(clientFile); const tail = read(tailFile);
  assert.equal(client.error,undefined); assert.equal(client.rows.length,14);
  assert.equal(tail.rows.filter(row=>row.path!=='/perf/ready').length,14);
  const markers = new Set(); markerSets.push(markers);
  for (const row of client.rows) {
    assert.equal(row.status,200); assert.equal(row.result.mode,mode);
    const matches = tail.rows.filter(event=>event.path===row.path && event.logs.some(log=>{
      const metadata = JSON.parse(log[1]);
      return metadata.mode===mode && (row.result.taskId ? metadata.taskId===row.result.taskId :
        metadata.isolateMarker===row.result.isolateMarker && metadata.isolateCall===row.result.isolateCall);
    }));
    assert.equal(matches.length,1);
    const event = matches[0];
    assert.equal(event.outcome,'ok');assert.equal(event.status,200);
    assert.deepEqual(event.exceptionNames,[]);
    assert.ok(Number.isInteger(event.cpuTime) && event.cpuTime>=0);
    assert.ok(Number.isInteger(event.wallTime) && event.wallTime>=event.cpuTime);
    for (const measurement of row.result.measurements ?? [row.result]) markers.add(measurement.isolateMarker);
    if (row.result.taskId) {
      const expected = row.result.case==='one' ? 1 : 3;
      const task = tasks.find(task=>task.id===row.result.taskId);
      assert.equal(task.status,'completed');assert.equal(task.result_state,'available');
      const result = JSON.parse(task.result_json);
      assert.equal(result.status,'completed');assert.match(result.answer,/四公里/);
      assert.equal(result.actions.filter(action=>action.kind==='model_requested').length,expected);
      const rows = receipts.filter(receipt=>receipt.task_id===task.id);
      assert.equal(rows.length,expected);
      for (const receipt of rows) {
        assert.equal(receipt.state,'settled');assert.equal(receipt.applied,1);
        assert.equal(receipt.charged_micros,78);assert.equal(receipt.violation,0);
        assert.equal(receipt.execution_matches,1);
        assert.equal(receipt.input_tokens,100);assert.equal(receipt.receipt_input,100);
        assert.equal(receipt.output_tokens,40);assert.equal(receipt.receipt_output,40);
      }
    }
    console.log(JSON.stringify({mode,path:row.path,pass:row.pass,cpuMs:event.cpuTime,
      wallMs:event.wallTime,edge:row.cfRay.split('-').at(-1),seenCaseBefore:row.result.seenCaseBefore}));
  }
}
assert.ok([...markerSets[0]].every(marker=>!markerSets[1].has(marker)));
console.log('28条请求与CPU日志一一对应；4条任务、8份收据与关闭公网状态验收通过。');
