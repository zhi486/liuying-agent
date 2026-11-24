import { fileURLToPath } from 'node:url';
import { inspectRuntimeConfig } from '../src/runtime-config.ts';

// 明确管道输入才读取；不扫描文件、不读取配置环境变量、不联网或写文件。
export async function readPreflightInput(stream) {
  if (stream.isTTY) throw new Error('preflight_input_invalid');
  const timer = setTimeout(() => stream.destroy(new Error('preflight_input_invalid')), 5000);
  timer.unref();
  try {
    const chunks = [];
    let size = 0;
    for await (const chunk of stream) {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      size += bytes.length;
      if (size > 16384) throw new Error('preflight_input_invalid');
      chunks.push(bytes);
    }
    const value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
    if (!value || typeof value !== 'object' || Array.isArray(value) ||
        Object.keys(value).some(key => !['DEEPSEEK_API_KEY', 'RATE_CARD_JSON'].includes(key)))
      throw new Error('preflight_input_invalid');
    return value;
  } catch {
    throw new Error('preflight_input_invalid');
  } finally { clearTimeout(timer); }
}

const labels = {
  missing: '缺失', invalid: '格式非法', format_valid: '格式通过',
  not_yet_valid: '尚未生效', expired: '已过期', valid: '结构与有效期通过',
};

async function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === '--help') {
    console.log('用法：通过私密管道传入 JSON，运行 preflight:config --stdin。最多 16 KiB，5 秒内完成输入。');
    console.log('只检查 DEEPSEEK_API_KEY 和字符串 RATE_CARD_JSON；不要将密钥写入命令参数或粘贴到终端。');
    return;
  }
  if (args.length !== 1 || args[0] !== '--stdin') {
    console.error('参数无效：仅接受 --stdin 或 --help，不接受密钥参数。');
    process.exitCode = 2;
    return;
  }
  try {
    const report = inspectRuntimeConfig(await readPreflightInput(process.stdin));
    console.log(`密钥：${labels[report.apiKey]}；费率：${labels[report.rateCard]}。`);
    console.log(report.localChecksPassed ? '本地预检通过。' : '本地预检未通过。');
    console.log('费率检查仅提示估算质量，不是执行门槛；旧费率或参考价不会因预算超出而停用功能。');
    console.log('未验证 API 认证，未核验官网现价，未验证云端配置或部署；不会刷新费率或调用模型。');
    process.exitCode = report.localChecksPassed ? 0 : 1;
  } catch {
    console.error('输入无效：需非交互 UTF-8 JSON 管道，最多 16 KiB，5 秒内结束；不显示输入或原始异常。');
    process.exitCode = 2;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await main();
