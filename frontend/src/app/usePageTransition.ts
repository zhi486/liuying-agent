import { useLayoutEffect, useRef, type RefObject } from 'react';

function depth(path: string) {
  if (path === '/agent' || path === '/run') return 0;
  if (path.startsWith('/settings/') || path.startsWith('/history/') || path === '/memories')
    return 2;
  return 1;
}

export function pageTransition(from: string, to: string) {
  if (from === to) return null;
  // 固定视口的聊天和跑步页只淡入，避免 transform 改变内部定位参照。
  if (to === '/agent' || to === '/run') return 'peer';
  const difference = depth(to) - depth(from);
  return difference > 0 ? 'forward' : difference < 0 ? 'back' : 'peer';
}

export function usePageTransition(content: RefObject<HTMLElement | null>, pathname: string) {
  const previous = useRef(pathname);
  useLayoutEffect(() => {
    const direction = pageTransition(previous.current, pathname);
    previous.current = pathname;
    const element = content.current;
    if (!element || !direction || !element.animate) return;
    const root = document.documentElement;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    const policy = root.dataset.motion;
    if (policy === 'off') return;
    const reduced = policy === 'reduced' || media?.matches;
    const style = getComputedStyle(root);
    const time = style.getPropertyValue(`--motion-page-${reduced ? 'reduced' : direction}`).trim();
    // 正式构建可能将 240ms 压缩为 .24s，WAAPI 始终需要毫秒。
    const duration = parseFloat(time) * (time.endsWith('ms') ? 1 : 1000);
    const distance = parseFloat(style.getPropertyValue('--motion-page-distance')) || 24;
    const frames: Keyframe[] =
      reduced || direction === 'peer'
        ? [{ opacity: 0 }, { opacity: 1 }]
        : [
            {
              opacity: 0,
              transform: `translateX(${direction === 'back' ? -distance : distance}px)`,
            },
            { opacity: 1, transform: 'translateX(0)' },
          ];
    // 只动画当前内容，不保留旧业务页面，也不延迟导航、请求或可交互时机。
    const animation = element.animate(frames, {
      duration: duration || (reduced ? 100 : 240),
      easing: style.getPropertyValue('--ease-out').trim() || 'ease-out',
    });
    const cancel = () => animation.cancel();
    const observer = new MutationObserver(cancel);
    observer.observe(root, { attributes: true, attributeFilter: ['data-motion'] });
    media?.addEventListener('change', cancel);
    return () => {
      animation.cancel();
      observer.disconnect();
      media?.removeEventListener('change', cancel);
    };
  }, [content, pathname]);
}
