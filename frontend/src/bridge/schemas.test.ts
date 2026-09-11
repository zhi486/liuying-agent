import { describe, expect, it } from 'vitest';

import {
  appSnapshotSchema,
  bridgeEnvelopeSchema,
  historyQueryResultSchema,
  nativeToWebEnvelopeSchema,
  parseInboundEnvelope,
  settingsQueryResultSchema,
  settingsUpdateBodySchema,
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
  it('接受心率警戒空值与整数边界', () => {
    for (const threshold of [null, 80, 240]) {
      for (const interval of [30, 300]) {
        expect(
          settingsUpdateBodySchema.safeParse({
            hrAlertThresholdBpm: threshold,
            hrAlertRepeatIntervalSec: interval,
          }).success,
        ).toBe(true);
      }
    }
  });

  it.each([
    { hrAlertThresholdBpm: 79 },
    { hrAlertThresholdBpm: 241 },
    { hrAlertThresholdBpm: '170' },
    { hrAlertThresholdBpm: 170.5 },
    { hrAlertRepeatIntervalSec: 29 },
    { hrAlertRepeatIntervalSec: 301 },
    { hrAlertRepeatIntervalSec: 120.5 },
    { hrAlertRepeatIntervalSec: null },
    { hrAlertThresholdBpm: null, unknownAlertSetting: 120 },
  ])('拒绝非法心率警戒契约 %j', (body) => {
    expect(settingsUpdateBodySchema.safeParse(body).success).toBe(false);
  });
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

  it('严格校验历史分页结果及可空均值', () => {
    const result = historyQueryResultSchema.parse({
      commandId: '06b8f5e1-6688-4494-abba-c50ba6baa0af',
      page: 1,
      pageSize: 10,
      totalItems: 1,
      hasNextPage: false,
      items: [
        {
          id: 'run-1',
          startedAt: '2026-09-01T11:00:00Z',
          endedAt: null,
          distanceMeters: 5020.5,
          durationSeconds: 1818,
          averagePaceSecPerKm: 362.15,
          averageHeartRate: null,
          averageCadence: null,
        },
      ],
    });

    expect(result.items[0]?.averageHeartRate).toBeNull();
  });

  it('拒绝字段缺失或负数的历史结果', () => {
    expect(() =>
      historyQueryResultSchema.parse({
        commandId: '06b8f5e1-6688-4494-abba-c50ba6baa0af',
        page: 1,
        pageSize: 10,
        totalItems: 1,
        hasNextPage: false,
        items: [
          {
            id: 'run-1',
            startedAt: '2026-09-01T11:00:00Z',
            endedAt: null,
            distanceMeters: -1,
            durationSeconds: 60,
            averagePaceSecPerKm: null,
            averageHeartRate: null,
          },
        ],
      }),
    ).toThrow();
  });

  it('严格校验设置查询结果', () => {
    const result = settingsQueryResultSchema.parse({
      commandId: '06b8f5e1-6688-4494-abba-c50ba6baa0af',
      settings: {
        targetPaceSecPerKm: null,
        targetDistanceKm: 5,
        maxHeartRate: 180,
        hrAlertThresholdBpm: null,
        hrAlertRepeatIntervalSec: 120,
        ttsEnabled: true,
        ttsMinIntervalSec: 30,
        llmEnabled: false,
        mockScenario: 'EASY_RUN',
        coachTone: 'balanced',
        fishReferenceId: 'default',
        fishApiKeyConfigured: false,
        llmApiKeyConfigured: false,
        themePreference: 'night',
        motionPreference: 'reduced',
      },
    });

    expect(result.settings.motionPreference).toBe('reduced');
  });

  it('严格校验主源、手环、完成摘要与同步状态', () => {
    const fixture = fixtureByType('snapshot');
    if (typeof fixture !== 'object' || fixture === null || !('payload' in fixture)) {
      throw new Error('snapshot 合同样本缺少 payload');
    }

    const parsed = appSnapshotSchema.parse(fixture.payload);

    expect(parsed.connection.deviceName).toBe('手机 GPS');
    expect(parsed.wearable.deviceName).toBe('Keep B5');
    expect(parsed.completedRun).toBeNull();
    expect(parsed.persistenceState).toBe('idle');
    expect(parsed.healthSync).toEqual({ state: 'idle', routeWritten: null });
  });

  it('拒绝缺少真实状态字段或非法同步状态的快照', () => {
    const fixture = fixtureByType('snapshot');
    if (typeof fixture !== 'object' || fixture === null || !('payload' in fixture)) {
      throw new Error('snapshot 合同样本缺少 payload');
    }

    const missingWearable = structuredClone(fixture.payload) as Record<string, unknown>;
    delete missingWearable.wearable;
    expect(() => appSnapshotSchema.parse(missingWearable)).toThrow();

    const invalidHealthSync = {
      ...(fixture.payload as Record<string, unknown>),
      healthSync: { state: 'success', routeWritten: true },
    };
    expect(() => appSnapshotSchema.parse(invalidHealthSync)).toThrow();
  });
});
