import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';

const readyFixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
const longTitle =
  '合成会话长标题用于检查归档管理与永久删除确认在窄屏和大字体下不会挤压操作按钮或横向溢出';

async function openSyntheticChat(page: Page, theme: string, duplicateCount = 0) {
  await page.route('**/*', (route) =>
    new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
  );
  await page.addInitScript(
    ({ fixture, theme, longTitle, duplicateCount }) => {
      fixture.payload.snapshot.theme = theme;
      fixture.payload.snapshot.session.state = 'finished';
      const id = '11111111-1111-4111-8111-111111111111';
      const archivedId = '22222222-2222-4222-8222-222222222222';
      const requestId = '33333333-3333-4333-8333-333333333333';
      const row = {
        requestId,
        prompt: '合成聊天正文',
        answer: '合成历史回答，未调用模型。',
        busy: false,
        attempted: true,
        status: 'completed',
        resultState: 'acknowledged',
        reason: null,
        error: null,
        pendingControl: null,
        confirmedControl: null,
        memoryRefs: [],
      };
      const calls: { name: string; body: unknown }[] = [];
      let listing = {
        configured: true,
        configuring: false,
        origin: 'https://agent.example.com',
        conversationId: id as string | null,
        conversationArchived: false,
        legacyAvailable: true,
        legacyArchived: false,
        conversations: [
          { id, title: longTitle, archived: false, updatedAtMillis: Date.now() },
          { id: archivedId, title: '合成归档对话', archived: true, updatedAtMillis: Date.now() },
          ...Array.from({ length: duplicateCount }, (_, index) => ({
            id: `44444444-4444-4444-8444-${String(index).padStart(12, '0')}`,
            title: '同名合成会话',
            archived: false,
            updatedAtMillis: Date.now(),
          })),
          ...Array.from({ length: duplicateCount }, (_, index) => ({
            id: `55555555-5555-4555-8555-${String(index).padStart(12, '0')}`,
            title: '同名合成会话',
            archived: true,
            updatedAtMillis: Date.now(),
          })),
        ],
        items: [row],
        cleanupPending: 0,
        cleanupBusy: false,
        retainedUsage: [] as { requestId: string; atMillis: number; cost: null }[],
      };
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
          const { name, body } = request.payload;
          calls.push({ name, body });
          if (name === 'cloud.selectConversation')
            listing = {
              ...listing,
              conversationId: body.conversationId,
              conversationArchived: listing.conversations.some(
                (item) => item.id === body.conversationId && item.archived,
              ),
            };
          else if (name === 'cloud.restoreConversation')
            listing = {
              ...listing,
              conversationArchived: false,
              conversations: listing.conversations.map((item) =>
                item.id === body.conversationId ? { ...item, archived: false } : item,
              ),
            };
          else if (name === 'cloud.archiveConversation')
            listing = {
              ...listing,
              conversationId: null,
              items: [],
              conversations: listing.conversations.map((item) =>
                item.id === body.conversationId ? { ...item, archived: true } : item,
              ),
            };
          else if (name === 'cloud.deleteConversation')
            listing = {
              ...listing,
              conversationId: null,
              conversationArchived: false,
              conversations: listing.conversations.filter(
                (item) => item.id !== body.conversationId,
              ),
              items: [],
              cleanupPending: 1,
              retainedUsage: [{ requestId, atMillis: 1000, cost: null }],
            };
          else if (name === 'cloud.retryConversationCleanup')
            listing = { ...listing, cleanupPending: 0 };
          else if (!['cloud.list', 'agent.list'].includes(name))
            throw new Error(`合成测试禁止命令：${name}`);
          const data =
            name === 'agent.list'
              ? { commandId: request.id, offset: 0, limit: 20, totalItems: 0, items: [] }
              : { commandId: request.id, ...listing };
          emit({
            contractVersion: 1,
            type: 'result',
            id: request.id,
            sentAt: new Date().toISOString(),
            payload: { status: 'accepted', data },
          });
        },
      };
      Object.assign(window, { LiuyingNative: port, lifecycleCalls: calls });
    },
    { fixture: readyFixture, theme, longTitle, duplicateCount },
  );
  await page.goto('/#/agent');
  await expect(
    page.getByTestId('message-user-bubble').filter({ hasText: '合成聊天正文' }).first(),
  ).toBeVisible();
}

