try {
  const theme = globalThis.localStorage.getItem('liuying-theme');
  const motion = globalThis.localStorage.getItem('liuying-motion');

  if (theme === 'morning' || theme === 'night') {
    globalThis.document.documentElement.dataset.theme = theme;
  }
  if (['balanced', 'expressive', 'reduced', 'off'].includes(motion)) {
    globalThis.document.documentElement.dataset.motion = motion;
  }
} catch {
  globalThis.document.documentElement.dataset.motion = 'reduced';
}
