import { describe, expect, it } from 'vitest';

import {
  bridgeEnvelopeSchema,
  nativeToWebEnvelopeSchema,
  parseInboundEnvelope,
  webToNativeEnvelopeSchema,
} from './schemas';

const validFixtures = import.meta.glob('../../../contracts/v1/fixtures/valid/*.json', {
  eager: true,
  import: 'default',
}) as Record<string, unknown>;

const invalidFixtures = import.meta.glob('../../../contracts/v1/fixtures/invalid/*.json', {
  eager: true,
  import: 'default',
}) as Record<string, unknown>;

function fixtureByType(type: string): unknown {
  const fixture = Object.values(validFixtures).find(
    (value) =>
      typeof value === 'object' && value !== null && 'type' in value && value.type === type,
  );

  if (!fixture) {
    throw new Error(`缺少 ${type} 合同样本`);
  }

  return fixture;
}

describe('Bridge v1 schema', () => {
  it.each(Object.entries(validFixtures))('接受合法样本 %s', (_, fixture) => {
    expect(() => bridgeEnvelopeSchema.parse(fixture)).not.toThrow();
  });

  it.each(Object.entries(invalidFixtures))('拒绝非法样本 %s', (_, fixture) => {
    expect(() => bridgeEnvelopeSchema.parse(fixture)).toThrow();
  });

  it.each(['hello', 'command'])('Native 到 Web 拒绝方向错误的 %s', (type) => {
    expect(() => nativeToWebEnvelopeSchema.parse(fixtureByType(type))).toThrow();
  });

  it.each(['ready', 'snapshot', 'result', 'event'])('Web 到 Native 拒绝方向错误的 %s', (type) => {
    expect(() => webToNativeEnvelopeSchema.parse(fixtureByType(type))).toThrow();
  });

  it('只从 Native 方向解析入站消息', () => {
    const raw = JSON.stringify(fixtureByType('snapshot'));

    const result = parseInboundEnvelope(raw);

    expect(result.ok).toBe(true);
  });

  it('拒绝畸形 JSON 且只返回稳定错误码', () => {
    expect(parseInboundEnvelope('{')).toEqual({
      ok: false,
      code: 'INVALID_MESSAGE',
    });
  });

  it('拒绝超过 262144 字节的消息', () => {
    const oversized = '流'.repeat(87_382);

    expect(new TextEncoder().encode(oversized).byteLength).toBeGreaterThan(262_144);
    expect(parseInboundEnvelope(oversized)).toEqual({
      ok: false,
      code: 'INVALID_MESSAGE',
    });
  });
});
