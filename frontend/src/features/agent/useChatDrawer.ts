import { useLayoutEffect, useRef, useState } from 'react';
import type { MouseEvent, TouchEvent } from 'react';

/** 只处理横向拖动；纵向滚动及输入框选字交给浏览器。 */
export function useChatDrawer(open: boolean, setOpen: (open: boolean) => void) {
  const [offset, setOffset] = useState<number | null>(null);
  const width = useRef(0);
  const gesture = useRef<{
    x: number;
    y: number;
    start: number;
    time: number;
    dragging: boolean;
  } | null>(null);
  const ignoreClickUntil = useRef(0);

  useLayoutEffect(() => {
    const root = document.documentElement;
    const update = () => {
      width.current = Math.min(
        window.innerWidth * 0.86,
        parseFloat(getComputedStyle(root).fontSize) * 21,
      );
      const x = Math.max(0, Math.min(width.current, offset ?? (open ? width.current : 0)));
      root.style.setProperty('--chat-drawer-width', `${width.current}px`);
      root.style.setProperty('--chat-drawer-x', `${x}px`);
      root.style.setProperty('--chat-drawer-progress', String(x / width.current));
      root.dataset.chatDragging = String(offset !== null);
    };
    update();
    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, [open, offset]);

  useLayoutEffect(
    () => () => {
      const root = document.documentElement;
      for (const name of ['--chat-drawer-width', '--chat-drawer-x', '--chat-drawer-progress'])
        root.style.removeProperty(name);
      delete root.dataset.chatDragging;
    },
    [],
  );

  const finish = (event: TouchEvent, cancelled = false) => {
    event.stopPropagation();
    const current = gesture.current;
    gesture.current = null;
    if (!current?.dragging) return;
    const dx = (event.changedTouches[0]?.clientX ?? current.x) - current.x;
    const speed = dx / Math.max(1, performance.now() - current.time);
    const next = cancelled
      ? open
      : Math.abs(speed) > 0.45
        ? dx > 0
        : current.start + dx > width.current / 2;
    ignoreClickUntil.current = performance.now() + 350;
    setOffset(null);
    setOpen(next);
  };

  return {
    onTouchStart(event: TouchEvent) {
      event.stopPropagation();
      if (
        event.touches.length !== 1 ||
        (event.target as HTMLElement).closest('input, textarea, select, [contenteditable="true"]')
      )
        return;
      const touch = event.touches[0];
      gesture.current = {
        x: touch.clientX,
        y: touch.clientY,
        start: open ? width.current : 0,
        time: performance.now(),
        dragging: false,
      };
    },
    onTouchMove(event: TouchEvent) {
      event.stopPropagation();
      const current = gesture.current;
      if (!current) return;
      if (event.touches.length !== 1) {
        gesture.current = null;
        setOffset(null);
        return;
      }
      const dx = event.touches[0].clientX - current.x;
      const dy = event.touches[0].clientY - current.y;
      if (!current.dragging) {
        if (Math.abs(dy) > 8 && Math.abs(dy) >= Math.abs(dx)) {
          gesture.current = null;
          return;
        }
        if (Math.abs(dx) < 8 || Math.abs(dx) < Math.abs(dy) * 1.2 || (!open && dx < 0)) return;
        current.dragging = true;
      }
      setOffset(Math.max(0, Math.min(width.current, current.start + dx)));
    },
    onTouchEnd: (event: TouchEvent) => finish(event),
    onTouchCancel: (event: TouchEvent) => finish(event, true),
    onClickCapture(event: MouseEvent) {
      if (performance.now() < ignoreClickUntil.current) {
        event.preventDefault();
        event.stopPropagation();
      }
    },
  };
}
