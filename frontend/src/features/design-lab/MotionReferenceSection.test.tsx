import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { MotionReferenceSection } from './MotionReferenceSection';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});
const mount = () =>
  render(
    <MemoryRouter>
      <MotionReferenceSection />
    </MemoryRouter>,
  );
const settings = () => fireEvent.click(screen.getByRole('button', { name: '打开设置预览' }));

it('汉堡与叉号保留同一个按钮和三条线，关闭后归还焦点', () => {
  mount();
  const button = screen.getByRole('button', { name: '打开菜单预览' });
  const glyph = button.querySelector('.menu-glyph');
  fireEvent.click(button);
  expect(screen.getByRole('button', { name: '关闭菜单预览' })).toBe(button);
  expect(button.querySelector('.menu-glyph')).toBe(glyph);
  expect(glyph).toHaveAttribute('data-open', 'true');
  expect(glyph?.children).toHaveLength(3);
  fireEvent.keyDown(button, { key: 'Escape' });
  expect(button).toHaveAttribute('aria-expanded', 'false');
  expect(button).toHaveFocus();
});

it('手动进度保留三根原线，关闭动效后仍可检查形状但不自动播放', () => {
  mount();
  const glyph = screen.getByTestId('menu-morph-demo');
  const lines = glyph.querySelector('.menu-glyph');
  fireEvent.change(screen.getByRole('slider', { name: '三横线变叉进度' }), {
    target: { value: '50' },
  });
  expect(glyph.style.getPropertyValue('--glyph-progress')).toBe('0.5');
  expect(glyph.querySelector('.menu-glyph')).toBe(lines);
  expect(lines?.children).toHaveLength(3);
  fireEvent.click(
    within(screen.getByRole('group', { name: '动态效果' })).getByRole('button', { name: '关闭' }),
  );
  expect(screen.getByRole('button', { name: '慢放还原三横线' })).toBeDisabled();
  fireEvent.change(screen.getByRole('slider', { name: '三横线变叉进度' }), {
    target: { value: '100' },
  });
  expect(glyph.style.getPropertyValue('--glyph-progress')).toBe('1');
});

it('进入子页与返回都只切换合成页面，不改变真实路由', () => {
  mount();
  settings();
  fireEvent.click(screen.getByRole('button', { name: /长期记忆/ }));
  expect(screen.getByRole('switch', { name: '自动记忆预览' })).not.toBeChecked();
  fireEvent.click(screen.getByRole('button', { name: '返回上一级预览' }));
  expect(screen.getByRole('switch', { name: '训练提醒预览' })).toBeChecked();
  fireEvent.click(screen.getByRole('button', { name: '返回上一级预览' }));
  expect(screen.getByRole('button', { name: '复盘 看看最近的表现' })).toBeVisible();
});

it('开启合成记忆必须确认，取消不启用；关闭直接生效且事实始终禁用', () => {
  mount();
  settings();
  fireEvent.click(screen.getByRole('button', { name: /长期记忆/ }));
  const toggle = screen.getByRole('switch', { name: '自动记忆预览' });
  fireEvent.click(toggle);
  const dialog = screen.getByRole('dialog', { name: '合成记忆确认' });
  expect(toggle).not.toBeChecked();
  expect(within(dialog).getByRole('button', { name: '确认开启（仅预览）' })).toBeDisabled();
  fireEvent.click(within(dialog).getByRole('button', { name: '关闭预览面板' }));
  expect(toggle).not.toBeChecked();
  fireEvent.click(toggle);
  fireEvent.click(screen.getByRole('checkbox', { name: '我已了解本次合成预览范围' }));
  fireEvent.click(screen.getByRole('button', { name: '确认开启（仅预览）' }));
  expect(toggle).toBeChecked();
  fireEvent.click(toggle);
  expect(toggle).not.toBeChecked();
  expect(screen.getByRole('switch', { name: '事实写入预览' })).toBeDisabled();
});

it('异步开关保存确认前保持原值，成功后才切换', () => {
  vi.useFakeTimers();
  mount();
  settings();
  const toggle = screen.getByRole('switch', { name: '训练提醒预览' });
  fireEvent.click(toggle);
  expect(toggle).toBeChecked();
  expect(toggle).toBeDisabled();
  act(() => vi.advanceTimersByTime(700));
  expect(toggle).not.toBeChecked();
  expect(toggle).toBeEnabled();
});

it('合成保存失败保留原值，结束后没有自动重试', () => {
  vi.useFakeTimers();
  mount();
  settings();
  fireEvent.click(screen.getByRole('checkbox', { name: '模拟保存失败' }));
  const toggle = screen.getByRole('switch', { name: '训练提醒预览' });
  fireEvent.click(toggle);
  act(() => vi.advanceTimersByTime(700));
  expect(toggle).toBeChecked();
  expect(toggle).toBeEnabled();
  expect(screen.getByRole('status')).toHaveTextContent('合成保存失败');
  act(() => vi.advanceTimersByTime(10000));
  expect(screen.getByRole('status')).toHaveTextContent('未自动重试');
});

it('动画可被再次导航打断，关闭动效不创建页面动画', () => {
  const cancel = vi.fn();
  const animate = vi.fn(() => ({ cancel }));
  const descriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'animate');
  Object.defineProperty(HTMLElement.prototype, 'animate', { configurable: true, value: animate });
  try {
    mount();
    settings();
    expect(animate).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /长期记忆/ }));
    expect(cancel).toHaveBeenCalledTimes(1);
    fireEvent.click(
      within(screen.getByRole('group', { name: '动态效果' })).getByRole('button', { name: '关闭' }),
    );
    const before = animate.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '返回上一级预览' }));
    expect(animate).toHaveBeenCalledTimes(before);
  } finally {
    if (descriptor) Object.defineProperty(HTMLElement.prototype, 'animate', descriptor);
    else Reflect.deleteProperty(HTMLElement.prototype, 'animate');
  }
});

it('输入面板关闭后归还焦点且发送不可用', () => {
  mount();
  const button = screen.getByRole('button', { name: '打开输入选项预览' });
  button.focus();
  fireEvent.click(button);
  expect(screen.getByRole('dialog', { name: '输入选项预览' })).toBeVisible();
  fireEvent.keyDown(screen.getByRole('button', { name: '关闭预览面板' }), { key: 'Escape' });
  expect(button).toHaveFocus();
  expect(screen.getByRole('button', { name: '发送（预览已禁用）' })).toBeDisabled();
});
