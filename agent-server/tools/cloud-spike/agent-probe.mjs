import { createAgentService } from '../../src/agent-service.ts';
import { createDeviceAdmin } from '../../src/device-auth.ts';
import { interceptD1 } from '../../test/d1-interceptor.ts';

// 此入口永远只返回固定供应商响应，不把传入 fetch 的 URL 或密钥发送到网络。
function reply(step, rounds) {
  const tool = step < rounds;
  const message = tool ? { role: 'assistant', content: null, tool_calls: [{
    id: `call_${step}`, type: 'function', function: step === 1 ?
      { name: 'runs_list', arguments: '{}' } :
      { name: 'runs_summary', arguments: '{"runId":"run_1"}' },
  }] } : { role: 'assistant', content: '虚构云验证：根据 run_1，你完成了四公里。' };
  return { id: `fixture_${step}`, object: 'chat.completion', created: 1789430400,
    model: 'deepseek-flash', system_fingerprint: 'fixture',
    choices: [{ index: 0, logprobs: null, finish_reason: tool ? 'tool_calls' : 'stop', message }],
    usage: { prompt_tokens: 100, completion_tokens: 40, total_tokens: 140,
      prompt_cache_hit_tokens: 0, prompt_cache_miss_tokens: 100,
      prompt_tokens_details: { cached_tokens: 0 } },
  };
}

export async function agentProbe(db, name, ctx, options = {}) {
  let receiptCommitted = false;
  let faultInjected = false;
  if (name === 'receipt-fault') db = interceptD1(db, async (sql, phase) => {
    if (phase === 'after' && sql.includes('INSERT INTO agent_usage_receipts')) receiptCommitted = true;
    if (receiptCommitted && !faultInjected && phase === 'before' &&
        sql.includes("SET state = 'settled', charged_micros")) {
      faultInjected = true;
      throw new Error('隔离探针：收据提交后仅注入一次结算故障');
    }
  });
  const ownerId = options.identity?.ownerId ?? `spike_${crypto.randomUUID()}`;
  let device = options.identity;
  if (!device) {
    await db.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)')
      .bind(ownerId).run();
    device = await createDeviceAdmin({ db }).issue(ownerId);
  }
  const rounds = name === 'three' ? 3 : 1;
  const background = name === 'background';
  const disconnect = name === 'disconnect';
  let markEntered;
  const entered = new Promise(resolve => { markEntered = resolve; });
  let dispatched = 0;
  const time = Date.now();
  const service = createAgentService({ db,
    rate: { version: 'cloud-spike-fixture', model: 'deepseek-flash',
      inputMicrosPerMillion: 300000, outputMicrosPerMillion: 1200000,
      checkedAtMillis: time, expiresAtMillis: time + 86400000 },
    apiKey: 'fixture-only-not-a-secret', countInputTokens: options.countInputTokens ?? (async () => 100),
    fetch: async () => {
      if (++dispatched > rounds) throw new Error('假供应商调用次数越界');
      markEntered();
      if (disconnect && options.onEntered) await options.onEntered({ case:name,
        taskId:prepared.task.id, ownerId, transport:'fake-only', transportEntered:true });
      if (disconnect) await new Promise(resolve => setTimeout(resolve, 4000));
      if (background) await new Promise(resolve => setTimeout(resolve, 2000));
      return Response.json(reply(dispatched, rounds));
    },
  });
  const prepared = await service.prepare(`Bearer ${device.token}`, {
    clientRequestId: crypto.randomUUID(), localTaskId: crypto.randomUUID(),
    prompt: '总结这次虚构跑步，注明来源', consent: { cloudProcessing: true, runSummaries: true },
    summaries: [{ runId: 'run_1', distanceMeters: 4000, durationSeconds: 1440,
      averagePaceSecPerKm: 360, averageHeartRate: 150 }],
  });
  if (!prepared.run) throw new Error('新虚构任务缺少执行入口');
  const execution = prepared.run({ waitUntil: promise => ctx.waitUntil(promise) });
  if (disconnect && !options.awaitCompletion) {
    ctx.waitUntil(execution);
    await Promise.race([entered, execution.then(() => { throw new Error('假传输未进入'); })]);
    let cancelled = false;
    const encoder = new TextEncoder();
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(JSON.stringify({ case:name, taskId:prepared.task.id,
          ownerId, transport:'fake-only', transportEntered:true }) + '\n'));
        ctx.waitUntil(execution.finally(() => {
          if (!cancelled) controller.close();
        }));
      },
      cancel() { cancelled = true; console.log('spike-client-stream-cancelled'); },
    });
    return new Response(body, { headers: { 'content-type':'application/x-ndjson',
      'cache-control':'no-store, no-transform' } });
  }
  if (background) ctx.waitUntil(execution);
  else await execution;
  return Response.json({ case: name, taskId: prepared.task.id, ownerId,
    transport: 'fake-only', tokenCounting: options.tokenCounting ?? 'fixed-fixture-not-exact' }, {
    status: background ? 202 : 200, headers: { 'cache-control': 'no-store' },
  });
}
