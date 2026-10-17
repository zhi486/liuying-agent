import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { agentProbe } from './agent-probe.mjs';
import { openBudgetDatabase } from '../../test/sqlite-d1.ts';

// 只捕获本地虚构Agent的真实序列化字符串；不读取/调用任何真实供应商。
const database = openBudgetDatabase();
const requests = new Set();
const pending = [];
try {
  await agentProbe(database.db,'three',{waitUntil:p=>pending.push(p)}, {
    countInputTokens:async body => { requests.add(body); return 100; },
  });
  await Promise.all(pending);
} finally { database.close(); }
assert.equal(requests.size,3);
const here = name => fileURLToPath(new URL(name,import.meta.url));
// Python仅计算官方参考结果，生成文件由此脚本按不可覆盖原则保存。
const program = `import sys,json,hashlib,struct
sys.path.insert(0,sys.argv[1])
import count_tokens as ref
sys.stdin.reconfigure(encoding='utf-8')
sys.stdout.reconfigure(encoding='utf-8')
out=[]
for i,raw in enumerate(json.load(sys.stdin)):
 prompt=ref.render_request(json.loads(raw))
 ids=ref._tokenizer().encode(prompt,add_special_tokens=False).ids
 out.append(dict(name='agent-round-'+str(i+1),requestSha256=hashlib.sha256(raw.encode('utf-8')).hexdigest(),prompt=prompt,inputTokens=len(ids),tokenIdsSha256=hashlib.sha256(struct.pack('<'+str(len(ids))+'I',*ids)).hexdigest()))
print(json.dumps(out,ensure_ascii=False))`;
const cases = JSON.parse(execFileSync(here('../token-count/.venv/Scripts/python.exe'),
  ['-B','-c',program,here('../token-count')],{input:JSON.stringify([...requests]),encoding:'utf8',maxBuffer:2*1024*1024,timeout:30000}));
assert.equal(cases.length,3);
const path = here('./.output/agent-tokenizer-cases.json');
const output = JSON.stringify(cases,null,2);
if (existsSync(path)) assert.equal(readFileSync(path,'utf8'),output,'既有Agent参考不同，停止覆盖');
else writeFileSync(path,output,{flag:'wx'});
console.log(JSON.stringify(cases.map(({name,requestSha256,inputTokens,tokenIdsSha256})=>
  ({name,requestSha256,inputTokens,tokenIdsSha256}))));
