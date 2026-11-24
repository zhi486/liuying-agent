import { readFileSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';

async function dispatchTouch(target: Locator, type: string, x: number, y: number) {
  await target.evaluate(
    (element, data) => {
      const touch = new Touch({
        identifier: 1,
        target: element,
        clientX: data.x,
        clientY: data.y,
      });
      element.dispatchEvent(
        new TouchEvent(data.type, {
          bubbles: true,
          touches: ['touchend', 'touchcancel'].includes(data.type) ? [] : [touch],
          changedTouches: [touch],
        }),
      );
    },
    { type, x, y },
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

const readyFixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

async function openChat(
  page: Page,
  theme = 'morning',
  running = false,
  empty = false,
  voiceState = 'failed',
  interrupted = false,
  mode = 'completed',
) {
  // 只用合成 Bridge 回包，任何外部网络和真实命令都不可达。
  await page.route('**/*', (route) =>
    new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
  );
  await page.addInitScript(
    ({ fixture, theme, running, empty, voiceState, interrupted, mode }) => {
      const scopeDenied = mode === 'scope-denied';
      const snapshot = fixture.payload.snapshot;
      snapshot.theme = theme;
      snapshot.session.state = running ? 'running' : 'finished';
      const calls: string[] = [];
      const port = {
        onmessage: null as ((event: { data: string }) => void) | null,
        postMessage(raw: string) {
          const request = JSON.parse(raw);
          const emit = (response: unknown) =>
            queueMicrotask(() => port.onmessage?.({ data: JSON.stringify(response) }));
          if (request.type === 'hello') {
            emit({
              ...fixture,
              payload: { ...fixture.payload, helloId: request.id },
            });
            return;
          }
          const name = request.payload.name;
          calls.push(name);
          const common = { commandId: request.id };
          let data: unknown;
          if (name === 'cloud.list' || name === 'cloud.newConversation') {
            if (name === 'cloud.newConversation') empty = true;
            data = {
              ...common,
              configured: true,
              configuring: false,
              origin: 'https://agent.example.com',
              conversationId: '33333333-3333-4333-8333-333333333333',
              legacyAvailable: true,
              conversations: [
                {
                  id: '33333333-3333-4333-8333-333333333333',
                  title: '我想通过跑步改善体能，这是用于检查侧栏长标题省略的合成对话',
                  updatedAtMillis: Date.now(),
                },
                ...[
                  '夜跑后的拉伸安排',
                  '流萤的故事',
                  '下周训练计划',
                  '今天想轻松跑',
                  '第一次五公里',
                ].map((title, index) => ({
                  id: `44444444-4444-4444-8444-${String(index).padStart(12, '0')}`,
                  title,
                  updatedAtMillis: Date.now() - (index + 1) * 15 * 24 * 60 * 60 * 1000,
                })),
              ],
              items: Array.from(
                { length: empty ? 0 : interrupted || scopeDenied ? 1 : 10 },
                (_, index) => ({
                  requestId: `22222222-2222-4222-8222-${String(index).padStart(12, '0')}`,
                  prompt: scopeDenied ? '我刚才那次跑步用了多久' : `合成对话 ${index + 1}`,
                  answer:
                    interrupted || scopeDenied
                      ? null
                      : '这是一段用于布局回归的合成回答，不是真实用户数据。\n'.repeat(30),
                  attempted: true,
                  busy: false,
                  status: scopeDenied ? 'blocked' : interrupted ? 'running' : 'completed',
                  resultState: interrupted ? 'none' : 'acknowledged',
                  reason: scopeDenied ? 'scope_denied' : null,
                  pendingControl: null,
                  confirmedControl: null,
                  error: interrupted
                    ? '连接或读取在线服务超时。可恢复查询原任务；不会自动重发问题。'
                    : null,
                  memoryRefs: [],
                }),
              ),
            };
          } else if (['agent.list', 'history.query'].includes(name)) {
            data =
              name === 'agent.list'
                ? { ...common, offset: 0, limit: 20, totalItems: 0, items: [] }
                : {
                    ...common,
                    page: 1,
                    pageSize: 10,
                    totalItems: 0,
                    hasNextPage: false,
                    items: [],
                  };
          } else if (name.startsWith('voice.')) {
            const state = name === 'voice.cancel' ? 'idle' : voiceState;
            data = {
              ...common,
              sessionId: request.payload.body.sessionId,
              state,
              text: state === 'completed' ? '合成识别文字' : null,
              preview: state === 'recording' ? '合成中间文字'.repeat(50) : null,
              error: state === 'failed' ? '模拟识别失败，可继续文字输入' : null,
            };
          } else {
            throw new Error(`布局测试禁止命令：${name}`);
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
      Object.assign(window, { LiuyingNative: port, layoutCalls: calls });
    },
    {
      fixture: readyFixture,
      theme,
      running,
      empty,
      voiceState,
      interrupted,
      mode,
    },
  );
  await page.goto('/#/agent');
  await expect(page.getByRole('article', { name: '在线任务' })).toHaveCount(
    empty ? 0 : interrupted || mode === 'scope-denied' ? 1 : 10,
  );
}

for (const theme of ['morning', 'night']) {
  test(`${theme} 未授权跑步提示可读且技术原因保持折叠`, async ({ page }) => {
    await page.setViewportSize({
      width: theme === 'night' ? 320 : 393,
      height: theme === 'night' ? 420 : 800,
    });
    await openChat(page, theme, false, false, 'failed', false, 'scope-denied');
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    const guidance = page.getByText(
      '本次未获准读取这条跑步记录。请前往“跑步记录”，选择一条记录后点击“让流萤复盘本次跑步”。',
    );
    await expect(guidance).toBeVisible();
    await guidance.scrollIntoViewIfNeeded();
    await expect(guidance).toBeInViewport();
    const reason = page.getByText('原因：scope_denied');
    await expect(reason).toBeHidden();
    await expect(page.getByRole('textbox', { name: '给流萤的消息' })).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/scope-denied-${theme}.png` });
    await page.getByText('更多操作').click();
    await expect(reason).toBeVisible();
  });

  test(`${theme} 查询中断时恢复入口直接可见且不遮挡输入框`, async ({ page }) => {
    await page.setViewportSize({
      width: theme === 'night' ? 320 : 393,
      height: 420,
    });
    await openChat(page, theme, false, false, 'failed', true);
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    await expect(page.getByText('查询已中断，结果待同步')).toBeVisible();
    await expect(page.getByText('流萤正在思考', { exact: true })).toHaveCount(0);
    const recovery = page.getByRole('button', { name: '恢复查询' });
    await recovery.scrollIntoViewIfNeeded();
    await expect(recovery).toBeInViewport();
    await expect(page.getByRole('textbox', { name: '给流萤的消息' })).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/query-interrupted-${theme}.png` });
  });
}

test('正文旧消息在上且初次定位最新，查看历史时轮询不抢滚动', async ({ page }) => {
  await openChat(page);
  const turns = page.getByRole('article', { name: '在线任务' });
  await expect(turns.first()).toContainText('合成对话 10');
  await expect(turns.last()).toContainText('合成对话 1');
  const conversation = page.getByRole('region', {
    name: '聊天内容',
    exact: true,
  });
  await expect
    .poll(() => conversation.evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight))
    .toBeLessThan(2);
  await conversation.evaluate((el) => {
    el.scrollTop = 100;
    el.dispatchEvent(new Event('scroll', { bubbles: true }));
  });
  // 跨过一次本机状态轮询，确保不会把查看历史的用户拉回底部。
  await page.waitForTimeout(2400);
  expect(await conversation.evaluate((el) => el.scrollTop)).toBeCloseTo(100, 0);
});

for (const theme of ['morning', 'night']) {
  test(`${theme} 会话侧栏底部管理入口可打开跑步记录`, async ({ page }) => {
    await openChat(page, theme);
    await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
    await expect(page.locator('.app-shell')).toHaveAttribute('inert', '');
    const dialog = page.getByRole('dialog', { name: '流萤', exact: true });
    await dialog.getByRole('button', { name: '设置与管理' }).click();
    const history = dialog.getByRole('link', { name: '跑步记录', exact: true });
    await expect
      .poll(() => dialog.evaluate((el) => Math.abs(el.getBoundingClientRect().x)))
      .toBeLessThan(1);
    await expect(history).toBeInViewport();
    await expect(dialog.getByRole('link', { name: '设置', exact: true })).toBeInViewport();
    await expect(dialog.getByRole('button', { name: '配置在线 Agent' })).toBeInViewport();
    const bounds = await history.boundingBox();
    expect(bounds!.y).toBeLessThan(180);
    expect(
      await history.evaluate((el) => {
        const box = el.getBoundingClientRect();
        return el.contains(
          document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2),
        );
      }),
    ).toBe(true);
    await page.screenshot({ path: `build/sidebar-${theme}.png` });
    await history.click();
    await expect(page).toHaveURL(/#\/history$/);
    await expect(page.getByRole('heading', { name: '还没有跑步记录', exact: true })).toBeVisible();
    await expect(page.locator('.app-shell')).not.toHaveAttribute('inert');
    await page.getByRole('link', { name: '返回设置与管理', exact: true }).click();
    await expect(page).toHaveURL(/#\/agent$/);
    await expect(page.getByRole('navigation', { name: '账户与管理' })).toBeVisible();
  });
}

for (const theme of ['morning', 'night']) {
  test(`${theme} 会话侧栏采用 C 双行预览并保留 44px 管理入口`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await openChat(page, theme);
    if (theme === 'night') {
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    }
    await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '流萤', exact: true });
    const rows = dialog.getByTestId('conversation-row-c');
    await expect(rows).toHaveCount(6);

    const current = dialog.locator('[data-testid="conversation-row-c"][data-current="true"]');
    const rowRadius = await radiusToken(page, 'row');
    await expect(current).toHaveCount(1);
    await expect(current.getByTestId('conversation-preview-c')).toHaveText('合成对话 1');
    await expect(current).toHaveCSS('border-top-left-radius', rowRadius);
    const rowMetrics = await rows.evaluateAll((elements) =>
      elements.map((row) => {
        const open = row.querySelector('[data-testid="conversation-open-c"]');
        const action = row.querySelector('button[aria-label^="管理对话："]');
        const rowRect = row.getBoundingClientRect();
        const openRect = open?.getBoundingClientRect();
        const actionRect = action?.getBoundingClientRect();
        return {
          height: rowRect.height,
          openHeight: openRect?.height ?? 0,
          actionWidth: actionRect?.width ?? 0,
          actionHeight: actionRect?.height ?? 0,
        };
      }),
    );
    for (const row of rowMetrics) {
      expect(row.height).toBeGreaterThanOrEqual(44);
      expect(row.openHeight).toBeGreaterThanOrEqual(44);
      expect(row.actionWidth).toBeGreaterThanOrEqual(44);
      expect(row.actionHeight).toBeGreaterThanOrEqual(44);
    }
    await expect(dialog.getByText(/^最近更新 · /).first()).toBeVisible();
    expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/sidebar-conversations-c-${theme}.png` });
  });
}

test('横向拖动带动主页面，纵向滚动不打开抽屉', async ({ page }) => {
  await openChat(page);
  const chat = page.getByRole('region', { name: '聊天内容', exact: true });
  const touch = (type: string, x: number, y: number) => dispatchTouch(chat, type, x, y);
  await touch('touchstart', 30, 300);
  await touch('touchmove', 34, 430);
  await touch('touchend', 34, 430);
  await expect(page.getByRole('dialog', { name: '流萤', exact: true })).toHaveCount(0);
  await touch('touchstart', 30, 300);
  await touch('touchmove', 190, 305);
  await expect
    .poll(() => page.locator('.app-shell').evaluate((el) => el.getBoundingClientRect().x))
    .toBe(160);
  await page.screenshot({ path: 'build/drawer-mid-drag.png' });
  await touch('touchend', 290, 305);
  const dialog = page.getByRole('dialog', { name: '流萤', exact: true });
  await expect(dialog).toBeVisible();
  await expect
    .poll(() => page.locator('.app-shell').evaluate((el) => el.getBoundingClientRect().x))
    .toBeGreaterThan(300);
  await dispatchTouch(dialog, 'touchstart', 270, 300);
  await dispatchTouch(dialog, 'touchmove', 35, 302);
  await dispatchTouch(dialog, 'touchend', 35, 302);
  await expect(dialog).toHaveCount(0);
  await expect
    .poll(() => page.locator('.app-shell').evaluate((el) => el.getBoundingClientRect().x))
    .toBe(0);
});

test('键盘可视区域偏移时输入框仍贴底并保留草稿', async ({ page }) => {
  await openChat(page);
  const input = page.getByRole('textbox', { name: '给流萤的消息' });
  await input.fill('键盘测试草稿，不发送');
  await page.evaluate(() => {
    Object.defineProperty(window.visualViewport, 'height', {
      configurable: true,
      value: 420,
    });
    Object.defineProperty(window.visualViewport, 'offsetTop', {
      configurable: true,
      value: 40,
    });
    window.visualViewport!.dispatchEvent(new Event('resize'));
    window.visualViewport!.dispatchEvent(new Event('scroll'));
  });
  await expect
    .poll(() => page.locator('.app-shell').evaluate((el) => el.getBoundingClientRect().bottom))
    .toBe(460);
  const composer = input.locator('..');
  expect(
    (await composer.boundingBox())!.y + (await composer.boundingBox())!.height,
  ).toBeLessThanOrEqual(460);
  await expect(input).toBeFocused();
  await expect(input).toHaveValue('键盘测试草稿，不发送');
  await page.screenshot({ path: 'build/keyboard-offset.png' });
});

test('长回答、缩小视口和跑步状态条都不挤走底部输入框', async ({ page }) => {
  await openChat(page, 'morning', true);
  for (const size of [
    { width: 393, height: 800 },
    { width: 320, height: 568 },
    { width: 393, height: 420 },
    { width: 800, height: 393 },
  ]) {
    await page.setViewportSize(size);
    await expect
      .poll(() =>
        page.locator('.app-shell-agent').evaluate((el) => el.getBoundingClientRect().height),
      )
      .toBe(size.height);
    const input = page.getByRole('textbox', { name: '给流萤的消息' });
    await expect(input).toBeInViewport();
    const send = page.getByRole('button', { name: '在线执行', exact: true });
    await expect(send).toBeInViewport();
    const before = await input.boundingBox();
    const conversation = page.getByRole('region', {
      name: '聊天内容',
      exact: true,
    });
    await conversation.evaluate((el) => (el.scrollTop = el.scrollHeight));
    const after = await input.boundingBox();
    // 断言目标是输入区不被挤走；允许浏览器布局取整造成不超过 1px 的抖动。
    expect(
      Math.abs(after!.y - before!.y),
      `${size.width}×${size.height}: ${before!.y} → ${after!.y}`,
    ).toBeLessThanOrEqual(1);
    // 等待浏览器完成视口缩放重排，仍严格要求文档不溢出。
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollHeight))
      .toBeLessThanOrEqual(size.height + 1);
    await input.fill('布局测试草稿，不发送');
    await expect(input).toBeFocused();
    await expect(input).toHaveCSS('outline-style', 'none');
    await expect(send).toBeInViewport();
    await page.screenshot({
      path: `build/chat-${size.width}-${size.height}.png`,
    });
  }
});

for (const theme of ['morning', 'night']) {
  test(`${theme} 消息流采用 C 双列气泡且元数据留在气泡外`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await openChat(page, theme);
    if (theme === 'night') {
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    }

    const turn = page.getByRole('article', { name: '在线任务', exact: true }).first();
    const user = turn.getByTestId('message-user-bubble');
    const assistant = turn.getByTestId('message-assistant-bubble');
    const expectedRadius = await radiusToken(page, 'message');
    const expectedTail = await radiusToken(page, 'message-tail');

    await expect(user).toHaveCSS('border-top-left-radius', expectedRadius);
    await expect(user).toHaveCSS('border-bottom-right-radius', expectedTail);
    await expect(assistant).toHaveCSS('border-top-left-radius', expectedRadius);
    await expect(assistant).toHaveCSS('border-bottom-left-radius', expectedTail);
    expect(
      await assistant.evaluate((element) => getComputedStyle(element).backdropFilter),
    ).toContain('blur');

    const [turnBox, userBox, assistantBox, moreBox] = await Promise.all([
      turn.boundingBox(),
      user.boundingBox(),
      assistant.boundingBox(),
      turn.getByText('更多操作', { exact: true }).boundingBox(),
    ]);
    expect(turnBox).not.toBeNull();
    expect(userBox).not.toBeNull();
    expect(assistantBox).not.toBeNull();
    expect(moreBox).not.toBeNull();
    expect(userBox!.x).toBeGreaterThan(assistantBox!.x);
    expect(
      turnBox!.x + turnBox!.width - (userBox!.x + userBox!.width),
      '用户气泡应贴齐右侧消息列',
    ).toBeLessThanOrEqual(24);
    expect(moreBox!.y).toBeGreaterThanOrEqual(assistantBox!.y + assistantBox!.height - 1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/message-bubbles-${theme}.png` });
  });
}

