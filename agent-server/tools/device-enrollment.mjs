// 仅生成本地登记草稿；不联网、不读取 OAuth/供应商密钥、不执行远程 SQL。
import { mkdir, lstat, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { randomSecret, digest } from '../src/device-auth.ts';
import { identifier, uuid, timestamp, DATABASE_TIME_SQL } from '../src/cloud-task-contracts.ts';

const lifetime = 7776000000;
const targetKeys = ['accountId', 'databaseId', 'origin', 'ownerId'];
const draftKeys = ['version', 'target', 'deviceId', 'token', 'createdAtMillis', 'expiresAtMillis'];
const invalid = () => { throw new Error('设备配置无效'); };
function exact(value, keys) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).length !== keys.length || !keys.every(key => Object.hasOwn(value, key))) invalid();
}
function validateTarget(target) {
  exact(target, targetKeys);
  if (typeof target.accountId !== 'string' || target.accountId.length !== 32 ||
      !/^[a-f0-9]+$/.test(target.accountId)) invalid();
  uuid(target.databaseId);
  if (target.databaseId === '00000000-0000-4000-8000-000000000000') invalid();
  identifier(target.ownerId);
  if (typeof target.origin !== 'string' || target.origin.length > 253 ||
      !/^https:\/\/liuying-agent\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.workers\.dev$/.test(target.origin) ||
      new URL(target.origin).origin !== target.origin) invalid();
}

export async function prepareEnrollment(target, now = Date.now()) {
  try {
    validateTarget(target);
    timestamp(() => now);
    return { version: 'device_enrollment_v1', target: { ...target }, deviceId: crypto.randomUUID(),
      token: 'lyd_' + randomSecret(), createdAtMillis: now, expiresAtMillis: now + lifetime };
  } catch { invalid(); }
}

export async function enrollmentSql(draft, expectedTarget, now = Date.now()) {
  try {
    // 首个 await 前复制，摘要计算期间调用方不能换目标或注入 SQL。
    draft = structuredClone(draft);
    expectedTarget = structuredClone(expectedTarget);
    exact(draft, draftKeys);
    validateTarget(draft.target);
    validateTarget(expectedTarget);
    if (draft.version !== 'device_enrollment_v1' ||
        !targetKeys.every(key => draft.target[key] === expectedTarget[key])) invalid();
    uuid(draft.deviceId);
    timestamp(() => draft.createdAtMillis);
    timestamp(() => now);
    if (draft.expiresAtMillis !== draft.createdAtMillis + lifetime ||
        now < draft.createdAtMillis || now >= draft.expiresAtMillis ||
        typeof draft.token !== 'string' || draft.token.length !== 68 ||
        !/^lyd_[a-f0-9]{64}$/.test(draft.token)) invalid();
    const hash = await digest(draft.token);
    // 上面只接受 ASCII 标识与整数；SQL 从不包含原始 Bearer。
    const owner = `'${draft.target.ownerId}'`;
    const device = `'${draft.deviceId}'`;
    const created = draft.createdAtMillis;
    const expires = draft.expiresAtMillis;
    const validTime = `${DATABASE_TIME_SQL} >= ${created} AND ${DATABASE_TIME_SQL} < ${expires}`;
    return {
      register: `-- 仅限账户 ${draft.target.accountId} 的数据库 ${draft.target.databaseId}。\n` +
        `-- 重复执行只复用原记录，不修改预算、到期时间、撤销状态或既有凭据。\n` +
        `INSERT INTO agent_budget_accounts (owner_id, monthly_limit_micros)\n` +
        `SELECT ${owner}, 1800000 WHERE ${validTime} ON CONFLICT(owner_id) DO NOTHING;\n` +
        `INSERT INTO agent_devices (id, owner_id, token_hash, created_at, expires_at)\n` +
        `SELECT ${device}, owner_id, '${hash}', ${created}, ${expires}\n` +
        `FROM agent_budget_accounts WHERE owner_id = ${owner} AND halted = 0 AND ${validTime}\n` +
        `ON CONFLICT(id) DO NOTHING;\n`,
      verify: `SELECT d.id, d.owner_id, d.expires_at, a.monthly_limit_micros,\n` +
        `CASE WHEN d.token_hash = '${hash}' AND d.created_at = ${created} AND d.expires_at = ${expires}\n` +
        `AND d.revoked_at IS NULL AND a.halted = 0 AND ${validTime} THEN 1 ELSE 0 END AS registered\n` +
        `FROM agent_devices d JOIN agent_budget_accounts a ON a.owner_id = d.owner_id\n` +
        `WHERE d.id = ${device} AND d.owner_id = ${owner};\n`,
    };
  } catch { invalid(); }
}

