import { timingSafeEqual } from 'node:crypto';

// 所有隔离探针共用鉴权、到期与请求形状门禁。
export async function rejectProbe(request, env) {
  if (!env.SPIKE_TOKEN || env.SPIKE_TOKEN.length < 32) return new Response(null, { status:503 });
  const expiresAt = Number(env.EXPIRES_AT);
  if (!Number.isSafeInteger(expiresAt) || expiresAt <= Date.now()) return new Response(null, { status:410 });
  const provided = request.headers.get('authorization') ?? '';
  if (provided.length > 256) return new Response(null, { status:401 });
  const encoder = new TextEncoder();
  const hashes = await Promise.all([provided, `Bearer ${env.SPIKE_TOKEN}`].map(value =>
    crypto.subtle.digest('SHA-256', encoder.encode(value))));
  if (!timingSafeEqual(new Uint8Array(hashes[0]), new Uint8Array(hashes[1])))
    return new Response(null, { status:401 });
  if (request.method !== 'POST' || new URL(request.url).search) return new Response(null, { status:404 });
  return null;
}
