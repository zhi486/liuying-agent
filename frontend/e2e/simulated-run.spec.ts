import { expect, test } from '@playwright/test';

for (const theme of ['morning', 'night']) {
  test(`模拟记录 ${theme} 可查看区间与关闭详情`, async ({ page }) => {
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await page.goto('/#/history/demo');
    await expect(page.getByRole('heading', { name: '模拟跑步记录' })).toBeVisible();
    await page.evaluate((theme) => {
      document.documentElement.dataset.theme = theme;
      if (theme === 'night') document.documentElement.style.fontSize = '20px';
    }, theme);
    await expect(page.getByText('6.00', { exact: true }).first()).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/simulated-summary-${theme}.png`, fullPage: true });
    for (const kind of ['配速', '心率']) {
      await page
        .getByRole('group', { name: '曲线指标' })
        .getByRole('button', { name: kind, exact: true })
        .click();
      const trigger = page.getByRole('button', { name: `查看跑步${kind}区间详情` });
      await trigger.click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText(/模拟数据/)).toBeVisible();
      await expect(dialog.getByRole('heading').first()).toBeInViewport();
      await dialog.getByRole('img').click({ position: { x: 120, y: 60 } });
      await expect(dialog.getByRole('status')).toBeVisible();
      expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
      await page.screenshot({ path: `build/simulated-${kind}-${theme}.png` });
      await dialog.evaluate((el) => {
        el.scrollTop = el.scrollHeight;
      });
      const close = dialog.getByRole('button', { name: '关闭详情' });
      await expect(close).toBeInViewport();
      expect(
        await close.evaluate((el) => {
          const bounds = el.getBoundingClientRect();
          return (
            document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2) ===
            el
          );
        }),
      ).toBe(true);
      await page.screenshot({ path: `build/simulated-sticky-${kind}-${theme}.png` });
      await dialog.getByRole('button', { name: '关闭详情' }).click();
      await expect(trigger).toBeFocused();
    }
    await page.getByRole('link', { name: '返回跑步记录' }).click();
    await expect(page.getByRole('link', { name: '演示记录', exact: true })).toBeVisible();
  });
}

test('两种曲线支持真实触摸拖动、边缘夹取、缺失值与取消手势', async ({ browser }) => {
  const context = await browser.newContext({
    viewport: { width: 393, height: 800 },
    hasTouch: true,
  });
  const page = await context.newPage();
  await page.route('**/*', (route) =>
    new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
  );
  await page.goto('http://127.0.0.1:4174/#/history/demo');
  const cdp = await context.newCDPSession(page);
  for (const kind of ['配速', '心率']) {
    await page
      .getByRole('group', { name: '曲线指标' })
      .getByRole('button', { name: kind, exact: true })
      .click();
    await page.getByRole('button', { name: `查看跑步${kind}区间详情` }).click();
    const dialog = page.getByRole('dialog');
    const chart = dialog.getByRole('img');
    const box = (await chart.boundingBox())!;
    const y = box.y + box.height / 2;
    const x = (seconds: number) => box.x + (box.width * (48 + (340 * seconds) / 1960)) / 400;
    const scroll = await dialog.evaluate((el) => el.scrollTop);
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchStart',
      touchPoints: [{ x: x(102), y }],
    });
    await expect(dialog.getByRole('status')).toContainText('01:40–01:45');
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: x(1052), y }],
    });
    await expect(dialog.getByRole('status')).toContainText('未采集');
    await expect(dialog.getByRole('status')).toContainText('有效 0 秒');
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: box.x + box.width - 1, y }],
    });
    await expect(dialog.getByRole('status')).toContainText('32:35–32:40');
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    expect(await dialog.evaluate((el) => el.scrollTop)).toBe(scroll);
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchStart',
      touchPoints: [{ x: x(250), y }],
    });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
    const cancelled = await dialog.getByRole('status').textContent();
    await page.mouse.move(x(500), y);
    await expect(dialog.getByRole('status')).toHaveText(cancelled!);
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchStart',
      touchPoints: [{ x: box.x + 1, y }],
    });
    await expect(dialog.getByRole('status')).toContainText('00:00–00:05');
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.screenshot({ path: `build/simulated-touch-${kind}.png` });
    await dialog.getByRole('button', { name: '关闭详情' }).click();
  }
  await context.close();
});
