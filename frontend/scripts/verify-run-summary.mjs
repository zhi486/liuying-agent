// 使用隔离的测试 Bridge 检查总结页；不连接手机、不调用云端。
/* global window, document */
import { readFile } from 'node:fs/promises';
import { chromium } from '@playwright/test';

const ready = JSON.parse(
  await readFile(new URL('../../contracts/v1/fixtures/valid/ready.json', import.meta.url), 'utf8'),
);
const browser = await chromium.launch({ channel: 'msedge', headless: true });
try {
  for (const width of [320, 360, 390]) {
    for (const theme of ['morning', 'night']) {
      const page = await browser.newPage({ viewport: { width, height: 800 } });
      await page.addInitScript(
        ({ ready, theme }) => {
          const record = {
            id: 'summary-test',
            startedAt: '2026-09-14T10:00:00Z',
            endedAt: '2026-09-14T10:28:47Z',
            distanceMeters: 4020,
            durationSeconds: 1727,
            averagePaceSecPerKm: 430,
            averageHeartRate: null,
            averageCadence: 160,
          };
          const splits = [388, 408, 481, 421].map((pace, index) => ({
            index: index + 1,
            endDistanceMeters: (index + 1) * 1000,
            durationSeconds: pace,
            averagePaceSecPerKm: pace,
            averageHeartRate: index === 0 ? null : 140 + index,
          }));
          const samples = Array.from(
            { length: Math.ceil(record.durationSeconds / 5) },
            (_, index) => {
              const start = index * 5;
              const end = Math.min(record.durationSeconds, start + 5);
              const gap = index >= 170 && index <= 174;
              const walking = index > 180 && index < 265;
              return {
                startElapsedSeconds: start,
                endElapsedSeconds: end,
                segmentId: index < 180 ? 0 : 1,
                averagePaceSecPerKm: gap
                  ? null
                  : walking
                    ? 750 + 170 * Math.sin(index * 0.6)
                    : 345 + 30 * Math.sin(index * 0.45),
                averageHeartRate: gap
                  ? null
                  : walking
                    ? 132 + 5 * Math.sin(index * 0.2)
                    : Math.min(200, 110 + index * 2) - 15 * (1 + Math.sin(index * 0.1)),
                paceValidSeconds: gap ? 0 : end - start,
                heartRateValidSeconds: gap ? 0 : end - start,
              };
            },
          );
          window.LiuyingNative = {
            onmessage: null,
            postMessage(raw) {
              const request = JSON.parse(raw);
              const response =
                request.type === 'hello'
                  ? {
                      ...ready,
                      id: crypto.randomUUID(),
                      payload: {
                        ...ready.payload,
                        helloId: request.id,
                        snapshot: {
                          ...ready.payload.snapshot,
                          theme,
                          session: { ...ready.payload.snapshot.session, state: 'idle' },
                        },
                      },
                    }
                  : {
                      contractVersion: 1,
                      type: 'result',
                      id: request.id,
                      sentAt: new Date().toISOString(),
                      payload: {
                        status: 'accepted',
                        data:
                          request.payload.name === 'history.series'
                            ? {
                                commandId: request.id,
                                sessionId: record.id,
                                offset: request.payload.body.offset,
                                totalItems: samples.length,
                                samples: samples.slice(
                                  request.payload.body.offset,
                                  request.payload.body.offset + request.payload.body.limit,
                                ),
                              }
                            : { commandId: request.id, record, splits },
                      },
                    };
              queueMicrotask(() => this.onmessage?.({ data: JSON.stringify(response) }));
            },
          };
        },
        { ready, theme },
      );
      await page.goto('http://127.0.0.1:4173/#/history/summary-test');
      await page.getByRole('table', { name: '每公里分段' }).waitFor();
      await page.evaluate(() => {
        const label = document.createElement('p');
        label.textContent = '视觉测试数据 · 非真实跑步记录';
        label.style.cssText = 'padding:8px 24px;color:#b65b00;font-size:12px';
        document.querySelector('main').prepend(label);
      });
      const actualWidth = await page.evaluate(() => document.documentElement.scrollWidth);
      if (actualWidth > width) throw new Error(`总结页横向溢出：${width} / ${theme}`);
      if (width === 360) {
        await page.screenshot({
          path: `../docs/handoffs/ui-preview-2026-09-14/summary-${theme}-360.png`,
          fullPage: true,
        });
      }
      console.log(`总结页窄屏通过：${width}px / ${theme}`);
      for (const [kind, title] of [
        ['pace', '跑步配速区间'],
        ['heartRate', '跑步心率区间'],
      ]) {
        const trigger = page.getByRole('button', { name: `查看${title}详情` });
        await trigger.click();
        const dialog = page.getByRole('dialog', { name: title });
        await dialog.waitFor();
        const overflow = await dialog.evaluate((node) => node.scrollWidth > node.clientWidth);
        if (overflow) throw new Error(`详情横向溢出：${kind} / ${width} / ${theme}`);
        const bars = await dialog.locator('h3 + div > div').evaluateAll((rows) =>
          rows
            .map((row) => {
              const area = row.children[2];
              const time = area.lastElementChild.textContent.split(':').map(Number);
              return {
                seconds: time[0] * 60 + time[1],
                width: area.firstElementChild.getBoundingClientRect().width,
              };
            })
            .sort((a, b) => b.seconds - a.seconds),
        );
        if (
          bars[1].seconds / bars[0].seconds > 0.5 &&
          Math.abs(bars[1].width / bars[0].width - bars[1].seconds / bars[0].seconds) > 0.06
        )
          throw new Error(`区间条长度不符合用时比例：${kind} / ${width}`);
        await dialog.getByRole('slider').focus();
        await page.keyboard.press('End');
        await dialog.getByRole('status').waitFor();
        await dialog.getByRole('img').click({ position: { x: width / 2, y: 60 } });
        if (width === 360) {
          await dialog.evaluate((node) => {
            const label = document.createElement('p');
            label.textContent = '视觉测试数据 · 非真实跑步记录';
            label.style.cssText = 'font-size:10px;color:#b65b00;margin:0 0 8px';
            node.querySelector('h2').before(label);
          });
          await page.screenshot({
            path: `../docs/handoffs/ui-preview-2026-09-14/zones-${kind}-${theme}-360.png`,
          });
        }
        await page.keyboard.press('Escape');
        await dialog.waitFor({ state: 'detached' });
        if (!(await trigger.evaluate((node) => node === document.activeElement)))
          throw new Error('关闭详情后未恢复焦点');
        console.log(`区间详情通过：${kind} / ${width}px / ${theme}`);
      }
      await page.close();
    }
  }
} finally {
  await browser.close();
}
