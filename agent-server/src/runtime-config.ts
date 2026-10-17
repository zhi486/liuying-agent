import type { RateCard } from './budget.ts';
import { CloudError } from './cloud-task-contracts.ts';

type RuntimeBindings = { DEEPSEEK_API_KEY?: unknown; RATE_CARD_JSON?: unknown };
type RateStatus = 'missing' | 'invalid' | 'not_yet_valid' | 'expired' | 'valid';

// 2026-09-23官方价格页核验的高峰未命中参考价；不是实时价格或供应商账单。
export const REFERENCE_RATE: RateCard = Object.freeze({
  version: 'reference_deepseek_flash_20260923', model: 'deepseek-flash',
  inputMicrosPerMillion: 300000, outputMicrosPerMillion: 1200000,
  checkedAtMillis: 1790174810000, expiresAtMillis: 1790261210000,
});

function inspectApiKey(value: unknown): 'missing' | 'invalid' | 'format_valid' {
  if (value === undefined || value === '') return 'missing';
  return typeof value === 'string' && /^[\x21-\x7e]{1,512}$/.test(value) ? 'format_valid' : 'invalid';
}

function inspectRateCard(value: unknown, now: number): { status: RateStatus; rate: RateCard | null } {
  try {
    if (value === undefined || value === '') return { status: 'missing', rate: null };
    if (typeof value !== 'string' || value.length > 2048)
      throw new Error();
    const rate = JSON.parse(value);
    if (!rate || typeof rate !== 'object' || Array.isArray(rate) || Object.keys(rate).length !== 6 ||
        typeof rate.version !== 'string' || !/^[a-zA-Z0-9_-]{1,80}$/.test(rate.version) ||
        rate.model !== 'deepseek-flash' ||
        !Number.isSafeInteger(rate.inputMicrosPerMillion) || rate.inputMicrosPerMillion < 1 ||
        !Number.isSafeInteger(rate.outputMicrosPerMillion) || rate.outputMicrosPerMillion < 1 ||
        !Number.isSafeInteger(rate.checkedAtMillis) || rate.checkedAtMillis < 0 ||
        !Number.isSafeInteger(rate.expiresAtMillis) || rate.expiresAtMillis <= rate.checkedAtMillis ||
        rate.expiresAtMillis - rate.checkedAtMillis > 86400000 ||
        !Number.isSafeInteger(now) || now < 0)
      throw new Error();
    if (now < rate.checkedAtMillis) return { status: 'not_yet_valid', rate: null };
    if (now >= rate.expiresAtMillis) return { status: 'expired', rate: Object.freeze(rate) as RateCard };
    return { status: 'valid', rate: Object.freeze(rate) as RateCard };
  } catch {
    return { status: 'invalid', rate: null };
  }
}

// 只返回白名单状态；不能据此宣称密钥认证、当前价格或云部署已验证。
export function inspectRuntimeConfig(bindings: RuntimeBindings, now = Date.now()) {
  const apiKey = inspectApiKey(bindings.DEEPSEEK_API_KEY);
  const rateCard = inspectRateCard(bindings.RATE_CARD_JSON, now).status;
  return { apiKey, rateCard, localChecksPassed: apiKey === 'format_valid' && rateCard === 'valid' };
}

export function readRuntimeConfig(
  bindings: RuntimeBindings, now = Date.now(),
): { apiKey: string; rate: RateCard } {
  try {
    const apiKey = bindings.DEEPSEEK_API_KEY;
    const result = inspectRateCard(bindings.RATE_CARD_JSON, now);
    if (typeof apiKey !== 'string' || inspectApiKey(apiKey) !== 'format_valid' ||
        !Number.isSafeInteger(now) || now < 0)
      throw new Error();
    return { apiKey, rate: result.rate ?? REFERENCE_RATE };
  } catch {
    throw new CloudError('unavailable');
  }
}
