import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, unlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test, { afterEach } from 'node:test';

import { verifyReactBitsProvenance } from './verify-reactbits-provenance.mjs';

const temporaryRoots = [];

afterEach(async () => {
  await Promise.all(
    temporaryRoots.splice(0).map((root) => rm(root, { recursive: true, force: true })),
  );
});

test('接受来源、哈希和目录均完整的 vendor 树', async () => {
  const fixture = await createFixture();

  const report = await verifyReactBitsProvenance({
    projectRoot: fixture.projectRoot,
    manifest: fixture.manifest,
  });

  assert.deepEqual(report, { componentCount: 1, verifiedFileCount: 3 });
});

test('拒绝 SOURCE.md 缺少本地 blob SHA', async () => {
  const fixture = await createFixture();
  const sourcePath = path.join(fixture.vendorRoot, 'Particles', 'SOURCE.md');
  const source = await readFile(sourcePath, 'utf8');
  await writeFile(sourcePath, source.replace('"localBlobSha1"', '"missingLocalBlobSha1"'), 'utf8');

  await assert.rejects(
    verifyReactBitsProvenance({ projectRoot: fixture.projectRoot, manifest: fixture.manifest }),
    /localBlobSha1/,
  );
});

test('拒绝与 SOURCE.md 本地哈希不一致的源码', async () => {
  const fixture = await createFixture();
  await writeFile(
    path.join(fixture.vendorRoot, 'Particles', 'Particles.tsx'),
    'export default function Tampered() { return null; }\n',
    'utf8',
  );

  await assert.rejects(
    verifyReactBitsProvenance({ projectRoot: fixture.projectRoot, manifest: fixture.manifest }),
    /Particles\.tsx.*本地 blob SHA 不匹配/,
  );
});

test('拒绝 SOURCE.md 声明但本地缺失的 CSS', async () => {
  const fixture = await createFixture();
  await unlink(path.join(fixture.vendorRoot, 'Particles', 'Particles.css'));

  await assert.rejects(
    verifyReactBitsProvenance({ projectRoot: fixture.projectRoot, manifest: fixture.manifest }),
    /Particles\.css.*不存在/,
  );
});

test('拒绝缺失的上游许可证', async () => {
  const fixture = await createFixture();
  await unlink(path.join(fixture.vendorRoot, 'LICENSE.md'));

  await assert.rejects(
    verifyReactBitsProvenance({ projectRoot: fixture.projectRoot, manifest: fixture.manifest }),
    /LICENSE\.md.*不存在/,
  );
});

test('拒绝未在 allowlist 中声明的 vendor 目录', async () => {
  const fixture = await createFixture();
  await mkdir(path.join(fixture.vendorRoot, 'UnknownComponent'));

  await assert.rejects(
    verifyReactBitsProvenance({ projectRoot: fixture.projectRoot, manifest: fixture.manifest }),
    /未声明的 vendor 条目.*UnknownComponent/,
  );
});

test('拒绝 motion 目录之外直接 import vendor 源码', async () => {
  const fixture = await createFixture();
  const featureDirectory = path.join(fixture.projectRoot, 'src', 'features');
  await mkdir(featureDirectory, { recursive: true });
  await writeFile(
    path.join(featureDirectory, 'Bypass.tsx'),
    "export { default } from '../vendor/reactbits/Particles/Particles';\n",
    'utf8',
  );

  await assert.rejects(
    verifyReactBitsProvenance({ projectRoot: fixture.projectRoot, manifest: fixture.manifest }),
    /Bypass\.tsx.*只能由 src\/motion/,
  );
});

async function createFixture() {
  const projectRoot = await mkdtemp(path.join(os.tmpdir(), 'liuying-reactbits-'));
  temporaryRoots.push(projectRoot);

  const vendorRoot = path.join(projectRoot, 'src', 'vendor', 'reactbits');
  const componentRoot = path.join(vendorRoot, 'Particles');
  const motionRoot = path.join(projectRoot, 'src', 'motion');
  await mkdir(componentRoot, { recursive: true });
  await mkdir(motionRoot, { recursive: true });

  const license = 'Fixture license\n';
  const componentSource = 'export default function Particles() { return null; }\n';
  const componentStyle = '.particles { display: block; }\n';
  const repository = 'https://example.com/react-bits';
  const commit = '1111111111111111111111111111111111111111';
  const manifest = {
    repository,
    commit,
    license: {
      upstreamPath: 'LICENSE.md',
      upstreamBlobSha1: gitBlobSha(license),
    },
    components: {
      Particles: {
        files: {
          'Particles.tsx': {
            upstreamPath: 'src/ts-default/Backgrounds/Particles/Particles.tsx',
            upstreamBlobSha1: gitBlobSha(componentSource),
          },
          'Particles.css': {
            upstreamPath: 'src/ts-default/Backgrounds/Particles/Particles.css',
            upstreamBlobSha1: gitBlobSha(componentStyle),
          },
        },
        allowedLocalChanges: [],
      },
    },
  };

  await writeFile(path.join(vendorRoot, 'LICENSE.md'), license, 'utf8');
  await writeFile(
    path.join(vendorRoot, 'UPSTREAM.md'),
    markdownMetadata('ReactBits 上游来源', {
      repository,
      commit,
      retrievedAt: '2026-09-03',
      licenseUpstreamPath: 'LICENSE.md',
      licenseUpstreamBlobSha1: gitBlobSha(license),
      licenseLocalBlobSha1: gitBlobSha(license),
    }),
    'utf8',
  );
  await writeFile(path.join(componentRoot, 'Particles.tsx'), componentSource, 'utf8');
  await writeFile(path.join(componentRoot, 'Particles.css'), componentStyle, 'utf8');
  await writeFile(
    path.join(componentRoot, 'SOURCE.md'),
    markdownMetadata('Particles 来源', {
      repository,
      commit,
      sourceUrl: `${repository}/tree/${commit}/src/ts-default/Backgrounds/Particles`,
      files: [
        {
          localPath: 'Particles.tsx',
          upstreamPath: 'src/ts-default/Backgrounds/Particles/Particles.tsx',
          upstreamBlobSha1: gitBlobSha(componentSource),
          localBlobSha1: gitBlobSha(componentSource),
        },
        {
          localPath: 'Particles.css',
          upstreamPath: 'src/ts-default/Backgrounds/Particles/Particles.css',
          upstreamBlobSha1: gitBlobSha(componentStyle),
          localBlobSha1: gitBlobSha(componentStyle),
        },
      ],
      localChanges: [],
      runtimeDependencies: ['react'],
    }),
    'utf8',
  );
  await writeFile(
    path.join(motionRoot, 'Allowed.tsx'),
    "export { default } from '../vendor/reactbits/Particles/Particles';\n",
    'utf8',
  );

  return { manifest, projectRoot, vendorRoot };
}

function markdownMetadata(title, value) {
  return `# ${title}\n\n\`\`\`json\n${JSON.stringify(value, null, 2)}\n\`\`\`\n`;
}

function gitBlobSha(content) {
  const bytes = Buffer.from(content);
  return createHash('sha1')
    .update(Buffer.from(`blob ${bytes.length}\0`))
    .update(bytes)
    .digest('hex');
}
