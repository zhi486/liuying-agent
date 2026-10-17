import { setTimeout } from 'node:timers/promises';

export async function waitReady(probe, delay = setTimeout) {
  for (let attempt = 1; attempt <= 4; attempt++) {
    const status = await probe();
    if (status === 404) return attempt;
    if (status !== 503 && status !== 401) throw new Error(`部署就绪检查失败：${status}`);
    if (attempt < 4) await delay(10000);
  }
  throw new Error('部署尚未就绪，停止本轮，不重试数据库查询');
}
