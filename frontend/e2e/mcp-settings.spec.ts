import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const ready = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
const settings = JSON.parse(
  readFileSync(new URL('../../contracts/mcp-phone-v1/settings.json', import.meta.url), 'utf8'),
);
for (const theme of ['morning', 'night']) {
  test(`${theme} MCP 设置在手机和窄屏大字体下对齐并仅使用合成命令`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
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
            const { name, body } = request.payload;
            calls.push(name);
            let data: unknown;
            if (name === 'settings.query') data = { settings: ready.payload.snapshot.settings };
            else if (name === 'mcp.status' || name === 'mcp.recover') {
              if (name === 'mcp.recover') {
                if (body.requestId !== settings.status.operations[0].requestId)
                  throw new Error('恢复标识不匹配');
                settings.status.operations[0].status = 'completed';
              }
              data = settings.status;
            } else if (name === 'mcp.endpoint' || name === 'mcp.remove') {
              if (
                body.confirmed !== true ||
                body.id !== settings.detail.service.id ||
                body.revision !== settings.detail.service.revision ||
                body.context !== settings.detail.private.context
              )
                throw new Error('变更审核绑定不匹配');
              settings.detail.private = {
                ...settings.detail.private,
                configured: false,
                hasCredential: false,
                endpointMatches: false,
                context: 'c'.repeat(64),
              };
              if (name === 'mcp.endpoint') {
                if (body.endpoint !== 'https://changed.example.com/mcp')
                  throw new Error('新地址不匹配');
                settings.detail.service = {
                  ...settings.detail.service,
                  endpoint: body.endpoint,
                  revision: '88888888-8888-4888-8888-888888888888',
                  catalog: null,
                  grants: [],
                  enabled: false,
                };
                settings.list.services = [
                  { ...settings.detail.service, private: settings.detail.private },
                ];
                data = settings.detail;
              } else {
                settings.list.services = [];
                data = { identityToken: settings.status.identityToken, removed: true };
              }
            } else if (name === 'mcp.list') data = settings.list;
            else if (name === 'mcp.detail' || name === 'mcp.approve') {
              if (body.id !== settings.detail.service.id) throw new Error('服务标识不匹配');
              if (name === 'mcp.approve') {
                if (
                  body.confirmed !== true ||
                  body.selection.length !== 1 ||
                  body.selection[0].mode !== 'confirm'
                )
                  throw new Error('授权不匹配');
                settings.detail.service.enabled = true;
                settings.detail.service.revision = '77777777-7777-4777-8777-777777777777';
                settings.detail.service.grants = [
                  { ...body.selection[0], modelName: 'mcp_synthetic_0' },
                ];
              }
              data = settings.detail;
            } else throw new Error(`合成测试禁止命令：${name}`);
            emit({
              contractVersion: 1,
              type: 'result',
              id: request.id,
              sentAt: new Date().toISOString(),
              payload: { status: 'accepted', data: { ...(data as object), commandId: request.id } },
            });
          },
        };
        Object.assign(window, { LiuyingNative: port, mcpSettingsCalls: calls });
      },
      { ready, settings, theme },
    );
    await page.goto('/#/settings');
    await page.getByRole('link', { name: 'MCP 插件', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'MCP 插件', exact: true })).toBeVisible();
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    await expect(page.getByRole('button', { name: '管理此服务' })).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    await page.getByRole('button', { name: '重新读取配置' }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('button', { name: '重新读取配置' })).toBeInViewport();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `build/mcp-settings-list-${theme}.png`, fullPage: true });
    await page.getByLabel('服务地址（不能包含密钥）').fill('https://synthetic.example.com/mcp');
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 420 });
    await page.getByLabel('服务地址（不能包含密钥）').focus();
    await page.getByRole('button', { name: '仅登记服务' }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('button', { name: '仅登记服务' })).toBeInViewport();
    await page.screenshot({ path: `build/mcp-settings-keyboard-${theme}.png` });
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await page.getByRole('button', { name: '管理此服务' }).click();
    const steps = page.getByRole('navigation', { name: '插件接入步骤' });
    await steps.getByRole('button', { name: /审阅授权/ }).click();
    await expect(page.locator('#mcp-permissions')).toBeFocused();
    await steps.getByRole('button', { name: /手机私密配置/ }).click();
    await expect(page.locator('#mcp-private')).toBeFocused();
    const card = page.getByRole('region', { name: '当前服务管理' });
    await expect(card.getByRole('button', { name: '发现工具目录' })).toBeDisabled();
    await card.getByRole('combobox').first().selectOption('confirm');
    await card.getByRole('checkbox', { name: /我已审核/ }).check();
    await expect(card.getByRole('button', { name: '保存所选权限' })).toBeEnabled();
    await card.getByText('查看工具定义（第三方提供）').first().click();
    await card.screenshot({ path: `build/mcp-settings-tools-${theme}.png` });
    await card.getByRole('button', { name: '保存所选权限' }).click();
    await expect(
      page.getByText('工具权限已保存；可到聊天选择本次使用的插件。', { exact: true }),
    ).toBeVisible();
    await expect(card.getByRole('button', { name: '保存所选权限' })).toBeDisabled();
    await card.getByText('修改地址或移除服务', { exact: true }).click();
    const changes = card.locator('details').last();
    await expect(changes.getByRole('button', { name: '确认移除服务' })).toBeDisabled();
    await changes
      .getByLabel('新的服务地址', { exact: true })
      .fill('https://changed.example.com/mcp');
    await expect(changes.getByRole('button', { name: '确认修改地址' })).toBeDisabled();
    await changes
      .getByRole('checkbox', { name: '确认修改地址，并撤销旧连接配置和工具权限。' })
      .check();
    await changes.screenshot({ path: `build/mcp-settings-changes-${theme}.png` });
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 420 });
    await changes.getByLabel('新的服务地址', { exact: true }).focus();
    await changes.getByRole('button', { name: '确认修改地址' }).scrollIntoViewIfNeeded();
    await expect(changes.getByRole('button', { name: '确认修改地址' })).toBeInViewport();
    await page.screenshot({ path: `build/mcp-settings-changes-keyboard-${theme}.png` });
    await changes.getByRole('button', { name: '确认修改地址' }).click();
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await expect(page.getByText(/服务地址已修改，旧目录/)).toBeVisible();
    await expect(card.getByText('尚无已确认目录。', { exact: true })).toBeVisible();
    await expect(card.getByRole('button', { name: '发现工具目录' })).toBeDisabled();
    await card.getByText('修改地址或移除服务', { exact: true }).click();
    await expect(changes.getByRole('button', { name: '确认移除服务' })).toBeDisabled();
    await changes
      .getByRole('checkbox', { name: '确认移除此服务，并撤销这台手机的私密配置。' })
      .check();
    await changes.getByRole('button', { name: '确认移除服务' }).click();
    await expect(page.getByText(/服务已移除，这台手机的私密配置已撤销/)).toBeVisible();
    await expect(page.getByRole('button', { name: '管理此服务' })).toHaveCount(0);
    await expect(card).toHaveCount(0);
    await page.getByRole('button', { name: '查询原发现记录' }).click();
    await expect(page.getByText('目录已保存', { exact: true })).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    const calls = await page.evaluate(
      () => (window as unknown as { mcpSettingsCalls: string[] }).mcpSettingsCalls,
    );
    expect(calls.filter((name) => name === 'mcp.approve')).toHaveLength(1);
    expect(calls.filter((name) => name === 'mcp.endpoint')).toHaveLength(1);
    expect(calls.filter((name) => name === 'mcp.remove')).toHaveLength(1);
    expect(calls).not.toContain('mcp.discover');
    expect(calls).not.toContain('cloud.send');
    await page.getByRole('link', { name: '返回设置', exact: true }).click();
    await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible();
  });
}
