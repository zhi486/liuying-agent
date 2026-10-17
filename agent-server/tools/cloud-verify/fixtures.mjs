import { longPrompts } from '../../runtime-test/long-text-fixture.mjs';

export const cases = [
  { name:'cold-short',prompt:'总结本次跑步',mode:'one',owner:0 },
  { name:'hot-short',prompt:'总结本次跑步',mode:'one',owner:0 },
  { name:'long-a',prompt:longPrompts[0],mode:'one',owner:0 },
  { name:'long-b',prompt:longPrompts[1],mode:'one',owner:1 },
  { name:'three-rounds',prompt:'合成验收：请先列出记录，再查看 run_1，最后总结。',mode:'three',owner:0 },
  { name:'disconnect',prompt:'合成验收：独立连接中断后恢复原任务。',mode:'disconnect',owner:0 },
  { name:'cancel',prompt:'合成验收：等待期间取消。',mode:'cancel',owner:0 },
  { name:'withdraw',prompt:'合成验收：等待期间撤回。',mode:'withdraw',owner:0 },
  { name:'deadline',prompt:'合成验收：严格原截止，不延长任务。',mode:'deadline',owner:0 },
  { name:'unknown',prompt:'合成验收：未取得用量时保持未知预留。',mode:'unknown',owner:1 },
];
export function fixture(index) {
  if(!cases[index]) throw new Error('未知合成用例');
  return { clientRequestId:`20260916-0000-4000-8000-${String(index).padStart(12,'0')}`,
    localTaskId:`20260916-0001-4000-8000-${String(index).padStart(12,'0')}`,
    prompt:cases[index].prompt,consent:{cloudProcessing:true,runSummaries:true},
    summaries:[{runId:'run_1',distanceMeters:4000,durationSeconds:1440,averagePaceSecPerKm:360,averageHeartRate:150}] };
}
export function matchFixture(value) {
  for(let index=0;index<cases.length;index++) {
    const expected=fixture(index);
    if(JSON.stringify(value)===JSON.stringify(expected)) return index;
    // 专用同键异文反例；仍交给正式指纹校验拒绝。
    if(index===0 && JSON.stringify(value)===JSON.stringify({...expected,prompt:'合成验收：不同文本'})) return index;
  }
  return null;
}

export function syntheticTransport(log=()=>{}) {
  let calls=0;
  return async (url,init) => {
    if(url!=='https://api.deepseek.com/chat/completions' || init?.method!=='POST' || typeof init.body!=='string')
      throw new Error('验收传输协议错误');
    const body=JSON.parse(init.body);
    const mode=cases.find(value=>value.prompt===body.messages.find(value=>value.role==='user')?.content)?.mode;
    if(!mode || ++calls>3) throw new Error('非验收模型请求');
    log({phase:'transport',round:calls,mode});
    if(mode==='unknown') throw new TypeError('合成网络失败');
    // 为客户端独立管理面观察留出时间；所有任务仍受正式原 30 秒截止控制。
    const wait=mode==='disconnect'?4000:['cancel','withdraw'].includes(mode)?15000:mode==='deadline'?40000:0;
    if(wait) await new Promise((resolve,reject)=>{
      let timer;
      const abort=()=>{clearTimeout(timer);init.signal?.removeEventListener('abort',abort);reject(new Error('合成请求取消'));};
      if(init.signal?.aborted) return abort();
      init.signal?.addEventListener('abort',abort,{once:true});
      timer=setTimeout(()=>{init.signal?.removeEventListener('abort',abort);resolve();},wait);
    });
    const tool=mode==='three' && calls<3;
    return Response.json({choices:[{index:0,finish_reason:tool?'tool_calls':'stop',message:tool?
      {role:'assistant',content:null,tool_calls:[{id:`verify_call_${calls}`,type:'function',function:
        calls===1?{name:'runs_list',arguments:'{}'}:{name:'runs_summary',arguments:'{"runId":"run_1"}'}}]}:
      {role:'assistant',content:'合成云验收完成，不是健康建议。'}}],
      usage:{prompt_tokens:1000,completion_tokens:20,total_tokens:1020}});
  };
}
