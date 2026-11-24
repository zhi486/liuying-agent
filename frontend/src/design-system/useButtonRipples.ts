import { useEffect } from 'react';

export function installButtonRipples(document: Document) {
  const active = new Map<HTMLElement, { release: () => void; remove: () => void }>();
  const pressed = new Map<string, () => void>();
  const start = (target: EventTarget | null, key: string, point?: { x: number; y: number }) => {
    const host = target instanceof Element ? target.closest<HTMLElement>('button, a[href]') : null;
    if (
      !host ||
      host.matches(':disabled, [aria-disabled="true"], [aria-hidden="true"], [tabindex="-1"]') ||
      host.matches('[data-suppress-ripple="true"]') ||
      host.closest('[inert]') ||
      ['reduced', 'off'].includes(document.documentElement.dataset.motion ?? '') ||
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    )
      return;
    const box = host.getBoundingClientRect();
    if (!box.width || !box.height) return;
    active.get(host)?.remove();
    const x = point ? Math.max(0, Math.min(box.width, point.x - box.left)) : box.width / 2;
    const y = point ? Math.max(0, Math.min(box.height, point.y - box.top)) : box.height / 2;
    const diameter = 2 * Math.hypot(Math.max(x, box.width - x), Math.max(y, box.height - y));
    const layer = document.createElement('span');
    layer.className = 'press-ripple';
    layer.setAttribute('aria-hidden', 'true');
    const ink = document.createElement('span');
    ink.style.width = ink.style.height = `${diameter}px`;
    ink.style.left = `${x - diameter / 2}px`;
    ink.style.top = `${y - diameter / 2}px`;
    layer.append(ink);
    const position = host.style.position;
    const repositioned = getComputedStyle(host).position === 'static';
    if (repositioned) host.style.position = 'relative';
    host.append(layer);
    let timer: ReturnType<typeof setTimeout>;
    const remove = () => {
      clearTimeout(timer);
      layer.removeEventListener('animationend', ended);
      layer.remove();
      if (repositioned) host.style.position = position;
      active.delete(host);
      if (pressed.get(key) === release) pressed.delete(key);
    };
    const release = () => {
      clearTimeout(timer);
      layer.dataset.released = 'true';
      timer = setTimeout(remove, 2500);
    };
    const ended = (event: AnimationEvent) => {
      if (event.target === layer) remove();
    };
    layer.addEventListener('animationend', ended);
    active.set(host, { release, remove });
    pressed.set(key, release);
    timer = setTimeout(remove, 2500);
  };
  const down = (event: PointerEvent) => {
    if (event.button !== 0 || event.isPrimary === false) return;
    start(event.target, `pointer-${event.pointerId}`, { x: event.clientX, y: event.clientY });
  };
  const up = (event: PointerEvent) => pressed.get(`pointer-${event.pointerId}`)?.();
  const keydown = (event: KeyboardEvent) => {
    if (event.repeat || !['Enter', ' '].includes(event.key)) return;
    if (event.key === ' ' && event.target instanceof Element && event.target.closest('a')) return;
    start(event.target, `key-${event.key}`);
  };
  const keyup = (event: KeyboardEvent) => pressed.get(`key-${event.key}`)?.();
  const releaseAll = () => active.forEach((effect) => effect.release());
  document.addEventListener('pointerdown', down, true);
  document.addEventListener('pointerup', up, true);
  document.addEventListener('pointercancel', up, true);
  document.addEventListener('keydown', keydown, true);
  document.addEventListener('keyup', keyup, true);
  window.addEventListener('blur', releaseAll);
  return () => {
    document.removeEventListener('pointerdown', down, true);
    document.removeEventListener('pointerup', up, true);
    document.removeEventListener('pointercancel', up, true);
    document.removeEventListener('keydown', keydown, true);
    document.removeEventListener('keyup', keyup, true);
    window.removeEventListener('blur', releaseAll);
    active.forEach((effect) => effect.remove());
    pressed.clear();
  };
}

export function useButtonRipples() {
  useEffect(() => installButtonRipples(document), []);
}
