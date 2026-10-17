import { DurableObject, RpcTarget } from 'cloudflare:workers';
import { rejectProbe } from './gate.mjs';
import { agentProbe } from './agent-probe.mjs';
import { createDeviceAdmin } from '../../src/device-auth.ts';
import { recoverFreeExecutions } from '../../src/execution-ledger.ts';
import { Tokenizer } from '../workers-spike/node_modules/@huggingface/tokenizers/dist/tokenizers.mjs';
import data from '../token-count/.assets/tokenizer.json' with {type:'json'};
import cases from './.output/tokenizer-cases.json' with {type:'json'};
import agentCases from './.output/agent-tokenizer-cases.json' with {type:'json'};
import { candidateTokens } from './counting-candidate.mjs';

const paths = new Set(['setup','agent/one','agent/three','agent/disconnect',
  'agent/receipt-fault','status/disconnect','recover',...cases.map(value=>`token/${value.name}`)]);
const json = (body,status=200) => Response.json(body,{status,headers:{'cache-control':'no-store'}});
const sha = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),
  value=>value.toString(16).padStart(2,'0')).join('');

export class LiuyingRuntimeProbe extends DurableObject {
  #tokenizer;
  #seen = new Set();
  #instanceMarker = crypto.randomUUID();
  #busy = false;

  async #measure(item) {
    this.#tokenizer ??= new Tokenizer(data,{});
    const tokens = candidateTokens(this.#tokenizer,item.prompt);
    const bytes = new Uint8Array(tokens.length*4);
    const view = new DataView(bytes.buffer);
    tokens.forEach((value,index)=>{
      const id = this.#tokenizer.token_to_id(value);
      if (!Number.isSafeInteger(id) || id < 0) throw new Error('无效token ID');
      view.setUint32(index*4,id,true);
    });
    const hash = await sha(bytes);
    if (hash !== item.tokenIdsSha256 || tokens.length !== item.inputTokens)
      throw new Error('固定输入的完整token ID不匹配');
    const result = {case:item.name,actualTokens:tokens.length,tokenIdsSha256:hash,exactIds:true,
      seenCaseBefore:this.#seen.has(item.name),instanceMarker:this.#instanceMarker,
      assurance:'canonical-prompt-bpe-only',memoryMeasurement:'not-available'};
    this.#seen.add(item.name);
    return result;
  }

