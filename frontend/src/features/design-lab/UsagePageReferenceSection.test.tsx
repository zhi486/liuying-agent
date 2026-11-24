import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it } from 'vitest';
import { UsagePageReferenceSection } from './UsagePageReferenceSection';

it('三案保留待确认费用，切换用量视图与合成错误恢复', () => {
  render(<UsagePageReferenceSection width="320" />);
  const card = screen.getByRole('article', { name: 'B 费用与用量' });
  expect(within(card).getByText('$0.004160')).toBeVisible();
  fireEvent.click(within(card).getByRole('button', { name: 'Token 用量' }));
  expect(within(card).getByText('1,626')).toBeVisible();
  fireEvent.click(within(card).getByRole('button', { name: '读取失败' }));
  expect(within(card).getByRole('alert')).toHaveTextContent('暂时无法读取本机回执');
  fireEvent.click(within(card).getByRole('button', { name: '重新读取' }));
  expect(within(card).getByRole('status')).toHaveTextContent('没有重新发送问题');
  fireEvent.click(within(card).getByRole('button', { name: '空记录' }));
  expect(within(card).getByText('暂无可展示的用量记录。')).toBeVisible();
  fireEvent.click(within(card).getByRole('button', { name: 'B 已接入正式页面' }));
  expect(within(card).getByRole('button', { name: 'B 已接入正式页面' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
});
