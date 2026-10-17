import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import worker, { countBoundRequest } from './tokenizer-perf-worker.mjs';
import { openBudgetDatabase } from '../../test/sqlite-d1.ts';
import { candidateTokens } from './counting-candidate.mjs';
import { Tokenizer } from '../workers-spike/node_modules/@huggingface/tokenizers/dist/tokenizers.mjs';
import data from '../token-count/.assets/tokenizer.json' with {type:'json'};
import cases from './.output/tokenizer-cases.json' with {type:'json'};
import agentCases from './.output/agent-tokenizer-cases.json' with {type:'json'};

const token='fixture-only-not-a-secret-32-characters';
const env=(mode)=>({PERF_MODE:mode,SPIKE_TOKEN:token,EXPIRES_AT:String(Date.now()+60000)});
const req=(path,given=token)=>new Request(`https://spike.invalid${path}`,{method:'POST',headers:{authorization:`Bearer ${given}`}});

test('性能入口保留鉴权、到期、固定路径与模式门禁',async()=>{
  assert.equal((await worker.fetch(req('/perf/production-round-1','wrong'),env('count'))).status,401);
  assert.equal((await worker.fetch(req('/perf/ready'),{...env('count'),EXPIRES_AT:'0'})).status,410);
  assert.equal((await worker.fetch(req('/perf/production-round-1?repeat=100'),env('count'))).status,404);
  assert.equal((await worker.fetch(req('/perf/unknown'),env('count'))).status,404);
  assert.equal((await worker.fetch(req('/perf/production-round-1'),env('unknown'))).status,503);
});
for(const mode of ['encode','count']) test(`${mode}六例返回真实计数，重复输入标注而非冒充首次`,async()=>{
  for(const item of cases){
    const response=await worker.fetch(req(`/perf/${item.name}`),env(mode));
    assert.equal(response.status,200);
    const first=await response.json();
    assert.equal(first.actualTokens,item.inputTokens);
    assert.equal(first.mode,mode);
    assert.equal(first.seenCaseBefore,false);
    const repeated=await (await worker.fetch(req(`/perf/${item.name}`),env(mode))).json();
    assert.equal(repeated.seenCaseBefore,true);
    assert.equal(repeated.isolateMarker,first.isolateMarker);
    assert.equal(repeated.actualTokens,first.actualTokens);
  }
});

test('三个实际Agent参考的完整ID独立对照通过',()=>{
  const tokenizer=new Tokenizer(data,{});
  for(const item of agentCases){
    const tokens=candidateTokens(tokenizer,item.prompt);
    assert.equal(tokens.length,item.inputTokens);
    const bytes=Buffer.alloc(tokens.length*4);
    tokens.forEach((value,index)=>{
      const id=tokenizer.token_to_id(value);
      assert.ok(Number.isSafeInteger(id)&&id>=0);
      bytes.writeUInt32LE(id,index*4);
    });
    assert.equal(createHash('sha256').update(bytes).digest('hex'),item.tokenIdsSha256);
  }
});
test('未知原始请求无法回退到预置计数',async()=>{
  await assert.rejects(countBoundRequest('{}','count'));
});
for(const mode of ['encode','count']) test(`${mode}三轮真实请求哈希命中并完成D1账务`,async(t)=>{
  const database=openBudgetDatabase(); t.after(()=>database.close());
  const pending=[];
  const response=await worker.fetch(req('/agent-perf/three'),{...env(mode),DB:database.db},
    {waitUntil:p=>pending.push(p)});
  assert.equal(response.status,200);
  const result=await response.json(); await Promise.all(pending);
  assert.deepEqual(result.measurements.map(row=>row.actualTokens),[397,471,562]);
  assert.equal(database.sqlite.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?').get(result.taskId).status,'completed');
  const rows=database.sqlite.prepare(`SELECT r.charged_micros,u.applied FROM agent_budget_reservations r
    JOIN agent_usage_receipts u ON r.id=u.reservation_id WHERE r.task_id=?`).all(result.taskId);
  assert.equal(rows.length,3);
  for(const row of rows) assert.deepEqual({...row},{charged_micros:78,applied:1});
});
