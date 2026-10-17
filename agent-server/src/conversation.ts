import { AgentError, type ConversationContext } from './contracts.ts';
import { isSupportedText } from './plain-text.ts';

export function hasConversation(value: unknown): boolean {
  return value !== null && typeof value === 'object' && Object.hasOwn(value, 'conversation');
}

/** 只接受显式批准的完整往返；历史文本不能声明 system 或重新授予工具权限。 */
export function parseConversation(value: unknown): ConversationContext {
  const fail = (): never => { throw new AgentError('invalid_task'); };
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fail();
  const row = value as Record<string, unknown>;
  const keys = ['version', 'id', 'approved', 'messages'];
  if (Object.keys(row).length !== keys.length || keys.some(key => !Object.hasOwn(row, key)) ||
    row.version !== 'conversation_v1' || row.approved !== true ||
    typeof row.id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(row.id) ||
    !Array.isArray(row.messages) || row.messages.length > 8 || row.messages.length % 2 !== 0) return fail();
  let chars = 0;
  const messages = row.messages.map((value: unknown, index: number) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return fail();
    const message = value as Record<string, unknown>;
    if (Object.keys(message).length !== 2 || !Object.hasOwn(message, 'role') || !Object.hasOwn(message, 'text') ||
      message.role !== (index % 2 === 0 ? 'user' : 'assistant') ||
      !isSupportedText(message.text) || !message.text.trim() || message.text.length > (index % 2 === 0 ? 2000 : 4000)) return fail();
    chars += message.text.length;
    if (chars > 12000) return fail();
    return Object.freeze({ role: message.role as 'user' | 'assistant', text: message.text });
  });
  return Object.freeze({ version: 'conversation_v1', id: row.id, approved: true, messages: Object.freeze(messages) });
}