export async function saveEnrollment(draft, expectedTarget,
  base = fileURLToPath(new URL('../secrets/', import.meta.url))) {
  // 私密交付文件和 SQL 必须来自同一份快照。
  draft = structuredClone(draft);
  expectedTarget = structuredClone(expectedTarget);
  const sql = await enrollmentSql(draft, expectedTarget);
  await mkdir(base, { recursive: true, mode: 0o700 });
  const parent = await lstat(base);
  if (!parent.isDirectory() || parent.isSymbolicLink()) invalid();
  const directory = join(base, `device-${draft.deviceId}`);
  // 必须是新目录；不覆盖或自动清理已有秘密。
  await mkdir(directory, { mode: 0o700 });
  if (process.platform === 'win32') {
    const options = { encoding: 'utf8', windowsHide: true, timeout: 10000, stdio: ['ignore', 'pipe', 'pipe'] };
    const identity = execFileSync('whoami.exe', ['/user', '/fo', 'csv', '/nh'], options);
    const sid = identity.match(/\bS-1-(?:\d+-)+\d+\b/)?.[0];
    if (!sid) invalid();
    // 仅修改新建草稿目录的 ACL；权限设置失败前不写秘密，不改仓库/父目录权限。
    execFileSync('icacls.exe', [directory, '/grant:r', `*${sid}:(OI)(CI)F`], options);
    execFileSync('icacls.exe', [directory, '/inheritance:r'], options);
  }
  const options = { flag: 'wx', mode: 0o600 };
  await writeFile(join(directory, 'phone-private.json'), JSON.stringify(draft, null, 2) + '\n', options);
  await writeFile(join(directory, 'register.sql'), sql.register, options);
  await writeFile(join(directory, 'verify.sql'), sql.verify, options);
  return directory;
}

async function main(args) {
  if (args.length === 1 && args[0] === '--help') {
    process.stdout.write('用法：prepare --account <账户ID> --database <正式D1 UUID> --origin <正式HTTPS地址> --owner <账户标识>\n' +
      '仅生成 secrets/ 下的新私密草稿。不会部署、注册设备、执行 SQL 或调用模型。不要传入 API 密钥。\n');
    return;
  }
  const flags = ['--account', '--database', '--origin', '--owner'];
  if (args.length !== 9 || args[0] !== 'prepare' ||
      flags.some((flag, index) => args[index * 2 + 1] !== flag)) {
    process.stderr.write('参数无效；请使用 --help。未执行云操作。\n');
    process.exitCode = 2;
    return;
  }
  try {
    const target = { accountId: args[2], databaseId: args[4], origin: args[6], ownerId: args[8] };
    const draft = await prepareEnrollment(target);
    const directory = await saveEnrollment(draft, target);
    process.stdout.write(`本地设备草稿已保存：${directory}\n尚未写入云端，凭据尚不可用。请勿把 phone-private.json 粘贴到聊天或日志。\n`);
  } catch {
    process.stderr.write('设备草稿准备失败；未执行云操作。若已生成部分本地文件，请保留并核查，不要直接重建身份。\n');
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main(process.argv.slice(2));
}
