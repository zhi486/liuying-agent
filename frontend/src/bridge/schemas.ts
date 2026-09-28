import { z } from 'zod';
import { agentCommandSchemas } from './agentTasks';
import { memoryCommandSchemas } from './memories';
import { cloudCommandSchemas } from './cloudAgent';
import { voiceCommandSchemas } from './voiceInput';

import type {
  AppSnapshot,
  BridgeEnvelopeV1,
  HistoryQueryResult,
  HistoryDetailResult,
  HistorySeriesResult,
  HistoryRunRecord,
  NativeToWebEnvelope,
  SettingsQueryResult,
  WebToNativeEnvelope,
} from './contract';

const canonicalUuidV4Schema = z
  .uuidv4()
  .refine(
    (value) => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value),
    '必须是小写 canonical UUID v4',
  );
const timestampSchema = z.iso.datetime({ offset: true });
const nullableNonNegativeNumber = z.number().nonnegative().nullable();
const permissionStateSchema = z.enum(['granted', 'denied', 'notRequested']);

const settingsSnapshotSchema = z
  .object({
    targetPaceSecPerKm: nullableNonNegativeNumber,
    targetDistanceKm: nullableNonNegativeNumber,
    maxHeartRate: z.number().int().positive(),
    hrAlertThresholdBpm: z.number().int().min(80).max(240).nullable(),
    hrAlertRepeatIntervalSec: z.number().int().min(30).max(300),
    ttsEnabled: z.boolean(),
    ttsMinIntervalSec: z.number().int().nonnegative(),
    llmEnabled: z.boolean(),
    mockScenario: z.enum([
      'EASY_RUN',
      'SPEED_TEST',
      'PROGRESSIVE',
      'HEART_RATE_SPIKE',
      'CADENCE_DROP',
    ]),
    coachTone: z.enum(['gentle', 'balanced', 'direct']),
    fishReferenceId: z.string(),
    fishApiKeyConfigured: z.boolean(),
    llmApiKeyConfigured: z.boolean(),
    deepseekApiKeyConfigured: z.boolean(),
    themePreference: z.enum(['system', 'morning', 'night']),
    motionPreference: z.enum(['system', 'expressive', 'balanced', 'reduced', 'off']),
  })
  .strict();

export const appSnapshotSchema: z.ZodType<AppSnapshot> = z
  .object({
    revision: z.number().int().nonnegative(),
    generatedAt: timestampSchema,
    connection: z
      .object({
        state: z.enum(['disconnected', 'connecting', 'connected', 'error']),
        deviceName: z.string().min(1).nullable(),
        batteryPercent: z.number().int().min(0).max(100).nullable(),
      })
      .strict(),
    wearable: z
      .object({
        supported: z.boolean(),
        state: z.enum(['disconnected', 'connecting', 'connected', 'error']),
        deviceName: z.string().min(1).nullable(),
        batteryPercent: z.number().int().min(0).max(100).nullable(),
      })
      .strict(),
    session: z
      .object({
        id: canonicalUuidV4Schema.nullable(),
        state: z.enum(['idle', 'preparing', 'running', 'paused', 'finished', 'error']),
        startedAt: timestampSchema.nullable(),
        targetPaceSecPerKm: nullableNonNegativeNumber,
        targetDistanceMeters: nullableNonNegativeNumber,
      })
      .strict(),
    metrics: z
      .object({
        heartRate: z.number().int().nonnegative().nullable(),
        paceSecPerKm: nullableNonNegativeNumber,
        cadence: z.number().int().nonnegative().nullable(),
        strideLengthCm: nullableNonNegativeNumber,
        distanceMeters: z.number().nonnegative(),
        elapsedSeconds: z.number().int().nonnegative(),
        hrZone: z.enum(['z1', 'z2', 'z3', 'z4', 'z5']).nullable(),
        isDataFresh: z.boolean().nullable(),
      })
      .strict(),
    latestCoachMessage: z
      .object({
        id: canonicalUuidV4Schema,
        text: z.string().min(1),
        priority: z.enum(['urgent', 'split', 'coach', 'app']),
        timestampMillis: z.number().int().nonnegative(),
      })
      .strict()
      .nullable(),
    settings: settingsSnapshotSchema,
    permissions: z
      .object({
        bluetooth: permissionStateSchema,
        bodySensors: permissionStateSchema,
        runningSensors: permissionStateSchema,
      })
      .strict(),
    theme: z.enum(['morning', 'night']),
    motionPolicy: z.enum(['expressive', 'balanced', 'reduced', 'off']),
    dataQuality: z
      .object({
        gps: z.enum(['waiting', 'fresh', 'stale']),
        heartRate: z.enum(['not_available', 'waiting', 'fresh', 'stale']),
      })
      .strict(),
    safetyState: z
      .object({
        level: z.enum(['normal', 'warning', 'emergency']),
        message: z.string().min(1).nullable(),
      })
      .strict(),
    completedRun: z
      .object({
        endedAt: timestampSchema,
        distanceMeters: z.number().nonnegative(),
        durationSeconds: z.number().int().nonnegative(),
        averagePaceSecPerKm: nullableNonNegativeNumber,
        averageHeartRate: nullableNonNegativeNumber,
        averageCadence: nullableNonNegativeNumber,
      })
      .strict()
      .nullable(),
    persistenceState: z.enum(['idle', 'saving', 'saved', 'failed']),
    healthSync: z
      .object({
        state: z.enum([
          'idle',
          'syncing',
          'synced',
          'permissionRequired',
          'providerUpdateRequired',
          'unavailable',
          'failed',
        ]),
        routeWritten: z.boolean().nullable(),
      })
      .strict(),
  })
  .strict();

