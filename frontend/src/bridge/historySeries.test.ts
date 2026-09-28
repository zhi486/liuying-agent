import { expect, it } from 'vitest';
import { historySeriesResultSchema } from './schemas';

const sample = {
  startElapsedSeconds: 0,
  endElapsedSeconds: 5,
  segmentId: 0,
  averagePaceSecPerKm: null,
  averageHeartRate: 140.5,
  paceValidSeconds: 0,
  heartRateValidSeconds: 4,
};
const page = {
  commandId: '00000000-0000-4000-8000-000000000012',
  sessionId: 'run-1',
  offset: 0,
  totalItems: 1,
  samples: [sample],
};

it('接受空值和真实覆盖量的分页序列', () => {
  expect(historySeriesResultSchema.parse(page).samples[0]).toEqual(sample);
});
it('拒绝重叠窗口、虚假覆盖、越界页和额外字段', () => {
  for (const invalid of [
    { ...page, samples: [{ ...sample, heartRateValidSeconds: 6 }] },
    { ...page, samples: [{ ...sample, endElapsedSeconds: 0 }] },
    { ...page, samples: [{ ...sample, averagePaceSecPerKm: 350, paceValidSeconds: 3 }] },
    { ...page, samples: [{ ...sample, extra: 1 }] },
    { ...page, samples: [sample, sample], totalItems: 2 },
    { ...page, offset: 1 },
  ])
    expect(historySeriesResultSchema.safeParse(invalid).success).toBe(false);
});