test('同名多条会话在滚动取消与归档阅读返回后恢复原行焦点', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await openSyntheticChat(page, 'night', 24);
  await page.evaluate(() => {
    document.documentElement.style.fontSize = '20px';
  });
  await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
  const sidebar = page.getByRole('dialog', { name: '流萤', exact: true });
  const lastAction = sidebar
    .getByRole('button', { name: '管理对话：同名合成会话', exact: true })
    .last();
  await lastAction.scrollIntoViewIfNeeded();
  const scrollBefore = await lastAction.evaluate(
    (el) => el.closest('ul')!.parentElement!.scrollTop,
  );
  expect(scrollBefore).toBeGreaterThan(200);
  await lastAction.click();
  await sidebar.getByRole('button', { name: '删除对话', exact: true }).click();
  await sidebar.getByRole('button', { name: '取消删除', exact: true }).click();
  await expect(lastAction).toBeFocused();
  await expect(lastAction).toBeInViewport();
  await expect(lastAction).toHaveAttribute(
    'data-conversation-action',
    '44444444-4444-4444-8444-000000000023',
  );
  expect(await lastAction.evaluate((el) => el.closest('ul')!.parentElement!.scrollTop)).toBeCloseTo(
    scrollBefore,
    0,
  );
  await page.screenshot({ path: 'build/lifecycle-duplicate-cancel-focus.png' });
  await sidebar.getByRole('button', { name: '设置与管理', exact: true }).click();
  await sidebar.getByRole('button', { name: '已归档对话', exact: true }).click();
  await sidebar.getByRole('textbox', { name: '搜索已归档会话标题' }).fill('同名');
  const source = sidebar
    .getByRole('button', { name: '打开会话：同名合成会话', exact: true })
    .last();
  await source.scrollIntoViewIfNeeded();
  const sourceScroll = await source.evaluate((el) => el.closest('ul')!.parentElement!.scrollTop);
  expect(sourceScroll).toBeGreaterThan(200);
  await source.click();
  await expect(page.getByRole('region', { name: '归档对话只读' })).toBeVisible();
  await page.getByRole('button', { name: '返回已归档对话' }).click();
  await expect(source).toBeFocused();
  await expect(source).toBeInViewport();
  await expect(source).toHaveAttribute(
    'data-conversation-open',
    '55555555-5555-4555-8555-000000000023',
  );
  await expect(sidebar.getByRole('textbox', { name: '搜索已归档会话标题' })).toHaveValue('同名');
  expect(await source.evaluate((el) => el.closest('ul')!.parentElement!.scrollTop)).toBeCloseTo(
    sourceScroll,
    0,
  );
  await page.screenshot({ path: 'build/lifecycle-duplicate-archive-source-focus.png' });
  const mutations = await page.evaluate(() =>
    (
      window as unknown as { lifecycleCalls: { name: string; body: unknown }[] }
    ).lifecycleCalls.filter(({ name }) => !['cloud.list', 'agent.list'].includes(name)),
  );
  expect(mutations).toEqual([
    {
      name: 'cloud.selectConversation',
      body: { conversationId: '55555555-5555-4555-8555-000000000023' },
    },
  ]);
});

