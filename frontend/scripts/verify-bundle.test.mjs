import assert from 'node:assert/strict';
import { test } from 'node:test';

import { inspectBundle } from './verify-bundle.mjs';

const expectedCsp =
  "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; worker-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'";

function validIndexHtml(scriptUrl = './assets/app.js', extraHead = '') {
  return `<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta http-equiv="Content-Security-Policy" content="${expectedCsp}" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    ${extraHead}
    <title>流萤</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="${scriptUrl}"></script>
  </body>
</html>`;
}

function seededBytes(length) {
  const bytes = Buffer.allocUnsafe(length);
  let state = 0x6d2b79f5;

  for (let index = 0; index < length; index += 1) {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    bytes[index] = state & 0xff;
  }

  return bytes;
}

test('拒绝 HTML 中的远程资源', () => {
  const result = inspectBundle({
    'index.html': validIndexHtml('https://cdn.example.com/app.js'),
  });

  assert.deepEqual(result.errors, ['检测到远程资源：https://cdn.example.com/app.js']);
});

test('拒绝疑似密钥且不回显密钥', () => {
  const secret = 'sk-ant-api03-secret-value';
  const result = inspectBundle({
    'index.html': validIndexHtml(),
    'assets/app.js': secret,
  });

  assert.match(result.errors[0], /疑似密钥/);
  assert.equal(result.errors.join('\n').includes(secret), false);
});

test('接受预算内的相对资源', () => {
  const result = inspectBundle({
    'index.html': validIndexHtml(),
    'assets/app.js': 'console.log("流萤")',
  });

  assert.deepEqual(result.errors, []);
});

test('允许 JavaScript 中不会发起请求的文档 URL 和 map 方法', () => {
  const result = inspectBundle({
    'index.html': validIndexHtml(),
    'assets/app.js':
      'const help = "https://react.dev/errors/418"; const values = items.map(renderItem);',
  });

  assert.deepEqual(result.errors, []);
});

test('拒绝 source map 文件和 sourceMappingURL', () => {
  const result = inspectBundle({
    'index.html': validIndexHtml(),
    'assets/app.js': 'console.log("流萤")\n//# sourceMappingURL=app.js.map',
    'assets/app.js.map': '{}',
  });

  assert.match(result.errors.join('\n'), /source map 文件/);
  assert.match(result.errors.join('\n'), /sourceMappingURL/);
});

test('拒绝 CSS 和 Vite manifest 中的远程资源', () => {
  const result = inspectBundle({
    'index.html': validIndexHtml(
      './assets/app.js',
      '<link rel="stylesheet" href="./assets/app.css" />',
    ),
    'assets/app.js': 'console.log("流萤")',
    'assets/app.css':
      '@import "https://cdn.example.com/theme.css"; .hero { background: url(//cdn.example.com/firefly.png); }',
    '.vite/manifest.json': JSON.stringify({
      'index.html': {
        file: 'https://cdn.example.com/app.js',
        isEntry: true,
      },
    }),
  });

  assert.deepEqual(result.errors, [
    '检测到远程资源：https://cdn.example.com/theme.css',
    '检测到远程资源：//cdn.example.com/firefly.png',
    '检测到远程资源：https://cdn.example.com/app.js',
  ]);
});

test('拒绝缺失、重复、错序或被放宽的 CSP', () => {
  const withoutCsp = inspectBundle({
    'index.html': '<meta charset="UTF-8" /><script type="module" src="./assets/app.js"></script>',
    'assets/app.js': '',
  });
  assert.match(withoutCsp.errors.join('\n'), /CSP meta 数量必须为 1/);

  const duplicated = inspectBundle({
    'index.html': validIndexHtml().replace(
      '<meta name="viewport"',
      `<meta http-equiv="Content-Security-Policy" content="${expectedCsp}" /><meta name="viewport"`,
    ),
    'assets/app.js': '',
  });
  assert.match(duplicated.errors.join('\n'), /CSP meta 数量必须为 1/);

  const wrongOrder = inspectBundle({
    'index.html': validIndexHtml().replace(
      '<meta charset="UTF-8" />\n    <meta http-equiv="Content-Security-Policy"',
      '<meta charset="UTF-8" />\n    <meta name="viewport" content="width=device-width" />\n    <meta http-equiv="Content-Security-Policy"',
    ),
    'assets/app.js': '',
  });
  assert.match(wrongOrder.errors.join('\n'), /CSP meta 必须紧跟 charset/);

  const relaxed = inspectBundle({
    'index.html': validIndexHtml().replace(
      "script-src 'self'",
      "script-src 'self' 'unsafe-inline'",
    ),
    'assets/app.js': '',
  });
  assert.match(relaxed.errors.join('\n'), /CSP 内容不匹配/);
});

test('拒绝 inline script', () => {
  const result = inspectBundle({
    'index.html': validIndexHtml().replace(
      '</body>',
      '<script>console.log("inline")</script></body>',
    ),
    'assets/app.js': '',
  });

  assert.match(result.errors.join('\n'), /inline script/);
});

test('初始 gzip 预算递归包含静态 imports 但排除 dynamicImports', () => {
  const largeChunk = seededBytes(500_000);
  const baseManifest = {
    'index.html': {
      file: 'assets/entry.js',
      isEntry: true,
    },
    'src/lazy.ts': {
      file: 'assets/lazy.js',
    },
  };

  const dynamicResult = inspectBundle({
    'index.html': validIndexHtml('./assets/entry.js'),
    'assets/entry.js': 'import("./lazy.js")',
    'assets/lazy.js': largeChunk,
    '.vite/manifest.json': JSON.stringify({
      ...baseManifest,
      'index.html': {
        ...baseManifest['index.html'],
        dynamicImports: ['src/lazy.ts'],
      },
    }),
  });
  assert.equal(
    dynamicResult.errors.some((error) => error.includes('初始 JavaScript')),
    false,
  );

  const staticResult = inspectBundle({
    'index.html': validIndexHtml('./assets/entry.js'),
    'assets/entry.js': 'import "./lazy.js"',
    'assets/lazy.js': largeChunk,
    '.vite/manifest.json': JSON.stringify({
      ...baseManifest,
      'index.html': {
        ...baseManifest['index.html'],
        imports: ['src/lazy.ts'],
      },
    }),
  });
  assert.match(staticResult.errors.join('\n'), /初始 JavaScript gzip 超出预算/);
});

test('拒绝超过总资源预算的 bundle', () => {
  const result = inspectBundle({
    'index.html': validIndexHtml(),
    'assets/app.js': Buffer.alloc(2_621_441),
  });

  assert.match(result.errors.join('\n'), /总资源超出预算/);
});
