import { spawn } from 'node:child_process';
import { cli,root,target,artifact,profile,requireExplicitProfile } from './control.mjs';
const keys=['message','role','phase','trace','kind','case','requestId','instance','status','round','mode','tokens','bodyHash'];
export function sanitizeTail(raw) {
  const logs=[];
  for(const record of raw.logs??[]) for(const value of Array.isArray(record.message)?record.message:[record.message]) {
    try {
      const parsed=typeof value==='string'?JSON.parse(value):value;
      if(parsed?.message!=='verify-runtime')continue;
      logs.push(Object.fromEntries(keys.filter(key=>Object.hasOwn(parsed,key)).map(key=>[key,parsed[key]])));
    } catch { /* 非验收日志、原始异常与正文不保存。 */ }
  }
  return {eventTimestamp:raw.eventTimestamp,outcome:raw.outcome,scriptName:raw.scriptName,
    cpuTime:raw.cpuTime,wallTime:raw.wallTime,entrypoint:raw.entrypoint,executionModel:raw.executionModel,
    rpcMethod:raw.event?.rpcMethod,status:raw.event?.response?.status,
    exceptionNames:(raw.exceptions??[]).map(value=>value.name),logs};
}
export async function captureTail() {
  requireExplicitProfile();
  const rows=[];let buffer='',parseError=false,closed=false,startFailed=false;
  const child=spawn(process.execPath,[cli,'tail',target,'--format','json'],{cwd:root,windowsHide:true,
    env:{...process.env,WRANGLER_SEND_METRICS:'false'},stdio:['ignore','pipe','pipe']});
  child.stdout.setEncoding('utf8');
  child.stdout.on('data',chunk=>{
    buffer+=chunk;
    if(buffer.length>2000000){parseError=true;child.kill();return;}
    while(true) {
      const start=buffer.indexOf('{');if(start<0){buffer='';return;}
      let depth=0,quoted=false,escaped=false,end=-1;
      for(let i=start;i<buffer.length;i++){
        const ch=buffer[i];
        if(quoted){if(escaped)escaped=false;else if(ch==='\\')escaped=true;else if(ch==='"')quoted=false;}
        else if(ch==='"')quoted=true;else if(ch==='{')depth++;else if(ch==='}'&&--depth===0){end=i+1;break;}
      }
      if(end<0)return;
      try {rows.push(sanitizeTail(JSON.parse(buffer.slice(start,end))));}catch{parseError=true;}
      buffer=buffer.slice(end);
    }
  });
  child.stderr.on('data',()=>{});
  child.on('error',()=>{startFailed=true;});
  const ended=new Promise(resolve=>child.on('close',code=>{closed=true;resolve(code);}));
  // JSON 模式没有连接 banner；调用方必须用收到的 ready 事件作屏障。
  return {rows,isAlive:()=>!closed&&!startFailed,async stop(){
    if(!closed)child.kill();const code=await ended;
    const report={target,profile:profile.name,finishedAt:Date.now(),code,parseError,rows};await artifact(`tail-${Date.now()}.json`,report);return report;
  }};
}
