import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { verifyAssets } from '../tools/token-count/verify-assets.ts';

test('构建门禁验证模板、字典与生产 profile 一致', async () => {
  await verifyAssets();
});

for (const name of ['encoding.py', 'tokenizer.json']) {
  test(`构建门禁拒绝缺失资源：${name}`, async () => {
    await assert.rejects(verifyAssets(async path => {
      if (path.pathname.endsWith(`/${name}`)) throw new Error('私密文件路径');
      return readFile(path);
    }), { message: '固定计数资源校验失败' });
  });
  test(`构建门禁拒绝同大小篡改：${name}`, async () => {
    await assert.rejects(verifyAssets(async path => {
      const bytes = await readFile(path);
      if (path.pathname.endsWith(`/${name}`)) bytes[0] = bytes[0]! ^ 1;
      return bytes;
    }), { message: '固定计数资源校验失败' });
  });
}

test('资源 lock 与运行时 profile 不一致不能通过门禁', async () => {
  await assert.rejects(verifyAssets(async path => {
    const bytes = await readFile(path);
    if (!path.pathname.endsWith('/assets.lock.json')) return bytes;
    const lock = JSON.parse(bytes.toString('utf8'));
    lock.profile = '其他版本';
    return Buffer.from(JSON.stringify(lock));
  }), { message: '固定计数资源校验失败' });
});
