import { gzipSync } from 'node:zlib';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const expectedCsp =
  "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; worker-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'";
const initialJavaScriptGzipLimit = 460_800;
const totalResourceLimit = 2_621_440;
const textExtensions = new Set([
  '.css',
  '.html',
  '.js',
  '.json',
  '.map',
  '.mjs',
  '.svg',
  '.txt',
  '.xml',
]);
const secretPatterns = [
  { label: 'sk-ant-', pattern: /sk-ant-/i },
  { label: 'Bearer', pattern: /Bearer\s+/i },
  { label: 'fish_audio_api_key', pattern: /fish_audio_api_key/i },
  { label: 'llm_api_key', pattern: /llm_api_key/i },
];
const loadingAttributes = new Map([
  ['audio', ['src']],
  ['embed', ['src']],
  ['iframe', ['src']],
  ['img', ['src', 'srcset']],
  ['input', ['src']],
  ['link', ['href']],
  ['object', ['data']],
  ['script', ['src']],
  ['source', ['src', 'srcset']],
  ['video', ['src', 'poster']],
]);

function normalizePath(filePath) {
  return filePath.replaceAll('\\', '/').replace(/^\.\//, '');
}

function toBuffer(value) {
  return Buffer.isBuffer(value) ? value : Buffer.from(value, 'utf8');
}

function isTextFile(filePath) {
  return textExtensions.has(path.posix.extname(filePath).toLowerCase());
}

function isRemoteUrl(url) {
  return /^(?:https?:)?\/\//i.test(url.trim());
}

function getAttribute(attributes, name) {
  const pattern = new RegExp(`\\b${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)')`, 'i');
  const match = pattern.exec(attributes);
  return match ? (match[1] ?? match[2] ?? '') : null;
}

function splitResourceValue(attribute, value) {
  if (attribute !== 'srcset') {
    return [value.trim()];
  }

  return value
    .split(',')
    .map((candidate) => candidate.trim().split(/\s+/)[0])
    .filter(Boolean);
}

function parseHtml(html, errors, recordRemote) {
  const tags = Array.from(html.matchAll(/<([a-z][\w:-]*)\b([^>]*)>/gi), (match) => ({
    attributes: match[2],
    index: match.index,
    name: match[1].toLowerCase(),
    raw: match[0],
  }));
  const charsetTags = tags.filter(
    (tag) => tag.name === 'meta' && getAttribute(tag.attributes, 'charset') !== null,
  );
  const cspTags = tags.filter(
    (tag) =>
      tag.name === 'meta' &&
      getAttribute(tag.attributes, 'http-equiv')?.toLowerCase() === 'content-security-policy',
  );

  if (cspTags.length !== 1) {
    errors.push(`index.html：CSP meta 数量必须为 1，实际为 ${cspTags.length}`);
  } else {
    const cspTag = cspTags[0];
    const charsetTag = charsetTags[0];

    if (!charsetTag || cspTag.index <= charsetTag.index) {
      errors.push('index.html：CSP meta 必须紧跟 charset');
    } else {
      const betweenTags = html.slice(charsetTag.index + charsetTag.raw.length, cspTag.index);
      if (betweenTags.trim() !== '') {
        errors.push('index.html：CSP meta 必须紧跟 charset');
      }
    }

    const content = getAttribute(cspTag.attributes, 'content');
    if (content !== expectedCsp) {
      errors.push('index.html：CSP 内容不匹配');
    }
    if (/script-src[^;]*(?:'unsafe-inline'|'unsafe-eval')/i.test(content ?? '')) {
      errors.push('index.html：script-src 不得包含 unsafe-inline 或 unsafe-eval');
    }
  }

  for (const tag of tags) {
    const attributes = loadingAttributes.get(tag.name) ?? [];
    for (const attribute of attributes) {
      const value = getAttribute(tag.attributes, attribute);
      if (value === null) {
        continue;
      }
      for (const url of splitResourceValue(attribute, value)) {
        if (isRemoteUrl(url)) {
          recordRemote(url);
        }
      }
    }
  }

  for (const script of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)) {
    if (getAttribute(script[1], 'src') === null && script[2].trim() !== '') {
      errors.push('index.html：不得包含 inline script');
    }
  }
}

