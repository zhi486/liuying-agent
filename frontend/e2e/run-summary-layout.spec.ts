import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

async function openSummary(page: Page, theme: string, empty = false) {
  // 仅使用合成跑步数据，不读取手机、不访问供应商。
  await page.route('**/*', (route) =>
    new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
  );
  await page.addInitScript(
    ({ fixture, theme, empty }) => {
      fixture.payload.snapshot.theme = theme;
      fixture.payload.snapshot.session.state = 'finished';
      const samples = Array.from({ length: 240 }, (_, index) => ({
        startElapsedSeconds: index * 5,
        endElapsedSeconds: (index + 1) * 5,
        segmentId: 0,
        averagePaceSecPerKm:
          index > 115 && index < 130
            ? null
            : 330 + 90 * Math.sin(index / 12) + 25 * Math.cos(index / 3),
        averageHeartRate:
          index > 115 && index < 130
            ? null
            : 135 + 45 * Math.sin(index / 60) + 12 * Math.cos(index / 8),
        paceValidSeconds: index > 115 && index < 130 ? 0 : 5,
        heartRateValidSeconds: index > 115 && index < 130 ? 0 : 5,
      }));
      const port = {
        onmessage: null as ((event: { data: string }) => void) | null,
        postMessage(raw: string) {
          const request = JSON.parse(raw);
          const emit = (response: unknown) =>
            queueMicrotask(() => port.onmessage?.({ data: JSON.stringify(response) }));
          if (request.type === 'hello') {
            emit({ ...fixture, payload: { ...fixture.payload, helloId: request.id } });
            return;
          }
          const name = request.payload.name;
          let data;
          if (name === 'history.detail')
            data = {
              record: {
                id: 'run-layout',
                startedAt: '2026-09-30T10:00:00Z',
                endedAt: '2026-09-30T10:20:00Z',
                distanceMeters: 3500,
                durationSeconds: 1200,
                averagePaceSecPerKm: 343,
                averageHeartRate: empty ? null : 161,
                averageCadence: empty ? null : 166,
              },
              splits: empty
                ? []
                : [365, 310, 345].map((seconds, i) => ({
                    index: i + 1,
                    endDistanceMeters: (i + 1) * 1000,
                    durationSeconds: seconds,
                    averagePaceSecPerKm: seconds,
                    averageHeartRate: i === 0 ? null : 160 + i * 6,
                  })),
            };
          else if (name === 'history.series')
            data = {
              sessionId: 'run-layout',
              offset: 0,
              totalItems: empty ? 0 : samples.length,
              samples: empty ? [] : samples,
            };
          else throw new Error(`布局测试禁止命令：${name}`);
          emit({
            contractVersion: 1,
            type: 'result',
            id: request.id,
            sentAt: new Date().toISOString(),
            payload: { status: 'accepted', data: { commandId: request.id, ...data } },
          });
        },
      };
      Object.assign(window, { LiuyingNative: port });
    },
    { fixture, theme, empty },
  );
  await page.goto('/#/history/run-layout');
  await expect(page.getByRole('heading', { name: '运动数据', exact: true })).toBeVisible();
}

for (const theme of ['morning', 'night']) {
  test(`${theme} 运动数据分段和两种区间详情可见且不溢出`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await openSummary(page, theme);
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    await expect(page.getByRole('button', { name: '查看跑步心率区间详情' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/run-summary-${theme}.png`, fullPage: true });
    await page.getByRole('button', { name: '查看分段详情', exact: true }).click();
    const splits = page.getByRole('dialog', { name: '分段详情', exact: true });
    await expect(splits.getByText('最快', { exact: true })).toBeVisible();
    await expect(splits.getByText('17:00', { exact: true })).toBeVisible();
    expect(await splits.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    await page.screenshot({ path: `build/run-splits-${theme}.png` });
    await splits.getByRole('button', { name: '关闭分段详情' }).click();
    for (const kind of ['配速', '心率']) {
      const trigger = page.getByRole('button', { name: `查看跑步${kind}区间详情` });
      await trigger.click();
      const dialog = page.getByRole('dialog', { name: `跑步${kind}区间`, exact: true });
      await expect(dialog.getByText(`${kind}区间用时分布`)).toBeVisible();
      await dialog.getByRole('img').click({ position: { x: 120, y: 60 } });
      await expect(dialog.getByRole('status')).toBeVisible();
      expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
      await page.screenshot({ path: `build/run-${kind}-${theme}.png` });
      await page.keyboard.press('Escape');
      await expect(trigger).toBeFocused();
    }
  });
}

test('旧记录不补造曲线、分段或缺失指标', async ({ page }) => {
  await openSummary(page, 'morning', true);
  await expect(page.getByText('未保存分段数据')).toBeVisible();
  await expect(page.getByText('本次记录未采集五秒序列，无法还原曲线。')).toBeVisible();
  await expect(page.getByRole('button', { name: '查看分段详情' })).toHaveCount(0);
  await expect(page.getByText(/千卡|广告|训练评分为/)).toHaveCount(0);
});

test('页面滚动后反复打开详情，标题关闭键始终在视口内且弹层从顶部展示', async ({ page }) => {
  await openSummary(page, 'morning');
  for (const name of ['分段', '跑步配速区间', '跑步心率区间']) {
    const trigger = page.getByRole('button', { name: `查看${name}详情`, exact: true });
    await trigger.scrollIntoViewIfNeeded();
    const before = await page.evaluate(() => window.scrollY);
    await trigger.click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toHaveCSS('position', 'fixed');
    expect((await dialog.boundingBox())!.y).toBeCloseTo(42, 0);
    await expect(dialog.getByRole('heading').first()).toBeInViewport();
    await expect(dialog.getByRole('button', { name: /关闭/ })).toBeInViewport();
    expect(await dialog.evaluate((el) => el.scrollTop)).toBe(0);
    await page.setViewportSize({ width: 393, height: 480 });
    const bounds = (await dialog.boundingBox())!;
    expect(bounds.y + bounds.height).toBeLessThanOrEqual(481);
    await dialog.evaluate((el) => {
      el.scrollTop = el.scrollHeight;
    });
    await expect(dialog.getByRole('button', { name: /关闭/ })).toBeInViewport();
    await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 393, height: 800 });
    await trigger.click();
    expect(await dialog.evaluate((el) => el.scrollTop)).toBe(0);
    await dialog.getByRole('button', { name: /关闭/ }).click();
    await expect(trigger).toBeFocused();
    expect(Math.abs((await page.evaluate(() => window.scrollY)) - before)).toBeLessThan(2);
  }
});
