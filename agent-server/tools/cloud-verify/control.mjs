// 固定目标的一次性控制工具；秘密仅写入忽略目录，不进入参数、日志或报告。
import assert from 'node:assert/strict';
import { readFile,writeFile,mkdir } from 'node:fs/promises';
import { randomBytes,randomUUID,createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { selectProfile } from './profile.mjs';

export const profile=selectProfile(process.env.LIUYING_VERIFY_PROFILE);
export const target=profile.target;
export const account='95a131611fbe1097f7196cfb014ebce3';
export const root=fileURLToPath(new URL('../../',import.meta.url));
export const output=new URL(`../../.generated/${profile.output}/`,import.meta.url);
export const configPath=fileURLToPath(new URL('wrangler.json',output));
export const cli=fileURLToPath(new URL('../../node_modules/wrangler/bin/wrangler.js',import.meta.url));
export function requireExplicitProfile(){
  assert.equal(process.env.LIUYING_VERIFY_PROFILE,profile.name,'运行验收命令必须显式选择 profile');
}
const hash=value=>createHash('sha256').update(value).digest('hex');
export const session=async()=>{
  const value=JSON.parse(await readFile(new URL('session.json',output),'utf8'));
  assert.equal(value.target,target);
  if(!profile.deliveryBarrier)assert.equal(value.profile,profile.name);
  return value;
};
export async function artifact(name,value) {
  assert.match(name,/^[a-zA-Z0-9_.-]+$/);
  await writeFile(new URL(name,output),JSON.stringify(value,null,2),{flag:'wx',mode:0o600});
}
export function wrangler(args) {
  return new Promise((resolve,reject)=>{
    const child=spawn(process.execPath,[cli,...args],{cwd:root,windowsHide:true,
      env:{...process.env,CI:'true',WRANGLER_SEND_METRICS:'false',CLOUDFLARE_ACCOUNT_ID:account},stdio:['ignore','pipe','pipe'],timeout:120000});
    let text='';
    child.stdout.on('data',chunk=>{text+=chunk;if(text.length>2000000)child.kill();});
    // CLI 错误中可能有配置值；失败只输出退出码，完整原文不写报告。
    child.stderr.on('data',()=>{});
    child.on('error',()=>reject(new Error('Wrangler 启动失败')));
    child.on('close',code=>code===0?resolve(text):reject(new Error(`Wrangler 失败，退出码 ${code}`)));
  });
}
async function api(path,method='GET',data) {
  assert.ok(path.startsWith(`accounts/${account}/workers/scripts/${target}/`));
  const auth=await readFile('C:/Users/fangr/AppData/Roaming/xdg.config/.wrangler/config/default.toml','utf8');
  const token=auth.match(/^oauth_token\s*=\s*"([^"\r\n]+)"/m)?.[1];
  assert.ok(token,'需要有效的既有 Wrangler 登录');
  const response=await fetch(`https://api.cloudflare.com/client/v4/${path}`,{method,
    headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},
    ...(data?{body:JSON.stringify(data)}:{}),redirect:'error',signal:AbortSignal.timeout(20000)});
  const result=await response.json();
  return {status:response.status,success:result.success,codes:result.errors?.map(value=>value.code),value:result.result};
}
export function validateTarget(value,created) {
  assert.equal(value.name,target);assert.equal(value.account_id,account);
  assert.equal(value.d1_databases.length,1);
  assert.equal(value.d1_databases[0].binding,'DB');
  assert.equal(value.d1_databases[0].database_name,target);
  assert.notEqual(value.d1_databases[0].database_id,'8b159107-a1f8-40d9-a932-c52df81c3dfe');
  if(created) {
    assert.equal(created.target,target);
    assert.equal(value.d1_databases[0].database_id,created.databaseId);
  } else assert.equal(value.d1_databases[0].database_id,'00000000-0000-4000-8000-000000000000');
}
async function config(beforeCreation=false) {
  const value=JSON.parse(await readFile(configPath,'utf8'));
  const created=beforeCreation?null:JSON.parse(await readFile(new URL('created.json',output),'utf8'));
  validateTarget(value,created);
  if(!profile.deliveryBarrier){
    assert.equal(value.main,fileURLToPath(new URL(profile.main,import.meta.url)));
    if(created)assert.equal(created.profile,profile.name);
  }
  return value;
}
export async function sql(query) {
  const cfg=await config();
  assert.notEqual(cfg.d1_databases[0].database_id,'00000000-0000-4000-8000-000000000000');
  return JSON.parse(await wrangler(['d1','execute','DB','--config',configPath,'--remote','--json','--command',query]));
}
export async function cloudState() {
  const subdomain=await api(`accounts/${account}/workers/scripts/${target}/subdomain`);
  const settings=await api(`accounts/${account}/workers/scripts/${target}/settings`);
  const report={checkedAt:Date.now(),target,subdomain:{status:subdomain.status,success:subdomain.success,
    enabled:subdomain.value?.enabled,previews_enabled:subdomain.value?.previews_enabled},
    settings:{status:settings.status,success:settings.success,bindings:settings.value?.bindings?.map(value=>({
      name:value.name,type:value.type,id:value.id,namespace_id:value.namespace_id,class_name:value.class_name,
      ...(value.name==='VERIFY_EXPIRES_AT'?{text:value.text}:{})}))}};
  await artifact(`state-${Date.now()}.json`,report);return report;
}
export async function attemptClosures(actions) {
  const result={};
  for(const [name,action] of Object.entries(actions)) {
    try {await action();result[name]=true;} catch {result[name]=false;}
  }
  return result;
}
export async function closePublic(call) {
  let before;
  try {before=await call('GET');} catch { /* 读取失败仍尝试固定目标的关闭操作。 */ }
  if(!before?.success || before.value.enabled || before.value.previews_enabled) {
    const changed=await call('POST');
    assert.equal(changed.success,true,'公网关闭失败');
  }
}
export async function closeCloud() {
  const measures=await attemptClosures({
    public:async()=>{
      const path=`accounts/${account}/workers/scripts/${target}/subdomain`;
      await closePublic(method=>api(path,method,method==='POST'?{enabled:false,previews_enabled:false}:undefined));
    },
    database:async()=>{await sql('UPDATE verify_limits SET expires_at=0 WHERE singleton=1');},
    deployment:async()=>{
      const cfg=await config();cfg.workers_dev=false;cfg.preview_urls=false;cfg.vars.VERIFY_EXPIRES_AT='0';
      await writeFile(configPath,JSON.stringify(cfg,null,2));
      const deployed=await wrangler(['deploy','--config',configPath,'--no-autoconfig']);
      await artifact(`close-deploy-${Date.now()}.json`,{output:deployed});
    },
  });
  await artifact(`close-measures-${Date.now()}.json`,measures);
  const state=await cloudState();assert.equal(state.subdomain.enabled,false);assert.equal(state.subdomain.previews_enabled,false);
  assert.equal(state.settings.bindings.find(value=>value.name==='VERIFY_EXPIRES_AT')?.text,'0');
  const limit=await sql('SELECT expires_at FROM verify_limits WHERE singleton=1');
  assert.equal(limit[0].results[0].expires_at,0);
  return state;
}

