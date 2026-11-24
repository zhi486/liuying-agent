import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

for (const theme of ['morning', 'night']) {
  test(`${theme} 事实授权单独确认和撤销，窄屏文档分类对齐`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ fixture, theme }) => {
        const snapshot = fixture.payload.snapshot;
        snapshot.theme = theme;
        snapshot.session.state = 'finished';
        let generation = 0;
        let enabled = true;
        let factsAllowed = false;
        const calls: { name: string; body: Record<string, unknown> }[] = [];
        const token = () => ['a', 'b', 'c', 'd'][generation].repeat(64);
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
            const { name, body } = request.payload;
            calls.push({ name, body });
            const commandId = request.id;
            let data: unknown;
            if (name === 'cloud.memoryAuthorization' || name === 'cloud.memoryConsent') {
              if (name === 'cloud.memoryConsent') {
                if (
                  body.identityToken !== token() ||
                  typeof body.factsAllowed !== 'boolean' ||
                  typeof body.enabled !== 'boolean' ||
                  (!body.enabled && body.factsAllowed)
                )
                  throw new Error('合成范围或代次不匹配');
                enabled = body.enabled;
                factsAllowed = body.factsAllowed;
                generation++;
              }
              data = { commandId, configured: true, enabled, factsAllowed, context: token() };
            } else if (name === 'memory.list') {
              data = { commandId, offset: 0, limit: 20, totalItems: 0, items: [] };
            } else if (name === 'memory.document') {
              const id = '22222222-2222-4222-8222-222222222222';
              data = {
                commandId,
                generatedAtMillis: 3000,
                offset: 0,
                limit: 20,
                totalItems: 1,
                items: [
                  {
                    memory: {
                      id,
                      content: '我的主要编程语言是Kotlin',
                      source: 'user_explicit',
                      version: 1,
                      createdAtMillis: 1000,
                      updatedAtMillis: 2000,
                    },
                    category: 'fact',
                    changeSource: 'agent',
                    sourceTurnId: id,
                  },
                ],
              };
            } else throw new Error(`合成测试禁止命令：${name}`);
            emit({
              contractVersion: 1,
              type: 'result',
              id: request.id,
              sentAt: new Date().toISOString(),
              payload: { status: 'accepted', data },
            });
          },
        };
        Object.assign(window, { LiuyingNative: port, factMemoryCalls: calls });
      },
      { fixture, theme },
    );
    await page.goto('/#/memories');
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    const checkbox = page.getByRole('checkbox', { name: '我另行允许上述事实写入与发送范围' });
    await expect(checkbox).not.toBeChecked();
    await expect(page.getByRole('switch', { name: '事实写入' })).toBeDisabled();
    await page.screenshot({ path: `build/batch54-facts-off-${theme}.png`, fullPage: true });
    await checkbox.check();
    await page.getByRole('switch', { name: '事实写入' }).click();
    await expect(page.getByText('事实写入已允许')).toBeVisible();
    await page.screenshot({ path: `build/batch54-facts-on-${theme}.png`, fullPage: true });
    await page.getByRole('switch', { name: '事实写入' }).click();
    await expect(page.getByText('事实写入未允许')).toBeVisible();
    await expect(page.getByText('自动记忆已开启')).toBeVisible();
    await expect(checkbox).not.toBeChecked();
    await page.getByRole('button', { name: '查看记忆文档' }).click();
    await expect(page.getByText('事实 · 版本 1 · Agent 经授权写入')).toBeVisible();
    await page.screenshot({ path: `build/batch54-fact-document-${theme}.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.getByRole('switch', { name: '自动记忆' }).click();
    await expect(page.getByText('自动记忆未开启')).toBeVisible();
    const calls = await page.evaluate(
      () =>
        (
          window as Window & {
            factMemoryCalls?: { name: string; body: Record<string, unknown> }[];
          }
        ).factMemoryCalls ?? [],
    );
    expect(
      calls.filter((call) => call.name === 'cloud.memoryConsent').map((call) => call.body),
    ).toEqual([
      { identityToken: 'a'.repeat(64), enabled: true, factsAllowed: true },
      { identityToken: 'b'.repeat(64), enabled: true, factsAllowed: false },
      { identityToken: 'c'.repeat(64), enabled: false, factsAllowed: false },
    ]);
    expect(
      calls.every((call) =>
        [
          'cloud.memoryAuthorization',
          'cloud.memoryConsent',
          'memory.list',
          'memory.document',
        ].includes(call.name),
      ),
    ).toBe(true);
  });
}
