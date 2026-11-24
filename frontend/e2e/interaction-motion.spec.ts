import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

declare global {
  interface Window {
    pageMotions: Animation[];
    pausePageMotion: boolean;
  }
}

for (const theme of ['morning', 'night']) {
  for (const width of [320, 393, 1024]) {
    test(`${theme} ${width}px 共享动效保留原生语义与布局`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 800 });
      await page.route('**/*', (route) =>
        new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
      );
      await page.addInitScript(
        ({ fixture, theme }) => {
          window.pageMotions = [];
          const animate = Element.prototype.animate;
          Element.prototype.animate = function (frames, options) {
            const animation = animate.call(this, frames, options);
            if (this.id === 'main-content') {
              window.pageMotions.push(animation);
              if (window.pausePageMotion) {
                animation.pause();
                animation.currentTime = 80;
              }
            }
            return animation;
          };
          fixture.payload.snapshot.theme = theme;
          fixture.payload.snapshot.motionPolicy = 'expressive';
          fixture.payload.snapshot.session.state = 'finished';
          const calls: string[] = [];
          const port = {
            onmessage: null as ((event: { data: string }) => void) | null,
            postMessage(raw: string) {
              const request = JSON.parse(raw);
              const emit = (message: unknown) =>
                queueMicrotask(() => port.onmessage?.({ data: JSON.stringify(message) }));
              if (request.type === 'hello') {
                emit({ ...fixture, payload: { ...fixture.payload, helloId: request.id } });
                return;
              }
              const name = request.payload.name;
              calls.push(name);
              const data =
                name === 'settings.query'
                  ? { settings: fixture.payload.snapshot.settings }
                  : name === 'cloud.list'
                    ? {
                        configured: true,
                        configuring: false,
                        origin: 'https://agent.example.com',
                        chatAuthorized: false,
                        chatConsentContext: 'a'.repeat(64),
                        conversationId: null,
                        conversations: [],
                        items: [],
                      }
                    : name === 'agent.list'
                      ? { offset: 0, limit: 20, totalItems: 0, items: [] }
                      : null;
              if (!data) throw new Error(`动效测试禁止业务命令：${name}`);
              emit({
                contractVersion: 1,
                type: 'result',
                id: request.id,
                sentAt: new Date().toISOString(),
                payload: { status: 'accepted', data: { ...data, commandId: request.id } },
              });
            },
          };
          Object.assign(window, { LiuyingNative: port, motionCalls: calls });
        },
        { fixture, theme },
      );
      await page.goto('/#/agent');
      const menu = page.getByRole('button', { name: '打开侧边栏', exact: true });
      await expect(menu).toHaveAttribute('aria-expanded', 'false');
      const original = await menu.elementHandle();
      await expect(menu.locator('.menu-glyph')).toHaveCSS('opacity', '1');
      await menu
        .locator('.menu-glyph')
        .evaluate((node) => (node as HTMLElement).style.setProperty('--motion-menu', '800ms'));
      const box = (await menu.boundingBox())!;
      await page.mouse.move(box.x + 6, box.y + 9);
      await page.mouse.down();
      const ripple = menu.locator('.press-ripple > span');
      await expect(ripple).toHaveCount(1);
      const origin = await ripple.evaluate((ink) => {
        const style = (ink as HTMLElement).style;
        return {
          x: parseFloat(style.left) + parseFloat(style.width) / 2,
          y: parseFloat(style.top) + parseFloat(style.height) / 2,
        };
      });
      expect(origin.x).toBeCloseTo(6, 0);
      expect(origin.y).toBeCloseTo(9, 0);
      await page.screenshot({ path: testInfo.outputPath('ripple.png') });
      await page.mouse.up();
      const close = page.getByRole('button', { name: '关闭侧边栏', exact: true });
      await expect(close).toBeVisible();
      await expect(close.locator('.menu-glyph')).toHaveAttribute('data-open', 'true');
      await expect(close.locator('.menu-glyph > span')).toHaveCount(3);
      expect(await close.evaluate((node, old) => node === old, original)).toBe(true);
      await expect(page.locator('button[aria-controls="agent-sidebar"]')).toHaveCount(1);
      const line = close.locator('.menu-glyph > span').first();
      await line.evaluate((node) =>
        node.getAnimations().forEach((animation) => {
          animation.pause();
          animation.currentTime = 180;
        }),
      );
      const angle = await line.evaluate((node) => {
        const matrix = new DOMMatrix(getComputedStyle(node).transform);
        return (Math.atan2(matrix.b, matrix.a) * 180) / Math.PI;
      });
      expect(angle).toBeGreaterThan(1);
      expect(angle).toBeLessThan(44);
      await expect(close.locator('.menu-glyph')).toHaveCSS('opacity', '1');
      await close.evaluate((node) =>
        node.getAnimations().forEach((animation) => animation.finish()),
      );
      await expect
        .poll(() =>
          page.locator('#agent-sidebar').evaluate((node) => getComputedStyle(node).transform),
        )
        .toBe('matrix(1, 0, 0, 1, 0, 0)');
      await page.screenshot({ path: testInfo.outputPath('menu-midpoint.png') });
      await close
        .locator('.menu-glyph > span')
        .evaluateAll((nodes) =>
          nodes.forEach((node) => node.getAnimations().forEach((animation) => animation.finish())),
        );
      await expect(close.locator('.menu-glyph > span').nth(1)).toHaveCSS('opacity', '0');
      await close.press('Shift+Tab');
      await expect(page.getByRole('button', { name: '设置与管理', exact: true })).toBeFocused();
      await page.keyboard.press('Tab');
      await expect(close).toBeFocused();
      await page.screenshot({ path: testInfo.outputPath('menu.png') });
      await page.keyboard.press('Escape');
      await expect(menu).toHaveAttribute('aria-expanded', 'false');
      await expect(menu).toBeFocused();

      await page.goto('/#/settings');
      const toggle = page.getByRole('switch', { name: '流萤资料', exact: true });
      await expect(toggle).toBeChecked();
      await expect
        .poll(() =>
          page
            .locator('#main-content')
            .evaluate(
              (node) =>
                node.getAnimations().filter((animation) => animation.playState === 'running')
                  .length,
            ),
        )
        .toBe(0);
      if (width === 320)
        await page.evaluate(() => {
          document.documentElement.style.fontSize = '20px';
        });
      const before = (await toggle.boundingBox())!;
      await toggle.click();
      await expect(toggle).not.toBeChecked();
      const visual = toggle.locator('..').locator('.choice-visual');
      await expect
        .poll(() => visual.evaluate((node) => getComputedStyle(node, '::after').animationName))
        .toBe('switch-return');
      await visual.evaluate((node) =>
        node.getAnimations({ subtree: true }).forEach((animation) => {
          animation.pause();
          animation.currentTime = 130;
        }),
      );
      const stretch = await visual.evaluate((node) => getComputedStyle(node, '::after').scale);
      expect(stretch).not.toBe('none');
      expect(stretch).not.toBe('1');
      expect(parseFloat(stretch)).toBeGreaterThan(1.3);
      await expect(page.getByRole('switch', { name: '允许身份与身世剧透' })).toBeDisabled();
      await page.screenshot({ path: testInfo.outputPath('switch.png') });
      await visual.evaluate((node) =>
        node.getAnimations({ subtree: true }).forEach((animation) => animation.finish()),
      );
      await toggle.press('Space');
      await expect(toggle).toBeChecked();
      await expect
        .poll(() =>
          visual.evaluate((node) => {
            const style = getComputedStyle(node, '::after');
            return { name: style.animationName, duration: parseFloat(style.animationDuration) };
          }),
        )
        .toEqual({ name: 'switch-arrive', duration: 0.38 });
      expect((await toggle.boundingBox())!.width).toBe(before.width);
      expect((await toggle.boundingBox())!.height).toBeGreaterThanOrEqual(44);

      await page.evaluate(() => {
        window.pageMotions = [];
        window.pausePageMotion = true;
      });
      await page.getByRole('link', { name: /用户须知/ }).click();
      await expect(page).toHaveURL(/#\/settings\/notices$/);
      await expect.poll(() => page.evaluate(() => window.pageMotions.length)).toBe(1);
      const forward = await page.evaluate(() => ({
        frames: (window.pageMotions[0].effect as KeyframeEffect).getKeyframes(),
        duration: window.pageMotions[0].effect!.getTiming().duration,
      }));
      expect(forward.frames[0].transform).toBe('translateX(24px)');
      expect(forward.duration).toBe(240);
      await expect(page.locator('.app-back-navigation')).toHaveCSS('transform', 'none');
      await page.screenshot({ path: testInfo.outputPath('page-forward.png') });
      await page.evaluate(() => window.pageMotions[0].finish());
      await page.getByRole('link', { name: '返回设置', exact: true }).click();
      await expect.poll(() => page.evaluate(() => window.pageMotions.length)).toBe(2);
      const backMotion = await page.evaluate(() => ({
        frames: (window.pageMotions[1].effect as KeyframeEffect).getKeyframes(),
        duration: window.pageMotions[1].effect!.getTiming().duration,
        oldState: window.pageMotions[0].playState,
      }));
      expect(backMotion.frames[0].transform).toBe('translateX(-24px)');
      expect(backMotion.duration).toBe(200);
      expect(backMotion.oldState).toBe('idle');
      await page.screenshot({ path: testInfo.outputPath('page-back.png') });
      await page.evaluate(() => {
        window.pageMotions[1].finish();
        window.pausePageMotion = false;
      });

      await page.goto('/#/settings/chat');
      if (width === 320)
        await page.evaluate(() => {
          document.documentElement.style.fontSize = '20px';
        });
      const checkbox = page.getByRole('checkbox', {
        name: '我同意上述范围，普通文字消息无需每次确认',
      });
      const stroke = checkbox.locator('..').locator('svg path');
      await expect(stroke).toHaveCSS('stroke-dashoffset', '1px');
      await checkbox.click();
      await expect(checkbox).toBeChecked();
      await expect
        .poll(() => stroke.evaluate((node) => node.getAnimations().length))
        .toBeGreaterThan(0);
      await stroke.evaluate((node) =>
        node.getAnimations().forEach((animation) => {
          animation.pause();
          animation.currentTime = 70;
        }),
      );
      const offset = await stroke.evaluate((node) =>
        parseFloat(getComputedStyle(node).strokeDashoffset),
      );
      expect(offset).toBeGreaterThan(0);
      expect(offset).toBeLessThan(1);
      await page.screenshot({ path: testInfo.outputPath('check.png') });
      await stroke.evaluate((node) =>
        node.getAnimations().forEach((animation) => animation.finish()),
      );
      await expect(stroke).toHaveCSS('stroke-dashoffset', '0px');
      await checkbox.press('Space');
      await expect(checkbox).not.toBeChecked();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );

      await page.emulateMedia({ reducedMotion: 'reduce' });
      await checkbox.check();
      await expect(stroke).toHaveCSS('transition-duration', '0s');
      await expect(stroke).toHaveCSS('stroke-dashoffset', '0px');
      await page.evaluate(() => {
        document.documentElement.dataset.motion = 'off';
      });
      const back = page.getByRole('link', { name: '返回设置', exact: true });
      await back.dispatchEvent('pointerdown', {
        button: 0,
        pointerId: 7,
        clientX: 20,
        clientY: 30,
      });
      await expect(page.locator('.press-ripple')).toHaveCount(0);
      const calls = await page.evaluate(
        () => (window as unknown as { motionCalls: string[] }).motionCalls,
      );
      expect(
        calls.every((name) => ['settings.query', 'cloud.list', 'agent.list'].includes(name)),
      ).toBe(true);
    });
  }
}
