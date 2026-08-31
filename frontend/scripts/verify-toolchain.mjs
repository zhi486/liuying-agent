const expectedNodeVersion = '22.22.2';
const expectedPnpmUserAgent = 'pnpm/11.19.0';
const actualPnpmUserAgent = process.env.npm_config_user_agent ?? '';
const errors = [];

if (process.versions.node !== expectedNodeVersion) {
  errors.push(`Node 版本必须为 ${expectedNodeVersion}，实际为 ${process.versions.node}`);
}

if (!actualPnpmUserAgent.includes(expectedPnpmUserAgent)) {
  errors.push(`必须使用 pnpm 11.19.0，当前 user agent 为 ${actualPnpmUserAgent || '空'}`);
}

if (errors.length > 0) {
  for (const error of errors) {
    console.error(error);
  }
  process.exitCode = 1;
} else {
  console.log('前端工具链版本验证通过');
}
