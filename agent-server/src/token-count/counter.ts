import { Tokenizer } from '@huggingface/tokenizers';
import { TOKENIZER_PROFILE } from './profile.ts';
import { renderInputPrompt } from './template.ts';

export async function createInputTokenCounter(
  asset: Uint8Array,
): Promise<(raw: string) => Promise<number>> {
  try {
    // 摘要验证与解析必须使用同一份独立快照，不能被调用方中途更换字典。
    if (asset.byteLength !== TOKENIZER_PROFILE.bytes) throw new Error();
    const bytes = Uint8Array.from(asset);
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    const hash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
    if (hash !== TOKENIZER_PROFILE.sha256) throw new Error();
    const data = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
    const tokenizer = new Tokenizer(data, {});
    return async (raw: string) => {
      try {
        // 只需 token 数；不分配 encode 额外的 ID 和 attention_mask 数组。
        return tokenizer.tokenize(renderInputPrompt(raw), { add_special_tokens: false }).length;
      } catch {
        throw new Error('输入计数不可用');
      }
    };
  } catch {
    throw new Error('输入计数不可用');
  }
}
