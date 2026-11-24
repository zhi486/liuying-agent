import { fireEvent, render, screen } from '@testing-library/react';
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
    ['/', '今天想把哪件事交给流萤？'],
    ['/agent', '今天想把哪件事交给流萤？'],
    ['/memories', '我的记忆'],
    ['/run', '跑步'],
    ['/history', '跑步记录'],
    ['/settings', '设置'],
    ['/settings/usage', '账单与用量'],
    ['/settings/chat', '聊天授权'],
    ['/settings/plugins', 'MCP 插件'],
    ['/settings/notices', '用户须知'],
    ['/design/reference', '组件参考台'],
  ])('渲染 %s', async (path, heading) => {
    render(<TestRouter initialEntry={path} />);

    expect(await screen.findByRole('heading', { name: heading }, { timeout: 5000 })).toBeVisible();
  });

  it('未知路径回到流萤首页', async () => {
    render(<TestRouter initialEntry="/unknown" />);

    expect(await screen.findByRole('heading', { name: '今天想把哪件事交给流萤？' })).toBeVisible();
  });

  it('首页明确未接入状态，右上角可切换到跑步', async () => {
    render(<TestRouter initialEntry="/agent" />);
    fireEvent.click(screen.getByRole('button', { name: '更多输入选项' }));
    expect(screen.getByRole('button', { name: '仅保存' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '在线执行' })).toBeDisabled();
    expect(screen.getByText('在线服务未配置，可在账户详情中设置')).toBeVisible();
    const navigation = screen.getByRole('navigation', { name: '页面切换' });
    expect(navigation).toHaveTextContent('跑步');
    expect(screen.getByRole('link', { name: '切换到跑步' })).toHaveAttribute('href', '/run');
  });

  it('跑步页使用无文字返回箭头回到聊天', async () => {
    render(<TestRouter initialEntry="/run" />);
    const back = screen.getByRole('link', { name: '返回聊天' });
    expect(back).toHaveAttribute('href', '/agent');
    expect(back).toHaveTextContent('');
    expect(screen.queryByRole('link', { name: '切换到聊天' })).not.toBeInTheDocument();
  });

  it('账户详情页仍有回聊天入口', async () => {
    render(<TestRouter initialEntry="/settings" />);
    expect(screen.getByRole('link', { name: '返回聊天' })).toHaveAttribute('href', '/agent');
  });
});