export const settingsUpdateBodySchema = z
  .object({
    targetPaceSecPerKm: nullableNonNegativeNumber.optional(),
    targetDistanceKm: nullableNonNegativeNumber.optional(),
    maxHeartRate: z.number().int().positive().optional(),
    hrAlertThresholdBpm: z.number().int().min(80).max(240).nullable().optional(),
    hrAlertRepeatIntervalSec: z.number().int().min(30).max(300).optional(),
    ttsEnabled: z.boolean().optional(),
    ttsMinIntervalSec: z.number().int().nonnegative().optional(),
    llmEnabled: z.boolean().optional(),
    mockScenario: z
      .enum(['EASY_RUN', 'SPEED_TEST', 'PROGRESSIVE', 'HEART_RATE_SPIKE', 'CADENCE_DROP'])
      .optional(),
    coachTone: z.enum(['gentle', 'balanced', 'direct']).optional(),
    fishApiKey: z.string().min(1).optional(),
    fishReferenceId: z.string().optional(),
    llmApiKey: z.string().min(1).optional(),
    themePreference: z.enum(['system', 'morning', 'night']).optional(),
    motionPreference: z.enum(['system', 'expressive', 'balanced', 'reduced', 'off']).optional(),
  })
  .strict()
  .refine((body) => Object.keys(body).length > 0, 'settings.update 至少包含一个字段');

export const historyRunRecordSchema: z.ZodType<HistoryRunRecord> = z
  .object({
    id: z.string().min(1),
    startedAt: timestampSchema,
    endedAt: timestampSchema.nullable(),
    distanceMeters: z.number().nonnegative(),
    durationSeconds: z.number().int().nonnegative(),
    averagePaceSecPerKm: nullableNonNegativeNumber,
    averageHeartRate: nullableNonNegativeNumber,
    averageCadence: nullableNonNegativeNumber,
  })
  .strict();

export const historyQueryResultSchema: z.ZodType<HistoryQueryResult> = z
  .object({
    commandId: canonicalUuidV4Schema,
    page: z.number().int().positive(),
    pageSize: z.number().int().min(1).max(100),
    totalItems: z.number().int().nonnegative(),
    hasNextPage: z.boolean(),
    items: z.array(historyRunRecordSchema),
  })
  .strict();

export const settingsQueryResultSchema: z.ZodType<SettingsQueryResult> = z
  .object({
    commandId: canonicalUuidV4Schema,
    settings: settingsSnapshotSchema,
  })
  .strict();

export const historyDetailResultSchema: z.ZodType<HistoryDetailResult> = z
  .object({
    commandId: canonicalUuidV4Schema,
    record: historyRunRecordSchema,
    splits: z
      .array(
        z
          .object({
            index: z.number().int().positive(),
            endDistanceMeters: z.number().nonnegative(),
            durationSeconds: z.number().int().nonnegative(),
            averagePaceSecPerKm: z.number().nonnegative(),
            averageHeartRate: nullableNonNegativeNumber,
          })
          .strict(),
      )
      .refine(
        (splits) => new Set(splits.map((split) => split.index)).size === splits.length,
        '分段序号重复',
      ),
  })
  .strict();

