import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
for (const theme of ['morning', 'night']) {
  test(`${theme} 只读记忆文档沿用管理页且窄屏长文本不溢出`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ fixture, theme }) => {
        fixture.payload.snapshot.theme = theme;
        fixture.payload.snapshot.session.state = 'finished';
        const calls: string[] = [];
        const entries = Array.from({ length: 2 }, (_, index) => ({
          memory: {
            id: `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`,
            content:
              index === 0
                ? '合成偏好：周末轻松跑，喜欢温柔一点的鼓励。多行正文保持原样。\n<script>只是文本，不执行</script>'
                : '合成长文本：' +
                  '这是用于窄屏阅读检查的合成目标与计划，不是用户真实记忆。'.repeat(4),
            source: 'user_explicit',
            version: 2,
            createdAtMillis: 1000,
            updatedAtMillis: 2000,
          },
          changeSource: index === 0 ? 'agent' : null,
          sourceTurnId: index === 0 ? '22222222-2222-4222-8222-222222222222' : null,
          category: index === 0 ? 'preference' : null,
        }));
        Object.assign(window, {
          documentTestCalls: calls,
          documentTestMode: 'content',
          documentExportMode: 'cancelled',
        });
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
            calls.push(name);
            const commandId = request.id;
            let data: unknown;
            if (name === 'memory.list')
              data = {
                commandId,
                offset: 0,
                limit: 20,
                totalItems: entries.length,
                items: entries.map((entry) => entry.memory),
              };
            else if (name === 'cloud.memoryAuthorization')
              data = { commandId, configured: false, enabled: false, context: null };
            else if (
              name === 'memory.exportBegin' ||
              name === 'memory.exportStatus' ||
              name === 'memory.exportCancel'
            ) {
              const phase =
                name === 'memory.exportBegin'
                  ? 'choosing'
                  : name === 'memory.exportCancel'
                    ? 'cancelled'
                    : (window as unknown as { documentExportMode: string }).documentExportMode;
              data = {
                commandId,
                exportId: request.payload.body.exportId,
                phase,
                totalItems: phase === 'saved' ? 121 : null,
                generatedAtMillis: phase === 'saved' ? 3000 : null,
              };
            } else if (name === 'memory.document') {
              const mode = (window as unknown as { documentTestMode: string }).documentTestMode;
              if (mode === 'error') {
                emit({
                  contractVersion: 1,
                  type: 'result',
                  id: request.id,
                  sentAt: new Date().toISOString(),
                  payload: {
                    status: 'rejected',
                    error: { code: 'INTERNAL_ERROR', message: '合成私密异常，不应展示' },
                  },
                });
                return;
              }
              const items = mode === 'empty' ? [] : entries;
              data = {
                commandId,
                generatedAtMillis: 3000,
                offset: 0,
                limit: 20,
                totalItems: items.length,
                items,
              };
            } else throw new Error(`合成测试禁止命令：${name}`);
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
    await page.goto('/#/memories');
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    const open = page.getByRole('button', { name: '查看记忆文档' });
    await open.click();
    await expect(page.getByRole('heading', { name: '记忆文档', exact: true })).toBeVisible();
    await expect(page.getByText('偏好 · 版本 2 · Agent 经授权写入')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/memory-document-${theme}.png`, fullPage: true });
    const save = page.getByRole('button', { name: '保存完整 Markdown' });
    await save.click();
    await expect(page.getByText('已取消保存，未写入记忆正文。')).toBeVisible();
    await page.evaluate(() => Object.assign(window, { documentExportMode: 'failed' }));
    await save.click();
    await expect(page.getByText(/保存失败，未确认完整写入/)).toBeVisible();
    const documentPanel = page.locator('section[aria-labelledby="memory-document-title"]');
    await documentPanel.screenshot({ path: `build/memory-save-failed-${theme}.png` });
    await page.evaluate(() => Object.assign(window, { documentExportMode: 'saved' }));
    await save.click();
    await expect(page.getByText(/已保存 121 条有效记忆/)).toBeVisible();
    await documentPanel.screenshot({ path: `build/memory-save-saved-${theme}.png` });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.getByRole('button', { name: '重新读取文档' }).scrollIntoViewIfNeeded();
    await page.evaluate(() => Object.assign(window, { documentTestMode: 'error' }));
    await page.getByRole('button', { name: '重新读取文档' }).click();
    await expect(page.getByRole('alert')).toContainText('文档未读取成功');
    await expect(page.getByText('合成私密异常，不应展示')).toHaveCount(0);
    await page.screenshot({ path: `build/memory-document-error-${theme}.png`, fullPage: true });
    await page.evaluate(() => Object.assign(window, { documentTestMode: 'empty' }));
    await page.getByRole('button', { name: '重新读取文档' }).click();
    await expect(page.getByText('暂无有效记忆。', { exact: true })).toBeVisible();
    await page.screenshot({ path: `build/memory-document-empty-${theme}.png`, fullPage: true });
    await page.getByRole('button', { name: '收起文档' }).click();
    await expect(page.getByRole('heading', { name: '记忆文档', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '返回聊天', exact: true })).toBeVisible();
    expect(
      await page.evaluate(() =>
        (window as unknown as { documentTestCalls: string[] }).documentTestCalls.every((name) =>
          [
            'memory.list',
            'memory.document',
            'memory.exportBegin',
            'memory.exportStatus',
            'memory.exportCancel',
            'cloud.memoryAuthorization',
          ].includes(name),
        ),
      ),
    ).toBe(true);
  });
}
