import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { RunningPage } from './RunningPage';

describe('跑步主流程', () => {
  it('从设备就绪进入跑步、暂停、继续并结束', () => {
    render(
      <MemoryRouter>
        <RunningPage />
      </MemoryRouter>,
    );

    expect(screen.getByText('设备已就绪')).toBeVisible();
    expect(screen.getByRole('button', { name: '开始跑步' })).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: '开始跑步' }));
    expect(screen.getByText('跑步中')).toBeVisible();
    expect(screen.getByText('6:02')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '暂停' }));
    expect(screen.getByText('已暂停')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '继续' }));
    expect(screen.getByText('跑步中')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '结束' }));
    expect(screen.getByRole('alertdialog', { name: '结束本次跑步？' })).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '确认结束' }));

    expect(screen.getByText('本次跑步已完成')).toBeVisible();
    expect(screen.getByRole('link', { name: '查看记录' })).toHaveAttribute('href', '/history');
  });
});
