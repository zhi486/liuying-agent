// 一次性内存编译实验：不覆盖源文件，不把拟议修复当成已合入。
import assert from 'node:assert/strict';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from '../../node_modules/typescript/lib/typescript.js';

const root = dirname(fileURLToPath(import.meta.url));
const config = ts.readConfigFile(resolve(root, 'tsconfig.json'), ts.sys.readFile);
assert.equal(config.error, undefined);
const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root);
assert.equal(parsed.errors.length, 0);
const patches = new Map([
  [
    resolve(root, '../../src/agent-service.ts'),
    ['clearTimeout(timer);', 'if (timer !== undefined) clearTimeout(timer);'],
  ],
  [
    resolve(root, '../../src/deepseek.ts'),
    ['{ fatal: true }).decode(bytes)', '{ fatal: true, ignoreBOM: false }).decode(bytes)'],
  ],
]);

function diagnose(hypothesis) {
  const host = ts.createCompilerHost(parsed.options);
  const originalRead = host.readFile.bind(host);
  const changed = new Set();
  host.readFile = (fileName) => {
    const source = originalRead(fileName);
    const patch = patches.get(resolve(fileName));
    if (!hypothesis || !patch || source === undefined) return source;
    assert.equal(source.split(patch[0]).length, 2, '拟议位置必须唯一且没有被生产修改');
    changed.add(resolve(fileName));
    return source.replace(patch[0], patch[1]);
  };
  const program = ts.createProgram(parsed.fileNames, parsed.options, host);
  const diagnostics = ts.getPreEmitDiagnostics(program).map((item) => ({
    code: item.code,
    file: item.file?.fileName.split(/[\\/]/).pop(),
    message: ts.flattenDiagnosticMessageText(item.messageText, '\n'),
  }));
  if (hypothesis) assert.equal(changed.size, 2);
  return diagnostics;
}

const baseline = diagnose(false);
const hypothesis = diagnose(true);
console.log(
  JSON.stringify(
    { assurance: 'in_memory_types_only_no_source_change', baseline, hypothesis },
    null,
    2,
  ),
);
assert.equal(baseline.length, 2, '原源码的两处 Workers 编译差异应可重现');
assert.equal(hypothesis.length, 0, '仅两处候选修改后应无额外类型差异');
