import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
declare global {
  interface Window {
    chatRevealMotions: Animation[];
    chatRevealCalls: string[];
  }
}

async function open(page: Page, theme: string, mode = 'normal') {
  await page.route('**/*', (route) =>
    new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
  );
  await page.addInitScript(
    ({ fixture, theme, mode }) => {
      fixture.payload.snapshot.theme = theme;
      fixture.payload.snapshot.motionPolicy =
        mode === 'off' || mode === 'reduced' ? mode : 'expressive';
      fixture.payload.snapshot.session.state = 'finished';
      window.chatRevealCalls = [];
      window.chatRevealMotions = [];
      if (mode === 'unsupported')
        Object.defineProperty(document, 'startViewTransition', { value: undefined });
      const animate = Element.prototype.animate;
      Element.prototype.animate = function (frames, options) {
        const animation = animate.call(this, frames, options);
        if (
          typeof options === 'object' &&
          options?.pseudoElement === '::view-transition-new(root)'
        ) {
          window.chatRevealMotions.push(animation);
          animation.pause();
          animation.currentTime = 50;
        }
        return animation;
      };
      let empty = false;
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
          window.chatRevealCalls.push(name);
          if (!['cloud.list', 'cloud.newConversation', 'agent.list'].includes(name))
            throw new Error(`禁止真实命令：${name}`);
          const reply = () => {
            if (name === 'cloud.newConversation' && mode !== 'denied') empty = true;
            const data =
              name === 'agent.list'
                ? { offset: 0, limit: 20, totalItems: 0, items: [] }
                : {
                    configured: true,
                    configuring: false,
                    origin: 'https://agent.example.com',
                    conversationId: empty
                      ? '33333333-3333-4333-8333-333333333333'
                      : '44444444-4444-4444-8444-444444444444',
                    conversations: [
                      {
                        id: '44444444-4444-4444-8444-444444444444',
                        title: '跑后复盘',
                        updatedAtMillis: 1000,
                      },
                    ],
                    items: empty
                      ? []
                      : [
                          {
                            requestId: '22222222-2222-4222-8222-222222222222',
                            prompt: '合成旧会话：今天的跑步感觉如何？',
                            answer:
                              '这是旧页面的合成回答。\n放慢脚步，留意呼吸与恢复。\n新页面从加号向四周展开。',
                            attempted: true,
                            busy: false,
                            status: 'completed',
                            resultState: 'acknowledged',
                            reason: null,
                            pendingControl: null,
                            confirmedControl: null,
                            error: null,
                            memoryRefs: [],
                          },
                        ],
                  };
            emit({
              contractVersion: 1,
              type: 'result',
              id: request.id,
              sentAt: new Date().toISOString(),
              payload:
                mode === 'denied' && name === 'cloud.newConversation'
                  ? {
                      status: 'rejected',
                      error: { code: 'SESSION_CONFLICT', message: '合成拒绝' },
                    }
                  : { status: 'accepted', data: { ...data, commandId: request.id } },
            });
          };
          if (mode === 'slow' && name === 'cloud.newConversation') setTimeout(reply, 900);
          else reply();
        },
      };
      Object.assign(window, { LiuyingNative: port });
    },
    { fixture, theme, mode },
  );
  await page.goto('/#/agent');
  await expect(page.getByTestId('message-user-bubble')).toHaveText(
    '合成旧会话：今天的跑步感觉如何？',
  );
}

test('短视口顶栏加号按下不跳位，松手即从原位置展开新对话', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 393, height: 568 });
  await open(page, 'night');
  const button = page.getByRole('button', { name: '开始新对话', exact: true });
  const before = (await button.boundingBox())!;
  const icon = (await button.locator('svg').boundingBox())!;
  await page.mouse.move(before.x + before.width / 2, before.y + before.height / 2);
  await page.mouse.down();
  await expect(button.locator('.press-ripple')).toHaveCount(1);
  expect(await button.boundingBox()).toEqual(before);
  expect(await button.locator('svg').boundingBox()).toEqual(icon);
  await page.screenshot({ path: testInfo.outputPath('header-pressed.png') });
  await page.mouse.up();
  await expect.poll(() => page.evaluate(() => window.chatRevealMotions.length)).toBe(1);
  const center = await page.evaluate(
    () => (window.chatRevealMotions[0].effect as KeyframeEffect).getKeyframes()[0].clipPath,
  );
  const origin = /^circle\(0px at ([\d.]+)px ([\d.]+)px\)$/.exec(String(center));
  expect(origin).not.toBeNull();
  expect(Number(origin![1])).toBeCloseTo(icon.x + icon.width / 2, 2);
  expect(Number(origin![2])).toBeCloseTo(icon.y + icon.height / 2, 2);
  await page.screenshot({ path: testInfo.outputPath('header-reveal.png') });
  await page.evaluate(() => window.chatRevealMotions[0].finish());
  await expect(page.getByRole('heading', { name: '今天想把哪件事交给流萤？' })).toBeVisible();
  await expect(page.getByRole('dialog', { name: '流萤', exact: true })).toHaveCount(0);
  expect(
    await page.evaluate(() =>
      window.chatRevealCalls.filter((name) => name === 'cloud.newConversation'),
    ),
  ).toEqual(['cloud.newConversation']);
});

