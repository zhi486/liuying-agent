// 仅允许审定的两个隔离目标；拼写错误不能静默落回旧验收。
export function selectProfile(name='barrier-20260916') {
  if(name==='barrier-20260916')return Object.freeze({name,target:'liuying-agent-verify-20260916',
    output:'cloud-verify',main:'worker.mjs',compatibilityDate:'2026-09-16',deliveryBarrier:true});
  if(name==='entry-20260917')return Object.freeze({name,target:'liuying-agent-entry-20260917',
    output:'cloud-entry-20260917',main:'entry-worker.mjs',compatibilityDate:'2026-09-17',deliveryBarrier:false});
  throw new Error('未知验收配置，拒绝选择云目标');
}
