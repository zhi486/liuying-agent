import { describe, expect, it, vi } from 'vitest';

import type { AppSnapshot, NativeToWebEnvelope } from './contract';
import { NativeBridgeClient, type NativeBridgePort } from './NativeBridgeClient';
import { bridgeEnvelopeSchema, webToNativeEnvelopeSchema } from './schemas';

describe('原生 Bridge 客户端', () => {
  it('握手后接收快照并使用 ready 的 helloId 发送命令', async () => {
    const posted: string[] = [];
    const port: NativeBridgePort = {
      postMessage: (message) => posted.push(message),
      onmessage: null,
    };
    const onReady = vi.fn();
    const onSnapshot = vi.fn();
    const client = new NativeBridgeClient(port, {
      onReady,
      onSnapshot,
      onError: vi.fn(),
    });

    client.start();
    const hello = webToNativeEnvelopeSchema.parse(JSON.parse(posted[0]));
    expect(hello.type).toBe('hello');

    const snapshot = createSnapshot(1);
    emit(port, {
      contractVersion: 1,
      type: 'ready',
      id: crypto.randomUUID(),
      sentAt: new Date().toISOString(),
      payload: {
        helloId: hello.id,
        buildId: 'native-test',
        capabilities: [
          'snapshot.v1',
          'history.pagination',
          'settings.v1',
          'heart-rate-alerts.v1',
          'data-quality.v1',
          'deepseek-companion.v1',
        ],
        snapshot,
      },
    });
    expect(onReady).toHaveBeenCalledWith(snapshot);

    const commandPromise = client.sendCommand('run.start', {});
    const command = webToNativeEnvelopeSchema.parse(JSON.parse(posted[1]));
    expect(command.type).toBe('command');
    if (command.type !== 'command') throw new Error('预期发送 command');
    expect(command.payload.helloId).toBe(hello.id);

    emit(port, {
      contractVersion: 1,
      type: 'result',
      id: command.id,
      sentAt: new Date().toISOString(),
      payload: { status: 'accepted', data: { commandId: command.id } },
    });
    await expect(commandPromise).resolves.toMatchObject({ status: 'accepted' });

    emit(port, {
      contractVersion: 1,
      type: 'snapshot',
      id: crypto.randomUUID(),
      sentAt: new Date().toISOString(),
      payload: createSnapshot(2),
    });
    expect(onSnapshot).toHaveBeenCalledWith(expect.objectContaining({ revision: 2 }));

    client.dispose();
  });

  it('严格按结果 envelope id 关联乱序的 accepted 与 rejected', async () => {
    const { client, port, posted } = prepareReadyClient();
    const firstPromise = client.sendCommand('run.start', {});
    const secondPromise = client.sendCommand('history.query', { page: 1, pageSize: 10 });
    const firstCommand = parseCommand(posted[1]);
    const secondCommand = parseCommand(posted[2]);

    emit(port, {
      contractVersion: 1,
      type: 'result',
      id: secondCommand.id,
      sentAt: new Date().toISOString(),
      payload: {
        status: 'rejected',
        error: { code: 'RESOURCE_UNAVAILABLE', message: '记录暂不可用' },
      },
    });

    await expect(secondPromise).resolves.toEqual({
      status: 'rejected',
      error: { code: 'RESOURCE_UNAVAILABLE', message: '记录暂不可用' },
    });

    let firstSettled = false;
    void firstPromise.finally(() => {
      firstSettled = true;
    });
    await Promise.resolve();
    expect(firstSettled).toBe(false);

    emit(port, {
      contractVersion: 1,
      type: 'result',
      id: firstCommand.id,
      sentAt: new Date().toISOString(),
      payload: { status: 'accepted', data: { commandId: firstCommand.id } },
    });
    await expect(firstPromise).resolves.toMatchObject({ status: 'accepted' });

    client.dispose();
  });

  it('命令等待 10 秒后超时并清理定时器', async () => {
    vi.useFakeTimers();
    const { client } = prepareReadyClient();

    try {
      const commandPromise = client.sendCommand('run.start', {});
      const rejection = expect(commandPromise).rejects.toThrow('原生命令响应超时');

      await vi.advanceTimersByTimeAsync(10_000);

      await rejection;
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      client.dispose();
      vi.useRealTimers();
    }
  });

  it('dispose 会拒绝所有待处理命令并清理超时任务', async () => {
    vi.useFakeTimers();
    const { client } = prepareReadyClient();

    try {
      const firstRejection = expect(client.sendCommand('run.start', {})).rejects.toThrow(
        '原生 Bridge 已关闭',
      );
      const secondRejection = expect(
        client.sendCommand('history.query', { page: 1, pageSize: 10 }),
      ).rejects.toThrow('原生 Bridge 已关闭');

      client.dispose();

      await Promise.all([firstRejection, secondRejection]);
      expect(vi.getTimerCount()).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });
});

function prepareReadyClient() {
  const posted: string[] = [];
  const port: NativeBridgePort = {
    postMessage: (message) => posted.push(message),
    onmessage: null,
  };
  const client = new NativeBridgeClient(port, {
    onReady: vi.fn(),
    onSnapshot: vi.fn(),
    onError: vi.fn(),
  });
  client.start();
  const hello = webToNativeEnvelopeSchema.parse(JSON.parse(posted[0]));
  if (hello.type !== 'hello') throw new Error('预期发送 hello');
  emit(port, {
    contractVersion: 1,
    type: 'ready',
    id: crypto.randomUUID(),
    sentAt: new Date().toISOString(),
    payload: {
      helloId: hello.id,
      buildId: 'native-test',
      capabilities: [
        'snapshot.v1',
        'history.pagination',
        'settings.v1',
        'heart-rate-alerts.v1',
        'data-quality.v1',
        'deepseek-companion.v1',
      ],
      snapshot: createSnapshot(1),
    },
  });
  return { client, port, posted };
}

function parseCommand(raw: string) {
  const command = webToNativeEnvelopeSchema.parse(JSON.parse(raw));
  if (command.type !== 'command') throw new Error('预期发送 command');
  return command;
}

function emit(port: NativeBridgePort, envelope: NativeToWebEnvelope) {
  const validEnvelope = bridgeEnvelopeSchema.parse(envelope);
  port.onmessage?.({ data: JSON.stringify(validEnvelope) });
}

function createSnapshot(revision: number): AppSnapshot {
  return {
    revision,
    generatedAt: new Date().toISOString(),
    connection: { state: 'connected', deviceName: '手机 GPS', batteryPercent: null },
    wearable: {
      supported: true,
      state: 'disconnected',
      deviceName: null,
      batteryPercent: null,
    },
    session: {
      id: null,
      state: 'idle',
      startedAt: null,
      targetPaceSecPerKm: 360,
      targetDistanceMeters: 5000,
    },
    metrics: {
      heartRate: null,
      paceSecPerKm: null,
      cadence: null,
      strideLengthCm: null,
      distanceMeters: 0,
      elapsedSeconds: 0,
      hrZone: null,
      isDataFresh: null,
    },
    completedRun: null,
    persistenceState: 'idle',
    healthSync: { state: 'idle', routeWritten: null },
    latestCoachMessage: null,
    settings: {
      targetPaceSecPerKm: 360,
      targetDistanceKm: 5,
      maxHeartRate: 180,
      hrAlertThresholdBpm: null,
      hrAlertRepeatIntervalSec: 120,
      ttsEnabled: true,
      ttsMinIntervalSec: 4,
      llmEnabled: false,
      mockScenario: 'EASY_RUN',
      coachTone: 'balanced',
      fishReferenceId: '',
      fishApiKeyConfigured: false,
      llmApiKeyConfigured: false,
      deepseekApiKeyConfigured: false,
      themePreference: 'system',
      motionPreference: 'system',
    },
    permissions: {
      bluetooth: 'granted',
      bodySensors: 'granted',
      runningSensors: 'granted',
    },
    theme: 'night',
    motionPolicy: 'balanced',
    safetyState: { level: 'normal', message: null },
    dataQuality: { gps: 'waiting', heartRate: 'waiting' },
  };
}
