import { rejectProbe } from './gate.mjs';
import { Tokenizer } from '../workers-spike/node_modules/@huggingface/tokenizers/dist/tokenizers.mjs';
import data from '../token-count/.assets/tokenizer.json' with {type:'json'};
import cases from './.output/tokenizer-cases.json' with {type:'json'};
import agentCases from './.output/agent-tokenizer-cases.json' with {type:'json'};
import { candidateTokens } from './counting-candidate.mjs';
import { agentProbe } from './agent-probe.mjs';

const tokenizer = new Tokenizer(data,{});
// 仅为九个固定虚构输入记录 isolate 诊断信息，不存储用户或鉴权状态。
let isolateMarker;
let isolateCall = 0;
const seen = new Set();
function measure(item,mode) {
  if (mode !== 'encode' && mode !== 'count') throw new Error('未知计数模式');
  isolateMarker ??= crypto.randomUUID();
  const key = `${mode}/${item.name}`;
  const seenCaseBefore = seen.has(key);
  const actualTokens = mode === 'encode' ?
    tokenizer.encode(item.prompt,{add_special_tokens:false}).ids.length :
    candidateTokens(tokenizer,item.prompt).length;
  if (actualTokens !== item.inputTokens) throw new Error('计数与独立参考不一致');
  seen.add(key);
  return {case:item.name,mode,actualTokens,isolateMarker,isolateCall:++isolateCall,seenCaseBefore};
}
async function measureBoundRequest(body,mode) {
  const hash = await crypto.subtle.digest('SHA-256',new TextEncoder().encode(body));
  const sha = Array.from(new Uint8Array(hash),value=>value.toString(16).padStart(2,'0')).join('');
  const item = agentCases.find(value=>value.requestSha256 === sha);
  if (!item) throw new Error('原始请求未匹配独立参考，拒绝计数');
  return measure(item,mode);
}
export async function countBoundRequest(body,mode) {
  return (await measureBoundRequest(body,mode)).actualTokens;
}
export default {
  async fetch(request,env,ctx) {
    const rejected = await rejectProbe(request,env);
    if (rejected) return rejected;
    const mode = env.PERF_MODE;
    if (mode !== 'encode' && mode !== 'count') return new Response(null,{status:503});
    const path = new URL(request.url).pathname;
    if (path === '/perf/ready') return Response.json({protocol:'count-perf-v1',mode});
    const item = cases.find(value=>path === `/perf/${value.name}`);
    if (item) {
      const measured = measure(item,mode);
      console.log('spike-count-perf',JSON.stringify(measured));
      return Response.json(measured,{headers:{'cache-control':'no-store'}});
    }
    const name = path === '/agent-perf/one' ? 'one' : path === '/agent-perf/three' ? 'three' : null;
    if (!name) return new Response(null,{status:404});
    const measurements = [];
    const response = await agentProbe(env.DB,name,ctx,{
      tokenCounting:'raw-request-hash-bound-canonical-fixture',
      countInputTokens:async body=>{
        const measured = await measureBoundRequest(body,mode);
        measurements.push(measured);
        return measured.actualTokens;
      },
    });
    const result = {...await response.json(),mode,measurements};
    console.log('spike-agent-perf',JSON.stringify(result));
    return Response.json(result,{headers:{'cache-control':'no-store'}});
  },
};
