import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

export type Reference = { prompt: string; inputTokens: number; tokenIdsSha256: string };
const referenceRoot = fileURLToPath(new URL('../tools/token-count/', import.meta.url));
const python = fileURLToPath(new URL('../tools/token-count/.venv/Scripts/python.exe', import.meta.url));

export function sha256(value: string | Uint8Array): string {
  return createHash('sha256').update(value).digest('hex');
}

export function referenceCases(): (Reference & { name: string; request: unknown })[] {
  return JSON.parse(execFileSync(python, [
    '-B', fileURLToPath(new URL('../tools/workers-spike/reference-cases.py', import.meta.url)), '--include-requests',
  ], { encoding: 'utf8', timeout: 60000, maxBuffer: 16 * 1024 * 1024 }));
}

export function referenceRequests(raws: string[]): Reference[] {
  // 只向锁定官方 Python 实现传请求，不共用待测 JS 模板或序列化器。
  const program = `import sys,json,hashlib,struct
sys.path.insert(0,sys.argv[1])
import count_tokens as ref
sys.stdin.reconfigure(encoding='utf-8')
sys.stdout.reconfigure(encoding='utf-8')
out=[]
for raw in json.load(sys.stdin):
 prompt=ref.render_request(ref.parse_request(raw.encode('utf-8')))
 ids=ref._tokenizer().encode(prompt,add_special_tokens=False).ids
 out.append(dict(prompt=prompt,inputTokens=len(ids),tokenIdsSha256=hashlib.sha256(struct.pack('<'+str(len(ids))+'I',*ids)).hexdigest()))
print(json.dumps(out,ensure_ascii=False))`;
  return JSON.parse(execFileSync(python, ['-B', '-c', program, referenceRoot], {
    input: JSON.stringify(raws), encoding: 'utf8', timeout: 60000, maxBuffer: 16 * 1024 * 1024,
  }));
}

export function tokenIdsHash(ids: number[]): string {
  const bytes = Buffer.alloc(ids.length * 4);
  ids.forEach((id, index) => bytes.writeUInt32LE(id, index * 4));
  return sha256(bytes);
}
