import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import type { BudgetDatabase, BudgetResult, BudgetStatement } from '../src/d1.ts';

// 只替代 D1 宿主：测试使用真实 SQLite 执行生产 SQL 和事务，不模拟预算结果。
export function openBudgetDatabase(path = ':memory:', now?: () => number) {
  const sqlite = new DatabaseSync(path);
  // 仅替换测试宿主时钟；全部 SQL 条件、写入和事务仍由 SQLite 执行。
  if (now)
    sqlite.function('strftime', { varargs: true }, (...args) => {
      const time = now();
      if (args[1] !== 'now') throw new Error('测试时钟仅接受 now');
      if (args[0] === '%s') return String(Math.floor(time / 1000));
      if (args[0] === '%f')
        return `${String(new Date(time).getUTCSeconds()).padStart(2, '0')}.${String(time % 1000).padStart(3, '0')}`;
      throw new Error('测试时钟不支持该格式');
    });
  sqlite.exec('PRAGMA foreign_keys = ON');
  for (const name of ['0001_agent_budget.sql', '0002_agent_device_tasks.sql']) {
    sqlite.exec(readFileSync(new URL(`../migrations/${name}`, import.meta.url), 'utf8'));
  }
  // 生产 D1 迁移执行器只运行一次；测试重开文件库时同样不能重复 ALTER。
  if (
    !sqlite
      .prepare('PRAGMA table_info(agent_budget_reservations)')
      .all()
      .some((row) => row.name === 'execution_hash')
  ) {
    sqlite.exec('BEGIN IMMEDIATE');
    try {
      sqlite.exec(
        readFileSync(new URL('../migrations/0003_agent_execution.sql', import.meta.url), 'utf8'),
      );
      sqlite.exec('COMMIT');
    } catch (error) {
      sqlite.exec('ROLLBACK');
      throw error;
    }
  }
  sqlite.exec(readFileSync(new URL('../migrations/0004_agent_maintenance.sql', import.meta.url), 'utf8'));
  sqlite.exec(readFileSync(new URL('../migrations/0007_agent_request_purges.sql', import.meta.url), 'utf8'));
  class Statement implements BudgetStatement {
    readonly sql: string;
    readonly values: (string | number | null)[];
    constructor(sql: string, values: (string | number | null)[] = []) {
      this.sql = sql;
      this.values = values;
    }
    bind(...values: (string | number | null)[]) {
      return new Statement(this.sql, values);
    }
    execute(): BudgetResult {
      const prepared = sqlite.prepare(this.sql);
      if (prepared.columns().length) {
        return { results: prepared.all(...this.values), meta: { changes: 0 } };
      }
      return { results: [], meta: { changes: Number(prepared.run(...this.values).changes) } };
    }
    async all() {
      return this.execute();
    }
    async run() {
      return this.execute();
    }
  }
  const db: BudgetDatabase = {
    prepare: (sql) => new Statement(sql),
    async batch(statements) {
      sqlite.exec('BEGIN IMMEDIATE');
      try {
        const results = statements.map((statement) => {
          if (!(statement instanceof Statement)) throw new Error('测试语句不属于此连接');
          return statement.execute();
        });
        sqlite.exec('COMMIT');
        return results;
      } catch (error) {
        sqlite.exec('ROLLBACK');
        throw error;
      }
    },
  };
  return { db, sqlite, close: () => sqlite.close() };
}
