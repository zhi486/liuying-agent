import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { Tokenizer } from '../workers-spike/node_modules/@huggingface/tokenizers/dist/tokenizers.mjs';
import { candidateTokens } from './counting-candidate.mjs';
import cases from './.output/tokenizer-cases.json' with { type:'json' };

// 两条路径每个用例各自新建Tokenizer；构造及ID哈希不计入编码耗时。
const data = JSON.parse(readFileSync(new URL('../token-count/.assets/tokenizer.json',import.meta.url),'utf8'));
const count = (tokenizer,prompt,mode) => mode === 'encode' ?
  tokenizer.encode(prompt,{add_special_tokens:false}).ids.length : candidateTokens(tokenizer,prompt).length;
const report = { startedAt:Date.now(),runtime:process.version,assurance:'local-node-not-cloud',rows:[] };
for (const [index,item] of cases.entries()) {
  // 交替先后顺序，减少固定顺序的JIT偏差；它不能完全隔离进程级JIT状态。
  for (const mode of index%2 ? ['count','encode'] : ['encode','count']) {
    const tokenizer = new Tokenizer(data,{});
    const firstStart = performance.now();
    const actual = count(tokenizer,item.prompt,mode);
    const firstWallMs = performance.now()-firstStart;
    assert.equal(actual,item.inputTokens);
    const durations = [];
    const cpu = process.cpuUsage();
    for (let repeat=0;repeat<50;repeat++) {
      const start = performance.now();
      const repeated = count(tokenizer,item.prompt,mode);
      durations.push(performance.now()-start);
      assert.equal(repeated,item.inputTokens);
    }
    const delta = process.cpuUsage(cpu);
    durations.sort((a,b)=>a-b);
    report.rows.push({ case:item.name,mode,tokens:actual,firstWallMs,
      repeatedCount:50,repeatMedianWallMs:durations[25],repeatMaxWallMs:durations[49],
      repeatAggregateCpuMs:(delta.user+delta.system)/1000 });
  }
}
report.finishedAt=Date.now();
writeFileSync(new URL(`./.output/counting-local-${report.startedAt}.json`,import.meta.url),
  JSON.stringify(report,null,2),{flag:'wx'});
console.log(JSON.stringify(report));
