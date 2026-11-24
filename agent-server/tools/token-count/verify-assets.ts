// 只读构建门禁：缺失时失败，绝不下载、覆盖或回退估算。
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { TOKENIZER_PROFILE } from '../../src/token-count/profile.ts';

export async function verifyAssets(
  read: (path: URL) => Promise<Uint8Array> = path => readFile(path),
): Promise<void> {
  try {
    const lock = JSON.parse(new TextDecoder().decode(await read(new URL('./assets.lock.json', import.meta.url))));
    const { model, modelRevision, profile, bytes, sha256 } = TOKENIZER_PROFILE;
    if (lock.model !== model || lock.modelRevision !== modelRevision || lock.profile !== profile ||
        lock.assets['tokenizer.json'].bytes !== bytes || lock.assets['tokenizer.json'].sha256 !== sha256)
      throw new Error();
    for (const name of ['encoding.py', 'tokenizer.json']) {
      const asset = await read(new URL(`./.assets/${name}`, import.meta.url));
      const expected = lock.assets[name];
      if (asset.byteLength !== expected.bytes || createHash('sha256').update(asset).digest('hex') !== expected.sha256)
        throw new Error();
    }
  } catch {
    throw new Error('固定计数资源校验失败');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await verifyAssets();
    console.log('固定模板、字典与生产 profile 校验通过');
  } catch {
    console.error('固定计数资源校验失败');
    process.exitCode = 1;
  }
}
