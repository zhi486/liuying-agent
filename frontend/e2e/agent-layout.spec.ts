import { readFileSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';

async function dispatchTouch(target: Locator, type: string, x: number, y: number) {
  await target.evaluate(
    (element, data) => {
      const touch = new Touch({ identifier: 1, target: element, clientX: data.x, clientY: data.y });
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
          const common = { commandId: request.id };
          let data: unknown;
          if (name === 'cloud.list') {
            data = {
              ...common,
              configured: true,
              configuring: false,
              origin: 'https://agent.example.com',
              conversationId: '33333333-3333-4333-8333-333333333333',
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
      Object.assign(window, { LiuyingNative: port });
    },
    { fixture: readyFixture, theme, running, empty, voiceState, interrupted, mode },
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
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 420 });
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
  const conversation = page.getByRole('region', { name: '聊天内容', exact: true });
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
    await expect(dialog.getByRole('link', { name: '运动与语音设置' })).toBeInViewport();
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
    await page.getByRole('link', { name: '返回聊天首页' }).click();
    await expect(page).toHaveURL(/#\/agent$/);
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
    Object.defineProperty(window.visualViewport, 'height', { configurable: true, value: 420 });
    Object.defineProperty(window.visualViewport, 'offsetTop', { configurable: true, value: 40 });
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
    const conversation = page.getByRole('region', { name: '聊天内容', exact: true });
    await conversation.evaluate((el) => (el.scrollTop = el.scrollHeight));
    expect((await input.boundingBox())!.y).toBeCloseTo(before!.y, 0);
    // 等待浏览器完成视口缩放重排，仍严格要求文档不溢出。
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollHeight))
      .toBeLessThanOrEqual(size.height + 1);
    await input.fill('布局测试草稿，不发送');
    await expect(input).toBeFocused();
    await expect(input).toHaveCSS('outline-style', 'none');
    await expect(send).toBeInViewport();
    await page.screenshot({ path: `build/chat-${size.width}-${size.height}.png` });
  }
});

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
  test(`${theme} 会话侧栏控件统一对齐且窄屏大字体不溢出`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    await openChat(page, theme);
    await page.evaluate(() => (document.documentElement.style.fontSize = '22px'));
    await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '流萤', exact: true });
    const create = dialog.getByRole('button', { name: '新建对话', exact: true });
    await expect(dialog).toHaveCSS('border-top-right-radius', '33px');
    await expect(dialog).toHaveCSS('border-bottom-right-radius', '33px');
    await expect(dialog).toHaveCSS('border-top-left-radius', '0px');
    await expect(dialog).toHaveCSS('border-bottom-left-radius', '0px');
    await expect(dialog).toHaveCSS('overflow-x', 'hidden');
    const legacy = dialog.getByRole('button', { name: '旧版历史记录', exact: true });
    const selected = dialog.locator('button[aria-current="page"]');
    await expect
      .poll(() => dialog.evaluate((el) => Math.abs(el.getBoundingClientRect().x)))
      .toBeLessThan(0.01);
    const createBox = (await create.boundingBox())!;
    const selectedBox = (await selected.boundingBox())!;
    expect(selectedBox.x).toBeCloseTo(createBox.x, 1);
    expect(selectedBox.width).toBeCloseTo(createBox.width, 1);
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
      expect(box.x).toBeCloseTo(createBox.x, 1);
      expect(box.width).toBeCloseTo(createBox.width, 1);
      await expect(control).toHaveCSS('border-top-width', '0px');
    }
    const inputBox = (await input.boundingBox())!;
    const saveBox = (await save.boundingBox())!;
    expect(saveBox.x).toBeCloseTo(inputBox.x, 1);
    expect(saveBox.width).toBeCloseTo(inputBox.width, 1);
    expect(saveBox.y).toBeGreaterThanOrEqual(inputBox.y + inputBox.height);
    expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    expect(await input.locator('..').evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    await page.screenshot({ path: `build/conversation-management-${theme}.png` });
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
  await expect(page.getByRole('heading', { name: '开拓者，想聊些什么？' })).toBeInViewport();
  await expect(page.getByRole('button', { name: '开始新对话' })).toBeInViewport();
  await expect(page.getByRole('button', { name: '在线执行', exact: true })).toBeInViewport();
  await page.screenshot({ path: 'build/chat-empty-night.png' });
});

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
