import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useBridgeStore } from '../../bridge/bridgeStore';
import type { AppSnapshot } from '../../bridge/contract';
import { SettingsPage } from './SettingsPage';

const sendNativeCommandMock = vi.hoisted(() => vi.fn());

vi.mock('../../bridge/bridgeStore', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../bridge/bridgeStore')>();
  return { ...actual, sendNativeCommand: sendNativeCommandMock };
});

describe('真实本地设置', () => {
  beforeEach(() => {
    sendNativeCommandMock.mockReset();
    useBridgeStore.setState({ status: 'unavailable', snapshot: null });
    document.documentElement.dataset.theme = 'night';
  });

  afterEach(() => {
    document.documentElement.dataset.theme = 'night';
  });

  it('浏览器预览不展示或保存虚构偏好', () => {
    render(<SettingsPage />);

    expect(screen.getByText('未连接手机本地设置')).toBeVisible();
    expect(screen.getByText(/不会展示或保存虚构偏好/)).toBeVisible();
    expect(screen.queryByLabelText(/目标配速/)).not.toBeInTheDocument();
    expect(sendNativeCommandMock).not.toHaveBeenCalled();
  });

  it('Bridge 就绪后查询并呈现持久化设置', async () => {
    sendNativeCommandMock.mockResolvedValue(accepted(settings));
    useBridgeStore.setState({ status: 'ready', snapshot });

    render(<SettingsPage />);

    expect(screen.getByLabelText(/目标配速/)).toHaveValue('6:15');
    expect(screen.getByLabelText(/目标距离/)).toHaveValue('5');
    await waitFor(() => expect(sendNativeCommandMock).toHaveBeenCalledWith('settings.query', {}));
  });

  it('切换晨光时写入原生设置并立即更新页面主题', async () => {
    sendNativeCommandMock
      .mockResolvedValueOnce(accepted(settings))
      .mockResolvedValueOnce(accepted({ ...settings, themePreference: 'morning' }));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');

    fireEvent.click(screen.getByRole('button', { name: /晨光/ }));

    expect(document.documentElement.dataset.theme).toBe('morning');
    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith('settings.update', {
        themePreference: 'morning',
      }),
    );
    expect(await screen.findByText('外观已保存')).toBeVisible();
  });

  it('校验后保存跑步配置，并明确下一次开跑生效', async () => {
    const updatedSettings = {
      ...settings,
      targetPaceSecPerKm: 390,
      targetDistanceKm: 8,
      maxHeartRate: 170,
      ttsEnabled: false,
      coachTone: 'gentle' as const,
      motionPreference: 'reduced' as const,
    };
    sendNativeCommandMock
      .mockResolvedValueOnce(accepted(settings))
      .mockResolvedValueOnce(accepted(updatedSettings));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');

    fireEvent.change(screen.getByLabelText(/目标配速/), { target: { value: '6:30' } });
    fireEvent.change(screen.getByLabelText(/目标距离/), { target: { value: '8' } });
    fireEvent.change(screen.getByLabelText(/最大心率/), { target: { value: '170' } });
    fireEvent.click(screen.getByRole('checkbox', { name: /语音提醒/ }));
    fireEvent.change(screen.getByLabelText('陪跑语气'), { target: { value: 'gentle' } });
    fireEvent.change(screen.getByLabelText('动态效果'), { target: { value: 'reduced' } });
    fireEvent.click(screen.getByRole('button', { name: '保存跑步偏好' }));

    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith('settings.update', {
        targetPaceSecPerKm: 390,
        targetDistanceKm: 8,
        maxHeartRate: 170,
        ttsEnabled: false,
        ttsMinIntervalSec: 30,
        coachTone: 'gentle',
        motionPreference: 'reduced',
      }),
    );
    expect(await screen.findByText('已保存，将在下一次跑步开始时生效')).toBeVisible();
  });

  it('拒绝不合理的心率且不发送更新命令', async () => {
    sendNativeCommandMock.mockResolvedValue(accepted(settings));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');

    fireEvent.change(screen.getByLabelText(/最大心率/), { target: { value: '300' } });
    fireEvent.click(screen.getByRole('button', { name: '保存跑步偏好' }));

    expect(screen.getByText('最大心率应为 80-240 之间的整数')).toBeVisible();
    expect(sendNativeCommandMock).toHaveBeenCalledTimes(1);
  });
});

const settings: AppSnapshot['settings'] = {
  targetPaceSecPerKm: 375,
  targetDistanceKm: 5,
  maxHeartRate: 180,
  ttsEnabled: true,
  ttsMinIntervalSec: 30,
  llmEnabled: false,
  mockScenario: 'EASY_RUN',
  coachTone: 'balanced',
  fishReferenceId: 'default',
  fishApiKeyConfigured: false,
  llmApiKeyConfigured: false,
  themePreference: 'night',
  motionPreference: 'system',
};

const snapshot: AppSnapshot = {
  revision: 1,
  generatedAt: '2026-09-01T12:00:00Z',
  connection: { state: 'disconnected', deviceName: null, batteryPercent: null },
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
    targetPaceSecPerKm: 375,
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
  settings,
  permissions: {
    bluetooth: 'notRequested',
    bodySensors: 'notRequested',
    runningSensors: 'notRequested',
  },
  theme: 'night',
  motionPolicy: 'balanced',
  safetyState: { level: 'normal', message: null },
};

function accepted(value: AppSnapshot['settings']) {
  return {
    status: 'accepted' as const,
    data: {
      commandId: '06b8f5e1-6688-4494-abba-c50ba6baa0af',
      settings: value,
    },
  };
}
