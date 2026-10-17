import { rejectProbe } from './gate.mjs';
import { agentProbe } from './agent-probe.mjs';
import { recoverFreeExecutions } from '../../src/execution-ledger.ts';

// 一次性云探针：不读取请求体、不提供任意 SQL，不调用任何外部模型。
const cases = Object.freeze({
  'seq-50': [50, 0], 'seq-51': [51, 0],
  'batch-51': [0, 51], 'mixed-49-2': [49, 2],
});
const json = (value) => Response.json(value, { headers: { 'cache-control': 'no-store' } });

async function quota(db, name) {
  const [sequential, batch] = cases[name];
  const result = { case: name, attemptedCalls: 0, completedCalls: 0,
    completedSql: 0, outcome: 'completed' };
  const validate = (row) => {
    if (row.results?.length !== 1 || row.results[0]?.probe !== 1)
      throw new Error('探针查询结果不匹配');
  };
  try {
    for (let i = 0; i < sequential; i++) {
      result.attemptedCalls++;
      const row = await db.prepare('SELECT 1 AS probe').all();
      result.completedCalls++;
      result.completedSql++;
      validate(row);
    }
    if (batch) {
      result.attemptedCalls++;
      const rows = await db.batch(Array.from({ length: batch }, () =>
        db.prepare('SELECT 1 AS probe')));
      result.completedCalls++;
      result.completedSql += rows.length;
      if (rows.length !== batch) throw new Error('探针批处理结果数不匹配');
      rows.forEach(validate);
    }
  } catch (error) {
    // 固定无敏感内容 SQL 的错误仅供鉴权探针诊断；不再查库或重试。
    result.outcome = 'database_error';
    result.error = error instanceof Error ? error.message.slice(0, 240) : 'unknown';
  }
  return json(result);
}

export default {
  async fetch(request, env, ctx) {
    const rejected = await rejectProbe(request, env);
    if (rejected) return rejected;
    const url = new URL(request.url);
    if (url.pathname === '/agent/recover') return json(await recoverFreeExecutions({ db:env.DB }));
    if (['/agent/one', '/agent/three', '/agent/background', '/agent/disconnect',
      '/agent/receipt-fault'].includes(url.pathname))
      return agentProbe(env.DB, url.pathname.slice('/agent/'.length), ctx);
    const name = url.pathname.slice('/quota/'.length);
    if (!url.pathname.startsWith('/quota/') ||
        !Object.hasOwn(cases, name)) return new Response(null, { status: 404 });
    return quota(env.DB, name);
  },
};
