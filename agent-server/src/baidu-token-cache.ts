type TokenEntry = Readonly<{ credentialId: string; token: string; expiresAt: number }>;

/** 仅缓存供应商应用令牌，不缓存用户、音频、转写、Promise或请求所属的I/O。 */
export function createBaiduTokenCache(now: () => number = Date.now) {
  let entry: TokenEntry | undefined;
  return {
    get(credentialId: string): TokenEntry | undefined {
      if (entry && (entry.expiresAt <= now() || entry.credentialId !== credentialId)) entry = undefined;
      return entry;
    },
    put(credentialId: string, token: string, expiresIn: unknown, issuedAt: number): TokenEntry {
      // 依供应商有效期提前一分钟失效，内存最多保存一小时；缺少有效期不缓存。
      const seconds = typeof expiresIn === 'number' && Number.isFinite(expiresIn) && expiresIn > 60
        ? Math.min(expiresIn, 3600) - 60 : 0;
      const candidate = Object.freeze({ credentialId, token, expiresAt: issuedAt + seconds * 1000 });
      if (candidate.expiresAt > now()) entry = candidate;
      return candidate;
    },
    invalidate(candidate: TokenEntry): void {
      if (entry === candidate) entry = undefined;
    },
  };
}

export async function baiduCredentialId(key: string, secret: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify([key, secret])));
  return Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('');
}