  async run(path,probeId,progress) {
    if (!paths.has(path)) return {status:404};
    const expiry = Number(this.env.EXPIRES_AT);
    if (!Number.isSafeInteger(expiry) || expiry <= Date.now()) return {status:410};
    if (this.#busy) return {status:409};
    this.#busy = true;
    try {
      const calls = await this.ctx.storage.transaction(async txn=>{
        const before = await txn.get('calls') ?? 0;
        if (before >= 20) return null;
        await txn.put('calls',before+1);
        return before+1;
      });
      if (calls === null) return {status:429};
      console.log('spike-do',JSON.stringify({role:'do',phase:'started',probeId,path,calls,
        instanceMarker:this.#instanceMarker}));
      let identity = await this.ctx.storage.get('identity');
      let body;
      if (path === 'setup') {
        if (!identity) {
          // 只在单独预置请求创建虚构账户；私有凭据不返回、不记录。
          const ownerId = `spike_${crypto.randomUUID()}`;
          await this.env.DB.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)')
            .bind(ownerId).run();
          const device = await createDeviceAdmin({db:this.env.DB}).issue(ownerId);
          identity = {ownerId,token:device.token};
          await this.ctx.storage.put('identity',identity);
        }
        body = {ownerId:identity.ownerId,calls,instanceMarker:this.#instanceMarker};
      } else if (!identity) return {status:409};
      else if (path.startsWith('token/')) {
        body = await this.#measure(cases.find(value=>path === `token/${value.name}`));
      } else if (path === 'recover') {
        body = await recoverFreeExecutions({db:this.env.DB});
      } else if (path === 'status/disconnect') {
        const last = await this.ctx.storage.get('last:disconnect');
        if (!last) return {status:404};
        const task = await this.env.DB.prepare('SELECT status,result_state FROM agent_cloud_tasks WHERE id=? AND owner_id=?')
          .bind(last.taskId,identity.ownerId).first();
        body = {...last,...task};
      } else {
        const name = path.slice('agent/'.length);
        if (await this.ctx.storage.get(`attempted:${name}`)) return {status:409};
        // 先占位再派发；对象重建或重复请求都不能重新执行此固定用例。
        await this.ctx.storage.put(`attempted:${name}`,true);
        const measurements = [];
        const pending = [];
        // DO waitUntil 无效：RPC 明确 await 全流程及其登记的任务。
        const response = await agentProbe(this.env.DB,name,{waitUntil:p=>pending.push(p)},{
          identity,awaitCompletion:true,tokenCounting:'raw-request-hash-bound-canonical-fixture',
          onEntered:async value=>{
            await this.ctx.storage.put('last:disconnect',value);
            await progress.entered(value);
          },
          countInputTokens:async raw=>{
            const hash = await sha(new TextEncoder().encode(raw));
            const item = agentCases.find(value=>value.requestSha256 === hash);
            if (!item) throw new Error('未知原始请求，不能计数');
            const measured = await this.#measure(item);
            measurements.push(measured);
            return measured.actualTokens;
          },
        });
        await Promise.all(pending);
        body = {...await response.json(),measurements};
        await this.ctx.storage.put(`last:${name}`,body);
      }
      console.log('spike-do',JSON.stringify({role:'do',phase:'completed',probeId,path,calls,
        taskId:body.taskId,instanceMarker:this.#instanceMarker}));
      return {status:200,body};
    } finally { this.#busy = false; }
  }
}

class Progress extends RpcTarget {
  #notify;
  constructor(notify) { super(); this.#notify = notify; }
  entered(value) { this.#notify(value); }
}

export default {
  async fetch(request,env,ctx) {
    const rejected = await rejectProbe(request,env);
    if (rejected) return rejected;
    const pathname = new URL(request.url).pathname;
    if (pathname === '/do/ready') return json({protocol:'do-runtime-v1'});
    const path = pathname.startsWith('/do/') ? pathname.slice(4) : '';
    if (!paths.has(path)) return new Response(null,{status:404});
    const probeId = crypto.randomUUID();
    console.log('spike-do',JSON.stringify({role:'entry',phase:'started',probeId,path}));
    const stub = env.PROBE.getByName('synthetic-runner-20260916');
    if (path !== 'agent/disconnect') {
      const execution = stub.run(path,probeId);
      ctx.waitUntil(execution);
      const result = await execution;
      return json({...result.body,probeId},result.status);
    }
    let controller, cancelled = false, markEntered;
    const entered = new Promise(resolve=>{ markEntered = resolve; });
    const body = new ReadableStream({start(value){controller=value;},cancel(){cancelled=true;}});
    const progress = new Progress(value=>{
      if (!cancelled) controller.enqueue(new TextEncoder().encode(JSON.stringify({...value,probeId})+'\n'));
      markEntered();
    });
    const execution = stub.run(path,probeId,progress);
    // 保持完整 RPC，包括结果落库；客户端取消输出不取消 execution。
    ctx.waitUntil(execution.then(()=>{if(!cancelled) controller.close();},error=>{
      if(!cancelled) controller.error(new Error('隔离任务失败'));
      throw error;
    }));
    const early = await Promise.race([entered.then(()=>null),execution]);
    if (early) return json({...early.body,probeId},early.status === 200 ? 500 : early.status);
    return new Response(body,{headers:{'content-type':'application/x-ndjson','cache-control':'no-store, no-transform'}});
  },
};