const emptyBodySchema = z.object({}).strict();
const seriesSessionIdSchema = z
  .string()
  .min(1)
  .max(128)
  .refine((id) => id.trim() === id && id.trim().length > 0);
const metricSampleSchema = z
  .object({
    startElapsedSeconds: z.number().int().nonnegative(),
    endElapsedSeconds: z.number().int().positive(),
    segmentId: z.number().int().nonnegative(),
    averagePaceSecPerKm: z.number().positive().nullable(),
    averageHeartRate: z.number().positive().nullable(),
    paceValidSeconds: z.number().int().min(0).max(5),
    heartRateValidSeconds: z.number().int().min(0).max(5),
  })
  .strict()
  .refine((p) => {
    const duration = p.endElapsedSeconds - p.startElapsedSeconds;
    return (
      duration > 0 &&
      duration <= 5 &&
      p.paceValidSeconds <= duration &&
      p.heartRateValidSeconds <= duration &&
      (p.averagePaceSecPerKm === null || p.paceValidSeconds === duration) &&
      (p.averageHeartRate === null ? p.heartRateValidSeconds === 0 : p.heartRateValidSeconds > 0)
    );
  }, '采样窗口或覆盖秒数无效');

export const historySeriesResultSchema: z.ZodType<HistorySeriesResult> = z
  .object({
    commandId: canonicalUuidV4Schema,
    sessionId: seriesSessionIdSchema,
    offset: z.number().int().nonnegative(),
    totalItems: z.number().int().nonnegative(),
    samples: z.array(metricSampleSchema).max(300),
  })
  .strict()
  .refine(
    (page) =>
      page.offset + page.samples.length <= page.totalItems &&
      page.samples.every(
        (p, i) =>
          i === 0 ||
          (p.startElapsedSeconds >= page.samples[i - 1].endElapsedSeconds &&
            p.segmentId >= page.samples[i - 1].segmentId),
      ),
    '序列分页或顺序无效',
  );

const commandPayloadSchema = z.discriminatedUnion('name', [
  ...voiceCommandSchemas,
  ...agentCommandSchemas,
  ...cloudCommandSchemas,
  ...memoryCommandSchemas,
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('history.series'),
      body: z
        .object({
          sessionId: seriesSessionIdSchema,
          offset: z.number().int().nonnegative(),
          limit: z.number().int().min(1).max(300),
        })
        .strict(),
    })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('history.detail'),
      body: z
        .object({
          sessionId: z
            .string()
            .min(1)
            .max(128)
            .refine((id) => id.trim() === id && id.trim().length > 0),
        })
        .strict(),
    })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('settings.configureDeepseek'),
      body: emptyBodySchema,
    })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('device.connect'),
      body: emptyBodySchema,
    })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('device.disconnect'),
      body: emptyBodySchema,
    })
    .strict(),
  z
    .object({ helloId: canonicalUuidV4Schema, name: z.literal('run.start'), body: emptyBodySchema })
    .strict(),
  z
    .object({ helloId: canonicalUuidV4Schema, name: z.literal('run.pause'), body: emptyBodySchema })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('run.resume'),
      body: emptyBodySchema,
    })
    .strict(),
  z
    .object({ helloId: canonicalUuidV4Schema, name: z.literal('run.stop'), body: emptyBodySchema })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('history.query'),
      body: z
        .object({ page: z.number().int().positive(), pageSize: z.number().int().min(1).max(100) })
        .strict(),
    })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('settings.query'),
      body: emptyBodySchema,
    })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('settings.update'),
      body: settingsUpdateBodySchema,
    })
    .strict(),
  z
    .object({
      helloId: canonicalUuidV4Schema,
      name: z.literal('permission.request'),
      body: z.object({ kind: z.literal('runningSensors') }).strict(),
    })
    .strict(),
]);

const baseEnvelopeShape = {
  contractVersion: z.literal(1),
  id: canonicalUuidV4Schema,
  sentAt: timestampSchema,
};

const helloEnvelopeSchema = z
  .object({
    ...baseEnvelopeShape,
    type: z.literal('hello'),
    payload: z
      .object({
        buildId: z.string().min(1),
        supportedContractVersions: z.tuple([z.literal(1)]),
      })
      .strict(),
  })
  .strict();

