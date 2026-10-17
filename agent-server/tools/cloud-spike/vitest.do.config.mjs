import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

// 仅本地绑定：不读取部署配置、OAuth 或远程数据库。
export default defineConfig({
  plugins:[cloudflareTest(async () => ({
    main:'./do-worker.mjs',
    miniflare:{
      compatibilityDate:'2026-09-15', compatibilityFlags:['nodejs_compat'],
      durableObjects:{ PROBE:{ className:'LiuyingRuntimeProbe', useSQLite:true } },
      d1Databases:['DB'],
      bindings:{ SPIKE_TOKEN:'fixture-only-not-a-secret-32-characters',
        EXPIRES_AT:String(Date.now() + 3600000),
        TEST_MIGRATIONS:await readD1Migrations('../../migrations') },
    },
  }))],
  test:{ include:['do-tests/*.spec.mjs'], testTimeout:20000 },
});
