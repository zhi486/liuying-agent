import type { RunMetricSample } from '../../bridge/contract';

export type MetricKind = 'pace' | 'heartRate';
export const zoneColors = ['#ff5674', '#ffad43', '#91d51f', '#00c68c', '#4b84ff', '#9cbcff'];
const paceBounds = [246, 265, 289, 311, 352];
const heartBounds = [194, 184, 178, 165, 144];
export const zoneNames = {
  pace: ['高强度间歇', '无氧耐力', '乳酸阈值', '马拉松', '轻松跑', '缓和热身'],
  heartRate: ['无氧动力', '无氧耐力', '乳酸阈值', '有氧动力', '有氧耐力', '缓和热身'],
};
export const zoneRanges = {
  pace: ['<04′06″', '04′06″–04′25″', '04′25″–04′49″', '04′49″–05′11″', '05′11″–05′52″', '≥05′52″'],
  heartRate: ['≥194', '184–193', '178–183', '165–177', '144–164', '<144'],
};

export function zoneIndex(kind: MetricKind, value: number) {
  const index =
    kind === 'pace'
      ? paceBounds.findIndex((b) => value < b)
      : heartBounds.findIndex((b) => value >= b);
  return index < 0 ? 5 : index;
}

export function chartData(samples: RunMetricSample[], kind: MetricKind) {
  const values = samples.map((p) => {
    const value = kind === 'pace' ? p.averagePaceSecPerKm : p.averageHeartRate;
    return value !== null && Number.isFinite(value) && value > 0 ? value : null;
  });
  const coverage = samples.map((p, i) =>
    values[i] === null ? 0 : kind === 'pace' ? p.paceValidSeconds : p.heartRateValidSeconds,
  );
  const zones = zoneNames[kind].map((name, index) => ({
    name,
    range: zoneRanges[kind][index],
    color: zoneColors[index],
    seconds: 0,
    percent: 0,
  }));
  let weighted = 0;
  let validSeconds = 0;
  let minimum: number | null = null;
  let maximum: number | null = null;
  const links: { from: number; to: number }[] = [];
  values.forEach((value, i) => {
    if (value === null || coverage[i] === 0) return;
    zones[zoneIndex(kind, value)].seconds += coverage[i];
    validSeconds += coverage[i];
    weighted += kind === 'pace' ? (coverage[i] * 1000) / value : value * coverage[i];
    minimum = minimum === null ? value : Math.min(minimum, value);
    maximum = maximum === null ? value : Math.max(maximum, value);
    const current = samples[i];
    const previous = samples[i - 1];
    if (
      previous &&
      values[i - 1] !== null &&
      previous.segmentId === current.segmentId &&
      previous.endElapsedSeconds === current.startElapsedSeconds &&
      coverage[i] === current.endElapsedSeconds - current.startElapsedSeconds &&
      coverage[i - 1] === previous.endElapsedSeconds - previous.startElapsedSeconds
    )
      links.push({ from: i - 1, to: i });
  });
  if (validSeconds > 0) {
    zones.forEach((z) => {
      z.percent = Math.floor((z.seconds / validSeconds) * 100);
    });
    const remainders = zones
      .map((z, i) => ({ i, remainder: (z.seconds / validSeconds) * 100 - z.percent }))
      .sort((a, b) => b.remainder - a.remainder);
    const missing = 100 - zones.reduce((sum, z) => sum + z.percent, 0);
    remainders.slice(0, missing).forEach(({ i }) => {
      zones[i].percent++;
    });
  }
  return {
    values,
    coverage,
    zones,
    links,
    validSeconds,
    minimum,
    maximum,
    average:
      validSeconds === 0
        ? null
        : kind === 'pace'
          ? (validSeconds * 1000) / weighted
          : weighted / validSeconds,
  };
}

export function formatMetric(value: number | null, kind: MetricKind) {
  if (value === null) return '未采集';
  if (kind === 'heartRate') return String(Math.round(value));
  const seconds = Math.round(value);
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}′${String(seconds % 60).padStart(2, '0')}″`;
}

export function formatTime(value: number) {
  const seconds = Math.round(value);
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

/** 按阈值切开相邻点，避免一段跨区间连线被涂成单色。 */
export function coloredStops(kind: MetricKind, from: number, to: number) {
  const bounds = kind === 'pace' ? paceBounds : heartBounds;
  const fractions = [
    0,
    ...bounds
      .filter((b) => b > Math.min(from, to) && b < Math.max(from, to))
      .map((b) => (b - from) / (to - from))
      .sort((a, b) => a - b),
    1,
  ];
  return fractions.slice(1).map((end, i) => ({
    start: fractions[i],
    end,
    color: zoneColors[zoneIndex(kind, from + ((to - from) * (fractions[i] + end)) / 2)],
  }));
}
