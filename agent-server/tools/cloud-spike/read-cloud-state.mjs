import { readFileSync,writeFileSync } from 'node:fs';

// 只读固定账户资源；复用 Wrangler 的既有 OAuth，绝不打印或保存凭据。
const credential = readFileSync('C:/Users/fangr/AppData/Roaming/xdg.config/.wrangler/config/default.toml','utf8');
const token = credential.match(/^oauth_token\s*=\s*"([^"\r\n]+)"/m)?.[1];
if(!token) throw new Error('未找到既有 Wrangler 登录');
const account='95a131611fbe1097f7196cfb014ebce3';
const script='liuying-free-spike-20260915';
const targets = {
  accountSettings:`accounts/${account}/workers/account-settings`,
  subscriptions:`accounts/${account}/subscriptions`,
  settings:`accounts/${account}/workers/scripts/${script}/settings`,
  subdomain:`accounts/${account}/workers/scripts/${script}/subdomain`,
  namespaces:`accounts/${account}/workers/durable_objects/namespaces`,
};
const report={checkedAt:Date.now()};
for(const [name,path] of Object.entries(targets)) {
  const response=await fetch(`https://api.cloudflare.com/client/v4/${path}`,{
    headers:{authorization:`Bearer ${token}`},redirect:'error',signal:AbortSignal.timeout(20000),
  });
  const raw=await response.json();
  const result=raw.result;
  report[name]={status:response.status,success:raw.success,errorCodes:raw.errors?.map(value=>value.code)};
  if(!raw.success) continue;
  if(name==='settings') report[name].value={
    migrationTag:result.migration_tag,observability:result.observability,
    bindings:result.bindings?.map(value=>({type:value.type,name:value.name,
      class_name:value.class_name,namespace_id:value.namespace_id,id:value.id,
      ...(value.name==='EXPIRES_AT'?{text:value.text}:{})})),
  };
  if(name==='accountSettings') report[name].value={defaultUsageModel:result.default_usage_model,
    usageModel:result.usage_model,fields:Object.keys(result)};
  if(name==='subscriptions') report[name].value=result.map(value=>({id:value.id,state:value.state,
    frequency:value.frequency,price:value.price,ratePlan:value.rate_plan?.id,name:value.rate_plan?.public_name}));
  if(name==='subdomain') report[name].value={enabled:result.enabled,previews_enabled:result.previews_enabled};
  if(name==='namespaces') report[name].value=result.filter(value=>value.script===script).map(value=>({
    id:value.id,name:value.name,class:value.class,script:value.script,use_sqlite:value.use_sqlite}));
}
writeFileSync(new URL(`./.output/cloud-state-${report.checkedAt}.json`,import.meta.url),JSON.stringify(report,null,2),{flag:'wx'});
console.log(JSON.stringify(report,null,2));