test('大字体下仍可关闭菜单，背景不能截获点击', async ({ page }) => {
  await openChat(page);
  await page.evaluate(() => (document.documentElement.style.fontSize = '22px'));
  const trigger = page.getByRole('button', { name: '打开侧边栏', exact: true });
  await trigger.click();
  const dialog = page.getByRole('dialog', { name: '流萤', exact: true });
  await expect(dialog.getByRole('button', { name: '设置与管理' })).toBeInViewport();
  await page
    .getByRole('button', { name: '关闭侧边栏背景' })
    .click({ position: { x: 380, y: 300 } });
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(page.getByRole('button', { name: '在线执行' })).toBeInViewport();
});

for (const theme of ['morning', 'night']) {
  test(`${theme} 输入工具使用底部 Sheet、可双向关闭且大字体不溢出`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    await openChat(page, theme);
    await page.evaluate(() => (document.documentElement.style.fontSize = '22px'));
    const input = page.getByRole('textbox', { name: '给流萤的消息' });
    const composerRadius = await radiusToken(page, 'panel');
    const sheetRadius = await radiusToken(page, 'sheet');
    await input.fill('准备保存的合成草稿');
    await input.focus();
    const composer = input.locator('..');
    await expect(composer).toHaveCSS('border-radius', composerRadius);
    await expect(composer).toHaveCSS('outline-style', 'solid');
    await expect(input).toHaveCSS('outline-style', 'none');

    const trigger = page.getByRole('button', {
      name: '更多输入选项',
      exact: true,
    });
    const triggerBox = (await trigger.boundingBox())!;
    expect(triggerBox.width).toBe(44);
    expect(triggerBox.height).toBe(44);
    await trigger.click();

    const dialog = page.getByRole('dialog', { name: '输入选项', exact: true });
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveCSS('border-top-left-radius', sheetRadius);
    await expect(dialog).toHaveCSS('border-top-right-radius', sheetRadius);
    expect(
      await dialog.evaluate((element) => element.parentElement?.parentElement === document.body),
    ).toBe(true);
    expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(
      true,
    );
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await expect(page.getByRole('button', { name: '关闭输入选项', exact: true })).toBeFocused();
    await page.screenshot({ path: `build/chat-tools-sheet-${theme}.png` });

    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();

    await trigger.click();
    await page
      .getByRole('button', { name: '关闭输入选项背景', exact: true })
      .click({ position: { x: 160, y: 24 } });
    await expect(page.getByRole('dialog', { name: '输入选项', exact: true })).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });
}

