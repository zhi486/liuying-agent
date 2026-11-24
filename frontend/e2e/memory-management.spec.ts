import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

for (const theme of ['morning', 'night']) {
  test(`${theme} 记忆授权历史恢复在手机与窄屏大字体下可操作`, async ({ page }) => {
    const width = theme === 'night' ? 320 : 393;
    await page.setViewportSize({ width, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ fixture, theme }) => {
        const snapshot = fixture.payload.snapshot;
        snapshot.theme = theme;
        snapshot.session.state = 'finished';
        const calls: { name: string; body: Record<string, unknown> }[] = [];
        const memoryId = '22222222-2222-4222-8222-222222222222';
        let version = 2;
        let content =
          '我喜欢晚上轻松跑，希望鼓励语气温柔一些。合成测试：长内容不应挤压操作按钮或超出手机边界。';
        const previous = '我更喜欢清晨跑步。';
        let enabled = false;
        let generation = 0;
        const token = () => ['a', 'b', 'c'][generation].repeat(64);
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
            const { name, body } = request.payload;
            calls.push({ name, body });
            const commandId = request.id;
            const memory = () => ({
              id: memoryId,
              content,
              source: 'user_explicit',
              createdAtMillis: 1000,
              updatedAtMillis: 2000,
              version,
            });
            let data: unknown;
            if (name === 'cloud.memoryAuthorization' || name === 'cloud.memoryConsent') {
              if (name === 'cloud.memoryConsent') {
                if (
                  body.identityToken !== token() ||
                  typeof body.enabled !== 'boolean' ||
                  body.enabled === enabled
                )
                  throw new Error('合成授权代次无效');
                enabled = body.enabled;
                generation++;
              }
              data = { commandId, configured: true, enabled, context: token() };
            } else if (name === 'memory.list') {
              data = { commandId, offset: 0, limit: 20, totalItems: 1, items: [memory()] };
            } else if (name === 'memory.history') {
              if (body.memoryId !== memoryId || body.offset !== 0 || body.limit !== 10)
                throw new Error('合成历史请求无效');
              data = {
                commandId,
                memoryId,
                currentVersion: version,
                offset: 0,
                limit: 10,
                totalItems: 2,
                items: [
                  {
                    version,
                    content,
                    changedAtMillis: 2000,
                    changeSource: 'agent',
                    turnId: '33333333-3333-4333-8333-333333333333',
                  },
                  {
                    version: 1,
                    content: previous,
                    changedAtMillis: 1000,
                    changeSource: 'manual',
                    turnId: null,
                  },
                ],
              };
            } else if (name === 'memory.restore') {
              if (
                body.memoryId !== memoryId ||
                body.version !== 2 ||
                body.targetVersion !== 1 ||
                body.confirmed !== true
              )
                throw new Error('合成恢复请求无效');
              version = 3;
              content = previous;
              data = { commandId, memory: memory() };
            } else if (name === 'settings.query') data = { commandId, settings: snapshot.settings };
            else throw new Error(`合成测试禁止命令：${name}`);
            emit({
              contractVersion: 1,
              type: 'result',
              id: request.id,
              sentAt: new Date().toISOString(),
              payload: { status: 'accepted', data },
            });
          },
        };
        Object.assign(window, { LiuyingNative: port, memoryManagementCalls: calls });
      },
      { fixture, theme },
    );
    await page.goto('/#/memories');
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    await expect(page.getByText('自动记忆未开启')).toBeVisible();
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    await expect(page.getByRole('switch', { name: '自动记忆' })).toBeDisabled();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/memory-authorization-off-${theme}.png`, fullPage: true });
    await page.getByRole('checkbox').check();
    await page.getByRole('switch', { name: '自动记忆' }).click();
    await expect(page.getByText('自动记忆已开启')).toBeVisible();
    await page.screenshot({ path: `build/memory-authorization-on-${theme}.png`, fullPage: true });
    await page.getByRole('switch', { name: '自动记忆' }).click();
    await expect(page.getByText('自动记忆未开启')).toBeVisible();

    await page.getByRole('button', { name: /^删除记忆：/ }).click();
    const deletion = page.getByRole('dialog', { name: '删除这条记忆？' });
    await expect(deletion).toBeVisible();
    for (const height of [800, 460]) {
      await page.setViewportSize({ width, height });
      await expect
        .poll(async () => {
          const box = await deletion.boundingBox();
          return (
            !!box &&
            box.x >= 0 &&
            box.width <= width &&
            box.y >= 0 &&
            Math.abs(box.y + box.height - height) < 2
          );
        })
        .toBe(true);
      await deletion.getByRole('button', { name: '保留记忆' }).scrollIntoViewIfNeeded();
      await page.screenshot({ path: `build/final-memory-delete-${theme}-${height}.png` });
    }
    await deletion.getByRole('button', { name: '保留记忆' }).click();
    await expect(deletion).not.toBeVisible();
    expect(
      await page.evaluate(
        () =>
          (
            window as Window & { memoryManagementCalls?: { name: string }[] }
          ).memoryManagementCalls?.filter((call) => call.name === 'memory.delete').length,
      ),
    ).toBe(0);
    await page.setViewportSize({ width, height: 800 });

    await page.getByRole('button', { name: /^版本历史：/ }).click();
    await expect(page.getByText('版本 2 · 当前 · 流萤记住')).toBeVisible();
    await page.getByRole('button', { name: '恢复版本 1' }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: `build/memory-history-${theme}.png`, fullPage: true });
    await page.getByRole('button', { name: '恢复版本 1' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('dialog').getByText('我更喜欢清晨跑步。')).toBeVisible();
    await page.screenshot({ path: `build/memory-restore-confirm-${theme}.png` });
    await page.getByRole('button', { name: '取消恢复' }).click();
    expect(
      await page.evaluate(
        () =>
          (
            window as Window & { memoryManagementCalls?: { name: string }[] }
          ).memoryManagementCalls?.filter((item) => item.name === 'memory.restore').length,
      ),
    ).toBe(0);
    await page.getByRole('button', { name: '恢复版本 1' }).click();
    await page.getByRole('button', { name: '确认恢复' }).click();
    await expect(page.getByText('已恢复为新版本，原有版本仍保留。')).toBeVisible();
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(page.getByText(/^版本 3 ·/)).toBeVisible();

    await page.getByRole('button', { name: /^删除记忆：/ }).click();
    await expect(page.getByRole('dialog').getByText(/正文及全部版本历史/)).toBeVisible();
    await page.getByRole('button', { name: '保留记忆' }).click();
    await page.getByRole('button', { name: /^编辑记忆：/ }).click();
    await page.setViewportSize({ width, height: 450 });
    const input = page.getByRole('textbox', { name: '修改记忆' });
    await input.fill('键盘模拟草稿，不发送不保存');
    await page.getByRole('button', { name: '保存修改' }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('button', { name: '保存修改' })).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/memory-keyboard-${theme}.png` });
    await page.getByRole('button', { name: '取消编辑' }).click();
    await page.setViewportSize({ width, height: 800 });
    await page.goto('/#/settings');
    await page.getByRole('link', { name: '长期记忆', exact: true }).click();
    await expect(page.getByRole('heading', { name: '我的记忆' })).toBeVisible();
    await expect(page.getByRole('link', { name: '返回设置' })).toHaveAttribute(
      'href',
      '#/settings',
    );
    const calls = await page.evaluate(
      () =>
        (window as Window & { memoryManagementCalls?: { name: string }[] }).memoryManagementCalls ??
        [],
    );
    expect(calls.filter((item) => item.name === 'cloud.memoryConsent')).toHaveLength(2);
    expect(calls.filter((item) => item.name === 'memory.restore')).toHaveLength(1);
    expect(
      calls.every((item) =>
        [
          'cloud.memoryAuthorization',
          'cloud.memoryConsent',
          'memory.list',
          'memory.history',
          'memory.restore',
          'settings.query',
        ].includes(item.name),
      ),
    ).toBe(true);
  });
}
