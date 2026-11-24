import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it } from 'vitest';
import { ConsentPageReferenceSection } from './ConsentPageReferenceSection';

it('三案不预选同意，演示开启关闭与读取失败，不触及真实权限', () => {
  render(<ConsentPageReferenceSection width="320" />);
  for (const name of ['A 状态优先', 'B 范围清单', 'C 分步确认']) {
    const card = within(screen.getByRole('article', { name }));
    if (name.startsWith('C')) fireEvent.click(card.getByRole('button', { name: '继续确认' }));
    expect(card.getByRole('checkbox')).not.toBeChecked();
    expect(card.getByRole('button', { name: '开启持续授权' })).toBeDisabled();
    fireEvent.click(card.getByRole('checkbox'));
    fireEvent.click(card.getByRole('button', { name: '开启持续授权' }));
    expect(card.getByText('持续授权已开启')).toBeVisible();
    expect(card.getByRole('status')).toHaveTextContent('未修改真实权限');
    fireEvent.click(card.getByRole('button', { name: '关闭持续授权' }));
    expect(card.getByRole('checkbox')).not.toBeChecked();
    fireEvent.click(card.getByRole('button', { name: '模拟读取失败' }));
    expect(card.getByRole('alert')).toHaveTextContent('不会自动重试');
    fireEvent.click(card.getByRole('button', { name: '重新读取' }));
    expect(card.queryByRole('alert')).not.toBeInTheDocument();
  }
});

it('分步返回复查清除同意，选择只记录预览候选', () => {
  render(<ConsentPageReferenceSection width="393" />);
  const card = within(screen.getByRole('article', { name: 'C 分步确认' }));
  fireEvent.click(card.getByRole('button', { name: '继续确认' }));
  fireEvent.click(card.getByRole('checkbox'));
  fireEvent.click(card.getByRole('button', { name: '授权范围' }));
  fireEvent.click(card.getByRole('button', { name: '继续确认' }));
  expect(card.getByRole('checkbox')).not.toBeChecked();
  fireEvent.click(card.getByRole('button', { name: '比较 C 方案' }));
  expect(card.getByRole('button', { name: '对比候选，正式页仍为 B' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
});
