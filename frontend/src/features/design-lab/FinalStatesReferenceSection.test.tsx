import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it } from 'vitest';
import { FinalStatesReferenceSection } from './FinalStatesReferenceSection';
it('三种加载方案都可切换空态、失败和示例恢复', () => {
  render(<FinalStatesReferenceSection kind="loadingEmpty" width="320" />);
  for (const name of ['A 原位文字', 'B 骨架占位', 'C 状态面板']) {
    const card = within(screen.getByRole('article', { name }));
    fireEvent.click(card.getByRole('button', { name: '无记录' }));
    expect(card.getByRole('status')).toHaveTextContent('还没有跑步记录');
    fireEvent.click(card.getByRole('button', { name: '读取失败' }));
    fireEvent.click(card.getByRole('button', { name: '重新读取' }));
    expect(card.getByText('晨间跑步')).toBeVisible();
  }
});
it('危险操作可取消，名称确认必须精确匹配且只移除演示项', () => {
  render(<FinalStatesReferenceSection kind="dangerConfirm" width="393" />);
  for (const name of ['A 原位二次确认', 'B 底部确认', 'C 输入名称确认']) {
    const card = within(screen.getByRole('article', { name }));
    fireEvent.click(card.getByRole('button', { name: '删除晨间跑步演示项' }));
    fireEvent.click(card.getByRole('button', { name: '保留记录' }));
    expect(card.queryByRole('region', { name: '删除演示项确认' })).not.toBeInTheDocument();
    fireEvent.click(card.getByRole('button', { name: '删除晨间跑步演示项' }));
    if (name.startsWith('C')) {
      expect(card.getByRole('button', { name: '删除演示项' })).toBeDisabled();
      fireEvent.change(card.getByLabelText('输入 晨间跑步'), { target: { value: '晨间跑步' } });
    }
    fireEvent.click(card.getByRole('button', { name: '删除演示项' }));
    expect(card.getByRole('status')).toHaveTextContent('真实记录未改变');
    fireEvent.click(card.getByRole('button', { name: '重置演示' }));
    expect(card.getByText('晨间跑步')).toBeVisible();
  }
});
