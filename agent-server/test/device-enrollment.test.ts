import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { mkdir, mkdtemp, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { openBudgetDatabase } from './sqlite-d1.ts';
import { createDeviceAuth, createDeviceAdmin } from '../src/device-auth.ts';
const { prepareEnrollment, enrollmentSql, saveEnrollment } =
  await import(new URL('../tools/device-enrollment.mjs', import.meta.url).href);

const now = Date.UTC(2026, 8, 18);
const target = {
  accountId: '1'.repeat(32), databaseId: '11111111-1111-4111-8111-111111111111',
  origin: 'https://liuying-agent.fixture.workers.dev', ownerId: 'owner_fixture',
};

test('设备草稿可由生产认证识别，SQL 不含原始秘密', async (t) => {
  const database = openBudgetDatabase(':memory:', () => now);
  t.after(database.close);
  const draft = await prepareEnrollment(target, now);
  const sql = await enrollmentSql(draft, target, now);
  assert.equal(sql.register.includes(draft.token), false);
  assert.equal(sql.verify.includes(draft.token), false);
  assert.match(draft.token, /^lyd_[a-f0-9]{64}$/);
  database.sqlite.exec(sql.register);
  assert.deepEqual(await createDeviceAuth({ db: database.db, now: () => now })
    .authenticate(`Bearer ${draft.token}`), { ownerId: target.ownerId, deviceId: draft.deviceId });
  const row = database.sqlite.prepare(sql.verify).get()!;
  assert.equal(row.registered, 1);
  assert.equal(row.monthly_limit_micros, 1800000);
  assert.equal(JSON.stringify(row).includes(draft.token), false);
  assert.equal('token_hash' in row, false);
  assert.equal(draft.expiresAtMillis, now + 7776000000);
});

test('重复登记同一草稿不重复创建设备，不修改既有预算', async (t) => {
  const database = openBudgetDatabase(':memory:', () => now);
  t.after(database.close);
  database.sqlite.prepare('INSERT INTO agent_budget_accounts VALUES (?, 42000, 0)').run(target.ownerId);
  const draft = await prepareEnrollment(target, now);
  const sql = await enrollmentSql(draft, target, now);
  database.sqlite.exec(sql.register);
  database.sqlite.exec(sql.register);
  assert.equal(database.sqlite.prepare('SELECT count(*) AS n FROM agent_devices').get()!.n, 1);
  assert.equal(database.sqlite.prepare(sql.verify).get()!.monthly_limit_micros, 42000);
});

test('已撤销设备不会复活，已熔断账户不会发新凭据', async (t) => {
  const database = openBudgetDatabase(':memory:', () => now);
  t.after(database.close);
  const draft = await prepareEnrollment(target, now);
  const sql = await enrollmentSql(draft, target, now);
  database.sqlite.exec(sql.register);
  await createDeviceAdmin({ db: database.db, now: () => now }).revoke(target.ownerId, draft.deviceId);
  database.sqlite.exec(sql.register);
  assert.equal(database.sqlite.prepare(sql.verify).get()!.registered, 0);
  database.sqlite.prepare('UPDATE agent_budget_accounts SET halted=1').run();
  const next = await prepareEnrollment(target, now);
  const nextSql = await enrollmentSql(next, target, now);
  database.sqlite.exec(nextSql.register);
  assert.equal(database.sqlite.prepare(nextSql.verify).get(), undefined);
  assert.equal(database.sqlite.prepare('SELECT halted FROM agent_budget_accounts').get()!.halted, 1);
});

test('设备 ID 冲突不能覆盖凭据或冒充登记成功', async (t) => {
  const database = openBudgetDatabase(':memory:', () => now);
  t.after(database.close);
  const draft = await prepareEnrollment(target, now);
  database.sqlite.exec((await enrollmentSql(draft, target, now)).register);
  const changed = { ...draft, token: 'lyd_' + 'a'.repeat(64) };
  const sql = await enrollmentSql(changed, target, now);
  database.sqlite.exec(sql.register);
  assert.equal(database.sqlite.prepare(sql.verify).get()!.registered, 0);
  assert.ok(await createDeviceAuth({ db: database.db, now: () => now }).authenticate(`Bearer ${draft.token}`));
});

test('草稿必须绑定显式正式目标，拒绝换库和额外字段', async () => {
  const draft = await prepareEnrollment(target, now);
  for (const changed of [
    { ...target, accountId: '2'.repeat(32) },
    { ...target, databaseId: '22222222-2222-4222-8222-222222222222' },
    { ...target, origin: 'https://liuying-agent.other.workers.dev' },
    { ...target, ownerId: 'another' },
  ]) await assert.rejects(enrollmentSql(draft, changed, now), /^Error: 设备配置无效$/);
  await assert.rejects(enrollmentSql({ ...draft, extra: true }, target, now));
});

test('摘要计算期间修改传入对象不能换 owner 或注入 SQL', async () => {
  const draft = await prepareEnrollment(target, now);
  const pending = enrollmentSql(draft, target, now);
  draft.target.ownerId = "other'; DROP TABLE agent_devices;--";
  draft.deviceId = "invalid';--";
  draft.createdAtMillis = "0; DROP TABLE agent_devices;--";
  draft.expiresAtMillis = 0;
  const sql = await pending;
  assert.ok(sql.register.includes("'owner_fixture'"));
  assert.equal(sql.register.includes('DROP TABLE'), false);
  assert.equal(sql.register.includes('invalid'), false);
});

test('拒绝非法标识、非正式入口、占位数据库与注入内容', async () => {
  for (const change of [
    { ownerId: "owner'; DROP TABLE agent_devices;--" }, { ownerId: '中文' },
    { accountId: '1'.repeat(31) }, { accountId: '1'.repeat(32) + '\n' },
    { databaseId: '00000000-0000-4000-8000-000000000000' },
    ...['http://liuying-agent.fixture.workers.dev', 'https://liuying-agent.fixture.workers.dev/',
      'https://liuying-agent.fixture.workers.dev:443', 'https://user@liuying-agent.fixture.workers.dev',
      'https://liuying-agent-entry-20260917.fixture.workers.dev', 'https://example.com',
      'https://liuying-agent.fixture.workers.dev\n'].map(origin => ({ origin })),
  ]) await assert.rejects(prepareEnrollment({ ...target, ...change }, now));
});

test('本地和 SQL 执行时均拒绝过期，不能重新延长 90 天', async (t) => {
  const draft = await prepareEnrollment(target, now);
  await assert.rejects(enrollmentSql(draft, target, now - 1));
  await assert.rejects(enrollmentSql(draft, target, draft.expiresAtMillis));
  await assert.rejects(enrollmentSql({ ...draft, expiresAtMillis: draft.expiresAtMillis + 1 }, target, now));
  const sql = await enrollmentSql(draft, target, now);
  const database = openBudgetDatabase(':memory:', () => draft.expiresAtMillis);
  t.after(database.close);
  database.sqlite.exec(sql.register);
  assert.equal(database.sqlite.prepare('SELECT count(*) AS n FROM agent_devices').get()!.n, 0);
});

test('CLI 默认不执行，不回显非法参数，也不存在远程 apply 动作', () => {
  const tool = fileURLToPath(new URL('../tools/device-enrollment.mjs', import.meta.url));
  const canary = 'SYNTHETIC_SECRET_MUST_NOT_APPEAR';
  for (const args of [[], ['apply'], ['prepare', '--token', canary]]) {
    const result = spawnSync(process.execPath, ['--experimental-strip-types', tool, ...args], {
      encoding: 'utf8', timeout: 10000,
    });
    assert.equal(result.status, 2);
    assert.equal((result.stdout + result.stderr).includes(canary), false);
    assert.match(result.stderr, /参数无效/);
  }
});

test('私密文件先落独立目录，同一草稿不覆盖；Windows 只保留当前用户显式权限', async () => {
  const generated = fileURLToPath(new URL('../.generated/', import.meta.url));
  await mkdir(generated, { recursive: true });
  const base = await mkdtemp(join(generated, 'enrollment-test-'));
  const draft = await prepareEnrollment(target);
  const original = structuredClone(draft);
  const pending = saveEnrollment(draft, target, base);
  draft.token = 'lyd_' + 'b'.repeat(64);
  draft.target.ownerId = 'changed_during_save';
  const directory = await pending;
  const before = await readFile(join(directory, 'phone-private.json'), 'utf8');
  assert.deepEqual(JSON.parse(before), original);
  assert.equal((await readFile(join(directory, 'register.sql'), 'utf8')).includes(original.token), false);
  await assert.rejects(saveEnrollment(original, target, base));
  assert.equal(await readFile(join(directory, 'phone-private.json'), 'utf8'), before);
  if (process.platform === 'win32') {
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      '$ErrorActionPreference="Stop"; $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; ' +
      '$acl=[System.IO.Directory]::GetAccessControl($env:ENROLLMENT_TEST_DIRECTORY); ' +
      '$rules=@($acl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier])); ' +
      'if (-not $acl.AreAccessRulesProtected -or $rules.Count -ne 1 -or ' +
      '$rules[0].IdentityReference.Value -ne $sid -or $rules[0].IsInherited -or ' +
      '$rules[0].AccessControlType -ne "Allow" -or ' +
      '$rules[0].FileSystemRights -ne [System.Security.AccessControl.FileSystemRights]::FullControl) {exit 1}; ' +
      '$file=[System.IO.Path]::Combine($env:ENROLLMENT_TEST_DIRECTORY,"phone-private.json"); ' +
      '$fileAcl=[System.IO.File]::GetAccessControl($file); ' +
      '$fileRules=@($fileAcl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier])); ' +
      'if ($fileRules.Count -ne 1 -or $fileRules[0].IdentityReference.Value -ne $sid -or ' +
      '$fileRules[0].AccessControlType -ne "Allow" -or ' +
      '$fileRules[0].FileSystemRights -ne [System.Security.AccessControl.FileSystemRights]::FullControl) {exit 1}'],
    { encoding: 'utf8', timeout: 10000, windowsHide: true,
      env: { ...process.env, ENROLLMENT_TEST_DIRECTORY: directory } });
    assert.equal(result.status, 0, `Windows 草稿目录权限必须隔离：${result.stderr}`);
  }
  // 虚构测试文件保留于 Git 忽略目录；不擅自删除用户文件。
});
