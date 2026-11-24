import { useEffect, useRef } from 'react';
import { flushSync } from 'react-dom';

export function revealCircle(
  box: Pick<DOMRect, 'left' | 'top' | 'width' | 'height'>,
  width: number,
  height: number,
) {
  const x = Math.max(0, Math.min(width, box.left + box.width / 2));
  const y = Math.max(0, Math.min(height, box.top + box.height / 2));
  return {
    x,
    y,
    radius: Math.ceil(Math.hypot(Math.max(x, width - x), Math.max(y, height - y))) + 1,
  };
}

function milliseconds(style: CSSStyleDeclaration, name: string, fallback: number) {
  const value = style.getPropertyValue(name).trim();
  return parseFloat(value) * (value.endsWith('ms') ? 1 : 1000) || fallback;
}

export function useNewConversationReveal() {
  const busy = useRef(false);
  const active = useRef<(() => void) | null>(null);
  useEffect(() => () => active.current?.(), []);

  return async (button: HTMLElement, change: () => Promise<boolean>) => {
    if (busy.current) return;
    busy.current = true;
    const root = document.documentElement;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    let update: Promise<boolean> | undefined;
    const commit = () => (update ??= change());
    try {
      if (
        !document.startViewTransition ||
        !root.animate ||
        media?.matches ||
        root.dataset.motion === 'off' ||
        root.dataset.motion === 'reduced'
      ) {
        await commit();
        return;
      }
      const circle = revealCircle(
        (button.querySelector('svg') ?? button).getBoundingClientRect(),
        window.innerWidth,
        window.innerHeight,
      );
      const style = getComputedStyle(root);
      let skipped = false;
      let animation: Animation | undefined;
      root.dataset.newChatReveal = 'true';
      const transition = document.startViewTransition(async () => {
        const changed = await commit();
        // 原生接纳后让 React 完成真实页面更新，再由浏览器截取新画面。
        flushSync(() => {});
        if (!changed) stop();
      });
      function stop() {
        skipped = true;
        animation?.cancel();
        transition.skipTransition();
        delete root.dataset.newChatReveal;
      }
      active.current = stop;
      const timer = window.setTimeout(
        stop,
        milliseconds(style, '--motion-chat-capture-limit', 600),
      );
      const observer = new MutationObserver(stop);
      observer.observe(root, { attributes: true, attributeFilter: ['data-motion', 'data-theme'] });
      media?.addEventListener('change', stop);
      window.addEventListener('resize', stop);
      window.visualViewport?.addEventListener('resize', stop);
      document.addEventListener('visibilitychange', stop);
      try {
        await transition.ready;
        window.clearTimeout(timer);
        if (!skipped) {
          animation = root.animate(
            [
              { clipPath: `circle(0px at ${circle.x}px ${circle.y}px)` },
              { clipPath: `circle(${circle.radius}px at ${circle.x}px ${circle.y}px)` },
            ],
            {
              duration: milliseconds(style, '--motion-chat-reveal', 480),
              easing: style.getPropertyValue('--ease-out').trim() || 'ease-out',
              pseudoElement: '::view-transition-new(root)',
              fill: 'both',
            },
          );
          await animation.finished;
          animation.cancel();
        }
      } catch {
        // 不支持快照、窗口变化或动画取消只结束视觉效果，不重发业务操作。
        stop();
      } finally {
        window.clearTimeout(timer);
        observer.disconnect();
        media?.removeEventListener('change', stop);
        window.removeEventListener('resize', stop);
        window.visualViewport?.removeEventListener('resize', stop);
        document.removeEventListener('visibilitychange', stop);
        // 清除快照后恢复正常命中与焦点；不保存旧页面 DOM 或正文副本。
        transition.skipTransition();
        await transition.finished.catch(() => undefined);
        delete root.dataset.newChatReveal;
        active.current = null;
      }
    } catch {
      // API 同步失败时仍只执行一次原来的新建操作。
      delete root.dataset.newChatReveal;
      await commit();
    } finally {
      busy.current = false;
    }
  };
}
