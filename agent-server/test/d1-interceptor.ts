import type { BudgetDatabase, BudgetStatement } from '../src/d1.ts';

// 仅在真实 SQL 宿主边界插入等待/故障，业务查询、条件和事务仍原样执行。
export function interceptD1(
  db: BudgetDatabase,
  hook: (sql: string, phase: 'before' | 'after') => Promise<void>,
): BudgetDatabase {
  const originals = new WeakMap<BudgetStatement, { statement: BudgetStatement; sql: string }>();
  function wrap(statement: BudgetStatement, sql: string): BudgetStatement {
    const wrapped: BudgetStatement = {
      bind: (...values) => wrap(statement.bind(...values), sql),
      async all() {
        await hook(sql, 'before');
        const result = await statement.all();
        await hook(sql, 'after');
        return result;
      },
      async run() {
        await hook(sql, 'before');
        const result = await statement.run();
        await hook(sql, 'after');
        return result;
      },
    };
    originals.set(wrapped, { statement, sql });
    return wrapped;
  }
  return {
    prepare: (sql) => wrap(db.prepare(sql), sql),
    async batch(statements) {
      const entries = statements.map((statement) => {
        const entry = originals.get(statement);
        if (!entry) throw new Error('测试语句不属于拦截器');
        return entry;
      });
      const sql = entries.map((entry) => entry.sql).join('\n');
      await hook(sql, 'before');
      const result = await db.batch(entries.map((entry) => entry.statement));
      await hook(sql, 'after');
      return result;
    },
  };
}
