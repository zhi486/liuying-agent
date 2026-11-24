import { expect, it } from 'vitest';
import { callMcpHttp } from '../src/mcp-http.ts';
import { approveMcpTools, collectMcpCatalog, createMcpService, setMcpCatalog } from '../src/mcp-registry.ts';

const tool = { name: 'synthetic.lookup', inputSchema: { type: 'object', properties: { query: { type: 'string', minLength: 1 } },
  required: ['query'], additionalProperties: false }, outputSchema: { type: 'object', properties: { count: { type: 'integer' } }, required: ['count'] } };
async function fixture(type = 'application/json', override) {
  const controller = new AbortController();
  const base = createMcpService('synthetic-owner', 'https://tools.example.com/mcp');
  const reviewed = setMcpCatalog(base, base.ownerId, await collectMcpCatalog(async () => ({ tools: [tool] }), controller.signal));
  const service = approveMcpTools(reviewed, base.ownerId, reviewed.revision, [{ name: tool.name, mode: 'confirm' }]);
  const methods = [];
  const authorizations = [];
  return { controller, methods, authorizations, options: {
    service, actor: service.ownerId, revision: service.revision, modelName: service.grants[0].modelName,
    arguments: { query: '合成资料' }, signal: controller.signal, timeoutMs: 1000,
    async authorizeDispatch(dispatch) { authorizations.push(dispatch); },
    async send(request) {
      expect(request.url).toBe(service.endpoint);
      expect(request.redirect).toBe('manual');
      expect(request.headers.has('authorization')).toBe(false);
      if (request.method === 'GET') return new Response(null, { status: 405 });
      if (request.method === 'DELETE') return new Response(null, { status: 204 });
      const message = await request.json();
      methods.push(message.method);
      const replacement = await override?.(message);
      if (replacement) return replacement;
      if (message.method.startsWith('notifications/')) return new Response(null, { status: 202 });
      const result = message.method === 'initialize'
        ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'synthetic', version: '1' } }
        : message.method === 'tools/list' ? { tools: [tool] }
        : { content: [{ type: 'text', text: '不可信合成结果' }], structuredContent: { count: 1 } };
      const body = JSON.stringify({ jsonrpc: '2.0', id: message.id, result });
      return new Response(type === 'text/event-stream' ? `data: ${body}\n\n` : body,
        { headers: { 'Content-Type': type, 'Mcp-Session-Id': 'synthetic-session' } });
    },
  } };
}

it.each(['application/json', 'text/event-stream'])('workerd 通过合成 %s 调用一次工具，无动态代码生成或外网', async type => {
  const server = await fixture(type);
  const result = await callMcpHttp(server.options);
  expect(result.structuredContent).toEqual({ count: 1 });
  expect(result.content).toEqual([{ type: 'text', text: '不可信合成结果' }]);
  expect(server.methods).toEqual(['initialize', 'notifications/initialized', 'tools/list', 'tools/call']);
  expect(server.authorizations).toHaveLength(1);
  expect(server.authorizations[0]).toMatchObject({ mode: 'confirm', argumentsJson: '{"query":"合成资料"}' });
});

it('workerd 中参数不合规则零出网，发送前撤销则零工具调用', async () => {
  const server = await fixture();
  await expect(callMcpHttp({ ...server.options, arguments: { query: 1 } })).rejects.toMatchObject({ code: 'invalid_arguments', dispatchState: 'not_sent' });
  expect(server.methods).toHaveLength(0);
  await expect(callMcpHttp({ ...server.options, async authorizeDispatch() { throw new Error('合成撤销'); } }))
    .rejects.toMatchObject({ code: 'authorization_denied', dispatchState: 'not_sent' });
  expect(server.methods.includes('tools/call')).toBe(false);
});

it.each(['initialize', 'catalog', 'call', 'cleanup'])('workerd 保留 %s 失败阶段，不输出私密异常或重发', async phase => {
  const server = await fixture();
  const attempts = [];
  await expect(callMcpHttp({ ...server.options, async send(request) {
    const method = request.method === 'POST' ? (await request.clone().json()).method : request.method;
    attempts.push(method);
    if ((phase === 'initialize' && method === 'initialize') ||
        (phase === 'catalog' && method === 'tools/list') ||
        (phase === 'call' && method === 'tools/call') ||
        (phase === 'cleanup' && method === 'DELETE')) throw new Error('PRIVATE-REMOTE-DETAIL');
    return server.options.send(request);
  } })).rejects.toMatchObject({ message: 'MCP 工具调用未完成', code: 'transport_failed', phase,
    dispatchState: phase === 'call' || phase === 'cleanup' ? 'sent' : 'not_sent' });
  expect(attempts.filter(method => method === 'tools/call')).toHaveLength(phase === 'call' || phase === 'cleanup' ? 1 : 0);
});

it('workerd 中发送后悬挂 SSE 超时释放源流，不重发写操作', async () => {
  let cancelled = 0;
  const server = await fixture('application/json', async message => message.method === 'tools/call'
    ? new Response(new ReadableStream({ cancel() { cancelled++; } }), { headers: { 'Content-Type': 'text/event-stream' } }) : undefined);
  await expect(callMcpHttp(server.options)).rejects.toMatchObject({ code: 'timeout', dispatchState: 'sent' });
  expect(server.methods.filter(method => method === 'tools/call')).toHaveLength(1);
  expect(server.authorizations).toHaveLength(1);
  expect(cancelled).toBe(1);
});

it('workerd 中超大结果有明确发送标记，取消后也不返回迟到的正常结果', async () => {
  const large = await fixture('application/json', async message => message.method === 'tools/call'
    ? Response.json({ jsonrpc: '2.0', id: message.id, result: { content: [{ type: 'text', text: '字'.repeat(45000) }], structuredContent: { count: 1 } } }) : undefined);
  await expect(callMcpHttp(large.options)).rejects.toMatchObject({ code: 'limit_exceeded', dispatchState: 'sent' });
  const server = await fixture();
  await expect(callMcpHttp({ ...server.options, async authorizeDispatch() { server.controller.abort(); } }))
    .rejects.toMatchObject({ code: 'cancelled', dispatchState: 'not_sent' });
  expect(server.methods.includes('tools/call')).toBe(false);
});

it('workerd 对有效大文本给出 Unicode 完整且有标记的有界片段', async () => {
  const text = '🛶字\\"\n'.repeat(3000);
  const server = await fixture('application/json', async message => message.method === 'tools/call'
    ? Response.json({ jsonrpc: '2.0', id: message.id, result: {
      content: [{ type: 'text', text }], structuredContent: { count: 1 },
    } }) : undefined);
  const result = await callMcpHttp(server.options);
  expect(result.truncated).toBe(true);
  expect(result.isError).toBe(false);
  expect(result.structuredContent).toBeUndefined();
  expect(new TextEncoder().encode(JSON.stringify(result)).byteLength).toBeLessThanOrEqual(16384);
  const prefix = result.content[0].text;
  expect(prefix.length).toBeGreaterThan(0);
  expect(prefix.length).toBeLessThan(text.length);
  expect(text.startsWith(prefix)).toBe(true);
  expect(new TextDecoder().decode(new TextEncoder().encode(prefix))).toBe(prefix);
  expect(server.methods.filter(method => method === 'tools/call')).toHaveLength(1);
  expect(server.authorizations).toHaveLength(1);
});
