import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { ChoiceInput } from './ChoiceInput';
import { MenuGlyph } from './MenuGlyph';
import { installButtonRipples } from './useButtonRipples';

afterEach(() => {
  delete document.documentElement.dataset.motion;
  vi.useRealTimers();
});

it('勾选保留原生标签与受控语义，只有交互后才标记动效', () => {
  const changed = vi.fn();
  const { rerender } = render(
    <label>
      <ChoiceInput checked={false} onChange={changed} />
      同意范围
    </label>,
  );
  const input = screen.getByRole('checkbox', { name: '同意范围' });
  expect(input.parentElement).toHaveAttribute('data-interacted', 'false');
  fireEvent.click(input);
  expect(changed).toHaveBeenCalledTimes(1);
  expect(input).not.toBeChecked();
  rerender(
    <label>
      <ChoiceInput checked onChange={changed} disabled />
      同意范围
    </label>,
  );
  expect(input).toBeChecked();
  expect(input).toBeDisabled();
  expect(input.parentElement).toHaveAttribute('data-interacted', 'true');
});

it('开关保留原有回调且可反向切换', () => {
  function Fixture() {
    const [checked, setChecked] = useState(false);
    return (
      <ChoiceInput
        variant="switch"
        role="switch"
        aria-label="测试开关"
        checked={checked}
        onChange={(event) => setChecked(event.target.checked)}
      />
    );
  }
  render(<Fixture />);
  const input = screen.getByRole('switch');
  fireEvent.click(input);
  expect(input).toBeChecked();
  fireEvent.click(input);
  expect(input).not.toBeChecked();
});

it('菜单始终复用同三条线而非替换图标', () => {
  const { container, rerender } = render(<MenuGlyph open={false} />);
  const lines = [...container.querySelectorAll('.menu-glyph > span')];
  expect(lines).toHaveLength(3);
  rerender(<MenuGlyph open />);
  expect(container.querySelector('.menu-glyph')).toHaveAttribute('data-open', 'true');
  expect([...container.querySelectorAll('.menu-glyph > span')]).toEqual(lines);
});

it('异步开关等待真实回执，失败不回弹，重读初始状态不播放', () => {
  const changed = vi.fn();
  const { rerender } = render(<ChoiceInput variant="switch" checked={false} onChange={changed} />);
  const input = screen.getByRole('checkbox');
  expect(input.parentElement).not.toHaveAttribute('data-spring');
  rerender(<ChoiceInput variant="switch" checked onChange={changed} />);
  expect(input.parentElement).not.toHaveAttribute('data-spring');
  fireEvent.click(input);
  expect(changed).toHaveBeenCalledTimes(1);
  expect(input).toBeChecked();
  expect(input.parentElement).not.toHaveAttribute('data-spring');
  rerender(<ChoiceInput variant="switch" checked onChange={changed} disabled />);
  expect(input.parentElement).not.toHaveAttribute('data-spring');
  rerender(<ChoiceInput variant="switch" checked={false} onChange={changed} />);
  expect(input.parentElement).toHaveAttribute('data-spring', 'return');
  rerender(<ChoiceInput variant="switch" checked onChange={changed} />);
  expect(input.parentElement).toHaveAttribute('data-spring', 'arrive');
});

it('非受控开关的每次真实变化也可回弹，禁用时不触发', () => {
  const changed = vi.fn();
  const { rerender } = render(<ChoiceInput variant="switch" onChange={changed} />);
  const input = screen.getByRole('checkbox');
  fireEvent.click(input);
  expect(input.parentElement).toHaveAttribute('data-spring', 'arrive');
  fireEvent.click(input);
  expect(input.parentElement).toHaveAttribute('data-spring', 'return');
  rerender(<ChoiceInput variant="switch" onChange={changed} disabled />);
  fireEvent.click(input);
  expect(changed).toHaveBeenCalledTimes(2);
});

