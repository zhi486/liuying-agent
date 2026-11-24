// 仅显式准备时下载固定官方资源；已有异文拒绝覆盖。
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

const root = new URL('./', import.meta.url);
const lock = JSON.parse(await readFile(new URL('assets.lock.json', root), 'utf8'));
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
await mkdir(new URL('.assets/', root), { recursive: true });
for (const [name, info] of Object.entries(lock.assets)) {
  const path = new URL(`.assets/${name}`, root);
  let bytes;
  try {
    bytes = await readFile(path);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (bytes) {
    if (bytes.length !== info.bytes || hash(bytes) !== info.sha256)
      throw new Error(`固定资源不匹配，拒绝覆盖：${name}`);
    console.log(`已核验，无需下载：${name}`);
    continue;
  }
  const response = await fetch(info.url, {
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) throw new Error(`官方资源读取失败：${response.status}`);
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > info.bytes) throw new Error('官方资源超过固定大小');
    chunks.push(chunk);
  }
  bytes = Buffer.concat(chunks);
  if (bytes.length !== info.bytes || hash(bytes) !== info.sha256)
    throw new Error(`官方资源校验失败：${name}`);
  await writeFile(path, bytes, { flag: 'wx' });
  console.log(`下载并校验完成：${name}`);
}
