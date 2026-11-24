// 仅编译，不运行；验证真实 Workers 绑定能满足后端端口。
import type { BudgetDatabase } from '../src/d1.ts';
import type { WaitUntil } from '../src/background.ts';
import { createAgentService } from '../src/agent-service.ts';
import { recoverFreeExecutions } from '../src/execution-ledger.ts';
import { createMcpStore } from '../src/mcp-store.ts';

declare const db: D1Database;
declare const ctx: ExecutionContext;
declare const serviceOptions: Omit<Parameters<typeof createAgentService>[0], 'db'>;

const database: BudgetDatabase = db;
const waitUntil: WaitUntil = (pending) => ctx.waitUntil(pending);
const service = createAgentService({ ...serviceOptions, db });
const recovery: Parameters<typeof recoverFreeExecutions>[0] = { db };
declare const mcpOptions: Omit<Parameters<typeof createMcpStore>[0], 'db'>;
const mcpStore = createMcpStore({ ...mcpOptions, db });
void [database, waitUntil, service, recovery, mcpStore];

// 官方 Workers 类型自带 process:any，不能用它检测 Node 类型混入。
// @ts-expect-error 误引入完整 Node 类型时，本检查应失败。
type ForbiddenNodeProcess = NodeJS.Process;
// @ts-expect-error Worker 不是浏览器页面：误引入 DOM 类型时，本检查应失败。
void document;

// HTTP 客户端的类型兼容项不能意外开放 SDK 的 stdio Buffer 参数。
// @ts-expect-error 本 Workers 工程不支持 stdio 传输。
const forbiddenMcpStdioChunk: Buffer = new Uint8Array();
void forbiddenMcpStdioChunk;
