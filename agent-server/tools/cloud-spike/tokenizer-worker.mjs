import { rejectProbe } from './gate.mjs';
import { Tokenizer } from '../workers-spike/node_modules/@huggingface/tokenizers/dist/tokenizers.mjs';
import tokenizerData from '../token-count/.assets/tokenizer.json' with { type:'json' };
import cases from './.output/tokenizer-cases.json' with { type:'json' };

// 模块初始化只发生于 isolate 创建；其平台启动耗时与请求 CPU 分开记录。
const tokenizer = new Tokenizer(tokenizerData, {});
export default {
  async fetch(request, env) {
    const rejected = await rejectProbe(request, env);
    if (rejected) return rejected;
    const path = new URL(request.url).pathname;
    const item = cases.find(item => path === `/tokenizer/${item.name}`);
    if (!item) return new Response(null, { status:404 });
    console.log(JSON.stringify({ message:'spike-tokenizer-start', case:item.name }));
    const encoded = tokenizer.encode(item.prompt, { add_special_tokens:false });
    const bytes = new Uint8Array(encoded.ids.length * 4);
    const view = new DataView(bytes.buffer);
    encoded.ids.forEach((id,index) => view.setUint32(index*4,id,true));
    const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),
      byte => byte.toString(16).padStart(2,'0')).join('');
    const result = { case:item.name, actualTokens:encoded.ids.length,
      tokenIdsSha256:hash, exactIds:hash === item.tokenIdsSha256,
      assurance:'canonical-prompt-bpe-only', memoryMeasurement:'not-available' };
    console.log(JSON.stringify({ message:'spike-tokenizer-result', ...result }));
    return Response.json(result, { headers:{ 'cache-control':'no-store' } });
  },
};
