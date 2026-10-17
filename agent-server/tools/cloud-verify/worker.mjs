import production,{LiuyingAgentRuntime} from '../../src/worker.ts';
import { createAgentService } from '../../src/agent-service.ts';
import { createDeviceAuth } from '../../src/device-auth.ts';
import { parseHttpRequest,errorResponse } from '../../src/agent-http.ts';
import { CloudError } from '../../src/cloud-task-contracts.ts';
import { takeHttpPermit,takeTaskPermit,verifyOuterToken } from './gate.mjs';
import { matchFixture,syntheticTransport } from './fixtures.mjs';

const emit=value=>console.log(JSON.stringify({message:'verify-runtime',...value}));
const reply=(status,error)=>Response.json({error},{status,headers:{'Cache-Control':'no-store','X-Verify-Protocol':'formal-do-v1'}});
const expiry=env=>Number(env.VERIFY_EXPIRES_AT);

// 仅此隔离类注入模拟 transport；正式类默认工厂不变，无新增 RPC 工厂方法。
export class VerificationRuntime extends LiuyingAgentRuntime {
  #instance=crypto.randomUUID();
  constructor(ctx,env) {
    const until=expiry(env);
    super(ctx,{...env,DEEPSEEK_API_KEY:'synthetic-no-external-provider',RATE_CARD_JSON:JSON.stringify({
      version:'synthetic-verification',model:'deepseek-flash',inputMicrosPerMillion:300000,
      outputMicrosPerMillion:1200000,checkedAtMillis:until-1800000,expiresAtMillis:until})},options=>{
      let round=0;
      const owner=ctx.storage.sql.exec('SELECT owner FROM runtime_owner WHERE singleton=1').one().owner;
      const fake=syntheticTransport(value=>emit({role:'do',...value}));
      return createAgentService({...options,
        countInputTokens:async raw=>{
          const tokens=await options.countInputTokens(raw);
          const bytes=new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(raw)));
          const bodyHash=Array.from(bytes,value=>value.toString(16).padStart(2,'0')).join('');
          emit({role:'do',phase:'count',tokens,bodyHash});
          return tokens;
        },
        fetch:async(url,init)=>{
          // 只作合成验收的派发观察；不记录正文、令牌或模型响应。
          const result=await options.db.prepare(`INSERT INTO verify_transport(task_id,round)
            SELECT id,? FROM agent_cloud_tasks WHERE owner_id=? AND status='running' RETURNING task_id`)
            .bind(++round,owner).all();
          if(result.results.length!==1) throw new Error('验收执行已失效');
          return fake(url,init);
        },
      });
    });
  }
  async execute(authorization,submission,receipt) {
    const index=matchFixture(submission);
    if(index===null || !Number.isSafeInteger(expiry(this.env)) || Date.now()>=expiry(this.env))
      return {ok:false,error:'unavailable'};
    emit({role:'do',phase:'begin',case:index,instance:this.#instance,requestId:submission.clientRequestId});
    const result=await super.execute(authorization,submission,receipt);
    emit({role:'do',phase:'end',case:index,instance:this.#instance,status:result.ok?result.task.status:result.error});
    return result;
  }
}

export function makeVerificationEntry({deliveryBarrier=true}={}) { return {
  async fetch(request,env,ctx) {
    try {
      const until=expiry(env);
      if(!Number.isSafeInteger(until) || until<=Date.now()) return reply(503,'verification_closed');
      if(!await verifyOuterToken(request.headers.get('X-Verify-Token'),env.VERIFY_TOKEN_HASH)) return reply(401,'unauthenticated');
      if(!await takeHttpPermit(env.DB)) return reply(429,'verification_limit');
      const trace=crypto.randomUUID();
      if(new URL(request.url).pathname==='/__verify_ready') {
        emit({role:'entry',phase:'ready',trace}); return reply(404,'verification_ready');
      }
      // 与正式协议同样有界读取，不能对未知正文先无界 JSON.parse。
      const route=await parseHttpRequest(request.clone());
      let index=null;
      if(route.kind==='submit') {
        index=matchFixture(route.input);
        if(index===null) throw new CloudError('invalid_request');
        const principal=await createDeviceAuth({db:env.DB}).authenticate(request.headers.get('Authorization'));
        if(!await takeTaskPermit(env.DB,principal.ownerId,route.input.clientRequestId)) return reply(429,'verification_task_limit');
      }
      emit({role:'entry',phase:'begin',trace,kind:route.kind,case:index,
        requestId:route.kind==='submit'?route.input.clientRequestId:route.id});
      const response=await production.fetch(request,env,ctx);
      emit({role:'entry',phase:'response',trace,status:response.status});
      if(deliveryBarrier && [5,6,7].includes(index) && response.status===202) {
        const {task}=await response.clone().json();
        const end=Math.min(task.deadlineAtMillis,Date.now()+5000);
        let started=false;
        for(let attempt=0;attempt<20 && Date.now()<end;attempt++) {
          const row=await env.DB.prepare('SELECT round FROM verify_transport WHERE task_id=? LIMIT 1').bind(task.id).first();
          if(row) {started=true;break;}
          await new Promise(resolve=>setTimeout(resolve,250));
        }
        if(!started) return reply(503,'verification_transport_missing');
        if(index!==5) return response;
        // 专用断流屏障：明确标为测试包络，不冒充正式接口的自然丢包。
        const stream=new TransformStream(); const writer=stream.writable.getWriter();
        const delivery=(async()=>{
          try {
            await writer.write(new TextEncoder().encode(JSON.stringify({taskId:task.id,transportEntered:true})+'\n'));
            await new Promise(resolve=>setTimeout(resolve,5000)); await writer.close();
          } catch { /* 客户端主动断开；正式完整执行仍由原 Worker waitUntil 持有。 */ }
        })();
        ctx.waitUntil(delivery);
        return new Response(stream.readable,{status:202,headers:{'Cache-Control':'no-store','Content-Type':'application/x-ndjson'}});
      }
      return response;
    } catch(error) { return errorResponse(error); }
  },
}; }

export default makeVerificationEntry();
