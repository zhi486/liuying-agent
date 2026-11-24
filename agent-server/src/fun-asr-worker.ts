import { createDeviceAuth } from './device-auth.ts';
import { CloudError } from './cloud-task-contracts.ts';
import { errorResponse } from './agent-http.ts';
import { createFunAsrProtocol } from './fun-asr-protocol.ts';

/** 应用只连接自己的已登记服务；服务端构造供应商地址与事件，网页不能指定目标。 */
export async function upgradeFunAsr(request: Request, env: Env): Promise<Response> {
  let upstream: WebSocket | undefined;
  const controller = new AbortController();
  const handshakeTimeout = setTimeout(() => controller.abort(), 10000);
  const abortHandshake = () => controller.abort();
  request.signal.addEventListener('abort', abortHandshake, { once: true });
  try {
    const url = new URL(request.url);
    if (url.protocol !== 'https:' || request.method !== 'GET' || url.search || request.headers.has('Origin') ||
        request.headers.get('Upgrade')?.toLowerCase() !== 'websocket' ||
        request.headers.get('X-Voice-Consent') !== 'fun-asr-v1') throw new CloudError('invalid_request');
    const auth = createDeviceAuth({ db: env.DB });
    const authorization = request.headers.get('Authorization');
    await auth.authenticate(authorization);
    const key = env.FUN_ASR_API_KEY, workspace = env.FUN_ASR_WORKSPACE_ID,
      region = env.FUN_ASR_REGION, vocabulary = env.FUN_ASR_VOCABULARY_ID;
    if (typeof key !== 'string' || !/^[\x21-\x7e]{1,512}$/.test(key) ||
        typeof workspace !== 'string' || !/^[a-zA-Z0-9-]{1,64}$/.test(workspace) ||
        !['cn-beijing', 'ap-southeast-1'].includes(region) ||
        typeof vocabulary !== 'string' || !/^[a-zA-Z0-9_-]{1,200}$/.test(vocabulary)) throw new CloudError('unavailable');
    if (request.signal.aborted) controller.abort();
    controller.signal.throwIfAborted();
    const response = await fetch(`https://${workspace}.${region}.maas.aliyuncs.com/api-ws/v1/inference`, {
      headers: { Upgrade: 'websocket', Authorization: `Bearer ${key}` }, redirect: 'manual', signal: controller.signal,
    });
    upstream = response.webSocket ?? undefined;
    if (response.status !== 101 || !upstream) { await response.body?.cancel(); throw new CloudError('unavailable'); }
    upstream.accept(); upstream.binaryType = 'arraybuffer';
    await auth.authenticate(authorization);
    controller.signal.throwIfAborted();
    const pair = new WebSocketPair(), client = pair[0], server = pair[1];
    server.accept(); server.binaryType = 'arraybuffer';
    const provider = upstream;
    const protocol = createFunAsrProtocol(crypto.randomUUID(), vocabulary);
    let closed = false, queued = 0, chain = Promise.resolve(), checkedAt = Date.now();
    const close = (failure = true) => {
      if (closed) return;
      closed = true; clearTimeout(lifetime); clearTimeout(startTimeout);
      try { if (failure) server.send(JSON.stringify({ type: 'error' })); } catch { /* 对端可能已断开。 */ }
      try { server.close(failure ? 1011 : 1000, failure ? '语音输入未完成' : '结束'); } catch { /* 已关闭。 */ }
      try { provider.close(1000, '结束'); } catch { /* 已关闭。 */ }
    };
    const lifetime = setTimeout(() => close(), 55000);
    const startTimeout = setTimeout(() => close(), 10000);
    // 有界串行处理，保持二进制帧与结束帧顺序；不共享请求级Promise。
    const enqueue = (operation: () => Promise<void>) => {
      if (closed) return;
      if (++queued > 32) { close(); return; }
      chain = chain.then(async () => { if (!closed) await operation(); }).catch(() => close()).finally(() => { queued--; });
    };
    const verify = async (force = false) => {
      if (force || Date.now() - checkedAt >= 5000) { await auth.authenticate(authorization); checkedAt = Date.now(); }
      if (closed) throw new Error('会话已结束');
    };
    server.addEventListener('message', event => {
      if (!(event.data instanceof ArrayBuffer) && event.data !== '{"type":"finish"}') { close(); return; }
      if (event.data instanceof ArrayBuffer && event.data.byteLength > 4096) { close(); return; }
      enqueue(async () => {
        await verify();
        if (event.data instanceof ArrayBuffer) { protocol.audio(event.data.byteLength); provider.send(event.data); }
        else { await verify(true); provider.send(protocol.finish()); }
      });
    });
    provider.addEventListener('message', event => {
      if (typeof event.data !== 'string' || event.data.length > 16384) { close(); return; }
      const raw = event.data;
      enqueue(async () => {
        const result = protocol.receive(raw);
        if (!result) return;
        await verify(result.type === 'ready' || result.type === 'completed');
        if (result.type === 'ready') clearTimeout(startTimeout);
        server.send(JSON.stringify(result));
        if (result.type === 'completed') close(false);
      });
    });
    server.addEventListener('close', () => close(false)); server.addEventListener('error', () => close());
    // 关闭与错误都先等待已接收事件处理完，避免最终结果鉴权期间被紧随的关闭错误截断。
    const providerEnded = () => { void chain.then(() => close()); };
    provider.addEventListener('close', providerEnded);
    provider.addEventListener('error', providerEnded);
    try {
      provider.send(protocol.start());
      return new Response(null, { status: 101, webSocket: client });
    } catch (error) { close(); throw error; }
  } catch (error) {
    try { upstream?.close(1000, '结束'); } catch { /* 握手已失败。 */ }
    return errorResponse(error instanceof CloudError ? error : new CloudError('unavailable'));
  } finally { clearTimeout(handshakeTimeout); request.signal.removeEventListener('abort', abortHandshake); }
}
