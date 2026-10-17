import { createDeviceAuth } from './device-auth.ts';
import { CloudError } from './cloud-task-contracts.ts';
import { errorResponse, readBody } from './agent-http.ts';
import type { BudgetDatabase } from './d1.ts';
import { baiduCredentialId, createBaiduTokenCache } from './baidu-token-cache.ts';

type VoiceBindings = { DB: BudgetDatabase; BAIDU_ASR_API_KEY?: string; BAIDU_ASR_SECRET_KEY?: string };

async function readJson(response: Response, signal: AbortSignal): Promise<Record<string, unknown>> {
  if (!response.ok) { await response.body?.cancel(); throw new CloudError('unavailable'); }
  const bytes = await readBody(new Request('https://bounded.invalid', { method: 'POST', body: response.body, signal,
    ...{ duplex: 'half' } }), 16384);
  const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new CloudError('unavailable');
  return value as Record<string, unknown>;
}

/** 单次普通话识别；仅转发内存 PCM，不创建聊天任务、保存正文或记录供应商响应。 */
export async function voiceInput(request: Request, env: VoiceBindings, transport: typeof fetch = fetch,
  tokenCache = createBaiduTokenCache()): Promise<Response> {
  const controller = new AbortController();
  const cancel = () => controller.abort();
  request.signal.addEventListener('abort', cancel, { once: true });
  if (request.signal.aborted) cancel();
  const timer = setTimeout(cancel, 25000);
  let audio: Uint8Array | undefined;
  let access: ReturnType<typeof tokenCache.get>;
  try {
    const url = new URL(request.url);
    if (url.protocol !== 'https:' || url.pathname !== '/v1/agent/voice' || url.search || url.hash ||
        url.username || url.password || request.method !== 'POST' || request.headers.has('content-encoding') ||
        request.headers.get('X-Voice-Consent') !== 'baidu-asr-v1' ||
        !/^audio\/pcm;\s*rate=16000$/i.test(request.headers.get('content-type') ?? ''))
      throw new CloudError('invalid_request');
    const auth = createDeviceAuth({ db: env.DB });
    const authorization = request.headers.get('Authorization');
    const principal = await auth.authenticate(authorization);
    const key = env.BAIDU_ASR_API_KEY, secret = env.BAIDU_ASR_SECRET_KEY;
    if (typeof key !== 'string' || typeof secret !== 'string' || !/^[\x21-\x7e]{1,512}$/.test(key) || !/^[\x21-\x7e]{1,512}$/.test(secret))
      throw new CloudError('unavailable');
    audio = await readBody(new Request(request, { signal: controller.signal }), 960000);
    if (audio.length < 3200 || audio.length % 2) throw new CloudError('invalid_request');
    controller.signal.throwIfAborted();
    const credentialId = await baiduCredentialId(key, secret);
    access = tokenCache.get(credentialId);
    if (!access) {
      const issuedAt = Date.now();
      const token = await readJson(await transport('https://aip.baidubce.com/oauth/2.0/token', {
        method: 'POST', redirect: 'error', signal: controller.signal,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ grant_type: 'client_credentials', client_id: key, client_secret: secret }).toString(),
      }), controller.signal);
      if (typeof token.access_token !== 'string' || !/^[\x21-\x7e]{1,4096}$/.test(token.access_token)) throw new CloudError('unavailable');
      controller.signal.throwIfAborted();
      access = tokenCache.put(credentialId, token.access_token, token.expires_in, issuedAt);
    }
    await auth.authenticate(authorization);
    controller.signal.throwIfAborted();
    const parts: string[] = [];
    for (let offset = 0; offset < audio.length; offset += 16384)
      parts.push(String.fromCharCode(...audio.subarray(offset, offset + 16384)));
    // Token 放正文，不放 URL，避免 URL 访问日志泄露；不缓存音频或识别结果。
    const result = await readJson(await transport('https://vop.baidu.com/server_api', {
      method: 'POST', redirect: 'error', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ format: 'pcm', rate: 16000, channel: 1, dev_pid: 1537,
        cuid: principal.deviceId, token: access.token, len: audio.length, speech: btoa(parts.join('')) }),
    }), controller.signal);
    if (result.err_no !== 0 || !Array.isArray(result.result) || typeof result.result[0] !== 'string') throw new CloudError('unavailable');
    const text = result.result[0].trim();
    if (!text || text.length > 2000) throw new CloudError('unavailable');
    await auth.authenticate(authorization);
    controller.signal.throwIfAborted();
    return Response.json({ text }, { headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    // 失败仅使下一次用户主动识别重新鉴权，本次绝不重发音频。
    if (access) tokenCache.invalidate(access);
    return errorResponse(error instanceof CloudError ? error : new CloudError('unavailable'));
  }
  finally { clearTimeout(timer); request.signal.removeEventListener('abort', cancel); audio?.fill(0); }
}
