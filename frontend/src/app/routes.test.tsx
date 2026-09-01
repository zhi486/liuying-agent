import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { AppRoutes } from './routes';

function TestRouter({ initialEntry }: { initialEntry: string }) {
  return (
    <MemoryRouter initialEntries={[initialEntry]}>
      <AppRoutes />
    </MemoryRouter>
  );
}

describe('应用路由', () => {
  it.each([
    ['/run', '跑步'],
    ['/history', '跑步记录'],
    ['/settings', '设置'],
  ])('渲染 %s', async (path, heading) => {
    render(<TestRouter initialEntry={path} />);

    expect(await screen.findByRole('heading', { name: heading })).toBeVisible();
  });

  it('未知路径回到跑步页', async () => {
    render(<TestRouter initialEntry="/unknown" />);

    expect(await screen.findByRole('heading', { name: '跑步' })).toBeVisible();
  });
});
