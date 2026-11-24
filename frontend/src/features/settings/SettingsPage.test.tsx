import { act, fireEvent, render as renderView, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useBridgeStore } from '../../bridge/bridgeStore';
import type { AppSnapshot } from '../../bridge/contract';
import { SettingsPage } from './SettingsPage';

const sendNativeCommandMock = vi.hoisted(() => vi.fn());
const render = (view: ReactElement) => renderView(<MemoryRouter>{view}</MemoryRouter>);

vi.mock('../../bridge/bridgeStore', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../bridge/bridgeStore')>();
  return { ...actual, sendNativeCommand: sendNativeCommandMock };
});

describe('真实本地设置', () => {
  it('已配置但关闭时不会说连接成功，也不会自动开启或试听', async () => {
    const configured = { ...settings, deepseekApiKeyConfigured: true, llmEnabled: false };
    sendNativeCommandMock.mockResolvedValue(accepted(configured));
    useBridgeStore.setState({ status: 'ready', snapshot: { ...snapshot, settings: configured } });
    await act(async () => {
      render(<SettingsPage />);
    });
    expect(screen.getByText('已配置，AI 陪伴关闭')).toBeVisible();
    expect(screen.getByRole('checkbox', { name: /AI 陪伴/ })).not.toBeChecked();
    expect(sendNativeCommandMock).toHaveBeenCalledTimes(1);
    expect(sendNativeCommandMock).toHaveBeenCalledWith('settings.query', {});
  });

  it.each(['running', 'paused'] as const)('在 %s 状态禁更换但可立即关闭 AI', async (state) => {
    const enabled = { ...settings, deepseekApiKeyConfigured: true, llmEnabled: true };
    sendNativeCommandMock
      .mockResolvedValueOnce(accepted(enabled))
      .mockResolvedValue(accepted({ ...enabled, llmEnabled: false }));
    useBridgeStore.setState({
      status: 'ready',
      snapshot: { ...snapshot, settings: enabled, session: { ...snapshot.session, state } },
    });
    await act(async () => {
      render(<SettingsPage />);
    });
    expect(screen.getByText('AI 陪伴已启用，尚未验证连接')).toBeVisible();
    expect(screen.getByRole('button', { name: '更换 DeepSeek 密钥' })).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox', { name: /AI 陪伴/ }));
    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith('settings.update', {
        llmEnabled: false,
      }),
    );
    expect(await screen.findByText('已配置，AI 陪伴关闭')).toBeVisible();
  });

  it('未配置 DeepSeek 时不可开启且配置按钮只发送无参数命令', async () => {
    sendNativeCommandMock.mockResolvedValue(accepted(settings));
    useBridgeStore.setState({
      status: 'ready',
      snapshot: { ...snapshot, session: { ...snapshot.session, state: 'idle' } },
    });
    await act(async () => {
      render(<SettingsPage />);
    });
    expect(screen.getByText('未配置')).toBeVisible();
    expect(screen.getByRole('checkbox', { name: /AI 陪伴/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '配置 DeepSeek 密钥' }));
    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith('settings.configureDeepseek', {}),
    );
    expect(screen.queryByLabelText(/API Key/)).not.toBeInTheDocument();
  });

  beforeEach(() => {
    sendNativeCommandMock.mockReset();
    useBridgeStore.setState({ status: 'unavailable', snapshot: null });
    document.documentElement.dataset.theme = 'night';
  });

  afterEach(() => {
    document.documentElement.dataset.theme = 'night';
  });

  it('允许清空警戒心率恢复自动并保存独立间隔', async () => {
    sendNativeCommandMock.mockResolvedValue(accepted(settings));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');
    fireEvent.change(screen.getByLabelText(/警戒心率/), { target: { value: '170' } });
    fireEvent.change(screen.getByLabelText(/警戒心率/), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText(/高心率重复提醒间隔/), { target: { value: '180' } });
    fireEvent.click(screen.getByRole('button', { name: '保存跑步偏好' }));
    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith(
        'settings.update',
        expect.objectContaining({ hrAlertThresholdBpm: null, hrAlertRepeatIntervalSec: 180 }),
      ),
    );
  });

  it('自动门槛跟随最大心率但手动值保持独立', async () => {
    sendNativeCommandMock.mockResolvedValue(accepted(settings));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');
    expect(screen.getByPlaceholderText('自动（当前 162 bpm）')).toHaveValue('');
    fireEvent.change(screen.getByLabelText(/最大心率/), { target: { value: '200' } });
    expect(screen.getByPlaceholderText('自动（当前 180 bpm）')).toHaveValue('');
    fireEvent.change(screen.getByLabelText(/警戒心率/), { target: { value: '170' } });
    fireEvent.change(screen.getByLabelText(/最大心率/), { target: { value: '190' } });
    expect(screen.getByLabelText(/警戒心率/)).toHaveValue('170');
    fireEvent.click(screen.getByRole('button', { name: '保存跑步偏好' }));
    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith(
        'settings.update',
        expect.objectContaining({
          maxHeartRate: 190,
          hrAlertThresholdBpm: 170,
          hrAlertRepeatIntervalSec: 120,
        }),
      ),
    );
  });

  it.each([
    [/警戒心率/, '79', '警戒心率应为 80-240 之间的整数，或留空使用自动门槛'],
    [/警戒心率/, '241', '警戒心率应为 80-240 之间的整数，或留空使用自动门槛'],
    [/警戒心率/, '162.5', '警戒心率应为 80-240 之间的整数，或留空使用自动门槛'],
    [/高心率重复提醒间隔/, '29', '高心率重复提醒间隔应为 30-300 之间的整数'],
    [/高心率重复提醒间隔/, '301', '高心率重复提醒间隔应为 30-300 之间的整数'],
    [/高心率重复提醒间隔/, '', '高心率重复提醒间隔应为 30-300 之间的整数'],
  ])('拒绝非法心率偏好 %s %s', async (label, value, message) => {
    sendNativeCommandMock.mockResolvedValue(accepted(settings));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
    fireEvent.click(screen.getByRole('button', { name: '保存跑步偏好' }));
    expect(screen.getByText(message)).toBeVisible();
    expect(sendNativeCommandMock).toHaveBeenCalledTimes(1);
  });

  it('浏览器预览不展示或保存虚构偏好', () => {
    render(<SettingsPage />);

    expect(screen.getByText('未连接手机本地设置')).toBeVisible();
    expect(screen.getByText(/不会展示或保存虚构偏好/)).toBeVisible();
    expect(screen.queryByLabelText(/目标配速/)).not.toBeInTheDocument();
    expect(sendNativeCommandMock).not.toHaveBeenCalled();
  });

  it('心率偏好保存失败时保留输入且不宣称已保存', async () => {
    sendNativeCommandMock
      .mockResolvedValueOnce(accepted(settings))
      .mockRejectedValueOnce(new Error('保存本地设置失败'));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');
    fireEvent.change(screen.getByLabelText(/警戒心率/), { target: { value: '170' } });
    fireEvent.click(screen.getByRole('button', { name: '保存跑步偏好' }));
    expect(await screen.findByText('保存本地设置失败')).toBeVisible();
    expect(screen.getByLabelText(/警戒心率/)).toHaveValue('170');
    expect(screen.queryByText('已保存，将在下一次跑步开始时生效')).not.toBeInTheDocument();
  });

  it('Bridge 就绪后查询并呈现持久化设置', async () => {
    sendNativeCommandMock.mockResolvedValue(accepted(settings));
    useBridgeStore.setState({ status: 'ready', snapshot });

    render(<SettingsPage />);

    expect(screen.getByLabelText(/目标配速/)).toHaveValue('6:15');
    expect(screen.getByLabelText(/目标距离/)).toHaveValue('5');
    await waitFor(() => expect(sendNativeCommandMock).toHaveBeenCalledWith('settings.query', {}));
  });

  it('以分组玻璃行呈现五个真实入口，并保留记忆返回来源', async () => {
    sendNativeCommandMock.mockResolvedValue(accepted(settings));
    useBridgeStore.setState({ status: 'ready', snapshot });

    await act(async () => {
      renderView(
        <MemoryRouter initialEntries={['/settings']}>
          <SettingsPage />
          <LocationProbe />
        </MemoryRouter>,
      );
    });

    expect(screen.getByRole('heading', { name: 'Agent 与数据' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Agent 与数据' }).parentElement).toHaveTextContent(
      '本机',
    );
    expect(screen.getByRole('status')).toHaveTextContent('本机已同步');
    expect(screen.getByRole('status').querySelector('svg')).toBeInTheDocument();

    const entries = [
      ['账单与用量', '/settings/usage', '查看模型调用、费用和本机用量记录', '用量'],
      ['聊天授权', '/settings/chat', '管理哪些会话可以发送给在线 Agent', '授权'],
      ['MCP 插件', '/settings/plugins', '连接工具与数据源，管理本机插件授权', '连接'],
      ['长期记忆', '/memories', '管理跑步习惯、表达偏好和已保存事实', '记忆'],
      ['用户须知', '/settings/notices', '查看联网、记忆和语音相关说明', '说明'],
    ] as const;

    for (const [name, path, description, category] of entries) {
      const link = screen.getByRole('link', { name });
      expect(link).toHaveAttribute('href', path);

      const describedBy = link.getAttribute('aria-describedby')?.split(' ') ?? [];
      expect(describedBy.map((id) => document.getElementById(id)?.textContent?.trim())).toEqual([
        description,
        category,
      ]);
    }

    fireEvent.click(screen.getByRole('link', { name: '长期记忆' }));
    expect(screen.getByTestId('location-pathname')).toHaveTextContent('/memories');
    expect(screen.getByTestId('location-state')).toHaveTextContent('{"from":"/settings"}');
  });

  it('切换晨光时写入原生设置并立即更新页面主题', async () => {
    const morningSettings = { ...settings, themePreference: 'morning' as const };
    sendNativeCommandMock
      .mockResolvedValueOnce(accepted(settings))
      .mockResolvedValueOnce(accepted(morningSettings));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');

    fireEvent.click(screen.getByRole('radio', { name: '晨光，清爽浅色' }));

    expect(document.documentElement.dataset.theme).toBe('morning');
    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith('settings.update', {
        themePreference: 'morning',
      }),
    );
    expect(await screen.findByRole('radio', { name: '晨光，清爽浅色' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(await screen.findByText('外观已保存')).toBeVisible();
  });

  it.each([
    ['ArrowRight', 'night', 'morning'],
    ['ArrowDown', 'night', 'morning'],
    ['ArrowLeft', 'morning', 'night'],
    ['ArrowUp', 'morning', 'night'],
    ['Home', 'night', 'morning'],
    ['End', 'morning', 'night'],
  ] as const)('主题单选组支持 %s 并维护 roving tabindex', async (key, initial, next) => {
    const initialSettings = { ...settings, themePreference: initial };
    const nextSettings = { ...settings, themePreference: next };
    sendNativeCommandMock
      .mockResolvedValueOnce(accepted(initialSettings))
      .mockResolvedValueOnce(accepted(nextSettings));
    useBridgeStore.setState({
      status: 'ready',
      snapshot: { ...snapshot, settings: initialSettings, theme: initial },
    });
    document.documentElement.dataset.theme = initial;
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');

    const initialRadio = screen.getByRole('radio', {
      name: initial === 'morning' ? '晨光，清爽浅色' : '夜萤，沉静深色',
    });
    const nextRadio = screen.getByRole('radio', {
      name: next === 'morning' ? '晨光，清爽浅色' : '夜萤，沉静深色',
    });

    expect(initialRadio).toHaveAttribute('tabindex', '0');
    expect(nextRadio).toHaveAttribute('tabindex', '-1');
    initialRadio.focus();
    fireEvent.keyDown(initialRadio, { key });

    expect(document.documentElement.dataset.theme).toBe(next);
    expect(nextRadio).toHaveFocus();
    await waitFor(() => {
      expect(nextRadio).toHaveAttribute('aria-checked', 'true');
      expect(nextRadio).toHaveAttribute('tabindex', '0');
      expect(initialRadio).toHaveAttribute('aria-checked', 'false');
      expect(initialRadio).toHaveAttribute('tabindex', '-1');
    });
    expect(sendNativeCommandMock).toHaveBeenLastCalledWith('settings.update', {
      themePreference: next,
    });
  });

  it('保存主题时标记整组忙碌并锁定全部选项', async () => {
    let resolveUpdate: (result: ReturnType<typeof accepted>) => void = () => {};
    const pendingUpdate = new Promise<ReturnType<typeof accepted>>((resolve) => {
      resolveUpdate = resolve;
    });
    sendNativeCommandMock
      .mockResolvedValueOnce(accepted(settings))
      .mockReturnValueOnce(pendingUpdate);
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');

    fireEvent.click(screen.getByRole('radio', { name: '晨光，清爽浅色' }));

    const group = screen.getByRole('radiogroup', { name: '界面主题' });
    expect(group).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('radio', { name: '晨光，清爽浅色' })).toBeDisabled();
    expect(screen.getByRole('radio', { name: '夜萤，沉静深色' })).toBeDisabled();

    await act(async () => {
      resolveUpdate(accepted({ ...settings, themePreference: 'morning' }));
      await pendingUpdate;
    });

    expect(group).toHaveAttribute('aria-busy', 'false');
    expect(screen.getByRole('radio', { name: '晨光，清爽浅色' })).toBeEnabled();
  });

  it('主题保存失败时回滚页面主题且不宣称已保存', async () => {
    sendNativeCommandMock
      .mockResolvedValueOnce(accepted(settings))
      .mockRejectedValueOnce(new Error('主题保存失败'));
    useBridgeStore.setState({ status: 'ready', snapshot });
    await act(async () => {
      render(<SettingsPage />);
    });
    await screen.findByText('本机已同步');

    fireEvent.click(screen.getByRole('radio', { name: '晨光，清爽浅色' }));

    expect(document.documentElement.dataset.theme).toBe('morning');
    expect(await screen.findByText('主题保存失败')).toBeVisible();
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('night'));
    expect(screen.getByRole('radio', { name: '夜萤，沉静深色' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.queryByText('外观已保存')).not.toBeInTheDocument();
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
    const voiceToggle = screen.getByRole('checkbox', { name: '语音提醒' });
    expect(voiceToggle).toBeChecked();
    expect(voiceToggle.closest('label')).toHaveTextContent('已开启');
    fireEvent.click(voiceToggle);
    expect(voiceToggle).not.toBeChecked();
    expect(voiceToggle.closest('label')).toHaveTextContent('已关闭');
    expect(sendNativeCommandMock).toHaveBeenCalledTimes(1);
    fireEvent.change(screen.getByLabelText('陪跑语气'), { target: { value: 'gentle' } });
    fireEvent.change(screen.getByLabelText('动态效果'), { target: { value: 'reduced' } });
    fireEvent.click(screen.getByRole('button', { name: '保存跑步偏好' }));

    await waitFor(() =>
      expect(sendNativeCommandMock).toHaveBeenLastCalledWith('settings.update', {
        targetPaceSecPerKm: 390,
        targetDistanceKm: 8,
        maxHeartRate: 170,
        hrAlertThresholdBpm: null,
        hrAlertRepeatIntervalSec: 120,
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
  deepseekApiKeyConfigured: false,
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
  dataQuality: { gps: 'waiting', heartRate: 'waiting' },
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

function LocationProbe() {
  const location = useLocation();

  return (
    <>
      <span data-testid="location-pathname">{location.pathname}</span>
      <span data-testid="location-state">{JSON.stringify(location.state)}</span>
    </>
  );
}
