export type CommandName =
  | 'device.connect'
  | 'device.disconnect'
  | 'run.start'
  | 'run.pause'
  | 'run.resume'
  | 'run.stop'
  | 'history.query'
  | 'settings.query'
  | 'settings.update'
  | 'permission.request';

export type BridgeErrorCode =
  | 'BRIDGE_VERSION_UNSUPPORTED'
  | 'INVALID_MESSAGE'
  | 'COMMAND_NOT_ALLOWED'
  | 'PERMISSION_REQUIRED'
  | 'SESSION_CONFLICT'
  | 'RESOURCE_UNAVAILABLE'
  | 'WEB_RUNTIME_FAILED'
  | 'INTERNAL_ERROR';

export interface AppSnapshot {
  revision: number;
  generatedAt: string;
  connection: {
    state: 'disconnected' | 'connecting' | 'connected' | 'error';
    deviceName: string | null;
    batteryPercent: number | null;
  };
  wearable: {
    supported: boolean;
    state: 'disconnected' | 'connecting' | 'connected' | 'error';
    deviceName: string | null;
    batteryPercent: number | null;
  };
  session: {
    id: string | null;
    state: 'idle' | 'preparing' | 'running' | 'paused' | 'finished' | 'error';
    startedAt: string | null;
    targetPaceSecPerKm: number | null;
    targetDistanceMeters: number | null;
  };
  metrics: {
    heartRate: number | null;
    paceSecPerKm: number | null;
    cadence: number | null;
    strideLengthCm: number | null;
    distanceMeters: number;
    elapsedSeconds: number;
    hrZone: 'z1' | 'z2' | 'z3' | 'z4' | 'z5' | null;
    isDataFresh: boolean | null;
  };
  latestCoachMessage: {
    id: string;
    text: string;
    priority: 'urgent' | 'split' | 'coach' | 'app';
    timestampMillis: number;
  } | null;
  settings: {
    targetPaceSecPerKm: number | null;
    targetDistanceKm: number | null;
    maxHeartRate: number;
    hrAlertThresholdBpm: number | null;
    hrAlertRepeatIntervalSec: number;
    ttsEnabled: boolean;
    ttsMinIntervalSec: number;
    llmEnabled: boolean;
    mockScenario: 'EASY_RUN' | 'SPEED_TEST' | 'PROGRESSIVE' | 'HEART_RATE_SPIKE' | 'CADENCE_DROP';
    coachTone: 'gentle' | 'balanced' | 'direct';
    fishReferenceId: string;
    fishApiKeyConfigured: boolean;
    llmApiKeyConfigured: boolean;
    themePreference: 'system' | 'morning' | 'night';
    motionPreference: 'system' | 'expressive' | 'balanced' | 'reduced' | 'off';
  };
  permissions: {
    bluetooth: 'granted' | 'denied' | 'notRequested';
    bodySensors: 'granted' | 'denied' | 'notRequested';
    runningSensors: 'granted' | 'denied' | 'notRequested';
  };
  theme: 'morning' | 'night';
  motionPolicy: 'expressive' | 'balanced' | 'reduced' | 'off';
  safetyState: {
    level: 'normal' | 'warning' | 'emergency';
    message: string | null;
  };
  completedRun: {
    endedAt: string;
    distanceMeters: number;
    durationSeconds: number;
    averagePaceSecPerKm: number | null;
    averageHeartRate: number | null;
    averageCadence: number | null;
  } | null;
  persistenceState: 'idle' | 'saving' | 'saved' | 'failed';
  healthSync: {
    state:
      | 'idle'
      | 'syncing'
      | 'synced'
      | 'permissionRequired'
      | 'providerUpdateRequired'
      | 'unavailable'
      | 'failed';
    routeWritten: boolean | null;
  };
}

export interface SettingsUpdateBody {
  targetPaceSecPerKm?: number | null;
  targetDistanceKm?: number | null;
  maxHeartRate?: number;
  hrAlertThresholdBpm?: number | null;
  hrAlertRepeatIntervalSec?: number;
  ttsEnabled?: boolean;
  ttsMinIntervalSec?: number;
  llmEnabled?: boolean;
  mockScenario?: AppSnapshot['settings']['mockScenario'];
  coachTone?: AppSnapshot['settings']['coachTone'];
  fishApiKey?: string;
  fishReferenceId?: string;
  llmApiKey?: string;
  themePreference?: AppSnapshot['settings']['themePreference'];
  motionPreference?: AppSnapshot['settings']['motionPreference'];
}

export interface SettingsQueryResult {
  commandId: string;
  settings: AppSnapshot['settings'];
}

export interface HistoryRunRecord {
  id: string;
  startedAt: string;
  endedAt: string | null;
  distanceMeters: number;
  durationSeconds: number;
  averagePaceSecPerKm: number | null;
  averageHeartRate: number | null;
  averageCadence: number | null;
}

export interface HistoryQueryResult {
  commandId: string;
  page: number;
  pageSize: number;
  totalItems: number;
  hasNextPage: boolean;
  items: HistoryRunRecord[];
}

export interface HelloPayload {
  buildId: string;
  supportedContractVersions: [1];
}

export interface ReadyPayload {
  helloId: string;
  buildId: string;
  capabilities: ['snapshot.v1', 'history.pagination', 'settings.v1', 'heart-rate-alerts.v1'];
  snapshot: AppSnapshot;
}

export interface CommandBodyMap {
  'device.connect': Record<string, never>;
  'device.disconnect': Record<string, never>;
  'run.start': Record<string, never>;
  'run.pause': Record<string, never>;
  'run.resume': Record<string, never>;
  'run.stop': Record<string, never>;
  'history.query': { page: number; pageSize: number };
  'settings.query': Record<string, never>;
  'settings.update': SettingsUpdateBody;
  'permission.request': { kind: 'runningSensors' };
}

export type CommandPayload<T extends CommandName = CommandName> = T extends CommandName
  ? { helloId: string; name: T; body: CommandBodyMap[T] }
  : never;

export type AppEventPayload =
  | {
      name: 'permission.result';
      requestId: string;
      kind: 'runningSensors';
      granted: boolean;
    }
  | { name: 'notice'; code: string; message: string };

export interface BridgeEnvelope<TType extends string, TPayload> {
  contractVersion: 1;
  type: TType;
  id: string;
  sentAt: string;
  payload: TPayload;
}

export type ResultPayload =
  | { status: 'accepted'; data: unknown }
  | {
      status: 'rejected';
      error: { code: BridgeErrorCode; message: string };
    };

export type WebToNativeEnvelope =
  | BridgeEnvelope<'hello', HelloPayload>
  | BridgeEnvelope<'command', CommandPayload>;

export type NativeToWebEnvelope =
  | BridgeEnvelope<'ready', ReadyPayload>
  | BridgeEnvelope<'snapshot', AppSnapshot>
  | BridgeEnvelope<'result', ResultPayload>
  | BridgeEnvelope<'event', AppEventPayload>;

export type BridgeEnvelopeV1 = WebToNativeEnvelope | NativeToWebEnvelope;
