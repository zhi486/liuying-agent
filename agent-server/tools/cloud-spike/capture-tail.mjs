import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { tailRecord } from './tail-record.mjs';

// 原始 tail 可含请求头，只保留下列白名单字段；原始输出绝不落盘。
const startedAt = Date.now();
const rows = [];
const child = spawn(process.execPath, ['node_modules/wrangler/bin/wrangler.js',
  'tail', 'liuying-free-spike-20260915', '--format', 'json'],
  { stdio:['ignore', 'pipe', 'pipe'], env:{ ...process.env, WRANGLER_SEND_METRICS:'false' } });
let buffer = '';
child.stdout.setEncoding('utf8');
child.stdout.on('data', chunk => {
  buffer += chunk;
  while (true) {
    const start = buffer.indexOf('{');
    if (start < 0) { buffer = ''; return; }
    let depth = 0, quoted = false, escaped = false, end = -1;
    for (let i = start; i < buffer.length; i++) {
      const ch = buffer[i];
      if (quoted) {
        if (escaped) escaped = false;
        else if (ch === '\\') escaped = true;
        else if (ch === '"') quoted = false;
      } else if (ch === '"') quoted = true;
      else if (ch === '{') depth++;
      else if (ch === '}' && --depth === 0) { end = i + 1; break; }
    }
    if (end < 0) return;
    const raw = JSON.parse(buffer.slice(start, end));
    buffer = buffer.slice(end);
    const row = tailRecord(raw);
    rows.push(row);
    console.log(JSON.stringify(row));
  }
});
child.stderr.on('data', () => { console.error('tail 状态更新（原文未保存）'); });
const timer = setTimeout(() => child.kill(), 90000);
child.on('close', code => {
  clearTimeout(timer);
  writeFileSync(new URL(`./.output/tail-${startedAt}.json`, import.meta.url),
    JSON.stringify({ startedAt, finishedAt:Date.now(), code, rows }, null, 2), { flag:'wx' });
  console.log(`tail 已结束，保存 ${rows.length} 条脱敏事件`);
});
