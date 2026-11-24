import type { TestContext } from 'node:test';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createDeviceAdmin } from '../src/device-auth.ts';
import { createCloudTasks } from '../src/cloud-tasks.ts';

export const start = Date.UTC(2026, 8, 15);
export const rate = {
  version: 'fixture',
  model: 'deepseek-flash' as const,
  inputMicrosPerMillion: 300000,
  outputMicrosPerMillion: 1200000,
  checkedAtMillis: start,
  expiresAtMillis: start + 86400000,
};
export const input = () => ({
  clientRequestId: crypto.randomUUID(),
  localTaskId: crypto.randomUUID(),
  prompt: '总结这次跑步，注明来源',
  consent: { cloudProcessing: true, runSummaries: true },
  summaries: [
    {
      runId: 'run_1',
      distanceMeters: 4000,
      durationSeconds: 1440,
      averagePaceSecPerKm: 360,
      averageHeartRate: 150,
    },
  ],
});
export async function fixture(t: TestContext, path = ':memory:') {
  let time = start;
  const now = () => time;
  const database = openBudgetDatabase(path, now);
  let closed = false;
  const close = () => {
    if (!closed) {
      database.close();
      closed = true;
    }
  };
  t.after(close);
  database.sqlite.exec(
    "INSERT OR IGNORE INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES ('owner',1800000)",
  );
  const admin = createDeviceAdmin({ db: database.db, now });
  const device = await admin.issue('owner');
  const header = `Bearer ${device.token}`;
  const tasks = createCloudTasks({ db: database.db, now });
  const accepted = await tasks.accept(header, input());
  const execution = await tasks.claim(header, accepted.task.id);
  return {
    database: { ...database, close },
    admin,
    device,
    header,
    tasks,
    execution,
    now,
    setTime: (value: number) => {
      time = value;
    },
  };
}
