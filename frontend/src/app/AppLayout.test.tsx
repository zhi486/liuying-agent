import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import type { AppSnapshot } from '../bridge/contract';
import { AppLayout } from './AppLayout';

const session = vi.hoisted(() => ({ state: 'idle' as AppSnapshot['session']['state'] }));
vi.mock('../bridge/bridgeStore', () => ({
  useBridgeStore: (selector: (value: { snapshot: { session: typeof session } }) => unknown) =>
    selector({ snapshot: { session } }),
}));

describe('跨页面运动入口', () => {
  it.each(['running', 'paused', 'preparing'] as const)('%s 时可以返回跑步页', (state) => {
    session.state = state;
    render(
      <MemoryRouter initialEntries={['/agent']}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: /返回跑步页/ })).toHaveAttribute('href', '/run');
  });

  it.each(['idle', 'finished', 'error'] as const)('%s 时不显示活动跑步', (state) => {
    session.state = state;
    render(
      <MemoryRouter initialEntries={['/agent']}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('link', { name: /返回跑步页/ })).not.toBeInTheDocument();
  });

  it('跑步页本身不重复显示返回入口', () => {
    session.state = 'running';
    render(
      <MemoryRouter initialEntries={['/run']}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('link', { name: /返回跑步页/ })).not.toBeInTheDocument();
  });
});
