import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const ready = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
const settings = JSON.parse(
  readFileSync(new URL('../../contracts/mcp-phone-v1/settings.json', import.meta.url), 'utf8'),
);

for (const theme of ['morning', 'night']) {
  for (const width of [320, 393]) {
    test(`${theme} ${width}px 返回按钮随内容吸附、回位且不改变返回层级`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 });
      await page.route('**/*', (route) =>
        new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
      );
      await page.addInitScript(
        ({ ready, settings, theme }) => {
          ready.payload.snapshot.theme = theme;
          ready.payload.snapshot.session.state = 'finished';
          const calls: string[] = [];
          const port = {
            onmessage: null as ((event: { data: string }) => void) | null,
            postMessage(raw: string) {
              const request = JSON.parse(raw);
              const emit = (message: unknown) =>
                queueMicrotask(() => port.onmessage?.({ data: JSON.stringify(message) }));
              if (request.type === 'hello') {
                emit({ ...ready, payload: { ...ready.payload, helloId: request.id } });
                return;
              }
              const { name } = request.payload;
              calls.push(name);
              const data =
                name === 'settings.query'
                  ? { settings: ready.payload.snapshot.settings }
                  : name === 'mcp.status'
                    ? settings.status
                    : name === 'mcp.list'
                      ? settings.list
                      : null;
              if (!data) throw new Error(`滚动验收禁止业务命令：${name}`);
              emit({
                contractVersion: 1,
                type: 'result',
                id: request.id,
                sentAt: new Date().toISOString(),
                payload: { status: 'accepted', data: { ...data, commandId: request.id } },
              });
            },
          };
          Object.assign(window, { LiuyingNative: port, stickyBackCalls: calls });
        },
        { ready, settings, theme },
      );
      await page.goto('/#/settings');
      await page.getByRole('link', { name: 'MCP 插件', exact: true }).click();
      await expect(page.getByRole('button', { name: '管理此服务' })).toBeVisible();
      await page.evaluate(async () => {
        document.documentElement.style.fontSize = '20px';
        await document.fonts.ready;
        window.scrollTo(0, 0);
      });
      const back = page.getByRole('link', { name: '返回设置', exact: true });
      const heading = page.getByRole('heading', { level: 1, name: 'MCP 插件', exact: true });
      const initial = await back.boundingBox();
      expect(initial).not.toBeNull();
      expect(initial!.width).toBeGreaterThanOrEqual(44);
      expect(initial!.height).toBe(initial!.width);
      await page.screenshot({ path: `build/sticky-back-${theme}-${width}-top.png` });

      const content = page.locator('main');
      const contentTop = (await content.boundingBox())!.y;
      const samples = [];
      for (const scroll of [0, 4, 8, 12, 80, 300]) {
        await page.evaluate((y) => window.scrollTo(0, y), scroll);
        await expect
          .poll(async () => (await back.boundingBox())!.y)
          .toBeCloseTo(Math.max(16, initial!.y - scroll), 0);
        const box = (await back.boundingBox())!;
        expect(box.x).toBeCloseTo(initial!.x, 1);
        expect(box.width).toBe(initial!.width);
        expect(box.height).toBe(initial!.height);
        const actualScroll = await page.evaluate(() => scrollY);
        expect((await content.boundingBox())!.y + actualScroll).toBeCloseTo(contentTop, 1);
        samples.push({ scroll: actualScroll, ...box });
      }
      await expect(heading).not.toBeInViewport();
      await expect(back).toBeInViewport();
      expect(
        await back.evaluate((button) => {
          const rect = button.getBoundingClientRect();
          return button.contains(
            document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2),
          );
        }),
      ).toBe(true);
      await back.focus();
      await page.keyboard.press('Tab');
      await page.keyboard.press('Shift+Tab');
      await expect(back).toBeFocused();
      expect(await back.evaluate((button) => getComputedStyle(button).outlineStyle)).toBe('solid');
      await page.screenshot({ path: `build/sticky-back-${theme}-${width}-pinned.png` });

      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.setViewportSize({ width, height: 420 });
      await expect(back).toBeInViewport();
      await page.screenshot({ path: `build/sticky-back-${theme}-${width}-short.png` });
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect.poll(async () => (await back.boundingBox())!.y).toBeCloseTo(initial!.y, 1);
      await expect(heading).toBeInViewport();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await test.info().attach('滚动位置采样', {
        body: JSON.stringify(samples, null, 2),
        contentType: 'application/json',
      });
      await back.click();
      await expect(page).toHaveURL(/#\/settings$/);
      expect(
        await page.evaluate(
          () => (window as unknown as { stickyBackCalls: string[] }).stickyBackCalls,
        ),
      ).toEqual(expect.arrayContaining(['mcp.status', 'mcp.list']));
    });
  }
}