async function main() {
  requireExplicitProfile();
  const action=process.argv[2];
  if(action==='prepare') {
    await mkdir(output,{recursive:true});
    const token=randomBytes(32).toString('hex');
    const data={target,profile:profile.name,token,createdAt:Date.now(),devices:Array.from({length:2},()=>({
      owner:randomUUID(),id:randomUUID(),token:'lyd_'+randomBytes(32).toString('hex')}))};
    await artifact('session.json',data);
    const cfg={$schema:'../../node_modules/wrangler/config-schema.json',name:target,account_id:account,
      main:fileURLToPath(new URL(profile.main,import.meta.url)),compatibility_date:profile.compatibilityDate,compatibility_flags:['nodejs_compat'],
      workers_dev:false,preview_urls:false,routes:[],vars:{VERIFY_EXPIRES_AT:'0',VERIFY_TOKEN_HASH:hash(token)},
      durable_objects:{bindings:[{name:'AGENT_RUNTIME',class_name:'VerificationRuntime'}]},
      migrations:[{tag:'v1',new_sqlite_classes:['VerificationRuntime']}],
      d1_databases:[{binding:'DB',database_name:target,database_id:'00000000-0000-4000-8000-000000000000',
        migrations_dir:fileURLToPath(new URL('../../migrations',import.meta.url))}],
      rules:[{type:'Data',globs:['**/*.bin'],fallthrough:false}],
      observability:{enabled:true,redact_query_string:true,logs:{enabled:true,invocation_logs:false},traces:{enabled:true}}};
    await writeFile(configPath,JSON.stringify(cfg,null,2),{flag:'wx'});
    console.log('本地会话与关闭态配置已生成，凭据不输出。');
  } else if(action==='create') {
    const cfg=await config(true);const before=JSON.parse(await wrangler(['d1','list','--json']));
    assert.ok(!before.some(value=>value.name===target),'同名测试库已存在，停止创建');
    const prior=await api(`accounts/${account}/workers/scripts/${target}/subdomain`);
    assert.ok(!prior.success && prior.codes?.includes(10007),'同名 Worker 已存在或未取得明确不存在证据');
    await wrangler(['d1','create',target,'--no-update-config']);
    const found=JSON.parse(await wrangler(['d1','list','--json'])).filter(value=>value.name===target);
    assert.equal(found.length,1);cfg.d1_databases[0].database_id=found[0].uuid;
    await writeFile(configPath,JSON.stringify(cfg,null,2));await artifact('created.json',{target,profile:profile.name,databaseId:found[0].uuid});
    console.log('仅新隔离 D1 已创建并核验。');
  } else if(action==='seed') {
    await config(); const data=await session();
    await wrangler(['d1','migrations','apply','DB','--config',configPath,'--remote']);
    let seed=await readFile(new URL('./schema.sql',import.meta.url),'utf8');
    seed+='\nINSERT INTO verify_limits VALUES (1,0,0,0);\n';
    for(const d of data.devices) {
      seed+=`INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES ('${d.owner}',1800000);\n`;
      seed+=`INSERT INTO agent_devices(id,owner_id,token_hash,created_at,expires_at) VALUES ('${d.id}','${d.owner}','${hash(d.token)}',${data.createdAt},${data.createdAt+7776000000});\n`;
    }
    const path=fileURLToPath(new URL('seed.sql',output));await writeFile(path,seed,{flag:'wx',mode:0o600});
    await wrangler(['d1','execute','DB','--config',configPath,'--remote','--file',path,'--yes']);
    await artifact('seeded.json',{at:Date.now()});console.log('三份正式迁移和合成身份已写入新库，窗口仍关闭。');
  } else if(action==='open') {
    const cfg=await config();const started=Date.now(),expires=started+1800000;
    await artifact('opened-once.json',{target,profile:profile.name,started,expires});
    const changed=await sql(`UPDATE verify_limits SET started_at=${started},expires_at=${expires}
      WHERE singleton=1 AND started_at=0 AND expires_at=0 AND requests=0 RETURNING started_at`);
    assert.equal(changed[0].results.length,1,'测试窗口不能续期或重开');
    cfg.vars.VERIFY_EXPIRES_AT=String(expires);cfg.workers_dev=true;
    await writeFile(configPath,JSON.stringify(cfg,null,2));
    try {
      const result=await wrangler(['deploy','--config',configPath,'--no-autoconfig']);
      await artifact('open-deploy.json',{at:Date.now(),output:result});
      console.log(JSON.stringify({target,started,expires,realProviderCalls:0}));
    } catch(error) {try {await closeCloud();} catch {console.error('部署异常且关闭未确认，请核对固定验收目标。');} throw error;}
  } else if(action==='close') console.log(JSON.stringify(await closeCloud()));
  else if(action==='state') console.log(JSON.stringify(await cloudState()));
  else throw new Error('只接受 prepare/create/seed/open/close/state');
}
if(process.argv[1]===fileURLToPath(import.meta.url)) main().catch(error=>{console.error(error.message);process.exitCode=1;});
