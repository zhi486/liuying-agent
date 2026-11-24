import { createServer } from 'node:https';
import { readFileSync } from 'node:fs';
import { createSummaryService } from '../src/summary-service.ts';
import { SummarySqliteStore } from './summary-sqlite-store.ts';
import { createSummaryApplication } from '../src/summary-factory.ts';
import { createInputTokenCounter } from '../src/token-count/counter.ts';
import { digest } from '../src/device-auth.ts';
import { openBudgetDatabase } from '../test/sqlite-d1.ts';

// 仅供跨进程合成测试；不挂生产入口，不读取真实凭据，不调用外部服务。
const [certificate, key, database] = process.argv.slice(2);
let dropResponse = process.argv[5] === 'drop-first-submit';
if (!certificate || !key || !database) throw new Error('缺少隔离测试参数');
const store = new SummarySqliteStore(database);
const factoryMode = process.argv[6] === 'factory';
const ledger = factoryMode ? openBudgetDatabase(database + '.usage') : null;
const legacy = createSummaryService({
  authenticate: async (authorization) => {
    if (authorization !== 'Bearer lyd_' + 'a'.repeat(64)) throw new Error('测试身份不匹配');
    return { ownerId: 'owner', deviceId: 'device' };
  },
  store,
  model: { generate: async () => {
    process.stdout.write('MODEL\n');
    return { text: '跨进程合成摘要', inputTokens: 7, outputTokens: 2 };
  } },
});
let handle = legacy;
if (ledger) {
  if (!ledger.sqlite.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='summary_usage'").get())
    ledger.sqlite.exec(readFileSync(new URL('./summary-runtime/ledger.sql', import.meta.url), 'utf8'));
  ledger.sqlite.prepare('INSERT OR IGNORE INTO agent_budget_accounts VALUES (?, ?, ?)').run('owner', 1, 1);
  const now = Date.now();
  ledger.sqlite.prepare('INSERT OR IGNORE INTO agent_devices (id, owner_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)')
    .run('device', 'owner', await digest('lyd_' + 'a'.repeat(64)), now, now + 7776000000);
  const count = await createInputTokenCounter(readFileSync(new URL('./token-count/.assets/tokenizer.json', import.meta.url)));
  handle = createSummaryApplication({ db: ledger.db, store, rate: { input: 300000, output: 1200000 },
    model: { apiKey: 'synthetic-key', countInputTokens: async raw => {
      const tokens = await count(raw);
      if (tokens <= 0) throw new Error('合成输入计数无效');
      process.stdout.write('COUNT ' + tokens + '\n');
      return tokens;
    }, fetch: async (_url, init) => {
      const body = JSON.parse(String(init?.body));
      if (body.tools.length !== 0 || init?.redirect !== 'manual') throw new Error('摘要安全配置不匹配');
      process.stdout.write('MODEL\n');
      return Response.json({ choices: [{ index: 0, finish_reason: 'stop',
        message: { role: 'assistant', content: '跨进程合成摘要' } }],
        usage: { prompt_tokens: 7, completion_tokens: 2, total_tokens: 9 } });
    } } }).handle;
}
const server = createServer({ cert: readFileSync(certificate), key: readFileSync(key) }, async (incoming, outgoing) => {
  try {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of incoming) {
      size += chunk.length;
      if (size > 100000) throw new Error('请求超长');
      chunks.push(chunk);
    }
    const address = server.address();
    if (!address || typeof address === 'string') throw new Error('回环服务未启动');
    const headers = new Headers();
    for (const [name, value] of Object.entries(incoming.headers)) {
      if (value !== undefined) headers.set(name, Array.isArray(value) ? value.join(', ') : value);
    }
    const method = incoming.method;
    if (!method) throw new Error('缺少请求方法');
    const response = await handle(new Request('https://127.0.0.1:' + address.port + incoming.url, {
      method, headers, ...(size > 0 ? { body: Buffer.concat(chunks) } : {}),
    }));
    if (dropResponse && incoming.method === 'POST' && incoming.url === '/v1/agent/summaries' && response.ok) {
      dropResponse = false;
      incoming.socket.destroy();
      return;
    }
    outgoing.writeHead(response.status, Object.fromEntries(response.headers));
    outgoing.end(Buffer.from(await response.arrayBuffer()));
  } catch {
    outgoing.writeHead(503); outgoing.end();
  }
});
server.listen(0, '127.0.0.1', () => {
  const address = server.address();
  if (address && typeof address !== 'string') process.stdout.write('PORT ' + address.port + '\n');
});
process.stdin.resume();
process.stdin.once('data', () => server.close(() => {
  if (ledger) {
    const rows = ledger.sqlite.prepare('SELECT input_tokens, output_tokens FROM summary_usage').all();
    if (rows.length !== 1 || rows[0]?.input_tokens !== 7 || rows[0]?.output_tokens !== 2)
      throw new Error('独立摘要用量不匹配');
    process.stdout.write('USAGE 7 2\n');
  }
  store.close(); ledger?.close(); process.exit(0);
}));