for (const theme of ['morning', 'night']) {
  test(`${theme} 会话侧栏控件统一对齐且窄屏大字体不溢出`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    await openChat(page, theme);
    await page.evaluate(() => (document.documentElement.style.fontSize = '22px'));
    await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '流萤', exact: true });
    const create = dialog.getByRole('button', {
      name: '新建对话',
      exact: true,
    });
    const panel = dialog.locator('#agent-sidebar');
    const drawerRadius = await radiusToken(page, 'drawer');
    const noRadius = await radiusToken(page, 'none');
    await expect(panel).toHaveCSS('border-top-right-radius', drawerRadius);
    await expect(panel).toHaveCSS('border-bottom-right-radius', drawerRadius);
    await expect(panel).toHaveCSS('border-top-left-radius', noRadius);
    await expect(panel).toHaveCSS('border-bottom-left-radius', noRadius);
    await expect(panel).toHaveCSS('overflow-x', 'hidden');
    const legacy = dialog.getByRole('button', {
      name: '打开会话：旧版历史记录',
      exact: true,
    });
    const selected = dialog.locator('button[aria-current="page"]');
    await expect
      .poll(() => panel.evaluate((el) => Math.abs(el.getBoundingClientRect().x)))
      .toBeLessThan(0.01);
    const createBox = (await create.boundingBox())!;
    const selectedBox = (await selected.boundingBox())!;
    expect(Math.abs(selectedBox.x - createBox.x)).toBeLessThanOrEqual(1);
    const selectedRowBox = (await selected.locator('..').boundingBox())!;
    expect(selectedRowBox.width).toBeCloseTo(createBox.width, 1);
    await page.screenshot({ path: `build/conversation-sidebar-${theme}.png` });
    await dialog.getByRole('button', { name: '设置与管理' }).click();
    const input = dialog.getByRole('textbox', { name: '重命名当前对话' });
    const save = dialog.getByRole('button', { name: '保存名称' });
    await expect(save).toBeDisabled();
    await input.fill('新的对话名称');
    await expect(save).toBeEnabled();
    await expect
      .poll(() => dialog.evaluate((el) => Math.abs(el.getBoundingClientRect().x)))
      .toBeLessThan(0.01);
    for (const control of [legacy]) {
      const box = (await control.boundingBox())!;
      expect(Math.abs(box.x - createBox.x)).toBeLessThanOrEqual(1);
      const rowBox = (await control.locator('..').boundingBox())!;
      expect(rowBox.width).toBeCloseTo(createBox.width, 1);
      await expect(control).toHaveCSS('border-top-width', '0px');
    }
    const inputBox = (await input.boundingBox())!;
    const saveBox = (await save.boundingBox())!;
    expect(saveBox.x).toBeCloseTo(inputBox.x, 1);
    expect(saveBox.width).toBeCloseTo(inputBox.width, 1);
    expect(saveBox.y).toBeGreaterThanOrEqual(inputBox.y + inputBox.height);
    expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    expect(await input.locator('..').evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    await page.screenshot({
      path: `build/conversation-management-${theme}.png`,
    });
  });
}

