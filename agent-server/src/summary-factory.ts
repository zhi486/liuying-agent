import type { BudgetDatabase } from './d1.ts';
import { createSummaryModel } from './summary-model.ts';
import { createSummaryService, type SummaryStore } from './summary-service.ts';
import { createSummaryUsage } from './summary-usage.ts';

/** 未挂载；调用方提供专用持久账本、可信计数器及费率，不创建或迁移数据库。 */
export function createSummaryApplication(options: {
  db: BudgetDatabase;
  store: SummaryStore;
  model: Parameters<typeof createSummaryModel>[0];
  rate: Readonly<{ input: number; output: number }>;
}) {
  const rate = Object.freeze({ ...options.rate });
  if (![rate.input, rate.output].every(n => Number.isSafeInteger(n) && n > 0))
    throw new Error('摘要费率无效');
  const usage = createSummaryUsage(options.db);
  const handle = createSummaryService({
    store: options.store,
    authenticate: usage.authenticate,
    model: createSummaryModel(options.model),
    accounting: { begin: (principal, id, hash) => usage.begin(principal, id, hash, rate), record: usage.record },
  });
  return { handle, estimate: usage.estimate };
}
