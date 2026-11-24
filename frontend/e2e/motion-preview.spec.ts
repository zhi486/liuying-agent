import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

const fixture = JSON.parse(
  readFileSync(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);

test('放大汉堡慢放与手动中间帧，减少动效不自动播放', async ({ page }, info) => {
  await page.goto('/#/design/reference?preview=motion');
  const sample = page.getByTestId('menu-morph-demo');
  const slider = page.getByRole('slider', { name: '三横线变叉进度' });
  await slider.fill('0');
  await page.getByRole('button', { name: '慢放三横线变叉' }).click();
  await expect.poll(() => slider.inputValue()).not.toBe('0');
  await expect(slider).toHaveValue('100');
  await expect(sample.locator('.menu-glyph > span').nth(1)).toHaveCSS('opacity', '0');
  await page.getByRole('button', { name: '慢放还原三横线' }).click();
  await expect(slider).toHaveValue('0');
  for (const progress of ['0', '50', '100']) {
    await slider.fill(progress);
    const middle = Number(
      await sample
        .locator('.menu-glyph > span')
        .nth(1)
        .evaluate((node) => getComputedStyle(node).opacity),
    );
    expect(middle).toBe(1 - Number(progress) / 100);
    const transform = await sample
      .locator('.menu-glyph > span')
      .first()
      .evaluate((node) => {
        const matrix = new DOMMatrix(getComputedStyle(node).transform);
        return { angle: (Math.atan2(matrix.b, matrix.a) * 180) / Math.PI, y: matrix.f };
      });
    expect(transform.angle).toBeCloseTo(Number(progress) * 0.45, 3);
    expect(transform.y).toBeCloseTo(-6 * (1 - Number(progress) / 100), 3);
    await page.screenshot({ path: info.outputPath(`morph-${progress}.png`) });
  }
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(page.getByRole('button', { name: '慢放还原三横线' })).toBeDisabled();
  await slider.fill('50');
  await expect(slider).toHaveValue('50');
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.evaluate(() => (document.documentElement.dataset.motion = 'off'));
  await expect(page.getByRole('button', { name: '慢放还原三横线' })).toBeDisabled();
});

test.beforeEach(async ({ page }) => {
  await page.route('**/*', (route) =>
    new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort(),
  );
  await page.addInitScript(
    ({ fixture }) => {
      const calls: string[] = [];
      const port = {
        onmessage: null as ((event: { data: string }) => void) | null,
        postMessage(raw: string) {
          const request = JSON.parse(raw);
          if (request.type === 'hello') {
            fixture.payload.snapshot.motionPolicy = 'expressive';
            queueMicrotask(() =>
              port.onmessage?.({
                data: JSON.stringify({
                  ...fixture,
                  payload: { ...fixture.payload, helloId: request.id },
                }),
              }),
            );
          } else calls.push(request.payload.name);
        },
      };
      Object.assign(window, { LiuyingNative: port, previewCalls: calls });
    },
    { fixture },
  );
});

for (const theme of ['浅色', '深色']) {
  for (const width of [320, 393, 1280]) {
    test(`${theme} ${width}px 预览可操作且无业务命令`, async ({ page }, info) => {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/#/design/reference?preview=motion');
      await page.getByRole('button', { name: theme, exact: true }).click();
      if (width === 320)
        await page.evaluate(() => {
          document.documentElement.style.fontSize = '20px';
        });
      const phone = page.getByTestId('motion-phone');
      await phone.screenshot({ path: info.outputPath('chat.png') });
      const menu = page.getByRole('button', { name: '打开菜单预览', exact: true });
      await menu.evaluate((node) => {
        node.dataset.original = 'same-button';
      });
      const rect = (await menu.boundingBox())!;
      expect(rect.width).toBeGreaterThanOrEqual(44);
      expect(rect.height).toBeGreaterThanOrEqual(44);
      await menu.click();
      const close = page.getByRole('button', { name: '关闭菜单预览', exact: true });
      await expect(close).toHaveAttribute('data-original', 'same-button');
      await expect(close.locator('.menu-glyph')).toHaveCSS('opacity', '1');
      await expect(close.locator('.menu-glyph > span').nth(1)).toHaveCSS('opacity', '0');
      await phone.screenshot({ path: info.outputPath('menu.png') });
      await close.press('Shift+Tab');
      await expect(page.getByRole('button', { name: '设置与管理', exact: true })).toBeFocused();
      await close.focus();
      await close.press('Escape');
      await expect(menu).toBeFocused();
      await page.getByRole('button', { name: '打开设置预览' }).click();
      const toggle = page.getByRole('switch', { name: '训练提醒预览' });
      await toggle.click();
      await expect(toggle).toBeDisabled();
      await expect(toggle).toBeChecked();
      await expect(toggle).not.toBeChecked();
      await expect(toggle).toBeEnabled();
      await page.getByRole('checkbox', { name: '模拟保存失败' }).check();
      await toggle.click();
      await expect(toggle).toBeDisabled();
      await expect(page.getByRole('status')).toHaveText('合成保存失败，保留原状态；未自动重试。');
      await expect(toggle).not.toBeChecked();
      await phone.screenshot({ path: info.outputPath('settings.png') });
      await page.getByRole('button', { name: /长期记忆/ }).click();
      const memory = page.getByRole('switch', { name: '自动记忆预览', includeHidden: true });
      await memory.click();
      await expect(memory).not.toBeChecked();
      await expect(page.getByRole('button', { name: '确认开启（仅预览）' })).toBeDisabled();
      await page.getByRole('checkbox', { name: '我已了解本次合成预览范围' }).check();
      await phone.screenshot({ path: info.outputPath('consent.png') });
      await page.getByRole('button', { name: '关闭预览面板' }).press('Escape');
      await expect(memory).toBeFocused();
      await expect(memory).not.toBeChecked();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      expect(await phone.evaluate((node) => node.scrollWidth <= node.clientWidth)).toBe(true);
      expect(
        await page.evaluate(() => (window as unknown as { previewCalls: string[] }).previewCalls),
      ).toEqual([]);
    });
  }
}

test('三档中间帧不同、返回反向、快速导航可打断、减少动效不位移', async ({ page }, info) => {
  await page.goto('/#/design/reference?preview=motion');
  const phone = page.getByTestId('motion-phone');
  for (const [letter, expected] of [
    ['A', 'fade'],
    ['B', 'stack'],
    ['C', 'focus'],
  ]) {
    await page.getByRole('button', { name: new RegExp(`^${letter} ·`) }).click();
    await page.getByRole('button', { name: '打开设置预览' }).click();
    const scene = page.locator('[aria-label="settings预览内容"]');
    const frames = await scene.evaluate((node) => {
      const animation = node.getAnimations()[0];
      if (!animation) throw new Error('缺少实际页面动画');
      animation.pause();
      animation.currentTime = 80;
      return (animation.effect as KeyframeEffect).getKeyframes();
    });
    expect(frames[0].transform).toBe(
      expected === 'fade'
        ? 'none'
        : expected === 'stack'
          ? 'translateX(24px)'
          : 'translateY(8px) scale(0.985)',
    );
    await phone.screenshot({ path: info.outputPath(`${expected}-mid.png`) });
    await page.getByRole('button', { name: '返回上一级预览' }).click();
    if (expected === 'stack') {
      const backFrames = await page
        .locator('[aria-label="chat预览内容"]')
        .evaluate((node) => (node.getAnimations()[0].effect as KeyframeEffect).getKeyframes());
      expect(backFrames[0].transform).toBe('translateX(-24px)');
    }
  }
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.getByRole('button', { name: '打开设置预览' }).click();
  const reducedFrames = await page
    .locator('[aria-label="settings预览内容"]')
    .evaluate((node) => (node.getAnimations()[0].effect as KeyframeEffect).getKeyframes());
  expect(reducedFrames.every((frame) => frame.transform === 'none')).toBe(true);
  const toggle = page.getByRole('switch', { name: '触感反馈预览' });
  await toggle.click();
  const thumb = toggle.locator('..').locator('.choice-visual');
  await expect
    .poll(() => thumb.evaluate((node) => getComputedStyle(node, '::after').animationName))
    .toBe('none');
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('button', { name: '返回上一级预览' }).click();
  expect(
    await page
      .locator('[aria-label="chat预览内容"]')
      .evaluate((node) => node.getAnimations().length),
  ).toBe(0);
});

test('汉堡连续变形与弹性滑块存在可观察的中间帧', async ({ page }, info) => {
  await page.goto('/#/design/reference?preview=motion');
  await page.getByRole('button', { name: '半速', exact: true }).click();
  const menu = page.getByRole('button', { name: '打开菜单预览', exact: true });
  await menu.click();
  const close = page.getByRole('button', { name: '关闭菜单预览', exact: true });
  const middle = await close.locator('.menu-glyph').evaluate((node) => {
    const animations = node.getAnimations({ subtree: true });
    if (!animations.length) throw new Error('汉堡没有实际形变动画');
    animations.forEach((animation) => {
      animation.pause();
      animation.currentTime = 120;
    });
    return getComputedStyle(node.children[1]).opacity;
  });
  expect(Number(middle)).toBeGreaterThan(0);
  expect(Number(middle)).toBeLessThan(1);
  await page.getByTestId('motion-phone').screenshot({ path: info.outputPath('menu-mid.png') });
  await close
    .locator('.menu-glyph')
    .evaluate((node) =>
      node.getAnimations({ subtree: true }).forEach((animation) => animation.finish()),
    );
  await close.press('Escape');
  await page.getByRole('button', { name: '打开设置预览' }).click();
  const toggle = page.getByRole('switch', { name: '触感反馈预览' });
  const before = (await toggle.boundingBox())!;
  await toggle.click();
  await expect
    .poll(() =>
      toggle
        .locator('..')
        .locator('.choice-visual')
        .evaluate((node) => getComputedStyle(node, '::after').animationName),
    )
    .toBe('switch-arrive');
  const scale = await toggle
    .locator('..')
    .locator('.choice-visual')
    .evaluate(async (node) => {
      const animations = node.getAnimations({ subtree: true });
      if (!animations.length) throw new Error('滑块没有实际弹性动画');
      animations.forEach((animation) => {
        animation.pause();
        animation.currentTime = 220;
      });
      await Promise.all(animations.map((animation) => animation.ready));
      await new Promise(requestAnimationFrame);
      return getComputedStyle(node, '::after').scale;
    });
  expect(scale).not.toBe('none');
  expect(scale).not.toBe('1');
  expect((await toggle.boundingBox())!.width).toBe(before.width);
  await page.getByTestId('motion-phone').screenshot({ path: info.outputPath('switch-mid.png') });
});
