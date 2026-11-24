import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

async function openWithSyntheticBridge(page: Page, theme: 'morning' | 'night') {
  await page.route('**/*', (route) =>
    new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
  );
  await page.addInitScript(
    ({ fixture, theme }) => {
      const snapshot = fixture.payload.snapshot;
      snapshot.theme = theme;
      snapshot.settings.themePreference = theme;
      snapshot.motionPolicy = 'reduced';
      snapshot.session.state = 'finished';
      const conversationId = '44444444-4444-4444-8444-444444444444';
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
          let data: unknown;
          if (name === 'settings.query') {
            data = { commandId: request.id, settings: snapshot.settings };
          } else if (name === 'cloud.list') {
            data = {
              commandId: request.id,
              configured: true,
              configuring: false,
              origin: 'https://agent.example.com',
              conversationId,
              chatAuthorized: false,
              chatConsentContext: 'a'.repeat(64),
              conversations: [
                { id: conversationId, title: '圆角合成会话', updatedAtMillis: 1000 },
                {
                  id: '55555555-5555-4555-8555-555555555555',
                  title: '第二个合成会话',
                  updatedAtMillis: 900,
                },
              ],
              items: [],
            };
          } else if (name === 'agent.list') {
            data = { commandId: request.id, offset: 0, limit: 20, totalItems: 0, items: [] };
          } else if (name === 'memory.list') {
            data = { commandId: request.id, offset: 0, limit: 20, totalItems: 0, items: [] };
          } else if (name === 'cloud.memoryAuthorization') {
            data = {
              commandId: request.id,
              configured: true,
              enabled: false,
              context: 'b'.repeat(64),
            };
          } else {
            throw new Error(`圆角验收禁止命令：${name}`);
          }

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
    },
    { fixture, theme },
  );
}

async function radiusToken(page: Page, name: string) {
  return page.evaluate((tokenName) => {
    const probe = document.createElement('div');
    probe.style.borderRadius = `var(--radius-${tokenName})`;
    document.body.append(probe);
    const value = getComputedStyle(probe).borderRadius;
    probe.remove();
    return value;
  }, name);
}

async function expectUniformRadius(
  locator: ReturnType<Page['locator']>,
  expected: string,
  label: string,
) {
  const radii = await locator.evaluateAll((elements) =>
    elements
      .filter((element) => {
        const rect = element.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0;
      })
      .map((element) => getComputedStyle(element).borderRadius),
  );
  expect(radii.length, `${label} 未找到可见控件`).toBeGreaterThan(0);
  expect([...new Set(radii)], `${label} 圆角未统一`).toEqual([expected]);
}

for (const theme of ['morning', 'night'] as const) {
  for (const width of [320, 393]) {
    test(`${theme} ${width}px 按输入、按钮、行和特殊形状统一圆角`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 800 });
      await openWithSyntheticBridge(page, theme);

      await page.goto('/#/settings');
      await expect(page.getByLabel('目标配速')).toBeVisible();
      if (width === 320) {
        await page.addStyleTag({ content: 'html { font-size: 20px; }' });
      }

      const inputRadius = await radiusToken(page, 'input');
      const buttonRadius = await radiusToken(page, 'button');
      const rowRadius = await radiusToken(page, 'row');
      const pillRadius = await radiusToken(page, 'pill');
      await expectUniformRadius(
        page.locator('.settings-page input:not([type="checkbox"]), .settings-page select'),
        inputRadius,
        '设置页文本输入框与下拉框',
      );
      await expectUniformRadius(
        page.locator('.settings-page :is(.primary-button, .secondary-button)'),
        buttonRadius,
        '设置页普通操作按钮',
      );
      await page.screenshot({
        path: testInfo.outputPath(`settings-${theme}-${width}.png`),
        fullPage: true,
      });

      await page.goto('/#/memories');
      await expect(page.getByRole('searchbox')).toBeVisible();
      await expectUniformRadius(page.getByRole('searchbox'), inputRadius, '记忆搜索框');
      await expectUniformRadius(
        page.locator('section.standard-page button'),
        buttonRadius,
        '长期记忆普通操作按钮',
      );
      await page.screenshot({
        path: testInfo.outputPath(`memories-${theme}-${width}.png`),
        fullPage: true,
      });

      await page.goto('/#/agent');
      await expect(page.getByLabel('给流萤的消息')).toBeVisible();
      await expectUniformRadius(page.getByLabel('给流萤的消息'), inputRadius, '聊天输入区');
      await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
      await expect(page.locator('#agent-sidebar')).toHaveCSS(
        'transform',
        'matrix(1, 0, 0, 1, 0, 0)',
      );
      await expectUniformRadius(
        page.getByLabel('搜索会话标题').locator('..'),
        inputRadius,
        '会话搜索框',
      );
      await expectUniformRadius(page.getByTestId('conversation-open-c'), rowRadius, '会话行');
      await expectUniformRadius(
        page.getByRole('button', { name: '关闭侧边栏', exact: true }),
        pillRadius,
        '侧栏菜单按钮',
      );
      await page.screenshot({
        path: testInfo.outputPath(`sidebar-${theme}-${width}.png`),
        fullPage: true,
      });

      await page.getByRole('button', { name: '管理对话：圆角合成会话', exact: true }).click();
      await page.getByRole('button', { name: '重命名对话', exact: true }).click();
      await expectUniformRadius(page.getByLabel('重命名当前对话'), inputRadius, '重命名输入框');
      await expectUniformRadius(
        page
          .locator('form')
          .filter({ has: page.getByLabel('重命名当前对话') })
          .getByRole('button'),
        buttonRadius,
        '重命名表单按钮',
      );
      await page.screenshot({
        path: testInfo.outputPath(`agent-${theme}-${width}.png`),
        fullPage: true,
      });

      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
    });
  }
}

test('输入、普通操作、整行与胶囊按独立语义令牌传播', async ({ page }) => {
  await page.setViewportSize({ width: 393, height: 800 });
  await openWithSyntheticBridge(page, 'morning');
  await page.goto('/#/agent');
  await expect(page.getByLabel('给流萤的消息')).toBeVisible();
  await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
  await page.locator('html').evaluate((node) => {
    node.style.setProperty('--radius-input', '21px');
    node.style.setProperty('--radius-button', '22px');
    node.style.setProperty('--radius-row', '23px');
    node.style.setProperty('--radius-pill', '24px');
  });

  await expectUniformRadius(page.getByLabel('搜索会话标题').locator('..'), '21px', '会话搜索输入');
  await expectUniformRadius(
    page.getByRole('button', { name: '新建对话', exact: true }),
    '22px',
    '侧栏普通操作按钮',
  );
  await expectUniformRadius(page.getByTestId('conversation-open-c'), '23px', '会话整行');
  await expectUniformRadius(
    page.getByRole('button', { name: '关闭侧边栏', exact: true }),
    '24px',
    '侧栏菜单胶囊',
  );

  await page.getByRole('button', { name: '管理对话：圆角合成会话', exact: true }).click();
  await page.getByRole('button', { name: '重命名对话', exact: true }).click();
  await expectUniformRadius(page.getByLabel('重命名当前对话'), '21px', '重命名输入');
  await expectUniformRadius(
    page
      .locator('form')
      .filter({ has: page.getByLabel('重命名当前对话') })
      .getByRole('button'),
    '22px',
    '重命名表单按钮',
  );

  await page.locator('html').evaluate((node) => {
    node.style.removeProperty('--radius-input');
    node.style.removeProperty('--radius-button');
    node.style.removeProperty('--radius-row');
    node.style.removeProperty('--radius-pill');
  });
});