for (const theme of ['morning', 'night']) {
  for (const width of [320, 390]) {
    test(`${theme} ${width}px 归档阅读、恢复、确认取消与清理待办布局`, async ({ page }) => {
      await page.setViewportSize({ width, height: 740 });
      await openSyntheticChat(page, theme);
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
      await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
      const sidebar = page.getByRole('dialog', { name: '流萤', exact: true });
      await expect(
        sidebar.getByRole('button', { name: '打开会话：合成归档对话', exact: true }),
      ).toHaveCount(0);
      await expect(
        sidebar.getByRole('button', { name: `管理对话：${longTitle}`, exact: true }),
      ).toBeInViewport();
      await expect
        .poll(() => sidebar.evaluate((el) => Math.abs(el.getBoundingClientRect().x)))
        .toBeLessThan(1);
      expect(await sidebar.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
      await page.locator('html').evaluate((node) => {
        node.style.setProperty('--radius-input', '23px');
        node.style.setProperty('--radius-button', '25px');
      });
      await expect(sidebar.getByRole('textbox', { name: '搜索会话标题' }).locator('..')).toHaveCSS(
        'border-radius',
        '23px',
      );
      await expect(sidebar.getByRole('button', { name: '新建对话', exact: true })).toHaveCSS(
        'border-radius',
        '25px',
      );
      await page.screenshot({ path: `build/lifecycle-${theme}-${width}-list.png` });
      await sidebar.getByRole('button', { name: '设置与管理', exact: true }).click();
      const rename = sidebar.getByRole('textbox', { name: '重命名当前对话' });
      await expect(rename).toHaveCSS('border-radius', '23px');
      await expect(sidebar.getByRole('button', { name: '保存名称', exact: true })).toHaveCSS(
        'border-radius',
        '25px',
      );
      await rename.focus();
      await page.setViewportSize({ width, height: 420 });
      await rename.scrollIntoViewIfNeeded();
      await expect(rename).toBeInViewport();
      expect(await sidebar.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
      await page.setViewportSize({ width, height: 740 });
      await sidebar.getByRole('button', { name: '已归档对话', exact: true }).click();
      await sidebar.getByRole('textbox', { name: '搜索已归档会话标题' }).fill('合成');
      await sidebar.getByRole('button', { name: '打开会话：合成归档对话', exact: true }).click();
      await expect(page.getByRole('region', { name: '归档对话只读' })).toBeInViewport();
      await expect(page.getByRole('button', { name: '在线执行' })).toHaveCount(0);
      await page.screenshot({ path: `build/lifecycle-${theme}-${width}-readonly.png` });
      await page.getByRole('button', { name: '返回已归档对话' }).click();
      await expect(sidebar.getByRole('textbox', { name: '搜索已归档会话标题' })).toHaveValue(
        '合成',
      );
      await sidebar.getByRole('button', { name: '管理对话：合成归档对话' }).click();
      await sidebar.getByRole('button', { name: '恢复对话', exact: true }).click();
      await expect(sidebar.getByText('已恢复，可继续对话。')).toBeVisible();
      await sidebar.getByRole('button', { name: '返回设置与管理' }).click();
      await sidebar.getByRole('button', { name: '返回会话列表' }).click();
      await sidebar.getByRole('button', { name: `打开会话：${longTitle}`, exact: true }).click();
      await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
      const operation = sidebar.getByRole('button', {
        name: `管理对话：${longTitle}`,
        exact: true,
      });
      await operation.click();
      await sidebar.getByRole('button', { name: '删除对话', exact: true }).click();
      await expect(sidebar.getByRole('button', { name: '取消删除' })).toBeFocused();
      const confirm = sidebar.getByRole('button', { name: '确认永久删除' });
      await confirm.scrollIntoViewIfNeeded();
      await expect(confirm).toBeInViewport();
      expect(await sidebar.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
      await page.screenshot({ path: `build/lifecycle-${theme}-${width}-confirm.png` });
      await sidebar.getByRole('button', { name: '取消删除' }).click();
      await expect(operation).toBeFocused();
      expect(
        await page.evaluate(() =>
          (window as unknown as { lifecycleCalls: { name: string }[] }).lifecycleCalls.some(
            ({ name }) => name === 'cloud.deleteConversation',
          ),
        ),
      ).toBe(false);
      await operation.click();
      await sidebar.getByRole('button', { name: '删除对话', exact: true }).click();
      await confirm.click();
      await expect(sidebar.getByText('本机聊天内容已删除。')).toBeVisible();
      await sidebar.getByRole('button', { name: '设置与管理', exact: true }).click();
      const retry = sidebar.getByRole('button', { name: '重试清理', exact: true });
      await expect(retry).toHaveCSS('border-radius', '25px');
      await page.locator('html').evaluate((node) => {
        node.style.removeProperty('--radius-input');
        node.style.removeProperty('--radius-button');
      });
      await retry.scrollIntoViewIfNeeded();
      await page.screenshot({ path: `build/lifecycle-${theme}-${width}-cleanup.png` });
      await retry.click();
      await expect(sidebar.getByRole('region', { name: '云端清理待办' })).toHaveCount(0);
      await sidebar.getByRole('button', { name: '展开设置入口', exact: true }).click();
      await sidebar.getByRole('link', { name: '账单与用量' }).click();
      await expect(page.getByRole('region', { name: '删除后保留的必要用量' })).toContainText(
        '待确认',
      );
      await expect(page.getByText('合成聊天正文', { exact: true })).toHaveCount(0);
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth))
        .toBeLessThanOrEqual(0);
      await page.screenshot({ path: `build/lifecycle-${theme}-${width}-usage.png` });
      const names = await page.evaluate(() =>
        (window as unknown as { lifecycleCalls: { name: string }[] }).lifecycleCalls.map(
          ({ name }) => name,
        ),
      );
      expect(
        names.every((name) =>
          [
            'cloud.list',
            'agent.list',
            'cloud.selectConversation',
            'cloud.restoreConversation',
            'cloud.deleteConversation',
            'cloud.retryConversationCleanup',
          ].includes(name),
        ),
      ).toBe(true);
    });
  }
}