const readyEnvelopeSchema = z
  .object({
    ...baseEnvelopeShape,
    type: z.literal('ready'),
    payload: z
      .object({
        helloId: canonicalUuidV4Schema,
        buildId: z.string().min(1),
        capabilities: z.union([
          z.tuple([
            z.literal('snapshot.v1'),
            z.literal('history.pagination'),
            z.literal('settings.v1'),
            z.literal('heart-rate-alerts.v1'),
            z.literal('data-quality.v1'),
            z.literal('deepseek-companion.v1'),
          ]),
          z.tuple([
            z.literal('snapshot.v1'),
            z.literal('history.pagination'),
            z.literal('settings.v1'),
            z.literal('heart-rate-alerts.v1'),
            z.literal('data-quality.v1'),
            z.literal('deepseek-companion.v1'),
            z.literal('agent.tasks.v1'),
            z.literal('memory.v1'),
            z.literal('agent.cloud.tasks.v1'),
          ]),
        ]),
        snapshot: appSnapshotSchema,
      })
      .strict(),
  })
  .strict();

const snapshotEnvelopeSchema = z
  .object({
    ...baseEnvelopeShape,
    type: z.literal('snapshot'),
    payload: appSnapshotSchema,
  })
  .strict();

const commandEnvelopeSchema = z
  .object({
    ...baseEnvelopeShape,
    type: z.literal('command'),
    payload: commandPayloadSchema,
  })
  .strict();

const bridgeErrorCodeSchema = z.enum([
  'BRIDGE_VERSION_UNSUPPORTED',
  'INVALID_MESSAGE',
  'COMMAND_NOT_ALLOWED',
  'PERMISSION_REQUIRED',
  'SESSION_CONFLICT',
  'RESOURCE_UNAVAILABLE',
  'WEB_RUNTIME_FAILED',
  'INTERNAL_ERROR',
]);

type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
const jsonValueSchema: z.ZodType<JsonValue> = z.lazy(() =>
  z.union([
    z.string(),
    z.number(),
    z.boolean(),
    z.null(),
    z.array(jsonValueSchema),
    z.record(z.string(), jsonValueSchema),
  ]),
);

const resultEnvelopeSchema = z
  .object({
    ...baseEnvelopeShape,
    type: z.literal('result'),
    payload: z.discriminatedUnion('status', [
      z.object({ status: z.literal('accepted'), data: jsonValueSchema }).strict(),
      z
        .object({
          status: z.literal('rejected'),
          error: z.object({ code: bridgeErrorCodeSchema, message: z.string().min(1) }).strict(),
        })
        .strict(),
    ]),
  })
  .strict();

const eventEnvelopeSchema = z
  .object({
    ...baseEnvelopeShape,
    type: z.literal('event'),
    payload: z.discriminatedUnion('name', [
      z
        .object({
          name: z.literal('permission.result'),
          requestId: canonicalUuidV4Schema,
          kind: z.literal('runningSensors'),
          granted: z.boolean(),
        })
        .strict(),
      z
        .object({
          name: z.literal('notice'),
          code: z.string().min(1),
          message: z.string().min(1),
        })
        .strict(),
    ]),
  })
  .strict();

export const webToNativeEnvelopeSchema: z.ZodType<WebToNativeEnvelope> = z.discriminatedUnion(
  'type',
  [helloEnvelopeSchema, commandEnvelopeSchema],
);

export const nativeToWebEnvelopeSchema: z.ZodType<NativeToWebEnvelope> = z.discriminatedUnion(
  'type',
  [readyEnvelopeSchema, snapshotEnvelopeSchema, resultEnvelopeSchema, eventEnvelopeSchema],
);

export const bridgeEnvelopeSchema: z.ZodType<BridgeEnvelopeV1> = z.discriminatedUnion('type', [
  helloEnvelopeSchema,
  readyEnvelopeSchema,
  snapshotEnvelopeSchema,
  commandEnvelopeSchema,
  resultEnvelopeSchema,
  eventEnvelopeSchema,
]);

export type ParseInboundResult =
  | { ok: true; value: NativeToWebEnvelope }
  | { ok: false; code: 'INVALID_MESSAGE' };

export function parseInboundEnvelope(raw: string): ParseInboundResult {
  if (new TextEncoder().encode(raw).byteLength > 262_144) {
    return { ok: false, code: 'INVALID_MESSAGE' };
  }

  try {
    const parsed: unknown = JSON.parse(raw);
    const result = nativeToWebEnvelopeSchema.safeParse(parsed);

    return result.success
      ? { ok: true, value: result.data }
      : { ok: false, code: 'INVALID_MESSAGE' };
  } catch {
    return { ok: false, code: 'INVALID_MESSAGE' };
  }
}