for (const theme of ['morning', 'night'])
  for (const width of [320, 393, 1024]) {
    test(`${theme} ${width}px 顶部与侧栏从加号圆形揭示新页面`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 800 });
      await open(page, theme);
      if (width === 320)
        await page.evaluate(() => {
          document.documentElement.style.fontSize = '20px';
        });
      for (const entry of ['header', 'sidebar']) {
        if (entry === 'sidebar') {
          await page.reload();
          await page.getByRole('button', { name: '打开侧边栏', exact: true }).click();
          await expect(page.locator('#agent-sidebar')).toHaveCSS(
            'transform',
            'matrix(1, 0, 0, 1, 0, 0)',
          );
        }
        const button = page.getByRole('button', {
          name: entry === 'header' ? '开始新对话' : '新建对话',
          exact: true,
        });
        const before = (await button.boundingBox())!;
        const icon = (await button.locator('svg').boundingBox())!;
        await page.screenshot({ path: testInfo.outputPath(`${entry}-old.png`) });
        if (width === 393 && entry === 'header') {
          await button.focus();
          await button.press('Enter');
        } else await button.click();
        await expect.poll(() => page.evaluate(() => window.chatRevealMotions.length)).toBe(1);
        if (entry === 'header') {
          expect(await button.boundingBox()).toEqual(before);
          await expect(button.locator('.press-ripple')).toHaveCount(1);
          await expect(page.getByRole('button', { name: '开始新对话', exact: true })).toHaveCount(
            1,
          );
          await expect(page.getByRole('button', { name: '新建对话', exact: true })).toHaveCount(0);
          await expect(page.getByRole('dialog', { name: '流萤', exact: true })).toHaveCount(0);
          await expect(page.getByRole('button', { name: '打开侧边栏', exact: true })).toHaveCount(
            1,
          );
          await expect(
            page.getByRole('button', { name: '打开侧边栏', exact: true }).locator('.menu-glyph'),
          ).toHaveAttribute('data-open', 'false');
        }
        const motion = await page.evaluate(() => {
          const animation = window.chatRevealMotions[0];
          const effect = animation.effect as KeyframeEffect;
          return {
            frames: effect.getKeyframes(),
            timing: effect.getTiming(),
            clip: getComputedStyle(document.documentElement, '::view-transition-new(root)')
              .clipPath,
          };
        });
        const origin = /^circle\(0px at ([\d.]+)px ([\d.]+)px\)$/.exec(
          String(motion.frames[0].clipPath),
        );
        expect(origin).not.toBeNull();
        // CSSOM 会舍入小数位，仍要求圆心与真实图标中心相差不足百分之一像素。
        expect(Number(origin![1])).toBeCloseTo(icon.x + icon.width / 2, 2);
        expect(Number(origin![2])).toBeCloseTo(icon.y + icon.height / 2, 2);
        expect(motion.timing.duration).toBe(480);
        expect(motion.clip).not.toMatch(/^circle\(0px/);
        await expect(page.locator('.app-shell-agent')).toHaveCSS(
          'transform',
          'matrix(1, 0, 0, 1, 0, 0)',
        );
        const drawer = await page.locator('#agent-sidebar').evaluate((node) => ({
          offset: new DOMMatrix(getComputedStyle(node).transform).m41,
          width: parseFloat(
            getComputedStyle(document.documentElement).getPropertyValue('--chat-drawer-width'),
          ),
        }));
        expect(drawer.offset).toBeCloseTo(-drawer.width, 2);
        await expect(
          page.getByRole('heading', { name: '今天想把哪件事交给流萤？' }),
        ).toBeAttached();
        await expect(page.getByTestId('message-user-bubble')).toHaveCount(0);
        await page.screenshot({ path: testInfo.outputPath(`${entry}-mid.png`) });
        await page.evaluate(() => {
          window.chatRevealMotions[0].finish();
        });
        await expect(page.locator('html')).not.toHaveAttribute('data-new-chat-reveal');
        await expect(page.getByLabel('给流萤的消息')).toBeVisible();
        await expect(page.getByLabel('给流萤的消息')).toHaveValue('');
        await page.screenshot({ path: testInfo.outputPath(`${entry}-new.png`) });
        expect(
          await page.evaluate(
            () =>
              document.documentElement.scrollWidth <= innerWidth &&
              document.documentElement.scrollHeight <= innerHeight,
          ),
        ).toBe(true);
        expect(
          await page.evaluate(
            () => window.chatRevealCalls.filter((name) => name === 'cloud.newConversation').length,
          ),
        ).toBe(1);
        expect(
          await page.evaluate(() =>
            window.chatRevealCalls.every((name) =>
              ['cloud.list', 'cloud.newConversation', 'agent.list'].includes(name),
            ),
          ),
        ).toBe(true);
      }
    });
  }

for (const mode of [
  'reduced',
  'off',
  'system',
  'unsupported',
  'denied',
  'slow',
  'keyboard-resize',
]) {
  test(`${mode} 正常降级、拒绝或中断，不额外新建或发送`, async ({ page }) => {
    if (mode === 'system') await page.emulateMedia({ reducedMotion: 'reduce' });
    await open(page, 'night', mode);
    await page.getByRole('button', { name: '开始新对话', exact: true }).click();
    if (mode === 'keyboard-resize') {
      await expect.poll(() => page.evaluate(() => window.chatRevealMotions.length)).toBe(1);
      await page.setViewportSize({ width: 393, height: 420 });
    }
    await expect(page.locator('html')).not.toHaveAttribute('data-new-chat-reveal');
    if (mode === 'denied') await expect(page.getByTestId('message-user-bubble')).toBeVisible();
    else
      await expect(page.getByRole('heading', { name: '今天想把哪件事交给流萤？' })).toBeVisible();
    if (mode !== 'keyboard-resize')
      expect(await page.evaluate(() => window.chatRevealMotions.length)).toBe(0);
    expect(
      await page.evaluate(
        () => window.chatRevealCalls.filter((name) => name === 'cloud.newConversation').length,
      ),
    ).toBe(1);
  });
}
