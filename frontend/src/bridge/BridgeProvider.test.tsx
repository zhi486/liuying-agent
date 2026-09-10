import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { AppSnapshot } from './contract';
import { BridgeProvider } from './BridgeProvider';
import { useBridgeStore } from './bridgeStore';

const snapshot = {
  theme: 'morning',
  motionPolicy: 'reduced',
} as AppSnapshot;

describe('BridgeProvider 偏好缓存', () => {
  beforeEach(() => {
    useBridgeStore.setState({ status: 'ready', snapshot });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    useBridgeStore.setState({ status: 'unavailable', snapshot: null });
  });

  it('localStorage 为 null 时仍应用原生主题并渲染界面', () => {
    vi.spyOn(window, 'localStorage', 'get').mockReturnValue(null as unknown as Storage);

    expect(() =>
      render(
        <BridgeProvider>
          <p>流萤已启动</p>
        </BridgeProvider>,
      ),
    ).not.toThrow();

    expect(screen.getByText('流萤已启动')).toBeVisible();
    expect(document.documentElement.dataset.theme).toBe('morning');
    expect(document.documentElement.dataset.motion).toBe('reduced');
  });

  it('localStorage 写入异常时安全降级', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('存储不可用', 'SecurityError');
    });

    expect(() =>
      render(
        <BridgeProvider>
          <p>流萤已启动</p>
        </BridgeProvider>,
      ),
    ).not.toThrow();

    expect(screen.getByText('流萤已启动')).toBeVisible();
  });
});
