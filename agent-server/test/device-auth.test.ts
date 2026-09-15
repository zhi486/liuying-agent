import assert from 'node:assert/strict';
import { test } from 'node:test';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createDeviceAuth, createDeviceAdmin } from '../src/device-auth.ts';

const timestamp = Date.UTC(2026, 8, 14);
const createAdmin = async (
  database: ReturnType<typeof openBudgetDatabase>,
  now = () => timestamp,
) => createDeviceAdmin({ db: database.db, now });
const addAccount = (database: ReturnType<typeof openBudgetDatabase>, owner = 'owner_1') =>
  database.sqlite
    .prepare('INSERT INTO agent_budget_accounts (owner_id, monthly_limit_micros) VALUES (?, 13440)')
    .run(owner);

test('没有登记的设备不能获得身份', async (t) => {
  const database = openBudgetDatabase();
  t.after(database.close);
  const auth = createDeviceAuth({ db: database.db });
  const result = await auth
    .authenticate('Bearer lyd_' + '1'.repeat(64))
    .catch((error: Error) => error.message);
  assert.equal(result, 'unauthenticated');
});

test('认证查询以数据库执行时刻核验到期，不能沿用等待前的时钟', async (t) => {
  let time = timestamp;
  const database = openBudgetDatabase(':memory:', () => time);
  t.after(database.close);
  addAccount(database);
  const device = await createDeviceAdmin({ db: database.db, now: () => time }).issue('owner_1');
  const auth = createDeviceAuth({ db: database.db, now: () => device.expiresAtMillis - 1 });
  time = device.expiresAtMillis + 1;
  await assert.rejects(auth.authenticate(`Bearer ${device.token}`), /unauthenticated/);
});

test('可信发放产生两个独立设备但归同一账户，数据库只保存凭据摘要', async (t) => {
  const database = openBudgetDatabase(':memory:', () => timestamp);
  t.after(database.close);
  addAccount(database);
  const admin = await createAdmin(database);
  const first = await admin.issue('owner_1');
  assert.ok(first, '必须产生可认证的设备凭据');
  const second = await admin.issue('owner_1');
  assert.notEqual(first.token, second.token);
  assert.match(first.token, /^lyd_[0-9a-f]{64}$/);
  const auth = createDeviceAuth({ db: database.db, now: () => timestamp });
  assert.deepEqual(await auth.authenticate(`Bearer ${first.token}`), {
    ownerId: 'owner_1',
    deviceId: first.deviceId,
  });
  assert.deepEqual(await auth.authenticate(`Bearer ${second.token}`), {
    ownerId: 'owner_1',
    deviceId: second.deviceId,
  });
  assert.equal(first.expiresAtMillis, timestamp + 7776000000);
  const rows = database.sqlite.prepare('SELECT * FROM agent_devices').all();
  assert.equal(JSON.stringify(rows).includes(first.token), false);
  assert.match(String(rows[0]!.token_hash), /^[0-9a-f]{64}$/);
  assert.equal(
    database.sqlite.prepare('SELECT COUNT(*) AS count FROM agent_budget_accounts').get()!.count,
    1,
  );
});

test('未预置账户不能发放，错误与畸形凭据不泄露秘密', async (t) => {
  const database = openBudgetDatabase(':memory:', () => timestamp);
  t.after(database.close);
  const admin = await createAdmin(database);
  assert.ok(admin);
  await assert.rejects(admin.issue('unknown'), /^Error: not_found$/);
  addAccount(database);
  const device = await admin.issue('owner_1');
  const auth = createDeviceAuth({ db: database.db, now: () => timestamp });
  for (const header of [
    null,
    {},
    device.token,
    `bearer ${device.token}`,
    `Bearer ${device.token.toUpperCase()}`,
    `Bearer ${device.token} `,
    `Bearer ${device.token}\n`,
    'Bearer lyd_' + 'f'.repeat(64),
  ]) {
    await assert.rejects(auth.authenticate(header), /^Error: unauthenticated$/);
  }
});

test('90 天到期与提前撤销拒绝后续认证，换机不改变另一凭据', async (t) => {
  let now = timestamp;
  const database = openBudgetDatabase(':memory:', () => now);
  t.after(database.close);
  addAccount(database);
  const admin = await createAdmin(database, () => now);
  assert.ok(admin);
  const first = await admin.issue('owner_1');
  const second = await admin.issue('owner_1');
  const auth = createDeviceAuth({ db: database.db, now: () => now });
  await admin.revoke('owner_1', first.deviceId);
  await admin.revoke('owner_1', first.deviceId);
  await assert.rejects(auth.authenticate(`Bearer ${first.token}`), /unauthenticated/);
  await assert.rejects(admin.revoke('other', second.deviceId), /not_found/);
  now = second.expiresAtMillis - 1;
  assert.ok(await auth.authenticate(`Bearer ${second.token}`));
  now++;
  await assert.rejects(auth.authenticate(`Bearer ${second.token}`), /unauthenticated/);
});

test('数据库失败不使用旧授权，时间异常不创建凭据', async () => {
  const database = openBudgetDatabase(':memory:', () => timestamp);
  addAccount(database);
  const admin = await createAdmin(database);
  assert.ok(admin);
  const device = await admin.issue('owner_1');
  const auth = createDeviceAuth({ db: database.db, now: () => timestamp });
  assert.ok(await auth.authenticate(`Bearer ${device.token}`));
  const invalid = await createAdmin(database, () => NaN);
  await assert.rejects(invalid.issue('owner_1'), /unavailable/);
  database.close();
  await assert.rejects(auth.authenticate(`Bearer ${device.token}`), /^Error: unavailable$/);
});
