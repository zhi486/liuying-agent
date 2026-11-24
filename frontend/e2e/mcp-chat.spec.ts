import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const ready = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/mcp-phone-v1/settings.json', import.meta.url), 'utf8'),
);
for (const theme of ['morning', 'night']) {
  test(`${theme} 本次插件选择和授权预览在手机窄屏键盘布局可用`, async ({ page }) => {
    const width = theme === 'night' ? 320 : 393;
    await page.setViewportSize({ width, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ ready, fixture, theme }) => {
        ready.payload.snapshot.theme = theme;
        ready.payload.snapshot.session.state = 'finished';
        const conversationId = '33333333-3333-4333-8333-333333333333';
        const token = '66666666-6666-4666-8666-666666666666';
        const calls: string[] = [];
        const port = {
          onmessage: null as ((event: { data: string }) => void) | null,
          postMessage(raw: string) {
            const request = JSON.parse(raw);
            const emit = (data: unknown) =>
              queueMicrotask(() => port.onmessage?.({ data: JSON.stringify(data) }));
            if (request.type === 'hello') {
              emit({ ...ready, payload: { ...ready.payload, helloId: request.id } });
              return;
            }
            const { name, body } = request.payload;
            calls.push(name);
            let data: unknown;
            if (name === 'agent.list') data = { offset: 0, limit: 20, totalItems: 0, items: [] };
            else if (name === 'cloud.list')
              data = {
                configured: true,
                configuring: false,
                origin: 'https://agent.example.com',
                chatAuthorized: true,
                chatConsentContext: fixture.status.identityToken,
                conversationId,
                conversations: [],
                items: [],
              };
            else if (name === 'mcp.status') data = fixture.status;
            else if (name === 'mcp.list') {
              if (body.identityToken !== fixture.status.identityToken || body.after !== null)
                throw new Error('目录身份未绑定');
              data = {
                ...fixture.list,
                services: fixture.list.services.map((row: object) => ({ ...row, enabled: true })),
              };
            } else if (name === 'cloud.prepare') {
              if (
                body.conversationId !== conversationId ||
                body.mcpServices?.length !== 1 ||
                body.mcpServices[0] !== fixture.list.services[0].id
              )
                throw new Error('本次选择不一致');
              data = {
                token,
                origin: 'https://agent.example.com',
                prompt: body.prompt,
                ...(body.knowledge ? { knowledge: body.knowledge } : {}),
                conversation: {
                  version: 'conversation_v1',
                  id: conversationId,
                  approved: true,
                  messages: [],
                },
                memories: [],
                mcpServices: [
                  {
                    id: fixture.list.services[0].id,
                    endpoint: fixture.list.services[0].endpoint,
                    tools: [
                      { name: 'notes.search', mode: 'read' },
                      {
                        name: 'notes.write_with_long_name_for_mobile_confirmation',
                        mode: 'confirm',
                      },
                    ],
                  },
                ],
              };
            } else if (name === 'cloud.discard') data = { discarded: true };
            else throw new Error(`合成测试禁止调用：${name}`);
            emit({
              contractVersion: 1,
              type: 'result',
              id: request.id,
              sentAt: new Date().toISOString(),
              payload: { status: 'accepted', data: { ...(data as object), commandId: request.id } },
            });
          },
        };
        Object.assign(window, { LiuyingNative: port, mcpChatCalls: calls });
      },
      { ready, fixture, theme },
    );
    await page.goto('/#/agent');
    await expect(page.getByLabel('给流萤的消息')).toBeEnabled();
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    await page.getByLabel('更多输入选项').click();
    const dialog = page.getByRole('dialog', { name: '输入选项', exact: true });
    await expect(dialog).toBeInViewport();
    expect((await dialog.boundingBox())!.height).toBeGreaterThan(100);
    // 模拟 WebView 键盘只缩小可视视口，布局视口仍保持原高度。
    await page.evaluate(() => {
      Object.defineProperty(window.visualViewport, 'height', { configurable: true, value: 470 });
      Object.defineProperty(window.visualViewport, 'offsetTop', { configurable: true, value: 20 });
      window.visualViewport!.dispatchEvent(new Event('resize'));
    });
    await expect(dialog).toHaveCSS('max-height', '385.4px');
    const keyboardBox = (await dialog.boundingBox())!;
    expect(keyboardBox.y).toBeGreaterThanOrEqual(20);
    expect(keyboardBox.y + keyboardBox.height).toBeLessThanOrEqual(491);
    await expect(
      dialog.getByRole('button', { name: '关闭输入选项', exact: true }),
    ).toBeInViewport();
    await page.getByRole('button', { name: '读取可用插件' }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: `build/mcp-chat-sheet-visual-viewport-${theme}.png` });
    await page.evaluate(() => {
      Reflect.deleteProperty(window.visualViewport!, 'height');
      Reflect.deleteProperty(window.visualViewport!, 'offsetTop');
      window.visualViewport!.dispatchEvent(new Event('resize'));
    });
    await page.getByRole('button', { name: '读取可用插件' }).click();
    await page.getByRole('checkbox').check();
    await page.getByRole('link', { name: '管理 MCP 插件' }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('link', { name: '管理 MCP 插件' })).toBeInViewport();
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: `build/mcp-chat-picker-${theme}.png`, fullPage: true });
    const overflow = () =>
      page.evaluate(() =>
        [...document.querySelectorAll('fieldset'), document.documentElement]
          .map((node) => ({
            tag: node.tagName,
            text: node.textContent?.slice(0, 40),
            visible: node.checkVisibility(),
            client: node.clientWidth,
            viewport: innerWidth,
            scroll: node.scrollWidth,
          }))
          .filter(
            (box) =>
              box.visible &&
              (box.scroll > box.client || (box.tag === 'HTML' && box.scroll > box.viewport)),
          ),
      );
    expect(await overflow()).toEqual([]);
    // 折叠内容没有可见布局；另外展开参考资料，检查它显示时也没有横向溢出。
    await page.locator('summary').filter({ hasText: '本次参考' }).click();
    expect(await overflow()).toEqual([]);
    await page.locator('summary').filter({ hasText: '本次参考' }).click();
    await page.getByRole('button', { name: '关闭输入选项', exact: true }).click();
    await page.getByLabel('给流萤的消息').fill('请用笔记插件查看今晚的安排');
    await page.getByRole('button', { name: '在线执行', exact: true }).click();
    const preview = page.getByRole('region', { name: '本次插件权限' });
    await expect(preview).toContainText('每次展示完整参数');
    await preview.scrollIntoViewIfNeeded();
    await page.screenshot({ path: `build/mcp-chat-preview-${theme}.png`, fullPage: true });
    expect(await overflow()).toEqual([]);
    await page.setViewportSize({ width, height: 470 });
    await page.getByRole('button', { name: '同意本次上传并发送' }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('button', { name: '同意本次上传并发送' })).toBeInViewport();
    await expect(page.getByLabel('给流萤的消息')).toBeInViewport();
    await page.screenshot({ path: `build/mcp-chat-keyboard-${theme}.png`, fullPage: true });
    expect(await overflow()).toEqual([]);
    await page.getByRole('button', { name: '暂不发送', exact: true }).click();
    await expect(page.getByRole('region', { name: '本次云端授权' })).toHaveCount(0);
    const calls = await page.evaluate(
      () => (window as unknown as { mcpChatCalls: string[] }).mcpChatCalls,
    );
    expect(calls).not.toContain('cloud.send');
    expect(calls).not.toContain('cloud.confirm');
    expect(calls).not.toContain('mcp.discover');
    expect(calls.filter((name) => name === 'mcp.list')).toHaveLength(1);
    expect(calls.filter((name) => name === 'cloud.prepare')).toHaveLength(1);
  });
}
