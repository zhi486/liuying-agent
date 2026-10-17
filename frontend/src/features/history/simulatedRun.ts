import type { HistoryDetailResult, RunMetricSample } from '../../bridge/contract';

// 独立演示数据，禁止写入真实记录或提交复盘。每段速度的周期波动积分恰好为一公里。
export const simulatedSamples: RunMetricSample[] = [];
const splits: HistoryDetailResult['splits'] = [];
let elapsed = 0;
const stages = [
  [420, 125],
  [330, 155],
  [300, 171],
  [270, 181],
  [240, 197],
  [390, 140],
];
stages.forEach(([pace, heartRate], stage) => {
  for (let i = 0; i < pace / 5; i++) {
    simulatedSamples.push({
      startElapsedSeconds: elapsed,
      endElapsedSeconds: elapsed + 5,
      segmentId: stage < 3 ? 0 : 1,
      averagePaceSecPerKm: pace / (1 + 0.1 * Math.sin((2 * Math.PI * i) / (pace / 5))),
      averageHeartRate: heartRate + 8 * Math.sin((4 * Math.PI * i) / (pace / 5)),
      paceValidSeconds: 5,
      heartRateValidSeconds: 5,
    });
    elapsed += 5;
  }
  splits.push({
    index: stage + 1,
    endDistanceMeters: (stage + 1) * 1000,
    durationSeconds: pace,
    averagePaceSecPerKm: pace,
    averageHeartRate: heartRate,
  });
  if (stage === 2) {
    for (let i = 0; i < 2; i++) {
      simulatedSamples.push({
        startElapsedSeconds: elapsed,
        endElapsedSeconds: elapsed + 5,
        segmentId: 0,
        averagePaceSecPerKm: null,
        averageHeartRate: null,
        paceValidSeconds: 0,
        heartRateValidSeconds: 0,
      });
      elapsed += 5;
    }
  }
});
const validSeconds = elapsed - 10;
export const simulatedRun: HistoryDetailResult = {
  commandId: '00000000-0000-4000-8000-000000000001',
  record: {
    id: 'simulated-run-preview',
    startedAt: '2026-09-30T10:00:00Z',
    endedAt: new Date(Date.parse('2026-09-30T10:00:00Z') + elapsed * 1000).toISOString(),
    distanceMeters: 6000,
    durationSeconds: elapsed,
    averagePaceSecPerKm: elapsed / 6,
    averageHeartRate:
      simulatedSamples.reduce(
        (sum, sample) => sum + (sample.averageHeartRate ?? 0) * sample.heartRateValidSeconds,
        0,
      ) / validSeconds,
    averageCadence: 166,
  },
  splits,
};
