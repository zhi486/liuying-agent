import { expect, test } from '@playwright/test';

for (const theme of ['morning', 'night']) {
  for (const width of [320, 393]) {
    test(`${theme} ${width}px 跑步记录标题唯一且演示记录可达`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 });
      await page.route('**/*', (route) =>
        new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
      );
      await page.goto('/#/history');
      await page.evaluate((theme) => {
        document.documentElement.dataset.theme = theme;
        document.documentElement.style.fontSize = '20px';
      }, theme);
      const title = page.getByRole('heading', { level: 1, name: '跑步记录', exact: true });
      await expect(title).toHaveCount(1);
      await expect(title).toBeInViewport();
      const back = page.getByRole('link', { name: '返回聊天', exact: true });
      await expect(back).toBeInViewport();
      expect((await back.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      const demo = page.getByRole('link', { name: '演示记录', exact: true });
      await expect(demo).toBeInViewport();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({ path: `build/frontend-gate-20261115/history-${theme}-${width}.png` });
      await demo.click();
      await expect(page.getByRole('heading', { name: '模拟跑步记录', exact: true })).toBeVisible();
      await page
        .getByRole('group', { name: '曲线指标' })
        .getByRole('button', { name: '心率', exact: true })
        .click();
      await page.getByRole('button', { name: '查看跑步心率区间详情', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: '跑步心率区间', exact: true });
      await expect(dialog.getByText(/模拟数据/)).toBeVisible();
      await page.screenshot({ path: `build/frontend-gate-20261115/heart-${theme}-${width}.png` });
      await dialog.getByRole('button', { name: '关闭详情', exact: true }).click();
      await page.getByRole('link', { name: '返回跑步记录', exact: true }).click();
      await expect(title).toHaveCount(1);
      await expect(title).toBeVisible();
    });
  }
}
