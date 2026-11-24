import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it } from 'vitest';
import { AgentFeedbackReferenceSection } from './AgentFeedbackReferenceSection';

it('工具摘要可展开并本地完成，选择仅标记候选', () => {
  render(<AgentFeedbackReferenceSection kind="toolFeedback" width="320" />);
  const a = within(screen.getByRole('article', { name: 'A 单行摘要' }));
  fireEvent.click(a.getByRole('button', { name: '正在读取跑步记录' }));
  expect(a.getByText('整理距离与分段')).toBeVisible();
  fireEvent.click(a.getByRole('button', { name: '演示完成' }));
  expect(a.getByRole('button', { name: '记录已读取' })).toBeVisible();
  fireEvent.click(a.getByRole('button', { name: '选择 A 方案' }));
  expect(a.getByRole('button', { name: '当前候选，尚未接入' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
});
it('三种来源入口都能查看对应合成记录', () => {
  render(<AgentFeedbackReferenceSection kind="sourceFeedback" width="393" />);
  const a = within(screen.getByRole('article', { name: 'A 行内引用' }));
  fireEvent.click(a.getByRole('button', { name: '查看来源 1' }));
  expect(a.getByRole('region', { name: '来源内容' })).toHaveTextContent('5.02');
  const b = within(screen.getByRole('article', { name: 'B 底部清单' }));
  fireEvent.click(b.getByRole('button', { name: '每公里分段' }));
  expect(b.getByRole('region', { name: '来源内容' })).toHaveTextContent('5:54');
  const c = within(screen.getByRole('article', { name: 'C 逐条审阅' }));
  fireEvent.click(c.getByRole('button', { name: '来源 2' }));
  expect(c.getByRole('region', { name: '来源内容' })).toHaveTextContent('5:54');
});
it('恢复支持本地重试及编辑，空白不可重发', () => {
  render(<AgentFeedbackReferenceSection kind="recoveryFeedback" width="320" />);
  const a = within(screen.getByRole('article', { name: 'A 原位重试' }));
  fireEvent.click(a.getByRole('button', { name: /^重试$/ }));
  expect(a.getByRole('status')).toHaveTextContent('未调用模型');
  const b = within(screen.getByRole('article', { name: 'B 底部提示' }));
  fireEvent.click(b.getByRole('button', { name: '重试发送' }));
  expect(b.getByRole('status')).toHaveTextContent('未调用模型');
  const c = within(screen.getByRole('article', { name: 'C 编辑重发' }));
  fireEvent.click(c.getByRole('button', { name: '编辑后重发' }));
  fireEvent.change(c.getByLabelText('消息草稿'), { target: { value: ' ' } });
  expect(c.getByRole('button', { name: '重新发送' })).toBeDisabled();
  fireEvent.change(c.getByLabelText('消息草稿'), { target: { value: '整理分段' } });
  fireEvent.click(c.getByRole('button', { name: '重新发送' }));
  expect(c.getByRole('status')).toHaveTextContent('未调用模型');
});