test('搜索仅过滤已加载会话标题，管理入口保持可见', async ({ page }) => {
  await openChat(page);
  await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
  const search = page.getByRole('textbox', { name: '搜索会话标题' });
  await search.fill('不存在的标题');
  await expect(page.getByText('没有匹配的会话标题')).toBeVisible();
  await expect(page.getByRole('button', { name: '设置与管理' })).toBeInViewport();
  await search.fill('体能');
  await expect(page.locator('button[aria-current="page"]')).toBeVisible();
});

test('新对话留白与紧凑输入区在窄屏和深色主题下保持完整', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 700 });
  await openChat(page, 'night', false, true);
  await expect(page.getByRole('heading', { name: '今天想把哪件事交给流萤？' })).toBeInViewport();
  await expect(page.getByRole('button', { name: '开始新对话' })).toBeInViewport();
  await expect(page.getByRole('button', { name: '在线执行', exact: true })).toBeInViewport();
  await page.screenshot({ path: 'build/chat-empty-night.png' });
});

test('320×568 空态任务和状态完整停在输入框上方且不滚动', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await openChat(page, 'morning', false, true);

  const prompts = page.getByRole('group', { name: '任务起点', exact: true });
  const status = page.getByRole('region', { name: '当前工作状态', exact: true });
  const input = page.getByRole('textbox', { name: '给流萤的消息' });
  const composer = input.locator('..');
  const statusBox = (await status.boundingBox())!;
  const composerBox = (await composer.boundingBox())!;

  await expect(prompts).toBeInViewport({ ratio: 1 });
  const conversation = page.getByRole('region', { name: '聊天内容', exact: true });
  expect(await conversation.evaluate((el) => el.scrollHeight <= el.clientHeight + 1)).toBe(true);
  expect(statusBox.y + statusBox.height).toBeLessThanOrEqual(composerBox.y + 0.5);
  await expect(status.getByText('本机已连接', { exact: true })).toBeVisible();
  await expect(status.getByText('已配置', { exact: true })).toBeVisible();
  await expect(status.getByText('发送前确认', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'build/chat-empty-320x568.png' });
});

