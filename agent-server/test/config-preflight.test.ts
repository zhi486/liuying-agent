import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { test } from 'node:test';
import { inspectRuntimeConfig, readRuntimeConfig } from '../src/runtime-config.ts';

const rate = { version: 'fixture', model: 'deepseek-flash', inputMicrosPerMillion: 300000,
  outputMicrosPerMillion: 1200000, checkedAtMillis: 1000, expiresAtMillis: 86401000 };
const config = (changes = {}) => ({ DEEPSEEK_API_KEY: 'fixture-only', RATE_CARD_JSON: JSON.stringify(rate), ...changes });

test('预检仍提示费率质量，生产只因密钥无效拒绝而不因费用信息停用', () => {
  const variants = [config(), config({ DEEPSEEK_API_KEY: 'a'.repeat(512) }),
    ...[undefined, null, '', ' ', 'key\n', '中文', 123, 'a'.repeat(513)].map(DEEPSEEK_API_KEY => config({ DEEPSEEK_API_KEY })),
    ...[undefined, null, '', '{}', '[]', '{', rate,
      JSON.stringify({ ...rate, extra: true }), JSON.stringify({ ...rate, version: 'bad\n' }),
      JSON.stringify({ ...rate, model: 'other' }), JSON.stringify({ ...rate, inputMicrosPerMillion: 0 }),
      JSON.stringify({ ...rate, outputMicrosPerMillion: -1 }), JSON.stringify({ ...rate, inputMicrosPerMillion: 0.5 }),
      JSON.stringify({ ...rate, outputMicrosPerMillion: Number.MAX_SAFE_INTEGER + 1 }),
      JSON.stringify({ ...rate, expiresAtMillis: 86401001 }), ' '.repeat(2049),
    ].map(RATE_CARD_JSON => config({ RATE_CARD_JSON }))];
  for (const value of variants) {
    let accepted = false;
    try { readRuntimeConfig(value, 1500); accepted = true; } catch (error) {
      assert.equal((error as Error).message, 'unavailable');
    }
    const report = inspectRuntimeConfig(value, 1500);
    assert.equal(report.apiKey === 'format_valid', accepted);
    assert.equal(report.localChecksPassed, report.apiKey === 'format_valid' && report.rateCard === 'valid');
    assert.deepEqual(Object.keys(report).sort(), ['apiKey', 'rateCard', 'localChecksPassed'].sort());
  }
});

test('费率checked可包含、expires排除，最长24小时，结构错误优先且不修改快照', () => {
  for (const [now, expected] of [[999, 'not_yet_valid'], [1000, 'valid'], [86400999, 'valid'], [86401000, 'expired']] as const) {
    assert.equal(inspectRuntimeConfig(config(), now).rateCard, expected);
    assert.equal(inspectRuntimeConfig(config(), now).localChecksPassed, expected === 'valid');
  }
  for (const now of [-1, NaN, Infinity, 1000.5, Number.MAX_SAFE_INTEGER + 1])
    assert.equal(inspectRuntimeConfig(config(), now).localChecksPassed, false);
  const invalid = config({ RATE_CARD_JSON: JSON.stringify({ ...rate, inputMicrosPerMillion: 0 }) });
  assert.equal(inspectRuntimeConfig(invalid, 86401000).rateCard, 'invalid');
  const text = JSON.stringify(rate);
  assert.equal(inspectRuntimeConfig(config({ RATE_CARD_JSON: text.padEnd(2048, ' ') }), 1000).rateCard, 'valid');
  assert.equal(inspectRuntimeConfig(config({ RATE_CARD_JSON: text.padEnd(2049, ' ') }), 1000).rateCard, 'invalid');
  const original = config();
  const saved = readRuntimeConfig(original, 1000);
  assert.equal(Object.isFrozen(saved.rate), true);
  original.RATE_CARD_JSON = '{}';
  assert.deepEqual(saved.rate, rate);
});

const tool = fileURLToPath(new URL('../tools/config-preflight.mjs', import.meta.url));
const canary = 'SYNTHETIC_SECRET_MUST_NOT_APPEAR_7193';
function cli(input: string | Buffer, args = ['--stdin']) {
  return spawnSync(process.execPath, ['--experimental-strip-types', tool, ...args], {
    input, encoding: 'utf8', timeout: 10000,
    env: { NODE_NO_WARNINGS: '1', DEEPSEEK_API_KEY: canary, RATE_CARD_JSON: 'ambient-value-must-not-be-read' },
  });
}

