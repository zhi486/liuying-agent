import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

for (const width of [360, 320]) {
  for (const theme of ['morning', 'night']) {
    test(`未设置目标且手环未连接 ${width} ${theme} 准备页无轻微滚动`, async ({ page }) => {
      await page.setViewportSize({ width, height: 762 });
      await page.route('**/*', (route) =>
        new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
      );
      await page.addInitScript(
        ({ fixture, theme }) => {
          fixture.payload.snapshot.session.state = 'idle';
          fixture.payload.snapshot.session.targetPaceSecPerKm = null;
          fixture.payload.snapshot.session.targetDistanceMeters = null;
          fixture.payload.snapshot.settings.targetPaceSecPerKm = null;
          fixture.payload.snapshot.settings.targetDistanceKm = null;
          fixture.payload.snapshot.wearable.state = 'disconnected';
          fixture.payload.snapshot.wearable.deviceName = null;
          fixture.payload.snapshot.wearable.batteryPercent = null;
          fixture.payload.snapshot.theme = theme;
          const port = {
            onmessage: null as null | ((event: { data: string }) => void),
            postMessage(raw: string) {
              const request = JSON.parse(raw);
              if (request.type !== 'hello') throw new Error('禁止真实命令');
              queueMicrotask(() =>
                port.onmessage?.({
                  data: JSON.stringify({
                    ...fixture,
                    payload: { ...fixture.payload, helloId: request.id },
                  }),
                }),
              );
            },
          };
          Object.assign(window, { LiuyingNative: port });
        },
        { fixture, theme },
      );
      await page.goto('/#/run');
      await expect(page.getByTestId('running-readiness-console')).toBeVisible();
      await expect(page.getByText('未设置距离和配速目标。')).toBeVisible();
      const content = page.locator('.app-content');
      expect(await content.evaluate((el) => el.scrollHeight - el.clientHeight)).toBe(0);
      await page.mouse.move(width / 2, 500);
      await page.mouse.wheel(0, 160);
      await expect.poll(() => content.evaluate((el) => el.scrollTop)).toBe(0);
      await expect(page.getByTestId('running-start-orb')).toBeInViewport();
      await page.screenshot({ path: `build/run-preparation-no-scroll-${theme}-${width}.png` });
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
      await content.evaluate((el) => {
        el.scrollTop = el.scrollHeight;
      });
      await expect(page.getByTestId('running-start-orb')).toBeInViewport();
    });
  }
}

for (const phase of ['idle', 'running', 'paused', 'finished']) {
  for (const width of [393, 360, 320]) {
    test(phase + ' ' + width + ' 连续背景且没有空白滚动', async ({ page }) => {
      await page.setViewportSize({ width, height: 762 });
      await page.route('**/*', (route) =>
        new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
      );
      await page.addInitScript(
        ({ fixture, phase }) => {
          fixture.payload.snapshot.session.state = phase;
          fixture.payload.snapshot.theme = phase === 'paused' ? 'night' : 'morning';
          fixture.payload.snapshot.persistenceState = 'saved';
          fixture.payload.snapshot.completedRun = {
            endedAt: '2026-10-30T04:25:00Z',
            distanceMeters: 10,
            durationSeconds: 20,
            averagePaceSecPerKm: 2101,
            averageHeartRate: 60,
            averageCadence: null,
          };
          const port = {
            onmessage: null as null | ((event: { data: string }) => void),
            postMessage(raw: string) {
              const request = JSON.parse(raw);
              if (request.type !== 'hello') throw new Error('禁止真实命令');
              queueMicrotask(() =>
                port.onmessage?.({
                  data: JSON.stringify({
                    ...fixture,
                    payload: { ...fixture.payload, helloId: request.id },
                  }),
                }),
              );
            },
          };
          Object.assign(window, { LiuyingNative: port });
        },
        { fixture, phase },
      );
      await page.goto('/#/run');
      const panel = page
        .locator(
          phase === 'idle'
            ? '[class*="readinessConsole_"]'
            : phase === 'finished'
              ? '[class*="finishedScorecard_"]'
              : '[class*="activeRunConsole_"]',
        )
        .first();
      await expect(panel).toBeVisible();
      const back = page.getByRole('link', { name: '返回聊天', exact: true });
      await expect(back).toHaveCount(1);
      await expect(back).toBeVisible();
      expect(await panel.locator('a[aria-label="返回聊天"]').count()).toBe(1);
      await expect(panel).toHaveCSS('background-color', 'rgb(23, 23, 31)');
      await expect(panel).toHaveCSS('border-radius', '0px');
      await expect(page.locator('.app-shell-run')).toHaveCSS('background-color', 'rgb(23, 23, 31)');
      expect(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight)).toBe(
        true,
      );
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      expect(
        await page.locator('.app-shell-run').evaluate((el) => el.getBoundingClientRect().height),
      ).toBeGreaterThan(700);
      await page.addStyleTag({
        content: '.app-shell-run { height: 100dvh; height: var(--chat-viewport-height, 100vh); }',
      });
      await expect(panel).toBeInViewport();
      if (width >= 360) {
        if (phase === 'running' || phase === 'paused') {
          const bounds = await page.locator('[class*="activeRunControls_"]').boundingBox();
          expect(bounds).not.toBeNull();
          expect(762 - (bounds!.y + bounds!.height)).toBeLessThan(32);
          expect(bounds!.y).toBeGreaterThan(600);
        }
        expect(
          await page
            .locator('.app-content')
            .evaluate((el) => el.scrollHeight <= el.clientHeight + 1),
        ).toBe(true);
      } else {
        await page.evaluate(() => {
          document.documentElement.style.fontSize = '20px';
        });
        await page.locator('.app-content').evaluate((el) => {
          el.scrollTop = el.scrollHeight;
        });
        const action = page.getByRole('button', {
          name: phase === 'finished' ? '再跑一次' : phase === 'paused' ? '继续跑步' : '结束跑步',
          exact: true,
        });
        if (await action.count()) await expect(action).toBeInViewport();
      }
      await page.screenshot({ path: 'build/run-fullbleed-' + phase + '-' + width + '.png' });
      if (phase === 'finished') {
        await page.getByRole('button', { name: '完成', exact: true }).click();
        await expect(page).toHaveURL(new RegExp('#/agent$'));
        await page.goto('/#/run');
        await expect(page.getByTestId('running-readiness-console')).toBeVisible();
        await page.reload();
        await expect(page.getByTestId('running-readiness-console')).toBeVisible();
      }
    });
  }
}