for (const theme of ['morning', 'night']) {
  test(`${theme} 393×800 新对话任务入口统一图标、圆角及短屏布局`, async ({ page }) => {
    await page.setViewportSize({ width: 393, height: 800 });
    await openChat(page, theme, false, true);

    const prompts = page.getByRole('group', { name: '任务起点', exact: true });
    const status = page.getByRole('region', { name: '当前工作状态', exact: true });
    const input = page.getByRole('textbox', { name: '给流萤的消息' });
    const composer = input.locator('..');

    const buttons = prompts.getByRole('button');
    const cardRadius = await radiusToken(page, 'card');
    await expect(buttons).toHaveCount(3);
    for (const button of await buttons.all()) {
      await expect(button).toHaveCSS('border-radius', cardRadius);
      await expect(button.locator('svg')).toHaveCount(2);
      expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }

    const promptBox = (await prompts.boundingBox())!;
    const statusBox = (await status.boundingBox())!;
    const composerBox = (await composer.boundingBox())!;
    const conversationBox = (await page
      .getByRole('region', { name: '聊天内容', exact: true })
      .boundingBox())!;
    const introBox = (await page
      .getByRole('heading', { name: '今天想把哪件事交给流萤？' })
      .locator('..')
      .boundingBox())!;
    expect(introBox.y - conversationBox.y).toBeGreaterThanOrEqual(16);
    expect(introBox.y - conversationBox.y).toBeLessThanOrEqual(24);
    expect(promptBox.y - introBox.y - introBox.height).toBeGreaterThan(80);
    expect(promptBox.y + promptBox.height).toBeLessThanOrEqual(statusBox.y);
    expect(statusBox.y + statusBox.height).toBeLessThanOrEqual(composerBox.y + 1);
    expect(composerBox.y - statusBox.y - statusBox.height).toBeLessThanOrEqual(32);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/welcome-position-${theme}-393x800.png` });

    await page.setViewportSize({ width: 393, height: 420 });
    await page.evaluate(() => {
      document.documentElement.style.fontSize = '20px';
    });
    await expect
      .poll(() => page.locator('.app-shell-agent').evaluate((el) => el.clientHeight))
      .toBe(420);
    await expect(input).toBeInViewport();
    await expect(page.getByRole('button', { name: '在线执行', exact: true })).toBeInViewport();
    await expect(prompts).toBeInViewport({ ratio: 1 });
    const conversation = page.getByRole('region', { name: '聊天内容', exact: true });
    expect(await conversation.evaluate((el) => el.scrollHeight <= el.clientHeight + 1)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/welcome-position-${theme}-393x420.png` });
  });
}

