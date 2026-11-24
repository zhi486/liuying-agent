// 一次性编译探针：排除 Node/DOM 环境，仅使用官方 Workers 类型。
import type { BudgetDatabase } from '../../src/d1.ts';
import type { WaitUntil } from '../../src/background.ts';
import { createAgentService } from '../../src/agent-service.ts';
import { recoverFreeExecutions } from '../../src/execution-ledger.ts';

declare const db: D1Database;
declare const ctx: ExecutionContext;
declare const serviceOptions: Omit<Parameters<typeof createAgentService>[0], 'db'>;

const database: BudgetDatabase = db;
const waitUntil: WaitUntil = (pending) => ctx.waitUntil(pending);
const service = createAgentService({ ...serviceOptions, db });
const recovery: Parameters<typeof recoverFreeExecutions>[0] = { db };
void [database, waitUntil, service, recovery];
