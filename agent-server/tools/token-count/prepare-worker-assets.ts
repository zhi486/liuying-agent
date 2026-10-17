import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { verifyAssets } from './verify-assets.ts';

export async function prepareWorkerAssets(
  read: (path: URL) => Promise<Uint8Array> = path => readFile(path),
  write: (bytes: Uint8Array) => Promise<void> = async bytes => {
    const directory = new URL('../../.generated/', import.meta.url);
    await mkdir(directory, { recursive: true });
    await writeFile(new URL('tokenizer.bin', directory), bytes);
  },
): Promise<void> {
  let tokenizer: Uint8Array | undefined;
  await verifyAssets(async path => {
    const bytes = Uint8Array.from(await read(path));
    if (path.pathname.endsWith('/tokenizer.json')) tokenizer = bytes;
    return bytes;
  });
  if (!tokenizer) throw new Error('固定计数资源校验失败');
  await write(tokenizer);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await prepareWorkerAssets();
    console.log('Worker 固定词表二进制资产已准备');
  } catch {
    console.error('Worker 固定词表资产准备失败');
    process.exitCode = 1;
  }
}
