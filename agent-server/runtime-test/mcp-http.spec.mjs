import { expect, it } from 'vitest';
import { discoverMcpHttp } from '../src/mcp-http.ts';

it.each(['application/json', 'text/event-stream'])('workerd 中官方 SDK 经合成 %s 初始化并发现目录，不触发外网', async type => {
  const methods = [];
  const requests = [];
  const result = await discoverMcpHttp({ endpoint: 'https://tools.example.com/mcp', signal: new AbortController().signal,
    async send(request) {
      requests.push(request);
      if (request.method === 'GET') return new Response(null, { status: 405 });
      if (request.method === 'DELETE') return new Response(null, { status: 204 });
      const message = await request.json();
      methods.push(message.method);
      if (message.method === 'notifications/initialized') return new Response(null, { status: 202 });
      const result = message.method === 'initialize'
        ? { protocolVersion: '2025-11-25', capabilities: { tools: {} }, serverInfo: { name: 'synthetic', version: '1.0.0' } }
        : { tools: [{ name: 'read', description: '合成资料', inputSchema: { type: 'object', additionalProperties: false } }] };
      const body = JSON.stringify({ jsonrpc: '2.0', id: message.id, result });
      return new Response(type === 'text/event-stream' ? `data: ${body}\n\n` : body,
        { headers: { 'Content-Type': type, 'Mcp-Session-Id': 'synthetic-session' } });
    },
  });
  expect(result.tools.map(tool => tool.name)).toEqual(['read']);
  expect(methods).toEqual(['initialize', 'notifications/initialized', 'tools/list']);
  expect(requests.every(request => request.redirect === 'manual')).toBe(true);
  expect(requests.filter(request => request.method === 'DELETE')).toHaveLength(1);
});

it('workerd 中悬挂 SSE 按总时限取消；不执行依赖中的动态代码生成', async () => {
  let cancelled = 0;
  let posts = 0;
  await expect(discoverMcpHttp({ endpoint: 'https://tools.example.com/mcp', signal: new AbortController().signal, timeoutMs: 1000,
    async send() {
      posts++;
      return new Response(new ReadableStream({ cancel() { cancelled++; } }), { headers: { 'Content-Type': 'text/event-stream' } });
    },
  })).rejects.toMatchObject({ code: 'timeout' });
  expect(posts).toBe(1);
  expect(cancelled).toBe(1);
});

it.each(['redirect', 'oversize', 'utf8'])('workerd 中拒绝 %s 响应并释放源流，不重试请求', async kind => {
  let calls = 0;
  let cancelled = 0;
  await expect(discoverMcpHttp({ endpoint: 'https://tools.example.com/mcp', signal: new AbortController().signal,
    async send(request) {
      calls++;
      expect(request.redirect).toBe('manual');
      if (kind === 'redirect') return new Response('私密错误正文', { status: 302, headers: { Location: 'https://169.254.169.254/' } });
      return new Response(new ReadableStream({
        pull(controller) { controller.enqueue(kind === 'oversize' ? new Uint8Array(131073) : new Uint8Array([0xff])); },
        cancel() { cancelled++; },
      }), { headers: { 'Content-Type': 'application/json' } });
    },
  })).rejects.toMatchObject({ code: kind === 'oversize' ? 'limit_exceeded' : kind === 'utf8' ? 'invalid_response' : 'transport_failed' });
  expect(calls).toBe(1);
  if (kind !== 'redirect') expect(cancelled).toBe(1);
});
