import type { McpCommandBodies } from './mcp';

export type CommandName =
  | 'cloud.speak'
  | 'cloud.stopSpeech'
  | keyof McpCommandBodies
  | 'voice.begin'
  | 'voice.status'
  | 'voice.finish'
  | 'voice.cancel'
  | 'cloud.list'
  | 'cloud.newConversation'
  | 'cloud.selectConversation'
  | 'cloud.renameConversation'
  | 'cloud.archiveConversation'
  | 'cloud.restoreConversation'
  | 'cloud.deleteConversation'
  | 'cloud.retryConversationCleanup'
  | 'cloud.configure'
  | 'cloud.prepare'
  | 'cloud.confirm'
  | 'cloud.send'
  | 'cloud.chatConsent'
  | 'cloud.memoryAuthorization'
  | 'cloud.memoryConsent'
  | 'cloud.discard'
  | 'cloud.recover'
  | 'cloud.cancel'
  | 'cloud.withdraw'
  | 'memory.create'
  | 'memory.list'
  | 'memory.update'
  | 'memory.delete'
  | 'memory.preview'
  | 'memory.history'
  | 'memory.restore'
  | 'memory.document'
  | 'memory.exportBegin'
  | 'memory.exportStatus'
  | 'memory.exportCancel'
  | 'agent.submit'
  | 'agent.list'
  | 'agent.detail'
  | 'agent.cancel'
  | 'device.connect'
  | 'device.disconnect'
  | 'run.start'
  | 'run.pause'
  | 'run.resume'
  | 'run.stop'
  | 'history.query'
  | 'history.detail'
  | 'history.series'
  | 'settings.query'
  | 'settings.configureDeepseek'
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
  dataQuality: {
    gps: 'waiting' | 'fresh' | 'stale';
    heartRate: 'not_available' | 'waiting' | 'fresh' | 'stale';
  };
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
    deepseekApiKeyConfigured: boolean;
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

export interface HistoryDetailResult {
  commandId: string;
  record: HistoryRunRecord;
  splits: {
    index: number;
    endDistanceMeters: number;
    durationSeconds: number;
    averagePaceSecPerKm: number;
    averageHeartRate: number | null;
  }[];
}

export interface RunMetricSample {
  startElapsedSeconds: number;
  endElapsedSeconds: number;
  segmentId: number;
  averagePaceSecPerKm: number | null;
  averageHeartRate: number | null;
  paceValidSeconds: number;
  heartRateValidSeconds: number;
}

export interface HistorySeriesResult {
  commandId: string;
  sessionId: string;
  offset: number;
  totalItems: number;
  samples: RunMetricSample[];
}

export interface HelloPayload {
  buildId: string;
  supportedContractVersions: [1];
}

export interface ReadyPayload {
  helloId: string;
  buildId: string;
  capabilities: [
    'snapshot.v1',
    'history.pagination',
    'settings.v1',
    'heart-rate-alerts.v1',
    'data-quality.v1',
    'deepseek-companion.v1',
    'agent.tasks.v1',
    'memory.v1',
    'agent.cloud.tasks.v1',
  ];
  snapshot: AppSnapshot;
}

export interface CommandBodyMap extends McpCommandBodies {
  'cloud.speak': { requestId: string };
  'cloud.stopSpeech': Record<string, never>;
  'voice.begin': { sessionId: string; approved: true };
  'voice.status': { sessionId: string };
  'voice.finish': { sessionId: string };
  'voice.cancel': { sessionId: string };
  'cloud.list': Record<string, never>;
  'cloud.newConversation': Record<string, never>;
  'cloud.selectConversation': { conversationId: string | null };
  'cloud.renameConversation': { conversationId: string; title: string };
  'cloud.archiveConversation': { conversationId: string | null };
  'cloud.restoreConversation': { conversationId: string | null };
  'cloud.deleteConversation': { conversationId: string | null; confirmed: true };
  'cloud.retryConversationCleanup': Record<string, never>;
  'cloud.configure': Record<string, never>;
  'cloud.prepare': {
    prompt: string;
    conversationId?: string;
    mcpServices?: string[];
    memoryRefs: { id: string; version: number }[];
    knowledge?: {
      version: 'curated_knowledge_v1';
      library: 'health' | 'firefly' | 'both';
      allowSpoilers: boolean;
    };
  };
  'cloud.confirm': { token: string; approved: true };
  'cloud.send': { token: string };
  'cloud.chatConsent': { identityToken: string; enabled: boolean };
  'cloud.memoryAuthorization': Record<string, never>;
  'cloud.memoryConsent': { identityToken: string; enabled: boolean; factsAllowed?: boolean };
  'cloud.discard': { token: string };
  'cloud.recover': { requestId: string };
  'cloud.cancel': { requestId: string };
  'cloud.withdraw': { requestId: string };
  'memory.create': { memoryId: string; content: string };
  'memory.list': { offset: number; limit: number };
  'memory.document': { offset: number; limit: number };
  'memory.exportBegin': { exportId: string; confirmed: true };
  'memory.exportStatus': { exportId: string };
  'memory.exportCancel': { exportId: string };
  'memory.update': { memoryId: string; version: number; content: string };
  'memory.delete': { memoryId: string; version: number; confirmed: true };
  'memory.preview': { memoryRefs: { id: string; version: number }[] };
  'memory.history': { memoryId: string; offset: number; limit: number };
  'memory.restore': { memoryId: string; version: number; targetVersion: number; confirmed: true };
  'agent.submit': { clientRequestId: string; prompt: string };
  'agent.list': { offset: number; limit: number };
  'agent.detail': { taskId: string };
  'agent.cancel': { taskId: string };
  'device.connect': Record<string, never>;
  'device.disconnect': Record<string, never>;
  'run.start': Record<string, never>;
  'run.pause': Record<string, never>;
  'run.resume': Record<string, never>;
  'run.stop': Record<string, never>;
  'history.query': { page: number; pageSize: number };
  'history.detail': { sessionId: string };
  'history.series': { sessionId: string; offset: number; limit: number };
  'settings.query': Record<string, never>;
  'settings.configureDeepseek': Record<string, never>;
  'settings.update': SettingsUpdateBody;
  'permission.request': { kind: 'runningSensors' };
}

export type CommandPayload<T extends CommandName = CommandName> = T extends CommandName
  ? { helloId: string; name: T; body: CommandBodyMap[T] }
  : never;

export type AppEventPayload =
  | { name: 'cloud.changed' }
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
