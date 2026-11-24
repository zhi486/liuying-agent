import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
for (const theme of ['morning', 'night']) {
  test(`${theme} B 用量页在手机和窄屏大字体下可切换展开`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 850 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ fixture, theme }) => {
        fixture.payload.snapshot.theme = theme;
        const cost = {
          currency: 'USD',
          estimatedMicros: 1420,
          inputTokens: 1240,
          outputTokens: 386,
          usageComplete: true,
          rateStatus: 'reference',
        };
        const calls: string[] = [];
        const port = {
          onmessage: null as ((event: { data: string }) => void) | null,
          postMessage(raw: string) {
            const request = JSON.parse(raw);
            const emit = (value: unknown) =>
              queueMicrotask(() => port.onmessage?.({ data: JSON.stringify(value) }));
            if (request.type === 'hello') {
              emit({ ...fixture, payload: { ...fixture.payload, helloId: request.id } });
              return;
            }
            calls.push(request.payload.name);
            if (request.payload.name !== 'cloud.list') throw new Error('禁止非只读命令');
            emit({
              contractVersion: 1,
              type: 'result',
              id: request.id,
              sentAt: new Date().toISOString(),
              payload: {
                status: 'accepted',
                data: {
                  commandId: request.id,
                  configured: true,
                  configuring: false,
                  origin: 'https://agent.example.com',
                  items: [
                    {
                      requestId: '11111111-1111-4111-8111-111111111111',
                      prompt: '合成轻松跑与恢复安排，长标题不应挤压金额或操作按钮',
                      busy: false,
                      attempted: true,
                      status: 'completed',
                      resultState: 'acknowledged',
                      reason: null,
                      answer: '合成回答',
                      pendingControl: null,
                      confirmedControl: null,
                      error: null,
                      memoryRefs: [],
                      cost,
                    },
                  ],
                  retainedUsage: [
                    {
                      requestId: '22222222-2222-4222-8222-222222222222',
                      atMillis: 1000,
                      cost: {
                        ...cost,
                        estimatedMicros: null,
                        usageComplete: false,
                        rateStatus: 'stale',
                      },
                    },
                  ],
                },
              },
            });
          },
        };
        Object.assign(window, { LiuyingNative: port, usageCalls: calls });
      },
      { fixture, theme },
    );
    await page.goto('/agent#/settings/usage');
    await expect(page.getByRole('button', { name: 'Token 用量' })).toBeVisible();
    await page.evaluate(() => {
      document.documentElement.style.fontSize = '20px';
    });
    await page.getByRole('button', { name: /合成轻松跑/ }).click();
    await expect(page.getByText('使用内置参考费率，不是实时价格。')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/usage-live-${theme}.png`, fullPage: true });
    await page.getByRole('button', { name: 'Token 用量' }).click();
    await expect(page.getByRole('button', { name: /合成轻松跑/ })).toContainText('1,626');
    await page.getByRole('button', { name: /已删除对话的用量/ }).click();
    await expect(page.getByText('沿用上次核验费率，可能已过时。')).toBeVisible();
    expect(
      await page.evaluate(() => (window as Window & { usageCalls?: string[] }).usageCalls),
    ).toEqual(['cloud.list']);
  });
}
