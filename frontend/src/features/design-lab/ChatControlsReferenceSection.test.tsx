import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it } from 'vitest';
import { ChatControlsReferenceSection } from './ChatControlsReferenceSection';
it('回答操作三个入口仅反馈演示复制', () => {
  render(<ChatControlsReferenceSection kind="answerActions" width="320" />);
  for (const name of ['A 常驻工具栏', 'B 折叠菜单', 'C 底部操作面板']) {
    const card = within(screen.getByRole('article', { name }));
    if (!name.startsWith('A')) fireEvent.click(card.getByRole('button', { name: '回答操作' }));
    fireEvent.click(card.getByRole('button', { name: '复制回答' }));
    expect(card.getByRole('status')).toHaveTextContent('未写入剪贴板');
  }
});
it('三个消息定位按钮只改变本地滚动状态', () => {
  render(<ChatControlsReferenceSection kind="latestMessage" width="320" />);
  for (const button of screen.getAllByRole('button', { name: '回到最新消息' }))
    fireEvent.click(button);
  expect(screen.getAllByText('已到最新消息')).toHaveLength(3);
});
it('知识库目录和分步核对保留本地选择', () => {
  render(<ChatControlsReferenceSection kind="knowledgePicker" width="393" />);
  const c = within(screen.getByRole('article', { name: 'C 分步核对' }));
  fireEvent.click(c.getByRole('button', { name: /运动健康/ }));
  fireEvent.click(c.getByRole('button', { name: '下一步' }));
  fireEvent.click(c.getByRole('button', { name: '确认选择' }));
  expect(c.getByRole('status')).toHaveTextContent('运动健康');
});
