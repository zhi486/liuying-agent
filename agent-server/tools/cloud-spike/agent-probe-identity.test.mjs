import assert from 'node:assert/strict';
import { test } from 'node:test';
import { agentProbe } from './agent-probe.mjs';
import { createDeviceAdmin } from '../../src/device-auth.ts';
import { openBudgetDatabase } from '../../test/sqlite-d1.ts';

test('预置身份不重复开户，断连钩子通知运行中任务但入口等到完整结算', async t => {
  const database = openBudgetDatabase();
  t.after(() => database.close());
  const ownerId = 'spike_pre_enrolled';
  await database.db.prepare('INSERT INTO agent_budget_accounts(owner_id,monthly_limit_micros) VALUES (?,1800000)')
    .bind(ownerId).run();
  const device = await createDeviceAdmin({ db:database.db }).issue(ownerId);
  let entered;
  const pending = [];
  const response = await agentProbe(database.db, 'disconnect', { waitUntil:p=>pending.push(p) }, {
    identity:{ ownerId, token:device.token }, awaitCompletion:true,
    onEntered:async value => {
      entered = value;
      assert.equal(database.sqlite.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?').get(value.taskId).status, 'running');
    },
  });
  await Promise.all(pending);
  const result = await response.json();
  assert.equal(result.ownerId, ownerId);
  assert.equal(result.taskId, entered.taskId);
  assert.equal(entered.transportEntered, true);
  assert.equal(database.sqlite.prepare('SELECT count(*) AS n FROM agent_budget_accounts').get().n, 1);
  assert.equal(database.sqlite.prepare('SELECT status FROM agent_cloud_tasks WHERE id=?').get(result.taskId).status, 'completed');
});
