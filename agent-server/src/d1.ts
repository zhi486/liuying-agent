// 仅声明预算模块使用的 D1 子集；生产环境直接注入已绑定的数据库。
export interface BudgetResult {
  results: Record<string, unknown>[];
  meta: { changes: number };
}

export interface BudgetStatement {
  bind(...values: (string | number | null)[]): BudgetStatement;
  all(): Promise<BudgetResult>;
  run(): Promise<BudgetResult>;
}

export interface BudgetDatabase {
  prepare(sql: string): BudgetStatement;
  batch(statements: BudgetStatement[]): Promise<BudgetResult[]>;
}
