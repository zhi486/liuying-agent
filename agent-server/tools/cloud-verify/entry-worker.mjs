import { makeVerificationEntry } from './worker.mjs';
export { VerificationRuntime } from './worker.mjs';

// 编译入口固定关闭交付屏障；不能通过请求或远程变量打开。
export default makeVerificationEntry({deliveryBarrier:false});
