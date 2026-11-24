import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it } from 'vitest';
import { MorePagesReferenceSection } from './MorePagesReferenceSection';

it('须知支持折叠搜索、分类阅读与独立选择', () => {
  render(<MorePagesReferenceSection kind="notices" width="320" />);
  const b = within(screen.getByRole('article', { name: 'B 问题索引' }));
  fireEvent.click(b.getByRole('button', { name: /费用与网络异常/ }));
  expect(b.getByText(/超时不代表云端未执行/)).toBeVisible();
  fireEvent.change(b.getByRole('textbox'), { target: { value: '无匹配测试' } });
  expect(b.getByRole('status')).toHaveTextContent('未找到');
  const c = within(screen.getByRole('article', { name: 'C 分类阅读' }));
  fireEvent.click(c.getByRole('button', { name: '资料与回答' }));
  expect(c.getByText(/回答可能有误/)).toBeVisible();
  fireEvent.click(c.getByRole('button', { name: '比较 C 方案' }));
  expect(c.getByRole('button', { name: '对比候选，正式页仍为 A' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
});

it('插件三案显示权限边界，支持合成空态与错误恢复', () => {
  render(<MorePagesReferenceSection kind="plugins" width="393" />);
  const a = within(screen.getByRole('article', { name: 'A 服务列表' }));
  fireEvent.click(a.getByRole('button', { name: /训练资料/ }));
  expect(a.getByText('搜索训练资料')).toBeVisible();
  const b = within(screen.getByRole('article', { name: 'B 工具审阅' }));
  expect(b.getByText('每次确认')).toBeVisible();
  fireEvent.click(b.getByRole('button', { name: '切换空列表' }));
  expect(b.getByText('暂无插件服务')).toBeVisible();
  fireEvent.click(b.getByRole('button', { name: '模拟读取失败' }));
  expect(b.getByRole('alert')).toHaveTextContent('不会自动重试');
  fireEvent.click(b.getByRole('button', { name: '重新读取' }));
  expect(b.queryByRole('alert')).not.toBeInTheDocument();
  const c = within(screen.getByRole('article', { name: 'C 接入流程' }));
  fireEvent.click(c.getByRole('button', { name: /手机私密配置/ }));
  expect(c.getByText(/网页不显示或收集任何凭据/)).toBeVisible();
});
