import { act, renderHook } from '@testing-library/react';
import type { TouchEvent } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { useChatDrawer } from './useChatDrawer';

function touch(x: number, y: number, target: HTMLElement = document.body) {
  return {
    touches: [{ clientX: x, clientY: y }],
    changedTouches: [{ clientX: x, clientY: y }],
    target,
    stopPropagation: vi.fn(),
  } as unknown as TouchEvent;
}

afterEach(() => {
  document.documentElement.style.fontSize = '';
});

it('横向拖动实时跟手且取消后恢复原状态，卸载清理样式', () => {
  document.documentElement.style.fontSize = '16px';
  const setOpen = vi.fn();
  const hook = renderHook(() => useChatDrawer(false, setOpen));
  act(() => hook.result.current.onTouchStart(touch(20, 200)));
  act(() => hook.result.current.onTouchMove(touch(180, 204)));
  expect(document.documentElement.style.getPropertyValue('--chat-drawer-x')).toBe('160px');
  act(() => hook.result.current.onTouchCancel(touch(180, 204)));
  expect(setOpen).toHaveBeenCalledWith(false);
  expect(document.documentElement.style.getPropertyValue('--chat-drawer-x')).toBe('0px');
  hook.unmount();
  expect(document.documentElement.style.getPropertyValue('--chat-drawer-x')).toBe('');
  expect(document.documentElement.dataset.chatDragging).toBeUndefined();
});

it('纵向滚动和输入框选字不打开菜单', () => {
  document.documentElement.style.fontSize = '16px';
  const setOpen = vi.fn();
  const hook = renderHook(() => useChatDrawer(false, setOpen));
  act(() => hook.result.current.onTouchStart(touch(20, 200)));
  act(() => hook.result.current.onTouchMove(touch(25, 320)));
  act(() => hook.result.current.onTouchEnd(touch(25, 320)));
  const input = document.createElement('textarea');
  act(() => hook.result.current.onTouchStart(touch(20, 200, input)));
  act(() => hook.result.current.onTouchMove(touch(250, 200, input)));
  act(() => hook.result.current.onTouchEnd(touch(250, 200, input)));
  expect(setOpen).not.toHaveBeenCalled();
  hook.unmount();
});
