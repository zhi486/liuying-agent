import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

for (const theme of ['morning', 'night']) {
  test(`${theme} 慢读取与连续通知仍展示流式草稿，工具轮不遮挡输入框且只读取本机状态`, async ({
    page,
  }) => {
    const width = theme === 'night' ? 320 : 393;
    await page.setViewportSize({ width, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ fixture, theme }) => {
        fixture.payload.snapshot.theme = theme;
        fixture.payload.snapshot.session.state = 'idle';
        const calls: string[] = [];
        let listInFlight = 0;
        let maximumListInFlight = 0;
        let notificationCount = 0;
        let notificationTimer: number | undefined;
        let notificationsStarted = false;
        const row = {
          requestId: '22222222-2222-4222-8222-222222222222',
          prompt: '今晚想轻松跑，帮我安排一下。',
          busy: true,
          attempted: true,
          status: 'running',
          resultState: 'none',
          reason: null,
          answer: null as string | null,
          draftAnswer: '我们先按舒服的节奏开始。' as string | undefined,
          pendingControl: null,
          confirmedControl: null,
          error: null,
          memoryRefs: [],
        };
        const emit = (type: string, id: string, payload: unknown) =>
          queueMicrotask(() =>
            port.onmessage?.({
              data: JSON.stringify({
                contractVersion: 1,
                type,
                id,
                sentAt: new Date().toISOString(),
                payload,
              }),
            }),
          );
        const port = {
          onmessage: null as ((event: { data: string }) => void) | null,
          postMessage(raw: string) {
            const request = JSON.parse(raw);
            if (request.type === 'hello') {
              emit('ready', crypto.randomUUID(), { ...fixture.payload, helloId: request.id });
              return;
            }
            const name = request.payload.name;
            calls.push(name);
            const commandId = request.id;
            let data: unknown;
            if (name === 'cloud.list') {
              listInFlight += 1;
              maximumListInFlight = Math.max(maximumListInFlight, listInFlight);
              if (!notificationsStarted) {
                notificationsStarted = true;
                notificationTimer = window.setInterval(() => {
                  notificationCount += 1;
                  emit('event', crypto.randomUUID(), { name: 'cloud.changed' });
                }, 40);
              }
              data = {
                commandId,
                configured: true,
                configuring: false,
                origin: 'https://agent.example.com',
                chatAuthorized: false,
                conversationId: '33333333-3333-4333-8333-333333333333',
                conversations: [
                  {
                    id: '33333333-3333-4333-8333-333333333333',
                    title: '合成流式会话',
                    updatedAtMillis: 1000,
                  },
                ],
                items: [{ ...row }],
              };
              window.setTimeout(() => {
                listInFlight -= 1;
                emit('result', commandId, { status: 'accepted', data });
              }, 240);
              return;
            } else if (name === 'agent.list')
              data = { commandId, items: [], offset: 0, limit: 20, totalItems: 0 };
            else if (name === 'settings.query') data = { commandId };
            else throw new Error(`合成测试禁止命令：${name}`);
            emit('result', commandId, { status: 'accepted', data });
          },
        };
        Object.assign(window, {
          LiuyingNative: port,
          streamingTestCalls: calls,
          streamingTestReadState: () => ({
            maximumListInFlight,
            notificationCount,
            notificationsRunning: notificationTimer !== undefined,
          }),
          streamingTestStopNotifications: () => {
            window.clearInterval(notificationTimer);
            notificationTimer = undefined;
          },
          streamingTestUpdate: (patch: Partial<typeof row>) => {
            Object.assign(row, patch);
            emit('event', crypto.randomUUID(), { name: 'cloud.changed' });
          },
        });
      },
      { fixture, theme },
    );
    await page.goto('/#/agent');
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    const input = page.getByRole('textbox', { name: '给流萤的消息' });
    expect(
      await input.evaluate((element) =>
        getComputedStyle(element).getPropertyValue('--composer-action-bg').trim(),
      ),
    ).toBe('#36d39e');
    const header = page.locator('[class*="chatHeader"]').first();
    const headerBounds = await header.boundingBox();
    expect(headerBounds!.x).toBeCloseTo(0, 0);
    expect(headerBounds!.width).toBeCloseTo(width, 0);
    const inputGeometry = await input.evaluate((element) => {
      const style = getComputedStyle(element);
      return {
        height: element.getBoundingClientRect().height,
        lineHeight: parseFloat(style.lineHeight),
        top: parseFloat(style.paddingTop),
        bottom: parseFloat(style.paddingBottom),
      };
    });
    expect(Math.abs(inputGeometry.top - inputGeometry.bottom)).toBeLessThan(1);
    expect(
      Math.abs(
        inputGeometry.height - inputGeometry.lineHeight - inputGeometry.top - inputGeometry.bottom,
      ),
    ).toBeLessThanOrEqual(2);
    await expect(page.getByRole('region', { name: '流萤正在回答' })).toHaveAttribute(
      'aria-busy',
      'true',
    );
    await expect(page.getByText('我们先按舒服的节奏开始。')).toBeVisible();
    const stop = page.getByRole('button', { name: '停止生成', exact: true });
    const stopIcon = stop.locator('svg');
    const stopLabel = stop.getByText('停止生成', { exact: true });
    const [stopIconBox, stopLabelBox] = await Promise.all([
      stopIcon.boundingBox(),
      stopLabel.boundingBox(),
    ]);
    expect(stopIconBox && stopLabelBox).toBeTruthy();
    expect(
      Math.abs(
        stopIconBox!.y + stopIconBox!.height / 2 - (stopLabelBox!.y + stopLabelBox!.height / 2),
      ),
    ).toBeLessThanOrEqual(1);
    const readState = await page.evaluate(() => Reflect.get(window, 'streamingTestReadState')());
    expect(readState.notificationCount).toBeGreaterThanOrEqual(3);
    expect(readState.notificationsRunning).toBe(true);
    expect(readState.maximumListInFlight).toBe(1);
    await page.evaluate(() => Reflect.get(window, 'streamingTestStopNotifications')());
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: `build/batch65-stream-refresh-${theme}.png` });
    await input.fill('保留的本机草稿，不发送');
    await page.setViewportSize({ width, height: 450 });
    await page.evaluate(() => {
      const update = Reflect.get(window, 'streamingTestUpdate');
      update({ draftAnswer: '我们先按舒服的节奏开始。身体感觉轻松时，就保持能说话的速度。' });
    });
    await expect(
      page.getByText('我们先按舒服的节奏开始。身体感觉轻松时，就保持能说话的速度。'),
    ).toBeVisible();
    await expect(input).toBeInViewport();
    await expect(input).toHaveValue('保留的本机草稿，不发送');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/batch65-stream-refresh-keyboard-${theme}.png` });
    await page.evaluate(() =>
      Reflect.get(window, 'streamingTestUpdate')({ draftAnswer: undefined }),
    );
    await expect(page.getByRole('region', { name: '流萤正在回答' })).toHaveCount(0);
    await page.evaluate(() =>
      Reflect.get(
        window,
        'streamingTestUpdate',
      )({
        draftAnswer: '工具返回后的新一轮，不能拼接上一轮文字。',
      }),
    );
    await expect(page.getByText('工具返回后的新一轮，不能拼接上一轮文字。')).toBeVisible();
    await page.evaluate(() =>
      Reflect.get(
        window,
        'streamingTestUpdate',
      )({
        draftAnswer: undefined,
        busy: false,
        status: 'completed',
        resultState: 'available',
        answer: '这是任务回执确认后的正式回答。',
      }),
    );
    await expect(page.getByRole('region', { name: '流萤的回答' })).toBeVisible();
    const copy = page.getByRole('button', { name: '复制回答', exact: true });
    const playback = page.getByRole('button', { name: '播放回答', exact: true });
    const copyBox = (await copy.boundingBox())!;
    const playBox = (await playback.boundingBox())!;
    expect(playBox.x).toBeGreaterThan(copyBox.x);
    expect(Math.abs(playBox.y - copyBox.y)).toBeLessThanOrEqual(1);
    await expect(playback).toBeDisabled();
    await expect(page.getByText('工具返回后的新一轮，不能拼接上一轮文字。')).toHaveCount(0);
    await expect(input).toBeInViewport();
    await expect(input).toHaveValue('保留的本机草稿，不发送');
    expect(
      await page.evaluate(
        () => Reflect.get(window, 'streamingTestReadState')().maximumListInFlight,
      ),
    ).toBe(1);
    await page.screenshot({ path: `build/batch65-stream-refresh-final-${theme}.png` });
    await page.evaluate(() =>
      Reflect.get(
        window,
        'streamingTestUpdate',
      )({
        status: 'running',
        answer: null,
        resultState: 'none',
        pendingControl: 'cancel',
        confirmedControl: null,
      }),
    );
    await expect(page.getByText(/正在停止生成；云端控制请求待确认/)).toBeVisible();
    await expect(input).toBeInViewport();
    await page.setViewportSize({ width, height: 800 });
    await page.getByRole('button', { name: '恢复查询', exact: true }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('button', { name: '恢复查询', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '恢复查询', exact: true })).toBeInViewport();
    await expect(input).toBeInViewport();
    await page.screenshot({ path: `build/control-pending-${theme}.png` });
    await page.evaluate(() =>
      Reflect.get(
        window,
        'streamingTestUpdate',
      )({
        status: 'cancelled',
        confirmedControl: 'cancel',
      }),
    );
    await expect(page.getByText(/停止请求已确认；云端已确认控制请求/)).toBeVisible();
    await expect(page.getByText(/正在停止生成/)).toHaveCount(0);
    await page.screenshot({ path: `build/control-confirmed-${theme}.png` });
    const newChat = await page.getByRole('button', { name: '开始新对话' }).boundingBox();
    const runLink = await page.getByRole('link', { name: '切换到跑步' }).boundingBox();
    expect(newChat && runLink).toBeTruthy();
    const currentHeader = (await header.boundingBox())!;
    expect(runLink!.y - currentHeader.y).toBeGreaterThanOrEqual(5);
    expect(
      currentHeader.y + currentHeader.height - runLink!.y - runLink!.height,
    ).toBeGreaterThanOrEqual(5);
    expect(
      Math.abs(newChat!.y + newChat!.height / 2 - runLink!.y - runLink!.height / 2),
    ).toBeLessThanOrEqual(1);
    const inputSurface = input.locator('..');
    expect((await inputSurface.boundingBox())!.height).toBeLessThan(180);
    await expect(page.getByRole('group', { name: '本次知识库' })).toHaveCount(0);
    await expect(page.locator('[class*="composerAuthority"]')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '开始新对话' })).toHaveCSS(
      'border-top-width',
      '0px',
    );
    await page.getByRole('link', { name: '切换到跑步' }).click();
    const back = page.getByRole('link', { name: '返回聊天', exact: true });
    await expect(back).toBeVisible();
    await expect(back).toHaveText('');
    expect(await back.evaluate((element) => getComputedStyle(element).borderRadius)).toBe('50%');
    await expect(page.locator('.app-shell-run')).toHaveCSS('background-color', 'rgb(23, 23, 31)');
    await page.screenshot({ path: `build/run-fullbleed-${theme}.png` });
    await back.click();
    await expect(input).toBeInViewport();
    await page.getByRole('button', { name: '打开侧边栏' }).click();
    await page.getByRole('button', { name: '设置与管理', exact: true }).click();
    const settingsMenu = page.getByRole('button', { name: '展开设置入口', exact: true });
    await expect(settingsMenu).toHaveAttribute('aria-expanded', 'false');
    await expect(page.getByRole('link', { name: 'MCP 插件', exact: true })).toBeHidden();
    await settingsMenu.click();
    await expect(page).toHaveURL(/#\/agent$/);
    await expect(page.getByRole('link', { name: '设置', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '运动与语音设置', exact: true })).toHaveCount(0);
    for (const name of ['账单与用量', '聊天授权', 'MCP 插件', '长期记忆', '用户须知']) {
      await expect(page.getByRole('link', { name, exact: true })).toBeVisible();
    }
    await page.screenshot({ path: `build/management-settings-${theme}.png` });
    await page.getByRole('link', { name: '用户须知', exact: true }).click();
    await page.getByRole('link', { name: '返回设置与管理', exact: true }).click();
    await expect(page.getByRole('button', { name: '展开设置入口', exact: true })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    await expect(page.getByRole('link', { name: 'MCP 插件', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '展开设置入口', exact: true }).click();
    await expect(page.getByRole('link', { name: 'MCP 插件', exact: true })).toBeHidden();
    await page.getByRole('link', { name: '设置', exact: true }).click();
    await expect(page.getByRole('switch', { name: '运动健康', exact: true })).toBeChecked();
    await expect(page.getByRole('switch', { name: '流萤资料', exact: true })).toBeChecked();
    await expect(
      page.getByRole('switch', { name: '流萤资料', exact: true }).locator('+ .choice-visual'),
    ).toHaveCSS(
      'background-color',
      theme === 'morning' ? 'rgb(23, 108, 83)' : 'rgb(139, 214, 186)',
    );
    await page.getByRole('switch', { name: '运动健康', exact: true }).uncheck();
    await expect(page.getByRole('switch', { name: '运动健康', exact: true })).not.toBeChecked();
    await page.screenshot({ path: `build/knowledge-settings-${theme}.png` });
    expect(
      await page.evaluate(() =>
        Reflect.get(window, 'streamingTestCalls').every((name: string) =>
          ['cloud.list', 'agent.list', 'settings.query'].includes(name),
        ),
      ),
    ).toBe(true);
  });
}
