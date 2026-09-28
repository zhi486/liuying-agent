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
    ['/', '开拓者，想聊些什么？'],
    ['/agent', '开拓者，想聊些什么？'],
    ['/memories', '我的记忆'],
    ['/run', '跑步'],
    ['/history', '跑步记录'],
    ['/settings', '设置'],
  ])('渲染 %s', async (path, heading) => {
    render(<TestRouter initialEntry={path} />);

    expect(await screen.findByRole('heading', { name: heading })).toBeVisible();
  });

  it('未知路径回到流萤首页', async () => {
    render(<TestRouter initialEntry="/unknown" />);

    expect(await screen.findByRole('heading', { name: '开拓者，想聊些什么？' })).toBeVisible();
  });

  it('首页明确未接入状态，右上角可切换到跑步', async () => {
    render(<TestRouter initialEntry="/agent" />);
    expect(screen.getByRole('button', { name: '仅保存' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '在线执行' })).toBeDisabled();
    expect(screen.getByText('在线服务未配置，可在账户详情中设置')).toBeVisible();
    const navigation = screen.getByRole('navigation', { name: '页面切换' });
    expect(navigation).toHaveTextContent('跑步');
    expect(screen.getByRole('link', { name: '切换到跑步' })).toHaveAttribute('href', '/run');
  });

  it('跑步页右上角可切换回对话', async () => {
    render(<TestRouter initialEntry="/run" />);
    expect(screen.getByRole('link', { name: '切换到对话' })).toHaveAttribute('href', '/agent');
  });

  it('账户详情页仍有回对话入口', async () => {
    render(<TestRouter initialEntry="/settings" />);
    expect(screen.getByRole('link', { name: '切换到对话' })).toHaveAttribute('href', '/agent');
  });
});