function parseCss(css, recordRemote) {
  for (const match of css.matchAll(/@import\s+(?:url\(\s*)?["']?([^"'\s)]+)["']?\s*\)?/gi)) {
    if (isRemoteUrl(match[1])) {
      recordRemote(match[1]);
    }
  }

  for (const match of css.matchAll(/url\(\s*(["']?)(.*?)\1\s*\)/gi)) {
    if (isRemoteUrl(match[2])) {
      recordRemote(match[2]);
    }
  }
}

function parseManifest(manifestText, errors, recordRemote) {
  let manifest;
  try {
    manifest = JSON.parse(manifestText);
  } catch {
    errors.push('.vite/manifest.json：不是有效 JSON');
    return null;
  }

  for (const node of Object.values(manifest)) {
    if (!node || typeof node !== 'object') {
      continue;
    }
    const resources = [
      node.file,
      ...(Array.isArray(node.css) ? node.css : []),
      ...(Array.isArray(node.assets) ? node.assets : []),
    ];
    for (const resource of resources) {
      if (typeof resource === 'string' && isRemoteUrl(resource)) {
        recordRemote(resource);
      }
    }
  }

  return manifest;
}

function collectInitialJavaScript(manifest) {
  const initialFiles = new Set();
  const visitedKeys = new Set();

  function visit(key) {
    if (visitedKeys.has(key)) {
      return;
    }
    visitedKeys.add(key);

    const node = manifest[key];
    if (!node || typeof node !== 'object') {
      return;
    }
    if (typeof node.file === 'string' && /\.(?:m?js)$/i.test(node.file)) {
      initialFiles.add(normalizePath(node.file));
    }
    if (Array.isArray(node.imports)) {
      for (const importedKey of node.imports) {
        if (typeof importedKey === 'string') {
          visit(importedKey);
        }
      }
    }
  }

  for (const [key, node] of Object.entries(manifest)) {
    if (node && typeof node === 'object' && node.isEntry === true) {
      visit(key);
    }
  }

  return initialFiles;
}

export function inspectBundle(inputFiles) {
  const files = new Map(
    Object.entries(inputFiles)
      .map(([filePath, value]) => [normalizePath(filePath), toBuffer(value)])
      .sort(([left], [right]) => left.localeCompare(right)),
  );
  const errors = [];
  const remoteUrls = new Set();
  const recordRemote = (url) => {
    const normalizedUrl = url.trim();
    if (!remoteUrls.has(normalizedUrl)) {
      remoteUrls.add(normalizedUrl);
      errors.push(`检测到远程资源：${normalizedUrl}`);
    }
  };
  let totalBytes = 0;

  for (const [filePath, contents] of files) {
    totalBytes += contents.byteLength;
    if (filePath.toLowerCase().endsWith('.map')) {
      errors.push(`${filePath}：检测到 source map 文件`);
    }
    if (!isTextFile(filePath)) {
      continue;
    }

    const text = contents.toString('utf8');
    if (text.includes('sourceMappingURL=')) {
      errors.push(`${filePath}：检测到 sourceMappingURL`);
    }
    for (const { label, pattern } of secretPatterns) {
      if (pattern.test(text)) {
        errors.push(`${filePath}：检测到疑似密钥（${label}）`);
      }
    }
  }

  const indexHtml = files.get('index.html')?.toString('utf8');
  if (indexHtml === undefined) {
    errors.push('缺少 index.html');
  } else {
    parseHtml(indexHtml, errors, recordRemote);
  }

  for (const [filePath, contents] of files) {
    if (filePath.toLowerCase().endsWith('.css')) {
      parseCss(contents.toString('utf8'), recordRemote);
    }
  }

  const manifestText = files.get('.vite/manifest.json')?.toString('utf8');
  const manifest = manifestText ? parseManifest(manifestText, errors, recordRemote) : null;
  let initialJavaScriptGzipBytes = 0;
  if (manifest) {
    for (const filePath of collectInitialJavaScript(manifest)) {
      const contents = files.get(filePath);
      if (contents) {
        initialJavaScriptGzipBytes += gzipSync(contents).byteLength;
      }
    }
  }

  if (initialJavaScriptGzipBytes > initialJavaScriptGzipLimit) {
    errors.push(
      `初始 JavaScript gzip 超出预算：${initialJavaScriptGzipBytes} > ${initialJavaScriptGzipLimit}`,
    );
  }
  if (totalBytes > totalResourceLimit) {
    errors.push(`总资源超出预算：${totalBytes} > ${totalResourceLimit}`);
  }

  return {
    errors,
    initialJavaScriptGzipBytes,
    totalBytes,
  };
}

async function readBundleDirectory(directory) {
  const files = {};

  async function visit(currentDirectory) {
    const entries = await readdir(currentDirectory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const absolutePath = path.join(currentDirectory, entry.name);
      if (entry.isDirectory()) {
        await visit(absolutePath);
      } else if (entry.isFile()) {
        const relativePath = normalizePath(path.relative(directory, absolutePath));
        files[relativePath] = await readFile(absolutePath);
      }
    }
  }

  await visit(directory);
  return files;
}

async function runCli() {
  const distDirectory = path.resolve(process.cwd(), 'dist');
  let files;
  try {
    files = await readBundleDirectory(distDirectory);
  } catch (error) {
    console.error(`无法读取 bundle 目录：${distDirectory}`);
    if (error && typeof error === 'object' && 'code' in error) {
      console.error(`错误代码：${error.code}`);
    }
    process.exitCode = 1;
    return;
  }

  const result = inspectBundle(files);
  if (result.errors.length > 0) {
    for (const error of result.errors) {
      console.error(error);
    }
    process.exitCode = 1;
    return;
  }

  console.log(
    `bundle 验证通过：初始 JS gzip ${result.initialJavaScriptGzipBytes} 字节，总资源 ${result.totalBytes} 字节`,
  );
}

const entryPath = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : '';
if (import.meta.url === entryPath) {
  await runCli();
}

export { expectedCsp, initialJavaScriptGzipLimit, totalResourceLimit };