it('波纹按触点定位，快速重按有界，释放和卸载均清理', () => {
  vi.useFakeTimers();
  const { container } = render(<button>测试按钮</button>);
  const button = screen.getByRole('button');
  vi.spyOn(button, 'getBoundingClientRect').mockReturnValue({
    left: 10,
    top: 20,
    width: 100,
    height: 44,
  } as DOMRect);
  const stop = installButtonRipples(document);
  const press = () =>
    button.dispatchEvent(
      Object.assign(new Event('pointerdown', { bubbles: true }), {
        button: 0,
        pointerId: 1,
        clientX: 25,
        clientY: 30,
      }),
    );
  press();
  const ink = container.querySelector<HTMLElement>('.press-ripple > span')!;
  expect(Number.parseFloat(ink.style.left) + Number.parseFloat(ink.style.width) / 2).toBeCloseTo(
    15,
  );
  press();
  expect(container.querySelectorAll('.press-ripple')).toHaveLength(1);
  document.dispatchEvent(Object.assign(new Event('pointercancel'), { pointerId: 1 }));
  container.querySelector('.press-ripple')!.dispatchEvent(new Event('animationend'));
  expect(container.querySelector('.press-ripple')).toBeNull();
  press();
  stop();
  expect(container.querySelector('.press-ripple')).toBeNull();
  expect(button.style.position).toBe('');
});

it('禁用和减少动效不产生波纹，也不触发业务点击', () => {
  const clicked = vi.fn();
  render(
    <button disabled onClick={clicked}>
      禁止按钮
    </button>,
  );
  const stop = installButtonRipples(document);
  fireEvent.pointerDown(screen.getByRole('button'), { button: 0 });
  expect(document.querySelector('.press-ripple')).toBeNull();
  document.documentElement.dataset.motion = 'reduced';
  const button = screen.getByRole('button');
  button.removeAttribute('disabled');
  vi.spyOn(button, 'getBoundingClientRect').mockReturnValue({
    left: 0,
    top: 0,
    width: 100,
    height: 44,
  } as DOMRect);
  button.dispatchEvent(
    Object.assign(new Event('pointerdown', { bubbles: true }), {
      button: 0,
      pointerId: 1,
      clientX: 25,
      clientY: 30,
    }),
  );
  expect(document.querySelector('.press-ripple')).toBeNull();
  expect(clicked).not.toHaveBeenCalled();
  stop();
});

it('自带圆形揭示等主反馈的按钮不叠加通用波纹', () => {
  render(
    <button data-suppress-ripple="true" type="button">
      新建对话
    </button>,
  );
  const button = screen.getByRole('button', { name: '新建对话' });
  vi.spyOn(button, 'getBoundingClientRect').mockReturnValue({
    left: 0,
    top: 0,
    width: 44,
    height: 44,
  } as DOMRect);
  const stop = installButtonRipples(document);
  fireEvent.pointerDown(button, { button: 0, clientX: 22, clientY: 22 });
  expect(button.querySelector('.press-ripple')).toBeNull();
  stop();
});

it('键盘波纹从中心出现，长按超时有界且移除监听后不再生成', () => {
  vi.useFakeTimers();
  render(<button>键盘按钮</button>);
  const button = screen.getByRole('button');
  vi.spyOn(button, 'getBoundingClientRect').mockReturnValue({
    left: 0,
    top: 0,
    width: 100,
    height: 44,
  } as DOMRect);
  const stop = installButtonRipples(document);
  fireEvent.keyDown(button, { key: 'Enter' });
  const ink = button.querySelector<HTMLElement>('.press-ripple > span')!;
  expect(Number.parseFloat(ink.style.left) + Number.parseFloat(ink.style.width) / 2).toBeCloseTo(
    50,
  );
  fireEvent.keyDown(button, { key: 'Enter', repeat: true });
  expect(button.querySelectorAll('.press-ripple')).toHaveLength(1);
  vi.advanceTimersByTime(2501);
  expect(button.querySelector('.press-ripple')).toBeNull();
  stop();
  fireEvent.keyDown(button, { key: 'Enter' });
  expect(button.querySelector('.press-ripple')).toBeNull();
});
