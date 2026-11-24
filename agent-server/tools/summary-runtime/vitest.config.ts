import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  plugins: [cloudflareTest({
    main: fileURLToPath(new URL('./worker.mjs', import.meta.url)),
    remoteBindings: false,
    miniflare: {
      compatibilityDate: '2026-09-16', compatibilityFlags: ['nodejs_compat'],
      durableObjects: { SUMMARY: { className: 'SummaryProbe', useSQLite: true } },
      outboundService() { throw new Error('摘要测试禁止外部网络'); },
    },
  })],
  test: { include: ['tools/summary-runtime/*.spec.mjs'], testTimeout: 30000 },
});