for (const theme of ['morning', 'night']) {
  test(`${theme} 1440×900 空态使用统一任务组件且侧栏可正常关闭`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openChat(page, theme, false, true);

    const shell = page.locator('.app-shell-agent');
    const heading = page.getByRole('heading', { name: '今天想把哪件事交给流萤？' });
    const prompts = page.getByRole('group', { name: '任务起点' });
    const status = page.getByRole('region', { name: '当前工作状态', exact: true });
    const input = page.getByRole('textbox', { name: '给流萤的消息' });

    expect((await shell.boundingBox())!.width).toBeGreaterThanOrEqual(1439);
    const headingBox = (await heading.boundingBox())!;
    const promptBox = (await prompts.boundingBox())!;
    expect(promptBox.width).toBeGreaterThan(360);
    const promptButtons = await prompts.getByRole('button').all();
    const firstPrompt = (await promptButtons[0].boundingBox())!;
    const lastPrompt = (await promptButtons[2].boundingBox())!;
    expect(lastPrompt.x).toBeGreaterThan(firstPrompt.x + firstPrompt.width);
    expect(lastPrompt.y).toBeCloseTo(firstPrompt.y, 0);
    expect((await input.boundingBox())!.width).toBeGreaterThan(700);

    await expect(heading).toBeVisible();
    await expect(prompts).toBeVisible();
    await expect(status).toBeVisible();
    await expect(input).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/chat-empty-desktop-${theme}.png` });

    await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
    const drawer = page.locator('#agent-sidebar');
    await expect
      .poll(() => drawer.evaluate((el) => Math.abs(el.getBoundingClientRect().x)))
      .toBeLessThan(0.01);
    await page.getByRole('button', { name: '关闭侧边栏', exact: true }).click();
    await expect
      .poll(() => drawer.evaluate((el) => el.getBoundingClientRect().right))
      .toBeLessThanOrEqual(1);
    const restoredHeadingBox = (await heading.boundingBox())!;
    expect(restoredHeadingBox.width).toBeGreaterThanOrEqual(headingBox.width - 1);
  });
}

test('浅色新对话比例及会话列表截图', async ({ page }) => {
  await openChat(page, 'morning', false, true);
  await expect(page.getByRole('button', { name: '开始新对话' })).toBeEnabled();
  await page.screenshot({ path: 'build/chat-empty-morning.png' });
  await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
  await expect(page.getByRole('heading', { name: '30 天内', exact: true })).toBeVisible();
  await expect
    .poll(() =>
      page
        .getByRole('dialog', { name: '流萤', exact: true })
        .evaluate((el) => Math.abs(el.getBoundingClientRect().x)),
    )
    .toBeLessThan(0.01);
  await page.screenshot({ path: 'build/chat-list-morning.png' });
});

for (const theme of ['morning', 'night']) {
  for (const width of [320, 393]) {
    test(`${theme} ${width}px 新建聊天后固定空态、三个入口只起草且键盘不挤走输入框`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 800 });
      await openChat(page, theme);
      const conversation = page.getByRole('region', { name: '聊天内容', exact: true });
      expect(await conversation.evaluate((el) => el.scrollHeight > el.clientHeight)).toBe(true);
      await page.getByRole('button', { name: '开始新对话', exact: true }).click();
      await expect(page.getByRole('article', { name: '在线任务' })).toHaveCount(0);
      await expect(conversation).toHaveAttribute('data-empty', 'true');
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
      const prompts = page.getByRole('group', { name: '任务起点', exact: true });
      const input = page.getByRole('textbox', { name: '给流萤的消息' });
      const composer = input.locator('..');
      const before = (await composer.boundingBox())!;
      const heading = page.getByRole('heading', { name: '今天想把哪件事交给流萤？' });
      const introBox = (await heading.locator('..').boundingBox())!;
      const conversationBox = (await conversation.boundingBox())!;
      const statusBox = (await page
        .getByRole('region', { name: '当前工作状态', exact: true })
        .boundingBox())!;
      expect(introBox.y - conversationBox.y).toBeGreaterThanOrEqual(16);
      expect(introBox.y - conversationBox.y).toBeLessThanOrEqual(30);
      expect((await prompts.boundingBox())!.y).toBeGreaterThan(introBox.y + introBox.height);
      expect(before.y - statusBox.y - statusBox.height).toBeGreaterThanOrEqual(0);
      expect(before.y - statusBox.y - statusBox.height).toBeLessThanOrEqual(40);
      await conversation.hover();
      await page.mouse.wheel(0, 500);
      await page.mouse.wheel(0, -500);
      expect(await conversation.evaluate((el) => el.scrollTop)).toBe(0);
      expect(await conversation.evaluate((el) => el.scrollHeight <= el.clientHeight + 1)).toBe(
        true,
      );
      expect((await composer.boundingBox())!.y).toBeCloseTo(before.y, 0);
      await page.screenshot({ path: `build/welcome-position-${theme}-${width}-large.png` });

      for (const button of await prompts.getByRole('button').all()) {
        await button.click();
        await expect(input).toBeFocused();
        expect(await input.inputValue()).not.toBe('');
        await input.fill('');
      }
      await page.setViewportSize({ width, height: 420 });
      await expect(prompts).toBeInViewport({ ratio: 1 });
      await expect(input).toBeInViewport({ ratio: 1 });
      await expect(page.getByRole('button', { name: '在线执行', exact: true })).toBeInViewport({
        ratio: 1,
      });
      expect(await conversation.evaluate((el) => el.scrollHeight <= el.clientHeight + 1)).toBe(
        true,
      );
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollHeight <= innerHeight + 1))
        .toBe(true);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.screenshot({ path: `build/welcome-position-${theme}-${width}-short.png` });
      const calls = await page.evaluate(
        () => (window as unknown as { layoutCalls: string[] }).layoutCalls,
      );
      expect(calls.filter((name) => name === 'cloud.newConversation')).toHaveLength(1);
      expect(
        calls.every((name) => ['cloud.list', 'agent.list', 'cloud.newConversation'].includes(name)),
      ).toBe(true);
    });
  }
}

for (const theme of ['morning', 'night']) {
  test(`${theme} 语音授权处理中及结果提示不遮挡输入框`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 420 });
    for (const state of ['recording', 'completed', 'failed']) {
      await page.goto('about:blank');
      await openChat(page, theme, false, true, state);
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
      const input = page.getByRole('textbox', { name: '给流萤的消息' });
      await input.fill('原草稿');
      await page.getByRole('button', { name: '语音输入', exact: true }).click();
      const assertClear = async () => {
        await expect(input).toBeInViewport();
        await expect(page.getByRole('button', { name: '在线执行', exact: true })).toBeInViewport();
        const slot = page.getByTestId('voice-status-slot');
        const a = (await slot.boundingBox())!;
        const b = (await input.boundingBox())!;
        expect(a.y + a.height).toBeLessThanOrEqual(b.y + 1);
        expect(
          await input.evaluate((el) => {
            const r = el.getBoundingClientRect();
            return document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2) === el;
          }),
        ).toBe(true);
      };
      await assertClear();
      await expect(page.getByRole('button', { name: '取消当前语音输入' })).toBeInViewport();
      expect(
        await page
          .getByTestId('voice-status-slot')
          .evaluate(
            (el) => el.parentElement === document.getElementById('agent-draft')?.parentElement,
          ),
      ).toBe(true);
      await page.getByRole('button', { name: '同意本次录音并识别' }).click();
      if (state === 'recording') await expect(page.getByLabel('实时识别预览')).toBeVisible();
      else await expect(page.getByRole('button', { name: '关闭语音提示' })).toBeVisible();
      await assertClear();
      await page.screenshot({ path: `build/voice-${theme}-${state}.png` });
      if (state === 'recording') await page.getByRole('button', { name: '取消语音输入' }).click();
      await page.getByRole('button', { name: '关闭语音提示' }).click();
      await expect(input).toBeEnabled();
      await expect(input).toHaveValue(state === 'completed' ? '原草稿\n合成识别文字' : '原草稿');
      await input.fill('仍可继续打字');
    }
  });
}

test('语音确认跨轮询仍可见，常驻取消后立即恢复打字且不会录音', async ({ page }) => {
  await openChat(page, 'morning', false, true);
  const input = page.getByRole('textbox', { name: '给流萤的消息' });
  await page.getByRole('button', { name: '语音输入', exact: true }).click();
  await page.waitForTimeout(2500);
  await expect(page.getByRole('button', { name: '同意本次录音并识别' })).toBeInViewport();
  await page.getByRole('button', { name: '取消当前语音输入' }).click();
  await expect(input).toBeEnabled();
  await input.fill('取消后可打字，不发送');
  await expect(input).toHaveValue('取消后可打字，不发送');
  await expect(page.getByRole('button', { name: '语音输入', exact: true })).toBeVisible();
  await page.screenshot({ path: 'build/voice-cancel-recovered.png' });
});

test('语音面板高度来源异常时仍可滚动访问授权操作', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 420 });
  await openChat(page, 'night', false, true);
  await page.getByRole('button', { name: '语音输入', exact: true }).click();
  const slot = page.getByTestId('voice-status-slot');
  await slot.evaluate((el) => {
    el.style.setProperty('--chat-viewport-height', '0px');
  });
  expect((await slot.boundingBox())!.height).toBeGreaterThanOrEqual(79);
  const consent = page.getByRole('button', { name: '同意本次录音并识别' });
  await consent.scrollIntoViewIfNeeded();
  await expect(consent).toBeInViewport();
  await page.screenshot({ path: 'build/voice-height-fallback.png' });
  await page.getByRole('button', { name: '取消当前语音输入' }).click();
  await expect(page.getByRole('textbox', { name: '给流萤的消息' })).toBeEnabled();
});