test('真实CLI仅检查显式stdin，成功也不声称认证或价格已核验且不输出秘密字段', () => {
  const now = Date.now();
  const input = config({ DEEPSEEK_API_KEY: canary, RATE_CARD_JSON: JSON.stringify({ ...rate,
    version: canary, checkedAtMillis: now - 1000, expiresAtMillis: now + 60000 }) });
  const result = cli(JSON.stringify(input));
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /本地预检通过/);
  assert.match(result.stdout, /未验证 API 认证/);
  assert.match(result.stdout, /未核验官网现价/);
  assert.equal((result.stdout + result.stderr).includes(canary), false);
  assert.equal((result.stdout + result.stderr).includes(String(now)), false);
  const empty = cli('{}');
  assert.equal(empty.status, 1);
  assert.match(empty.stdout, /缺失/);
  assert.equal((empty.stdout + empty.stderr).includes(canary), false);
});

test('CLI拒绝非法封装、未知参数、超字节上限和非法UTF8，错误不回显内容', () => {
  const inputs = ['', '[{}]', 'null', `{"${canary}":true}`, '{' + canary, '{}{}', ' '.repeat(16385), Buffer.from([0xff])];
  for (const input of inputs) {
    const result = cli(input);
    assert.equal(result.error, undefined);
    assert.equal(result.status, 2);
    assert.equal((result.stdout + result.stderr).includes(canary), false);
    assert.doesNotMatch(result.stderr, /SyntaxError|stack|position|\.json/);
  }
  const badArgs = cli('{}', ['--key', canary]);
  assert.equal(badArgs.status, 2);
  assert.equal((badArgs.stdout + badArgs.stderr).includes(canary), false);
  assert.equal(cli('{}', []).status, 2);
  assert.equal(cli('{}', ['--help']).status, 0);
  assert.equal(cli(JSON.stringify(config({ RATE_CARD_JSON: rate }))).status, 1);
});

test('有效费率结构但已过期时CLI失败，不会自动续期或联网核验', () => {
  const result = cli(JSON.stringify(config({ DEEPSEEK_API_KEY: canary })));
  assert.equal(result.status, 1);
  assert.match(result.stdout, /已过期/);
  assert.equal((result.stdout + result.stderr).includes(canary), false);
});

test('输入流拒绝交互TTY、分块按原始字节限额，流异常不会暴露秘密', () => {
  const program = `
    import assert from 'node:assert/strict';
    import { Readable } from 'node:stream';
    import { readPreflightInput } from ${JSON.stringify(pathToFileURL(tool).href)};
    const tty = Readable.from(['{}']); tty.isTTY = true;
    await assert.rejects(readPreflightInput(tty), { message: 'preflight_input_invalid' });
    assert.equal(tty.readableDidRead, false);
    assert.deepEqual(await readPreflightInput(Readable.from([Buffer.from('{}'), Buffer.alloc(16382,32)])), {});
    await assert.rejects(readPreflightInput(Readable.from([Buffer.alloc(16000,32), Buffer.from('中'.repeat(200))])),
      { message: 'preflight_input_invalid' });
    const broken = Readable.from((async function* () { yield '{'; throw new Error(${JSON.stringify(canary)}); })());
    await assert.rejects(readPreflightInput(broken), { message: 'preflight_input_invalid' });
  `;
  const result = spawnSync(process.execPath, ['--experimental-strip-types', '--input-type=module', '-e', program],
    { encoding: 'utf8', timeout: 10000, env: { NODE_NO_WARNINGS: '1' } });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stderr);
  assert.equal((result.stdout + result.stderr).includes(canary), false);
});

test('输入管道一直不结束时按预设截止拒绝且不回显部分密钥', { timeout: 15000 }, async t => {
  const child = spawn(process.execPath, ['--experimental-strip-types', tool, '--stdin'],
    { stdio: ['pipe', 'pipe', 'pipe'], env: { NODE_NO_WARNINGS: '1' } });
  t.after(() => { if (child.exitCode === null && !child.killed) child.kill(); });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk.toString('utf8'); });
  child.stderr.on('data', chunk => { output += chunk.toString('utf8'); });
  child.stdin.on('error', () => {});
  const closed = new Promise<number | null>((resolve, reject) => {
    child.once('error', reject);
    child.once('close', resolve);
  });
  child.stdin.write(`{"DEEPSEEK_API_KEY":"${canary}`);
  assert.equal(await closed, 2);
  assert.match(output, /输入无效/);
  assert.equal(output.includes(canary), false);
});
