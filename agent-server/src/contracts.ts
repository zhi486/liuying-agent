import type { KnowledgeRequest, KnowledgeCitation } from './knowledge-contracts.ts';
import type { CostEstimate } from './cost-estimate.ts';

export type RunSummary = Readonly<{
  runId: string;
  distanceMeters: number;
  durationSeconds: number;
  averagePaceSecPerKm: number | null;
  averageHeartRate: number | null;
}>;

// 此输入只能由已认证的应用服务构造；布尔授权不是身份认证。
export type MemoryContext = Readonly<{
  version: 'selected_memories_v1';
  approved: true;
  items: readonly Readonly<{ id: string; version: number; content: string }>[];
}>;

export type ConversationContext = Readonly<{
  version: 'conversation_v1';
  id: string;
  approved: true;
  messages: readonly Readonly<{ role: 'user' | 'assistant'; text: string }>[];
}>;

export type AgentTask = Readonly<{
  taskId: string;
  prompt: string;
  consent: Readonly<{ cloudProcessing: true; runSummaries: boolean }>;
  summaries: readonly RunSummary[];
  memoryContext?: MemoryContext;
  knowledge?: KnowledgeRequest;
  conversation?: ConversationContext;
  // 仅声明原生工具往返协议；实际授权仍由原手机逐次核验。
  memoryTools?: Readonly<{ version: 'native_memory_v1'; generation: string } |
    { version: 'native_memory_v2'; generation: string; factsAllowed: true }>;
  mcpTools?: Readonly<{ version: 'native_mcp_v1'; services: readonly Readonly<{
    serviceId: string; revision: string; generation: string;
  }>[] }>;
}>;

export type ToolCall = Readonly<
  { id: string } & (
    | { name: 'runs.list'; args: Readonly<Record<string, never>> }
    | { name: 'runs.summary'; args: Readonly<{ runId: string }> }
    | { name: 'memory.search'; args: Readonly<{ query: string }> }
    | { name: 'knowledge.search'; args: Readonly<{ query: string }> }
    | { name: 'memory.upsert'; args: Readonly<{
        category: 'preference' | 'goal' | 'fact'; evidence: string;
        target: Readonly<{ id: string; version: number }> | null;
      }> }
    | { name: 'mcp.call'; args: Readonly<{ modelName: string; argumentsJson: string }> }
  )
>;

export type MemoryToolCall = Extract<ToolCall, { name: 'memory.search' | 'memory.upsert' }>;

// 只能由可信宿主按本次请求绑定；不能从 HTTP 输入、模型或资料中构造权限。
// 实现必须在手机原生端再次核对来源、授权代际及任务状态，并持久化去重。
export interface MemoryToolPort {
  readonly factsAllowed?: boolean;
  execute(call: MemoryToolCall, signal: AbortSignal): Promise<unknown>;
}

export type McpModelCall = Extract<ToolCall, { name: 'mcp.call' }>;
// 目录由可信宿主从当前批准快照生成；正文只是工具资料，不包含私密配置。
export interface McpModelPort {
  readonly catalog: readonly Readonly<{
    modelName: string; description: string; parameters: unknown; mode: 'read' | 'confirm';
  }>[];
  validate(call: McpModelCall): void;
  execute(call: McpModelCall, signal: AbortSignal): Promise<unknown>;
}

export type ModelDecision =
  | { kind: 'final'; text: string }
  | { kind: 'tools'; calls: readonly ToolCall[] };

export type ModelMessage =
  | { role: 'user'; text: string }
  | { role: 'assistant'; text: string }
  | { role: 'assistant'; calls: readonly ToolCall[] }
  | { role: 'tool'; callId: string; name: ToolCall['name']; data: unknown };

export type Usage = Readonly<{ inputTokens: number; outputTokens: number }>;
export type TokenLimits = Readonly<{ maxInputTokens: number; maxOutputTokens: number }>;

export interface ModelPort {
  // 生产适配器发送前必须校验输入上限、约束输出，并返回供应商实际用量。
  generate(
    request: {
      requestId: string;
      instructions: string;
      messages: readonly ModelMessage[];
      tools: readonly { name: ToolCall['name']; description: string; parameters: unknown }[];
      limits: TokenLimits;
    },
    signal: AbortSignal,
  ): Promise<{ decision: unknown; usage: unknown }>;
}

export type Settlement =
  | { kind: 'usage'; usage: Usage }
  | { kind: 'uncertain' }
  | { kind: 'not_dispatched' };

export interface BudgetPort {
  // 原子登记且拒绝重复requestId；费用只估算，不因超预算拒绝。没有生产内存替代实现。
  reserve(
    request: {
      taskId: string;
      requestId: string;
      limits: TokenLimits;
    },
    signal: AbortSignal,
  ): Promise<{ reservationId: string } | null>;
  // 必须幂等。uncertain 保留全部额度待对账；not_dispatched 才可释放。
  settle(reservationId: string, settlement: Settlement): Promise<void>;
}

export type StopReason =
  | 'invalid_task'
  | 'consent_required'
  | 'invalid_tool'
  | 'scope_denied'
  | 'invalid_model_output'
  | 'duplicate_tool'
  | 'tool_limit'
  | 'step_limit'
  | 'budget_denied'
  | 'budget_unavailable'
  | 'model_unavailable'
  | 'usage_invalid'
  | 'usage_limit'
  | 'cancelled'
  | 'deadline';

export type Action = Readonly<{
  kind: 'model_requested' | 'tool_completed';
  step: number;
  tool?: ToolCall['name'];
}>;

export type AgentResult = (
  | { status: 'completed'; answer: string; actions: readonly Action[]; sources?: readonly KnowledgeCitation[] }
  | {
      status: 'blocked' | 'failed' | 'cancelled' | 'timed_out';
      reason: StopReason;
      actions: readonly Action[];
    }) & { cost?: CostEstimate };

export class AgentError extends Error {
  readonly code: StopReason;

  constructor(code: StopReason) {
    super(code);
    this.code = code;
  }
}
