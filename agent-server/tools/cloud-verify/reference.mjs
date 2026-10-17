// 仅本地：真实服务构造请求，独立锁定 Python 参考计数；不把常量计数作为云运行答案。
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createAgentService } from '../../src/agent-service.ts';
import { createDeviceAdmin } from '../../src/device-auth.ts';
import { openBudgetDatabase } from '../../test/sqlite-d1.ts';
import { cases,fixture,syntheticTransport } from './fixtures.mjs';
import { artifact,root,target,profile,requireExplicitProfile } from './control.mjs';
requireExplicitProfile();
const database=openBudgetDatabase();const rows=[];
const now=Date.now();const rate={version:'reference-only',model:'deepseek-flash',inputMicrosPerMillion:300000,
  outputMicrosPerMillion:1200000,checkedAtMillis:now,expiresAtMillis:now+3600000};
try {
  const owner=crypto.randomUUID();
  await database.db.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)').bind(owner).run();
  const device=await createDeviceAdmin({db:database.db}).issue(owner);
  for(let index=0;index<cases.length;index++){
    const requests=[];
    const fake=syntheticTransport();
    const service=createAgentService({db:database.db,rate,apiKey:'local-only',
      countInputTokens:async raw=>{requests.push(raw);return 1;},
      fetch:cases[index].mode==='three'?fake:async()=>Response.json({choices:[{index:0,finish_reason:'stop',
        message:{role:'assistant',content:'本地参考构造完成'}}],usage:{prompt_tokens:1000,completion_tokens:20,total_tokens:1020}})});
    const prepared=await service.prepare(`Bearer ${device.token}`,fixture(index));await prepared.run();
    assert.equal(requests.length,index===4?3:1);
    for(const [round,raw] of requests.entries()){
      const expected=await new Promise((resolve,reject)=>{
        const child=spawn(fileURLToPath(new URL('../token-count/.venv/Scripts/python.exe',import.meta.url)),
          ['-B','tools/token-count/count_tokens.py','--stdin'],{cwd:root,windowsHide:true,stdio:['pipe','pipe','ignore']});
        let text='';child.stdout.on('data',chunk=>{text+=chunk;});child.on('error',reject);
        child.on('close',code=>code===0?resolve(JSON.parse(text)):reject(new Error('Python 参考失败')));child.stdin.end(raw);
      });
      rows.push({case:index,round:round+1,bodyHash:createHash('sha256').update(raw).digest('hex'),...expected});
    }
  }
  await artifact('reference.json',{target,profile:profile.name,assurance:'local_reference_only',rows});
  console.log(JSON.stringify(rows.map(({case:index,round,inputTokens})=>({case:index,round,inputTokens}))));
} finally {database.close();}
