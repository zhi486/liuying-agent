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
    await expect(page.getByText(/USD|token|本次成本估算/)).toHaveCount(0);
    await expect(page.getByText('在线发送前会再次确认')).toHaveCSS(
      'clip',
      'rect(0px, 0px, 0px, 0px)',
    );
    await expect(page.getByText('合成来源标题')).toBeHidden();
    await page.getByText('来源（1）').click();
    await expect(page.getByText('合成来源标题')).toBeVisible();
    await page.getByText('来源（1）').click();
    await page.screenshot({ path: `build/agent-clean-chat-${theme}.png` });

    const input = page.getByRole('textbox', { name: '给流萤的消息' });
    await input.fill('合成草稿，不发送');
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 450 });
    await expect(input).toBeInViewport();
    await expect(page.getByRole('button', { name: '在线执行', exact: true })).toBeInViewport();
    await page.screenshot({ path: `build/agent-clean-keyboard-${theme}.png` });
    await page.setViewportSize({ width: theme === 'night' ? 320 : 393, height: 800 });

    await page.getByRole('button', { name: '打开侧边栏' }).click();
    await page.getByRole('button', { name: '设置与管理' }).click();
    await page.getByRole('link', { name: '账单与用量', exact: true }).click();
    await expect(page.getByRole('heading', { name: '账单与用量' })).toBeVisible();
    await expect(page.getByText('$0.000078 USD')).toBeVisible();
    await expect(page.getByText('待确认', { exact: true })).toBeVisible();
    await expect(page.getByText(/当前会话最近 10 条本机记录/)).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/agent-usage-${theme}.png`, fullPage: true });
    await page.getByRole('button', { name: '刷新本机用量' }).click();
    await expect(page.getByText('$0.000078 USD')).toBeVisible();
    await page.getByRole('link', { name: '返回设置', exact: true }).click();
    await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible();
    await page.getByRole('link', { name: '用户须知', exact: true }).click();
    await expect(page.getByRole('heading', { name: '用户须知' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/agent-notices-${theme}.png`, fullPage: true });
    await page.getByRole('link', { name: '返回设置', exact: true }).click();
    await page.getByRole('link', { name: '聊天授权', exact: true }).click();
    const enable = page.getByRole('button', { name: '开启持续授权' });
    await expect(enable).toBeDisabled();
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `build/agent-chat-consent-off-${theme}.png`, fullPage: true });
    await page.getByRole('checkbox').check();
    await enable.click();
    await expect(page.getByRole('heading', { name: '持续授权已开启' })).toBeVisible();
    await page.screenshot({ path: `build/agent-chat-consent-on-${theme}.png`, fullPage: true });
    await page.getByRole('button', { name: '关闭持续授权' }).click();
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    expect(
      await page.evaluate(() =>
        (window as Window & { agentSettingsTestCalls?: string[] }).agentSettingsTestCalls?.every(
          (name) =>
            ['cloud.list', 'cloud.chatConsent', 'agent.list', 'settings.query'].includes(name),
        ),
      ),
    ).toBe(true);
  });
}
