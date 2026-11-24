import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

for (const theme of ['morning', 'night']) {
  test(`${theme} 聊天减负与设置账单在窄屏大字体下可用`, async ({ page }) => {
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });
    await page.route('**/*', (route) =>
      new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
    );
    await page.addInitScript(
      ({ fixture, theme }) => {
        const snapshot = fixture.payload.snapshot;
        snapshot.theme = theme;
        snapshot.settings.themePreference = theme;
        snapshot.session.state = 'finished';
        const calls: string[] = [];
        let chatAuthorized = false;
        const port = {
          onmessage: null as ((event: { data: string }) => void) | null,
          postMessage(raw: string) {
            const request = JSON.parse(raw);
            const emit = (data: unknown) =>
              queueMicrotask(() => port.onmessage?.({ data: JSON.stringify(data) }));
            if (request.type === 'hello') {
              emit({ ...fixture, payload: { ...fixture.payload, helloId: request.id } });
              return;
            }
            const name = request.payload.name;
            calls.push(name);
            const commandId = request.id;
            let data: unknown;
            if (name === 'cloud.list' || name === 'cloud.chatConsent') {
              if (name === 'cloud.chatConsent') {
                if (request.payload.body.identityToken !== 'a'.repeat(64))
                  throw new Error('授权身份不一致');
                chatAuthorized = request.payload.body.enabled;
              }
              data = {
                commandId,
                configured: true,
                configuring: false,
                chatAuthorized,
                chatConsentContext: 'a'.repeat(64),
                origin: 'https://agent.example.com',
                conversationId: '33333333-3333-4333-8333-333333333333',
                conversations: [
                  {
                    id: '33333333-3333-4333-8333-333333333333',
                    title: '合成测试：今晚轻松跑的安排与长期训练偏好',
                    updatedAtMillis: 1000,
                  },
                ],
                items: [
                  {
                    requestId: '22222222-2222-4222-8222-222222222222',
                    prompt: '流萤，今晚轻松跑。',
                    answer: '好呀，我们按舒服的节奏来。你想跑多久？',
                    busy: false,
                    attempted: true,
                    status: 'completed',
                    resultState: 'acknowledged',
                    reason: null,
                    pendingControl: null,
                    confirmedControl: null,
                    error: null,
                    memoryRefs: [],
                    memorySaved: true,
                    cost: {
                      currency: 'USD',
                      estimatedMicros: 78,
                      inputTokens: 100,
                      outputTokens: 40,
                      usageComplete: true,
                      rateStatus: 'stale',
                    },
                    sources: [
                      {
                        citationId: 'official_test',
                        title: '合成来源标题',
                        url: 'https://hsr.hoyoverse.com/zh-tw/character',
                        publishedAt: null,
                        checkedAtMillis: 1000,
                      },
                    ],
                  },
                  {
                    requestId: '44444444-4444-4444-8444-444444444444',
                    prompt: '合成问题：该条用量尚待确认',
                    answer: null,
                    busy: false,
                    attempted: true,
                    status: 'failed',
                    resultState: 'none',
                    reason: 'invalid_model_output',
                    pendingControl: null,
                    confirmedControl: null,
                    error: null,
                    memoryRefs: [],
                  },
                ],
              };
            } else if (name === 'agent.list')
              data = { commandId, offset: 0, limit: 20, totalItems: 0, items: [] };
            else if (name === 'cloud.memoryAuthorization')
              data = { commandId, configured: true, enabled: false, context: 'b'.repeat(64) };
            else if (name === 'memory.list')
              data = {
                commandId,
                offset: 0,
                limit: 20,
                totalItems: 1,
                items: [
                  {
                    id: '55555555-5555-4555-8555-555555555555',
                    content: '我喜欢夜跑',
                    source: 'user_explicit',
                    version: 1,
                    createdAtMillis: 1000,
                    updatedAtMillis: 1000,
                  },
                ],
              };
            else if (name === 'settings.query') data = { commandId, settings: snapshot.settings };
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
        Object.assign(window, { LiuyingNative: port, agentSettingsTestCalls: calls });
      },
      { fixture, theme },
    );
    await page.goto('/#/agent');
    if (theme === 'night')
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
    await expect(page.getByText('好呀，我们按舒服的节奏来。你想跑多久？')).toBeVisible();
    await expect(page.getByText('已完成', { exact: true })).toHaveCount(0);
    const savedMemory = page.getByRole('link', { name: '记忆已保存，管理记忆' });
    await expect(savedMemory).toHaveAttribute('href', '#/memories');
    await savedMemory.scrollIntoViewIfNeeded();
    await expect(savedMemory).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/agent-memory-saved-${theme}.png` });
    await expect(page.getByText(/USD|token|本次成本估算/)).toHaveCount(0);
    await expect(page.getByText('在线发送前会再次确认')).toHaveCSS(
      'clip',
      'rect(0px, 0px, 0px, 0px)',
    );
    await expect(page.getByText('合成来源标题')).toBeHidden();
    await page.getByLabel('查看来源 1', { exact: true }).click();
    await expect(page.getByText('合成来源标题')).toBeVisible();
    await page.getByLabel('查看来源 1', { exact: true }).click();
    await page.screenshot({ path: `build/agent-clean-chat-${theme}.png` });

    await savedMemory.click();
    await expect(page.getByRole('heading', { name: '我的记忆', exact: true })).toBeVisible();
    await expect(page.getByText('我喜欢夜跑', { exact: true })).toBeVisible();
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    await page.goBack();
    await expect(page.getByRole('link', { name: '记忆已保存，管理记忆' })).toBeVisible();

    const input = page.getByRole('textbox', { name: '给流萤的消息' });
    await input.fill('合成草稿，不发送');
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 450 });
    await expect(input).toBeInViewport();
    await expect(page.getByRole('button', { name: '在线执行', exact: true })).toBeInViewport();
    await page.screenshot({ path: `build/agent-clean-keyboard-${theme}.png` });
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });

    await page.getByRole('button', { name: '打开侧边栏' }).click();
    await page.getByRole('button', { name: '设置与管理' }).click();
    await page.getByRole('link', { name: '设置', exact: true }).click();
    await expect(page).toHaveURL(/#\/settings$/);
    const settingsNav = page.getByRole('navigation', { name: 'Agent 与数据' });
    const settingsLinks = settingsNav.getByRole('link');
    await expect(page.getByRole('heading', { name: 'Agent 与数据' })).toBeVisible();
    await expect(settingsLinks).toHaveCount(5);
    for (const name of ['账单与用量', '聊天授权', 'MCP 插件', '长期记忆', '用户须知']) {
      await expect(page.getByRole('link', { name, exact: true })).toBeVisible();
    }
    const settingsGeometry = await settingsLinks.evaluateAll((elements) =>
      elements.map((element) => {
        const style = getComputedStyle(element);
        return {
          height: element.getBoundingClientRect().height,
          backdropFilter: style.backdropFilter,
        };
      }),
    );
    expect(settingsGeometry.every(({ height }) => height >= 44)).toBe(true);
    expect(settingsGeometry.every(({ backdropFilter }) => backdropFilter === 'none')).toBe(true);
    expect(
      await settingsNav.evaluate((element) => {
        const style = getComputedStyle(element);
        return style.backdropFilter !== 'none' && style.backgroundColor !== 'rgba(0, 0, 0, 0)';
      }),
    ).toBe(true);
    const themeGroup = page.getByRole('radiogroup', { name: '界面主题' });
    const themeRadios = themeGroup.getByRole('radio');
    const appearanceSection = page.locator('section[aria-labelledby="appearance-title"]');
    const selectedTheme = theme === 'morning' ? '晨光，清爽浅色' : '夜萤，沉静深色';
    const otherTheme = theme === 'morning' ? '夜萤，沉静深色' : '晨光，清爽浅色';
    await appearanceSection.scrollIntoViewIfNeeded();
    await expect(appearanceSection).toBeVisible();
    await expect(themeGroup).toBeVisible();
    await expect(themeRadios).toHaveCount(2);
    await expect(page.getByRole('radio', { name: selectedTheme })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await expect(page.getByRole('radio', { name: otherTheme })).toHaveAttribute(
      'aria-checked',
      'false',
    );
    await expect(page.getByRole('radio', { name: selectedTheme })).toHaveAttribute('tabindex', '0');
    await expect(page.getByRole('radio', { name: otherTheme })).toHaveAttribute('tabindex', '-1');
    const themeGeometry = await themeRadios.evaluateAll((elements) =>
      elements.map((element) => {
        const rect = element.getBoundingClientRect();
        return {
          top: rect.top,
          width: rect.width,
          height: rect.height,
          right: rect.right,
          viewportWidth: document.documentElement.clientWidth,
        };
      }),
    );
    expect(themeGeometry.every(({ height }) => height >= 44)).toBe(true);
    expect(themeGeometry.every(({ width }) => width > 0)).toBe(true);
    expect(themeGeometry.every(({ right, viewportWidth }) => right <= viewportWidth)).toBe(true);
    expect(Math.abs(themeGeometry[0].top - themeGeometry[1].top)).toBeLessThanOrEqual(1);
    expect(
      await appearanceSection.evaluate((element) => {
        const themeWindow = element.querySelector('[class*="themeCanvasWindow"]');
        if (!themeWindow) return false;
        const style = getComputedStyle(themeWindow);
        return style.backdropFilter !== 'none' && style.backgroundColor !== 'rgba(0, 0, 0, 0)';
      }),
    ).toBe(true);
    if (theme === 'night') {
      await page.setViewportSize({ width: 393, height: 800 });
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '16px';
      });
      await page.evaluate(() => document.fonts.ready);
      await appearanceSection.scrollIntoViewIfNeeded();
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        .toBe(true);
      await appearanceSection.screenshot({
        path: 'build/design-reference/settings-theme-393-night-C.png',
      });
      await page.setViewportSize({ width: 320, height: 800 });
      await page.evaluate(() => {
        document.documentElement.style.fontSize = '20px';
      });
      await page.evaluate(() => document.fonts.ready);
      await appearanceSection.scrollIntoViewIfNeeded();
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        .toBe(true);
    }
    await settingsLinks.first().focus();
    await expect(settingsLinks.first()).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.evaluate(() => document.fonts.ready);
    await appearanceSection.screenshot({
      path: `build/design-reference/settings-theme-${theme === 'night' ? '320-night-20px' : '393-morning'}-C.png`,
    });
    await page.screenshot({ path: `build/agent-settings-${theme}.png`, fullPage: true });
    await page.getByRole('link', { name: '账单与用量', exact: true }).click();
    await expect(page.getByRole('heading', { name: '账单与用量' })).toBeVisible();
    const knownUsage = page.getByRole('button', {
      name: '流萤，今晚轻松跑。 用量已确认 $0.000078 USD',
      exact: true,
    });
    await expect(knownUsage).toBeVisible();
    await expect(
      page.getByRole('button', {
        name: '合成问题：该条用量尚待确认 用量不完整，不代表零费用 待确认 USD',
        exact: true,
      }),
    ).toBeVisible();
    await expect(page.getByText(/当前会话最近 10 条本机记录/)).toBeVisible();
    // 切页会加载此页样式与字体；等待布局收敛，仍要求实际内容宽度不超出视口。
    await page.evaluate(() => document.fonts.ready);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
    await page.screenshot({ path: `build/agent-usage-${theme}.png`, fullPage: true });
    await page.getByRole('button', { name: '刷新本机用量' }).click();
    await expect(knownUsage).toBeVisible();
    await page.getByRole('link', { name: '返回设置', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Agent 与数据' })).toBeVisible();
    await page.getByRole('link', { name: '用户须知', exact: true }).click();
    await expect(page.getByRole('heading', { name: '用户须知' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/agent-notices-${theme}.png`, fullPage: true });
    await page.getByRole('link', { name: '返回设置', exact: true }).click();
    await page.getByRole('link', { name: '聊天授权', exact: true }).click();
    const enable = page.getByRole('switch', { name: '持续聊天授权' });
    await expect(enable).toBeDisabled();
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/agent-chat-consent-off-${theme}.png`, fullPage: true });
    await page.getByRole('checkbox').check();
    await enable.click();
    await expect(page.getByText('持续授权已开启', { exact: true })).toBeVisible();
    await page.screenshot({ path: `build/agent-chat-consent-on-${theme}.png`, fullPage: true });
    await page.getByRole('switch', { name: '持续聊天授权' }).click();
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    expect(
      await page.evaluate(() =>
        (window as Window & { agentSettingsTestCalls?: string[] }).agentSettingsTestCalls?.every(
          (name) =>
            [
              'cloud.list',
              'cloud.chatConsent',
              'agent.list',
              'settings.query',
              'memory.list',
              'cloud.memoryAuthorization',
            ].includes(name),
        ),
      ),
    ).toBe(true);
  });
}
