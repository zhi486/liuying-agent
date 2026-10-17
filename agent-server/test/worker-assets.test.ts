import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { prepareWorkerAssets } from '../tools/token-count/prepare-worker-assets.ts';
import { TOKENIZER_PROFILE } from '../src/token-count/profile.ts';

test('只有完整通过固定资源校验才能输出二进制资产，写入同一份已验快照', async () => {
  let writes = 0;
  await prepareWorkerAssets(path => readFile(path), async bytes => {
    writes++;
    assert.equal(bytes.byteLength, TOKENIZER_PROFILE.bytes);
  });
  assert.equal(writes, 1);
  for (const missing of [true, false]) {
    await assert.rejects(prepareWorkerAssets(async path => {
      if (path.pathname.endsWith('/tokenizer.json')) {
        if (missing) throw new Error('不存在');
        const changed = await readFile(path);
        changed[0] = 0;
        return changed;
      }
      return readFile(path);
    }, async () => { writes++; }));
  }
  assert.equal(writes, 1);
});
