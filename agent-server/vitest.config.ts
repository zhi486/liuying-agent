import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

// 测试绑定独立构造，不加载部署配置、.dev.vars 或旧云探针的身份。
export default defineConfig({
  plugins: [cloudflareTest(async () => ({
    main: fileURLToPath(new URL('./src/worker.ts', import.meta.url)),
    remoteBindings: false,
    miniflare: {
      compatibilityDate: '2026-09-16', compatibilityFlags: ['nodejs_compat'],
      modulesRules: [{ type: 'Data', include: ['**/*.bin'], fallthrough: false }],
      durableObjects: { AGENT_RUNTIME: { className: 'LiuyingAgentRuntime', useSQLite: true } },
      d1Databases: ['DB'],
      bindings: {
        RUN_UNSAFE_ABORT_REPRO: process.env.LIUYING_RUN_ABORT_REPRO === '1',
        DEEPSEEK_API_KEY: 'fixture-only-not-a-secret',
        RATE_CARD_JSON: JSON.stringify({ version: 'fixture', model: 'deepseek-flash',
          inputMicrosPerMillion: 300000, outputMicrosPerMillion: 1200000,
          checkedAtMillis: Date.now() - 1000, expiresAtMillis: Date.now() + 3600000 }),
        TEST_MIGRATIONS: await readD1Migrations(fileURLToPath(new URL('./migrations', import.meta.url))),
      },
      outboundService() { throw new Error('测试禁止未模拟的外部网络'); },
    },
  }))],
  test: { include: ['runtime-test/*.spec.mjs'], testTimeout: 45000 },
});
