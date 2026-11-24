import type { BudgetDatabase } from './d1.ts';
import { createCloudTasks } from './cloud-tasks.ts';
import { recoverFreeExecutions } from './execution-ledger.ts';

// 只做状态和账务维护，不读取模型配置、不恢复模型执行。单次最多 41 条 SQL。
export async function runMaintenance({ db, now = Date.now }: {
  db: BudgetDatabase;
  now?: () => number;
}) {
  try {
    // 先清理过期内容，不能让长期未知费用阻止本轮隐私清理。
    await createCloudTasks({ db, now }).purge();
    const state = (await db.prepare(
      "SELECT cursor, revision FROM agent_maintenance_state WHERE id = 'execution-recovery'",
    ).all()).results[0];
    if (!state || typeof state.cursor !== 'string' || state.cursor.length > 80 ||
        /[^a-zA-Z0-9_-]/.test(state.cursor) || typeof state.revision !== 'number' ||
        !Number.isSafeInteger(state.revision) || state.revision < 0 || state.revision >= Number.MAX_SAFE_INTEGER)
      throw new Error();
    const result = await recoverFreeExecutions({ db, now, cursor: state.cursor });
    const saved = await db.prepare(
      `UPDATE agent_maintenance_state SET cursor = ?, revision = revision + 1
       WHERE id = 'execution-recovery' AND revision = ?`,
    ).bind(result.nextCursor ?? '', state.revision).run();
    // 重叠调用可能已提交较新进度；本次只报告，不用旧游标覆盖它。
    return { processed: result.processed, receiptsApplied: result.receiptsApplied,
      closed: result.closed, uncertain: result.uncertain, cursorSaved: saved.meta.changes === 1 };
  } catch {
    throw new Error('maintenance_unavailable');
  }
}
