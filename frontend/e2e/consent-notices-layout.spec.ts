import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
for (const theme of ['morning', 'night']) {
  test(`${theme} 授权 B 与须知 A 布局及确认边界`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ fixture, theme }) => {
        fixture.payload.snapshot.theme = theme;
        fixture.payload.snapshot.session.state = 'finished';
        let authorized = false;
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
            const name = request.payload.name;
            calls.push(name);
            if (name === 'cloud.chatConsent') authorized = request.payload.body.enabled;
            else if (name !== 'cloud.list') throw new Error('禁止无关命令');
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
                  items: [],
                  chatAuthorized: authorized,
                  chatConsentContext: 'a'.repeat(64),
                },
              },
            });
          },
        };
        Object.assign(window, { LiuyingNative: port, consentCalls: calls });
      },
      { fixture, theme },
    );
    await page.goto('/agent#/settings/chat');
    await expect(page.getByText('持续授权未开启', { exact: true })).toBeVisible();
    await page.evaluate(() => {
      document.documentElement.style.fontSize = '20px';
    });
    await expect(page.getByRole('switch', { name: '持续聊天授权' })).toBeDisabled();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/consent-live-${theme}.png`, fullPage: true });
    await page.getByRole('checkbox').check();
    await page.getByRole('switch', { name: '持续聊天授权' }).click();
    await expect(page.getByText('持续授权已开启', { exact: true })).toBeVisible();
    await page.getByRole('switch', { name: '持续聊天授权' }).click();
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    await page.getByRole('link', { name: '用户须知' }).click();
    await expect(page).toHaveURL(/#\/settings\/notices$/);
    await expect(page.getByRole('heading', { name: '费用与网络异常' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/notices-live-${theme}.png`, fullPage: true });
    expect(
      await page.evaluate(() => (window as Window & { consentCalls?: string[] }).consentCalls),
    ).toEqual(['cloud.list', 'cloud.chatConsent', 'cloud.chatConsent']);
    await page.getByRole('link', { name: '返回上一级' }).click();
    await expect(page).toHaveURL(/#\/settings\/chat$/);
    await expect(page.getByRole('switch', { name: '持续聊天授权' })).not.toBeChecked();
  });
}
