import { expect, it, vi } from 'vitest';
import { NativeMcpChannel } from '../src/native-mcp-channel.ts';
import { discoverMcpHttp } from '../src/mcp-http.ts';
import fixture from '../../contracts/mcp-phone-v1/transport.json';

const actor = { ownerId: 'synthetic-owner', deviceId: '77777777-7777-4777-8777-777777777777' };
const { version: _version, exchangeId: _exchangeId, method, body, sessionId, protocolVersion, ...scope } = fixture.request;
const request = () => new Request(scope.endpoint, { method, body,
  headers: { 'Mcp-Session-Id': sessionId, 'MCP-Protocol-Version': protocolVersion } });
const pause = () => new Promise(resolve => setTimeout(resolve, 1));

it('workerd 使用共享手机信封往返；只交付一次、重复回执幂等且异设备不可领取', async () => {
  const channel = new NativeMcpChannel({ ...actor, ...scope }, async () => {});
  try {
    const work = channel.send(request());
    await pause();
    await expect(channel.take({ ...actor, deviceId: crypto.randomUUID() })).rejects.toMatchObject({ code: 'scope_denied' });
    const packet = await channel.take(actor);
    expect({ ...packet, exchangeId: fixture.request.exchangeId }).toEqual(fixture.request);
    expect(await channel.take(actor)).toBeNull();
    const reply = { ...fixture.reply, exchangeId: packet.exchangeId };
    await channel.reply(actor, reply); await channel.reply(actor, reply);
    expect(await (await work).text()).toBe(fixture.reply.text);
    await expect(channel.reply(actor, { ...reply, text: '{}' })).rejects.toMatchObject({ code: 'conflict' });
  } finally { channel.close(); }
});

it('workerd 官方 SDK 经手机合成信封完成初始化和发现，独立 GET 不出网', async () => {
  const channel = new NativeMcpChannel({ ...actor, ...scope }, async () => {});
  const methods = []; let done = false;
  const work = discoverMcpHttp({ endpoint: scope.endpoint, signal: new AbortController().signal,
    send: request => channel.send(request), timeoutMs: 2000 }).finally(() => { done = true; });
  const phone = (async () => {
    while (!done) {
      const packet = await channel.take(actor);
      if (!packet) { await pause(); continue; }
      const message = packet.body ? JSON.parse(packet.body) : null;
      methods.push(message?.method ?? packet.method);
      const status = packet.method === 'DELETE' ? 204 : message.method === 'notifications/initialized' ? 202 : 200;
      const result = message?.method === 'initialize'
        ? { protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'synthetic', version: '1' } }
        : { tools: [{ name: 'read', inputSchema: { type: 'object' } }] };
      await channel.reply(actor, { version: 'native_mcp_v1', exchangeId: packet.exchangeId, generation: scope.generation,
        status, text: status === 200 ? JSON.stringify({ jsonrpc: '2.0', id: message.id, result }) : '', sessionId });
    }
  })();
  try {
    expect((await work).tools.map(tool => tool.name)).toEqual(['read']);
    expect(methods).toEqual(['initialize', 'notifications/initialized', 'tools/list', 'DELETE']);
  } finally { channel.close(); await phone; }
});

it.each(['credential_unavailable', 'stale_configuration', 'transport_failed', 'invalid_response',
  'catalog_changed', 'limit_exceeded', 'authentication_required', 'cancelled'])('workerd 手机失败 %s 只记录核验后的固定码，不泄露正文', async code => {
  const warning = vi.spyOn(console, 'warn').mockImplementation(() => {});
  const channel = new NativeMcpChannel({ ...actor, ...scope }, async () => {});
  const work = channel.send(request()); const failed = expect(work).rejects.toMatchObject({ code: 'transport_failed' });
  try {
    let packet;
    for (let i = 0; i < 100 && !packet; i++) { await pause(); packet = await channel.take(actor); }
    expect(packet).toBeDefined();
    const reply = { version: 'native_mcp_v1', exchangeId: packet.exchangeId, generation: scope.generation, error: code };
    await expect(channel.reply(actor, { ...reply, error: 'PRIVATE-REMOTE-DETAIL' })).rejects.toMatchObject({ code: 'invalid_request' });
    await expect(channel.reply(actor, { ...reply, generation: crypto.randomUUID() })).rejects.toMatchObject({ code: 'conflict' });
    await expect(channel.reply({ ...actor, deviceId: crypto.randomUUID() }, reply)).rejects.toMatchObject({ code: 'scope_denied' });
    expect(warning).not.toHaveBeenCalled();
    await channel.reply(actor, reply); await failed;
    expect(warning).toHaveBeenCalledExactlyOnceWith(JSON.stringify({ event: 'native_mcp_exchange_failed',
      taskId: scope.taskId, exchangeId: packet.exchangeId, code }));
    await expect(channel.reply(actor, reply)).rejects.toMatchObject({ code: 'inactive' });
    expect(warning).toHaveBeenCalledTimes(1);
  } finally { channel.close(); await failed; warning.mockRestore(); }
});

it('workerd 未交付或已撤权的失败回执不记录为已接纳诊断', async () => {
  const warning = vi.spyOn(console, 'warn').mockImplementation(() => {});
  try {
    for (const revoked of [false, true]) {
      let enabled = true;
      const channel = new NativeMcpChannel({ ...actor, ...scope }, async () => { if (!enabled) throw new Error('私密异常'); });
      const work = channel.send(request()); const failed = expect(work).rejects.toMatchObject({ code: 'inactive' });
      try {
        await pause();
        const packet = revoked ? await channel.take(actor) : null;
        if (revoked) enabled = false;
        await expect(channel.reply(actor, { version: 'native_mcp_v1', exchangeId: packet?.exchangeId ?? crypto.randomUUID(),
          generation: scope.generation, error: 'transport_failed' })).rejects.toMatchObject({ code: 'inactive' });
        expect(warning).not.toHaveBeenCalled();
      } finally { channel.close(); await failed; }
    }
  } finally { warning.mockRestore(); }
});

it('workerd 撤权拒绝待发请求及已交付结果；关闭后不能再次派发', async () => {
  for (const delivered of [false, true]) {
    let enabled = true;
    const channel = new NativeMcpChannel({ ...actor, ...scope }, async () => { if (!enabled) throw new Error('私密异常'); });
    const work = channel.send(request()); const failed = expect(work).rejects.toMatchObject({ code: 'inactive' });
    await pause(); const packet = delivered ? await channel.take(actor) : null;
    enabled = false;
    if (packet) await expect(channel.reply(actor, { ...fixture.reply, exchangeId: packet.exchangeId }))
      .rejects.toMatchObject({ code: 'inactive', message: '手机插件通道未完成' });
    else await expect(channel.take(actor)).rejects.toMatchObject({ code: 'inactive' });
    channel.close(); await failed;
    await expect(channel.send(request())).rejects.toMatchObject({ code: 'inactive' });
  }
});

it('workerd 总时限取消悬挂正文和授权检查，不依赖手机联网或外部取消', async () => {
  let cancelled = 0;
  const channel = new NativeMcpChannel({ ...actor, ...scope }, async () => {}, 25);
  await expect(channel.send(new Request(scope.endpoint, { method: 'POST',
    body: new ReadableStream({ cancel() { cancelled++; } }),
  }))).rejects.toMatchObject({ code: 'inactive' });
  expect(cancelled).toBe(1); channel.close();
  const hanging = new NativeMcpChannel({ ...actor, ...scope }, () => new Promise(() => {}), 25);
  await expect(hanging.send(request())).rejects.toMatchObject({ code: 'inactive' });
  hanging.close();
});
