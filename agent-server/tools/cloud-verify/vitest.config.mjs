import { cloudflareTest,readD1Migrations } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { selectProfile } from './profile.mjs';
const profile=selectProfile(process.env.LIUYING_VERIFY_PROFILE);

export default defineConfig({plugins:[cloudflareTest(async()=>({
  main:fileURLToPath(new URL(profile.main,import.meta.url)),remoteBindings:false,
  miniflare:{compatibilityDate:profile.compatibilityDate,compatibilityFlags:['nodejs_compat'],
    modulesRules:[{type:'Data',include:['**/*.bin'],fallthrough:false}],
    durableObjects:{AGENT_RUNTIME:{className:'VerificationRuntime',useSQLite:true}},d1Databases:['DB'],
    bindings:{VERIFY_EXPIRES_AT:String(Date.now()+1800000),
      VERIFY_TOKEN_HASH:createHash('sha256').update('local-test-token').digest('hex'),
      TEST_MIGRATIONS:await readD1Migrations(fileURLToPath(new URL('../../migrations',import.meta.url)))},
    outboundService(){throw new Error('隔离验收禁止真实外部网络');},
  },
}))],test:{include:['tools/cloud-verify/runtime.spec.mjs'],testTimeout:45000}});
