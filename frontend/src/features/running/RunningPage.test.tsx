import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';

import { useBridgeStore } from '../../bridge/bridgeStore';
import * as bridgeStore from '../../bridge/bridgeStore';
import type { AppSnapshot, ResultPayload } from '../../bridge/contract';
import { formatPace } from './formatters';
import { RunningPage } from './RunningPage';

const baseSnapshot: AppSnapshot = {
  dataQuality: { gps: 'waiting', heartRate: 'waiting' },
  revision: 1,
  generatedAt: '2026-09-01T12:00:00Z',
  connection: {
    state: 'disconnected',
    deviceName: '手机 GPS',
    batteryPercent: null,
  },
  wearable: {
    supported: true,
    state: 'disconnected',
    deviceName: 'Keep B5',
    batteryPercent: null,
  },
  session: {
    id: null,
    state: 'idle',
    startedAt: null,
    targetPaceSecPerKm: null,
    targetDistanceMeters: null,
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
  latestCoachMessage: null,
  settings: {
    targetPaceSecPerKm: 360,
    targetDistanceKm: 5,
    maxHeartRate: 190,
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
    themePreference: 'system',
    motionPreference: 'balanced',
  },
  permissions: {
    bluetooth: 'granted',
    bodySensors: 'granted',
    runningSensors: 'granted',
  },
  theme: 'night',
  motionPolicy: 'balanced',
  safetyState: {
    level: 'normal',
    message: null,
  },
  completedRun: null,
  persistenceState: 'idle',
  healthSync: {
    state: 'idle',
    routeWritten: null,
  },
};

function createSnapshot(overrides: Partial<AppSnapshot> = {}): AppSnapshot {
  return { ...baseSnapshot, ...overrides };
}

function renderRunning(snapshot?: AppSnapshot) {
  useBridgeStore.setState(
    snapshot ? { status: 'ready', snapshot } : { status: 'unavailable', snapshot: null },
  );
  return render(
    <MemoryRouter>
      <RunningPage />
    </MemoryRouter>,
  );
}

describe('跑步页真实数据', () => {
  it('定位与心率同时停更仍显示手环已连接且保留安全提示', () => {
    renderRunning(
      createSnapshot({
        session: { ...baseSnapshot.session, state: 'running' },
        wearable: { ...baseSnapshot.wearable, state: 'connected' },
        dataQuality: { gps: 'stale', heartRate: 'stale' },
        metrics: { ...baseSnapshot.metrics, heartRate: 190, hrZone: 'z5' },
        safetyState: { level: 'warning', message: '请留意身体感受' },
      }),
    );
    expect(screen.getByText('定位暂未更新')).toBeVisible();
    expect(screen.getByText('心率暂未更新')).toBeVisible();
    expect(screen.getByText('Keep B5 已连接')).toBeVisible();
    expect(screen.getByText('请留意身体感受')).toBeVisible();
    expect(screen.queryByRole('meter')).not.toBeInTheDocument();
  });

  it('开跑尚无样本显示等待而不是中断', () => {
    renderRunning(
      createSnapshot({
        session: { ...baseSnapshot.session, state: 'running' },
        dataQuality: { gps: 'waiting', heartRate: 'waiting' },
      }),
    );
    expect(screen.getByText('等待定位')).toBeVisible();
    expect(screen.getByText('等待心率')).toBeVisible();
    expect(screen.queryByText('定位暂未更新')).not.toBeInTheDocument();
  });

  beforeEach(() => {
    vi.restoreAllMocks();
    useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  });

  it('开跑前只派发手环连接且等待期间防重复点击，不虚报连接成功', async () => {
    let finish!: (result: ResultPayload) => void;
    const pending = new Promise<ResultPayload>((resolve) => {
      finish = resolve;
    });
    const send = vi.spyOn(bridgeStore, 'sendNativeCommand').mockReturnValueOnce(pending);
    renderRunning(
      createSnapshot({ permissions: { ...baseSnapshot.permissions, runningSensors: 'denied' } }),
    );
    fireEvent.click(screen.getByRole('button', { name: '连接手环' }));
    expect(screen.getByRole('button', { name: '正在连接手环…' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '正在连接手环…' }));
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith('device.connect', {});
    finish({ status: 'accepted', data: null });
    await waitFor(() => expect(screen.getByRole('button', { name: '连接手环' })).toBeEnabled());
    expect(screen.queryByRole('button', { name: '手环已连接' })).not.toBeInTheDocument();
    expect(screen.getByText('Keep B5 未连接，可仅用手机 GPS')).toBeVisible();
  });

  it.each([
    ['connecting', '正在连接手环…'],
    ['connected', '手环已连接'],
  ] as const)('手环状态为 %s 时不能重复连接但仍可开跑', (state, label) => {
    renderRunning(createSnapshot({ wearable: { ...baseSnapshot.wearable, state } }));
    expect(screen.getByRole('button', { name: label })).toBeDisabled();
    expect(screen.getByRole('button', { name: '开始跑步' })).toBeEnabled();
  });

  it('手环错误时提供重试且展示连接请求失败', async () => {
    vi.spyOn(bridgeStore, 'sendNativeCommand').mockRejectedValueOnce(new Error('测试通信失败'));
    renderRunning(createSnapshot({ wearable: { ...baseSnapshot.wearable, state: 'error' } }));
    fireEvent.click(screen.getByRole('button', { name: '重试连接手环' }));
    expect(await screen.findByText('连接手环请求失败，请重试')).toBeVisible();
    expect(screen.getByRole('button', { name: '重试连接手环' })).toBeEnabled();
  });

  it('缺少蓝牙权限时先请求系统授权而不发送连接命令', async () => {
    const send = vi.spyOn(bridgeStore, 'sendNativeCommand').mockResolvedValueOnce({
      status: 'accepted',
      data: null,
    });
    renderRunning(
      createSnapshot({ permissions: { ...baseSnapshot.permissions, bluetooth: 'denied' } }),
    );
    fireEvent.click(screen.getByRole('button', { name: '连接手环' }));
    await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
    expect(send).toHaveBeenCalledWith('permission.request', { kind: 'runningSensors' });
    expect(await screen.findByText(/授权请求已发送/)).toBeVisible();
  });

  it('不支持手环时不展示连接按钮', () => {
    renderRunning(createSnapshot({ wearable: { ...baseSnapshot.wearable, supported: false } }));
    expect(screen.queryByRole('button', { name: '连接手环' })).not.toBeInTheDocument();
  });

  it('浏览器模式明确标记交互预览且不伪造设备数据', () => {
    renderRunning();

    expect(screen.getByText('交互预览，不代表设备数据')).toBeVisible();
    expect(screen.getByText('交互预览不提供设备目标。')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '进入交互预览' }));

    expect(screen.getByText('跑步交互预览')).toBeVisible();
    expect(screen.queryByText('6:02')).not.toBeInTheDocument();
    expect(screen.getAllByText('--').length).toBeGreaterThan(2);
  });

  it('结束确认框锁定焦点，支持 Escape 关闭并恢复触发按钮焦点', () => {
    renderRunning();
    fireEvent.click(screen.getByRole('button', { name: '进入交互预览' }));

    const stopTrigger = screen.getByRole('button', { name: '结束' });
    stopTrigger.focus();
    fireEvent.click(stopTrigger);

    const dialog = screen.getByRole('alertdialog');
    const continueButton = screen.getByRole('button', { name: '继续跑' });
    const confirmButton = screen.getByRole('button', { name: '确认结束' });
    expect(continueButton).toHaveFocus();
    expect(dialog.parentElement?.previousElementSibling).toHaveAttribute('inert');

    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(confirmButton).toHaveFocus();
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(continueButton).toHaveFocus();

    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(stopTrigger).toHaveFocus();
  });

  it('跑步阶段切换后回到页面顶部', async () => {
    renderRunning();
    fireEvent.click(screen.getByRole('button', { name: '进入交互预览' }));
    document.documentElement.scrollTop = 206;

    fireEvent.click(screen.getByRole('button', { name: '结束' }));
    fireEvent.click(screen.getByRole('button', { name: '确认结束' }));

    await waitFor(() => expect(document.documentElement.scrollTop).toBe(0));
  });

  it('权限未授权时不写设备就绪，并给出可执行的系统授权路径', async () => {
    renderRunning(
      createSnapshot({
        permissions: {
          bluetooth: 'denied',
          bodySensors: 'notRequested',
          runningSensors: 'denied',
        },
      }),
    );

    expect(screen.getByText('开跑前需要系统授权')).toBeVisible();
    expect(screen.getByText('等待位置信息授权')).toBeVisible();
    expect(screen.queryByText('设备已就绪')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '请求系统授权' }));

    await waitFor(() => {
      expect(screen.getByText(/系统设置 > 应用 > 流萤 > 权限/)).toBeVisible();
    });
  });

  it('分别展示 GPS 主源和未连接的 Keep B5', () => {
    renderRunning(
      createSnapshot({
        dataQuality: { gps: 'fresh', heartRate: 'waiting' },
        connection: {
          state: 'connected',
          deviceName: '手机 GPS',
          batteryPercent: null,
        },
        wearable: {
          supported: true,
          state: 'disconnected',
          deviceName: 'Keep B5',
          batteryPercent: null,
        },
        session: {
          id: 'run-1',
          state: 'running',
          startedAt: '2026-09-01T11:50:00Z',
          targetPaceSecPerKm: 330,
          targetDistanceMeters: 10_000,
        },
      }),
    );

    expect(screen.getByText('手机 GPS 已连接')).toBeVisible();
    expect(screen.getByText('Keep B5 未连接，可仅用手机 GPS')).toBeVisible();
  });

  it('展示 Keep B5 真实名称与电量，不覆盖 GPS 主源', () => {
    renderRunning(
      createSnapshot({
        connection: {
          state: 'connected',
          deviceName: '手机 GPS',
          batteryPercent: null,
        },
        wearable: {
          supported: true,
          state: 'connected',
          deviceName: 'Keep B5 2A19',
          batteryPercent: 68,
        },
      }),
    );

    expect(screen.queryByText('开跑时启用手机 GPS')).not.toBeInTheDocument();
    expect(screen.getByText('手机 GPS 已连接')).toBeVisible();
    expect(screen.getByText('Keep B5 2A19 已连接，电量 68%')).toBeVisible();
  });

  it('没有心率时隐藏强度条并显示暂无提醒', () => {
    renderRunning(
      createSnapshot({
        session: {
          id: 'run-2',
          state: 'running',
          startedAt: '2026-09-01T11:50:00Z',
          targetPaceSecPerKm: null,
          targetDistanceMeters: null,
        },
        metrics: {
          ...baseSnapshot.metrics,
          paceSecPerKm: 365,
          distanceMeters: 820,
          elapsedSeconds: 300,
        },
      }),
    );

    expect(screen.queryByRole('meter')).not.toBeInTheDocument();
    expect(screen.getByText('暂无提醒')).toBeVisible();
  });

  it('原样展示原生 safetyState 警告', () => {
    renderRunning(
      createSnapshot({
        session: {
          id: 'run-safety',
          state: 'running',
          startedAt: '2026-09-01T11:50:00Z',
          targetPaceSecPerKm: null,
          targetDistanceMeters: null,
        },
        safetyState: {
          level: 'warning',
          message: 'Keep B5 已断开，GPS 跑步仍在继续',
        },
      }),
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Keep B5 已断开，GPS 跑步仍在继续');
  });

  it('跑步中只展示 session 冻结目标，不读取已变化的 settings 目标', () => {
    renderRunning(
      createSnapshot({
        session: {
          id: 'run-3',
          state: 'running',
          startedAt: '2026-09-01T11:50:00Z',
          targetPaceSecPerKm: 330,
          targetDistanceMeters: 10_000,
        },
        settings: {
          ...baseSnapshot.settings,
          targetPaceSecPerKm: 360,
          targetDistanceKm: 5,
        },
      }),
    );

    expect(screen.getByText('配速 5:30')).toBeVisible();
    expect(screen.getByText('距离 10 km')).toBeVisible();
    expect(screen.queryByText('配速 6:00')).not.toBeInTheDocument();
  });

  it('有真实心率时按最大心率计算强度', () => {
    renderRunning(
      createSnapshot({
        session: {
          id: 'run-4',
          state: 'running',
          startedAt: '2026-09-01T11:50:00Z',
          targetPaceSecPerKm: null,
          targetDistanceMeters: null,
        },
        metrics: {
          ...baseSnapshot.metrics,
          heartRate: 152,
          hrZone: 'z3',
        },
        dataQuality: { gps: 'waiting', heartRate: 'fresh' },
        settings: {
          ...baseSnapshot.settings,
          maxHeartRate: 190,
        },
      }),
    );

    expect(screen.getByRole('meter', { name: '当前强度 80%' })).toHaveAttribute(
      'aria-valuenow',
      '80',
    );
    expect(screen.getByText('有氧进阶区')).toBeVisible();
  });

  it('完成页只使用 completedRun，并在保存成功后开放历史入口', () => {
    renderRunning(
      createSnapshot({
        session: {
          id: 'run-5',
          state: 'finished',
          startedAt: '2026-09-01T11:30:00Z',
          targetPaceSecPerKm: 330,
          targetDistanceMeters: 10_000,
        },
        metrics: {
          ...baseSnapshot.metrics,
          heartRate: 199,
          distanceMeters: 99_999,
          elapsedSeconds: 1,
        },
        completedRun: {
          endedAt: '2026-09-01T12:00:00Z',
          distanceMeters: 5_020,
          durationSeconds: 1_818,
          averagePaceSecPerKm: 362,
          averageHeartRate: 137,
          averageCadence: 169,
        },
        persistenceState: 'saved',
        healthSync: {
          state: 'synced',
          routeWritten: true,
        },
      }),
    );

    expect(screen.getByText('5.02')).toBeVisible();
    expect(screen.getByText('30:18')).toBeVisible();
    expect(screen.getByText('6:02')).toBeVisible();
    expect(screen.getByText('137 bpm')).toBeVisible();
    expect(screen.getByText('169 spm')).toBeVisible();
    expect(screen.getByText('已同步，包含路线')).toBeVisible();
    expect(screen.getByRole('link', { name: '查看记录' })).toHaveAttribute('href', '/history');
    expect(screen.queryByText('99.99')).not.toBeInTheDocument();
  });

  it.each([
    ['idle', '等待记录保存'],
    ['saving', '正在保存记录'],
    ['failed', '记录保存失败'],
  ] as const)('记录状态为 %s 时不开放历史入口', (persistenceState, actionText) => {
    renderRunning(
      createSnapshot({
        session: {
          id: 'run-6',
          state: 'finished',
          startedAt: '2026-09-01T11:30:00Z',
          targetPaceSecPerKm: null,
          targetDistanceMeters: null,
        },
        completedRun: {
          endedAt: '2026-09-01T12:00:00Z',
          distanceMeters: 1_000,
          durationSeconds: 360,
          averagePaceSecPerKm: null,
          averageHeartRate: null,
          averageCadence: null,
        },
        persistenceState,
      }),
    );

    expect(screen.queryByRole('link', { name: '查看记录' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: actionText })).toBeDisabled();
  });

  it.each([
    ['idle', null, '尚未同步'],
    ['syncing', null, '正在同步'],
    ['synced', false, '已同步，本次未写入路线'],
    ['permissionRequired', null, '需要 Health Connect 授权'],
    ['providerUpdateRequired', null, '需要更新 Health Connect'],
    ['unavailable', null, '当前设备不可用'],
    ['failed', null, '同步失败'],
  ] as const)('展示 Health Connect 状态 %s', (state, routeWritten, expected) => {
    renderRunning(
      createSnapshot({
        session: {
          id: 'run-7',
          state: 'finished',
          startedAt: '2026-09-01T11:30:00Z',
          targetPaceSecPerKm: null,
          targetDistanceMeters: null,
        },
        completedRun: null,
        persistenceState: 'saved',
        healthSync: { state, routeWritten },
      }),
    );

    expect(screen.getByText(expected)).toBeVisible();
  });
});

describe('配速格式化', () => {
  it('将 5:59.6 正确进位为 6:00，不产生 5:60', () => {
    expect(formatPace(359.6)).toBe('6:00');
    expect(formatPace(359.4)).toBe('5:59');
  });

  it('无有效配速时显示占位值', () => {
    expect(formatPace(null)).toBe('--');
    expect(formatPace(0)).toBe('--');
  });
});
