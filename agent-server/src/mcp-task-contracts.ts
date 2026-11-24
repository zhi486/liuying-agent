import { CloudError, uuid } from './cloud-task-contracts.ts';
import { parseNativeMcpReply } from './native-mcp-channel.ts';

export type McpTaskAction = 'get' | 'take' | 'reply' | 'decision' | 'close';
export type McpTaskDecision = Readonly<{
  version: 'native_mcp_v1'; invocationId: string; intentDigest: string; generation: string; decision: 'approve' | 'deny';
}>;

export function parseMcpTaskDecision(input: unknown): McpTaskDecision {
  try {
    if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error();
    const row = input as Record<string, unknown>;
    const keys = ['version', 'invocationId', 'intentDigest', 'generation', 'decision'];
    if (Object.keys(row).length !== keys.length || keys.some(key => !Object.hasOwn(row, key)) ||
        row.version !== 'native_mcp_v1' || typeof row.intentDigest !== 'string' || !/^[0-9a-f]{64}$/.test(row.intentDigest) ||
        (row.decision !== 'approve' && row.decision !== 'deny')) throw new Error();
    return Object.freeze({ version: 'native_mcp_v1', invocationId: uuid(row.invocationId), intentDigest: row.intentDigest,
      generation: uuid(row.generation), decision: row.decision });
  } catch { throw new CloudError('invalid_request'); }
}

export function parseMcpTaskPayload(action: McpTaskAction, input: unknown): unknown {
  try {
    if (action === 'reply') return parseNativeMcpReply(input);
    if (action === 'decision') return parseMcpTaskDecision(input);
    if (input !== undefined) throw new Error();
    return undefined;
  } catch { throw new CloudError('invalid_request'); }
}
