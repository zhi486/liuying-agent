import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const ready = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
const settings = JSON.parse(
  readFileSync(new URL('../../contracts/mcp-phone-v1/settings.json', import.meta.url), 'utf8'),
);

for (const theme of ['morning', 'night']) {
  test(`${theme} 坏目录仍有对齐的确认移除入口且不删除其他服务`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ ready, settings, theme }) => {
        ready.payload.snapshot.theme = theme;
        ready.payload.snapshot.session.state = 'finished';
        const first = settings.list.services[0];
        const services = [
          first,
          {
            ...first,
            id: '99999999-9999-4999-8999-999999999999',
            endpoint: 'https://other.example.com/mcp',
          },
        ];
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
            else if (name === 'mcp.status') data = settings.status;
            else if (name === 'mcp.list') data = { ...settings.list, services };
            else if (name === 'mcp.detail') {
              emit({
                contractVersion: 1,
                type: 'result',
                id: request.id,
                sentAt: new Date().toISOString(),
                payload: {
                  status: 'rejected',
                  error: { code: 'RESOURCE_UNAVAILABLE', message: '合成坏目录' },
                },
              });
              return;
            } else if (name === 'mcp.remove') {
              if (
                body.id !== first.id ||
                body.revision !== first.revision ||
                body.context !== first.private.context ||
                body.identityToken !== settings.status.identityToken ||
                body.confirmed !== true
              )
                throw new Error('清理绑定不匹配');
              services.splice(0, 1);
              data = { identityToken: settings.status.identityToken, removed: true };
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
        Object.assign(window, { LiuyingNative: port, mcpCleanupCalls: calls });
      },
      { ready, settings, theme },
    );
    await page.goto('/#/settings/plugins');
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    const list = page.getByRole('region', { name: '已登记服务' });
    await list.getByRole('button', { name: '管理此服务' }).first().click();
    await expect(page.getByRole('alert')).toHaveText(/操作结果未确认/);
    const item = list.getByRole('listitem').first();
    await item.getByText('无法管理？移除此服务').click();
    const remove = item.getByRole('button', { name: '确认移除列表中的服务' });
    await expect(remove).toBeDisabled();
    await item.getByRole('checkbox', { name: /确认从列表移除此服务/ }).check();
    await expect(remove).toBeEnabled();
    await remove.focus();
    await remove.scrollIntoViewIfNeeded();
    await expect(remove).toBeInViewport();
    await page.evaluate(() => document.fonts.ready);
    await list.screenshot({ path: `build/mcp-cleanup-${theme}.png` });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await remove.click();
    await expect(page.getByText(/服务已移除，这台手机的私密配置已撤销/)).toBeVisible();
    await expect(list.getByRole('listitem')).toHaveCount(1);
    await expect(list.getByText('https://other.example.com/mcp', { exact: true })).toBeVisible();
    const calls = await page.evaluate(
      () => (window as unknown as { mcpCleanupCalls: string[] }).mcpCleanupCalls,
    );
    expect(calls.filter((name) => name === 'mcp.remove')).toHaveLength(1);
    expect(calls.filter((name) => name === 'mcp.detail')).toHaveLength(1);
    expect(calls.some((name) => ['mcp.discover', 'mcp.approve', 'cloud.send'].includes(name))).toBe(
      false,
    );
    await page.getByRole('link', { name: '返回设置', exact: true }).click();
    await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible();
  });
}
