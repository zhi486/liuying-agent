import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

async function systemBack(page: Page) {
  return page.evaluate(() =>
    window.dispatchEvent(new Event('liuying:navigate-back', { cancelable: true })),
  );
}

async function management(page: Page) {
  await page.getByRole('button', { name: '打开侧边栏' }).click();
  await page.getByRole('button', { name: '设置与管理' }).click();
  const back = page.getByRole('button', { name: '返回会话列表', exact: true });
  await expect(back).toHaveCSS('border-radius', '50%');
  await page.evaluate(() => {
    document.documentElement.style.setProperty('--back-control-size', '52px');
    document.documentElement.style.setProperty('--back-icon-size', '22px');
  });
  await expect(back).toHaveCSS('width', '52px');
  await expect(back.locator('svg')).toHaveCSS('width', '22px');
}

for (const theme of ['morning', 'night']) {
  test(`${theme} 入口感知返回、系统返回分层与直接进入兜底`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    // 仅提供合成只读快照，禁止真实 Bridge、修改设置和联网调用。
    await page.addInitScript(
      ({ fixture, value }) => {
        const snapshot = fixture.payload.snapshot;
        snapshot.theme = value;
        snapshot.session.state = location.search.includes('back-run=1') ? 'idle' : 'finished';
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
            const data =
              name === 'settings.query'
                ? { commandId: request.id, settings: snapshot.settings }
                : name === 'cloud.list'
                  ? {
                      commandId: request.id,
                      configured: false,
                      configuring: false,
                      origin: null,
                      conversationId: null,
                      conversations: [],
                      items: [],
                    }
                  : null;
            if (!data) throw new Error(`导航测试禁止命令：${name}`);
            emit({
              contractVersion: 1,
              type: 'result',
              id: request.id,
              sentAt: new Date().toISOString(),
              payload: { status: 'accepted', data },
            });
          },
        };
        Object.assign(window, { LiuyingNative: port });
        window.addEventListener('DOMContentLoaded', () => {
          document.documentElement.dataset.theme = value;
          if (value === 'night') document.documentElement.style.fontSize = '20px';
        });
      },
      { fixture, value: theme },
    );
    await page.goto('/#/agent');
    await management(page);
    expect(await systemBack(page)).toBe(false);
    await expect(page.getByRole('button', { name: '新建对话', exact: true })).toBeVisible();
    expect(await systemBack(page)).toBe(false);
    await expect(page.getByRole('button', { name: '关闭侧边栏', exact: true })).toBeHidden();
    expect(await systemBack(page)).toBe(true);

    await management(page);
    await page.getByRole('button', { name: '展开设置入口', exact: true }).click();
    await page.getByRole('link', { name: '用户须知', exact: true }).click();
    const back = page.locator('.app-back-button');
    await expect(back).toHaveAttribute('href', '#/agent');
    await expect(back).toBeInViewport();
    await page.evaluate(() => {
      document.documentElement.style.setProperty('--back-control-size', '52px');
      document.documentElement.style.setProperty('--back-icon-size', '22px');
    });
    await expect(back).toHaveCSS('width', '52px');
    await expect(back.locator('svg')).toHaveCSS('width', '22px');
    await expect(page.getByRole('heading', { name: '用户须知', exact: true })).toBeVisible();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
    await page.screenshot({ path: `build/back-from-sidebar-${theme}.png` });
    await back.click();
    await expect(page).toHaveURL(/#\/agent$/);
    await expect(page.getByRole('button', { name: '展开设置入口', exact: true })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    await page.getByRole('link', { name: '设置', exact: true }).click();
    await expect(page).toHaveURL(/#\/settings$/);
    await page.getByRole('link', { name: '用户须知', exact: true }).click();
    await expect(back).toHaveAttribute('href', '#/settings');
    expect(await systemBack(page)).toBe(false);
    await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible();
    await back.click();
    await expect(page).toHaveURL(/#\/agent$/);

    await page.goto('/?back-run=1#/run');
    const runBack = page.getByRole('link', { name: '返回聊天', exact: true });
    expect((await runBack.boundingBox())!.width).toBeGreaterThanOrEqual(44);
    await page.evaluate(() => {
      document.documentElement.style.setProperty('--back-control-size', '52px');
      document.documentElement.style.setProperty('--back-icon-size', '22px');
    });
    await expect(runBack).toHaveCSS('width', '52px');
    await expect(runBack.locator('svg')).toHaveCSS('width', '22px');
    await page.getByRole('link', { name: '调整跑步目标', exact: true }).click();
    await page.getByRole('link', { name: '用户须知', exact: true }).click();
    await expect(back).toHaveAttribute('href', '#/settings');
    await back.click();
    await expect(page).toHaveURL(/#\/settings$/);
    await expect(back).toHaveAttribute('href', '#/run');
    await back.click();
    await expect(page).toHaveURL(/#\/run$/);

    // 新文档直接进入详情，没有可回退记录时按默认上级逐层返回。
    await page.goto('/?back-direct=1#/history/demo');
    await expect(back).toHaveCount(1);
    await expect(back).toHaveAttribute('href', '#/history');
    await page.getByRole('button', { name: '查看分段详情' }).click();
    await expect(page.getByRole('dialog', { name: '分段详情' })).toBeVisible();
    expect(await systemBack(page)).toBe(false);
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page).toHaveURL(/#\/history\/demo$/);
    await back.scrollIntoViewIfNeeded();
    await page.screenshot({ path: `build/back-run-summary-${theme}.png` });
    await back.click();
    await expect(page).toHaveURL(/#\/history$/);
    await back.click();
    await expect(page).toHaveURL(/#\/agent$/);
    expect(await systemBack(page)).toBe(true);
  });
}
