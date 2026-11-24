// 仅允许当前纯文本协议；不把 ASCII image 标签当作媒体指令。
export function isSupportedText(value: unknown): value is string {
  if (typeof value !== 'string' || value.includes('<｜image｜>') || value.includes('<｜deepseek_image｜>'))
    return false;
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(++index);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false;
    } else if (code >= 0xdc00 && code <= 0xdfff) return false;
  }
  return true;
}
