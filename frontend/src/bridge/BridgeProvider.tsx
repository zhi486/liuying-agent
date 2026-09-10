import { useEffect, useLayoutEffect, type PropsWithChildren } from 'react';

import { connectNativeBridge, useBridgeStore } from './bridgeStore';

function cachePreference(key: string, value: string) {
  try {
    window.localStorage?.setItem(key, value);
  } catch {
    // WebView 存储不可用时仍应继续渲染，原生快照依然是偏好的真实来源。
  }
}

export function BridgeProvider({ children }: PropsWithChildren) {
  const theme = useBridgeStore((state) => state.snapshot?.theme);
  const motionPolicy = useBridgeStore((state) => state.snapshot?.motionPolicy);

  useEffect(() => connectNativeBridge(), []);

  useLayoutEffect(() => {
    if (theme) {
      document.documentElement.dataset.theme = theme;
      cachePreference('liuying-theme', theme);
    }
    if (motionPolicy) {
      document.documentElement.dataset.motion = motionPolicy;
      cachePreference('liuying-motion', motionPolicy);
    }
  }, [motionPolicy, theme]);

  return children;
}
