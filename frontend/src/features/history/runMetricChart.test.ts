import { expect, it } from 'vitest';
import { chartData, coloredStops, zoneIndex } from './runMetricChart';
import type { RunMetricSample } from '../../bridge/contract';

const point = (start: number, hr: number | null, pace: number | null = 360): RunMetricSample => ({
  startElapsedSeconds: start,
  endElapsedSeconds: start + 5,
  segmentId: 0,
  averagePaceSecPerKm: pace,
  averageHeartRate: hr,
  paceValidSeconds: pace === null ? 0 : 5,
  heartRateValidSeconds: hr === null ? 0 : 5,
});

it('六区间边界准确归属且不改变五区间安全规则', () => {
  expect([245, 246, 265, 289, 311, 352].map((v) => zoneIndex('pace', v))).toEqual([
    0, 1, 2, 3, 4, 5,
  ]);
  expect([194, 184, 178, 165, 144, 143.9].map((v) => zoneIndex('heartRate', v))).toEqual([
    0, 1, 2, 3, 4, 5,
  ]);
});

it('区间按有效秒数统计而不是按点数，缺失不归零', () => {
  const data = chartData(
    [point(0, 150), { ...point(5, 180), heartRateValidSeconds: 2 }, point(10, null)],
    'heartRate',
  );
  expect(data.zones.map((z) => z.seconds)).toEqual([0, 0, 2, 0, 5, 0]);
  expect(data.zones.map((z) => z.percent)).toEqual([0, 0, 29, 0, 71, 0]);
  expect(data.validSeconds).toBe(7);
  expect(data.average).toBeCloseTo(158.5714);
});

it('配速汇总按时间除以距离而非均值，并保留最快窗口', () => {
  const data = chartData([point(0, 140, 300), point(5, 140, 600)], 'pace');
  expect(data.average).toBeCloseTo(400);
  expect(data.minimum).toBe(300);
});

it('缺失、部分覆盖、暂停段号和时间断档阻止连线', () => {
  const samples = [
    point(0, 140),
    point(5, 150),
    point(10, null),
    point(15, 155),
    { ...point(20, 160), heartRateValidSeconds: 2 },
    point(25, 170),
    { ...point(30, 175), segmentId: 1 },
    { ...point(40, 180), segmentId: 1 },
  ];
  const data = chartData(samples, 'heartRate');
  expect(data.links.map((l) => [l.from, l.to])).toEqual([[0, 1]]);
  expect(data.values[2]).toBeNull();
});

it('空数据和无效数值不产生虚假区间或无限坐标', () => {
  expect(chartData([], 'pace').average).toBeNull();
  expect(chartData([point(0, null, 0), point(5, null, Infinity)], 'pace').validSeconds).toBe(0);
});

it('跨区间连线在真实阈值处分色且反向保持一致', () => {
  const stops = coloredStops('heartRate', 140, 180);
  expect(stops.map((stop) => stop.end)).toEqual([0.1, 0.625, 0.95, 1]);
  expect(stops.map((stop) => stop.color)).toEqual(['#9cbcff', '#4b84ff', '#00c68c', '#91d51f']);
  expect(coloredStops('heartRate', 180, 140).map((stop) => stop.color)).toEqual([
    '#91d51f',
    '#00c68c',
    '#4b84ff',
    '#9cbcff',
  ]);
});
