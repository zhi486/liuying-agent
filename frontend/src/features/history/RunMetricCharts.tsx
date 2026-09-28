import { useEffect, useMemo, useRef, useState } from 'react';
import type { RunMetricSample } from '../../bridge/contract';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import { historySeriesResultSchema } from '../../bridge/schemas';
import {
  chartData,
  coloredStops,
  formatMetric,
  formatTime,
  zoneColors,
  zoneIndex,
  type MetricKind,
} from './runMetricChart';
import styles from './RunMetricCharts.module.css';

type RecordSummary = { id: string; durationSeconds: number; averagePaceSecPerKm: number | null };
const titles = { pace: '跑步配速区间', heartRate: '跑步心率区间' };

export function RunMetricCharts({ record }: { record: RecordSummary }) {
  const [samples, setSamples] = useState<RunMetricSample[] | null>(null);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [expanded, setExpanded] = useState<MetricKind | null>(null);
  useEffect(() => {
    let active = true;
    setSamples(null);
    setError('');
    void (async () => {
      try {
        const all: RunMetricSample[] = [];
        let total: number | null = null;
        do {
          const result = await sendNativeCommand('history.series', {
            sessionId: record.id,
            offset: all.length,
            limit: 300,
          });
          if (!active) return;
          if (result.status === 'rejected')
            throw new Error(
              result.error.code === 'INVALID_MESSAGE'
                ? '当前版本不支持五秒曲线，请更新 App。'
                : '读取本地曲线失败，请重试。',
            );
          const page = historySeriesResultSchema.parse(result.data);
          if (
            page.sessionId !== record.id ||
            page.offset !== all.length ||
            (total !== null && page.totalItems !== total) ||
            (page.samples.length === 0 && all.length < page.totalItems)
          )
            throw new Error('曲线分页数据不一致，请重试。');
          total = page.totalItems;
          for (const point of page.samples) {
            const previous = all.at(-1);
            if (
              point.endElapsedSeconds > record.durationSeconds ||
              (previous &&
                (point.startElapsedSeconds < previous.endElapsedSeconds ||
                  point.segmentId < previous.segmentId))
            )
              throw new Error('曲线时间顺序无效。');
            all.push(point);
          }
        } while (all.length < total);
        setSamples(all);
      } catch {
        if (active) setError('读取五秒曲线失败或数据不完整，请重试。');
      }
    })();
    return () => {
      active = false;
    };
  }, [record.id, record.durationSeconds, attempt]);

  if (error)
    return (
      <section className={styles.notice} role="alert">
        <p>{error}</p>
        <button className="secondary-button" onClick={() => setAttempt((v) => v + 1)}>
          重读曲线
        </button>
      </section>
    );
  if (samples === null) return <p className={styles.notice}>正在读取五秒曲线…</p>;
  if (samples.length === 0)
    return <p className={styles.notice}>本次记录未采集五秒序列，无法还原曲线。</p>;
  return (
    <>
      {(['pace', 'heartRate'] as const).map((kind) => (
        <section className={styles.card} key={kind} aria-label={titles[kind]}>
          <header className={styles.cardHeader}>
            <h2>{titles[kind]}</h2>
            <button onClick={() => setExpanded(kind)} aria-label={`查看${titles[kind]}详情`}>
              查看详情 ›
            </button>
          </header>
          <MetricPanel samples={samples} kind={kind} record={record} />
        </section>
      ))}
      {expanded && (
        <MetricDialog
          onClose={() => setExpanded(null)}
          kind={expanded}
          samples={samples}
          record={record}
        />
      )}
    </>
  );
}

function MetricDialog({
  onClose,
  kind,
  samples,
  record,
}: {
  onClose: () => void;
  kind: MetricKind;
  samples: RunMetricSample[];
  record: RecordSummary;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    ref.current?.showModal();
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={styles.dialog}
      aria-label={titles[kind]}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <button className={styles.close} aria-label="关闭详情" onClick={onClose}>
        ×
      </button>
      <h2 className={styles.title}>{titles[kind]}</h2>
      <p className={styles.subtitle}>点击图表可查看具体数值</p>
      <MetricPanel samples={samples} kind={kind} record={record} expanded />
    </dialog>
  );
}

function MetricPanel({
  samples,
  kind,
  record,
  expanded = false,
}: {
  samples: RunMetricSample[];
  kind: MetricKind;
  record: RecordSummary;
  expanded?: boolean;
}) {
  const data = useMemo(() => chartData(samples, kind), [samples, kind]);
  const [selected, setSelected] = useState<number | null>(null);
  const minimum = data.minimum ?? (kind === 'heartRate' ? 80 : 300);
  const maximum = data.maximum ?? (kind === 'heartRate' ? 200 : 600);
  const padding = Math.max(kind === 'heartRate' ? 10 : 30, (maximum - minimum) * 0.1);
  const low = Math.max(0, Math.floor((minimum - padding) / 10) * 10);
  const high = Math.ceil((maximum + padding) / 10) * 10;
  const x = (seconds: number) => 48 + (340 * seconds) / Math.max(1, record.durationSeconds);
  const y = (value: number) =>
    14 + 186 * (kind === 'pace' ? (value - low) / (high - low) : (high - value) / (high - low));
  const midpoint = (index: number) =>
    (samples[index].startElapsedSeconds + samples[index].endElapsedSeconds) / 2;
  const maxZoneSeconds = Math.max(1, ...data.zones.map((zone) => zone.seconds));
  const selectedPoint = selected === null ? null : samples[selected];
  return (
    <>
      <div className={styles.stats}>
        <span>
          平均{' '}
          <strong>
            {formatMetric(kind === 'pace' ? record.averagePaceSecPerKm : data.average, kind)}
          </strong>
        </span>
        <span>
          {kind === 'pace' ? '最快' : '最大'}{' '}
          <strong>{formatMetric(kind === 'pace' ? data.minimum : data.maximum, kind)}</strong>
        </span>
        {kind === 'heartRate' && (
          <span>
            最小 <strong>{formatMetric(data.minimum, kind)}</strong>
          </span>
        )}
      </div>
      <svg
        className={styles.chart}
        viewBox="0 0 400 236"
        role="img"
        aria-label={`${titles[kind]}五秒平均曲线`}
        onClick={(event) => {
          if (!expanded) return;
          const rect = event.currentTarget.getBoundingClientRect();
          const second =
            ((((event.clientX - rect.left) / rect.width) * 400 - 48) / 340) *
            record.durationSeconds;
          let nearest = 0;
          samples.forEach((_, i) => {
            if (Math.abs(midpoint(i) - second) < Math.abs(midpoint(nearest) - second)) nearest = i;
          });
          setSelected(nearest);
        }}
      >
        {[0, 1, 2, 3].map((index) => {
          const value =
            kind === 'pace' ? low + ((high - low) * index) / 3 : high - ((high - low) * index) / 3;
          return (
            <g key={index}>
              <line
                x1="48"
                x2="388"
                y1={14 + index * 62}
                y2={14 + index * 62}
                className={styles.grid}
              />
              <text x="40" y={19 + index * 62} textAnchor="end">
                {formatMetric(value, kind)}
              </text>
            </g>
          );
        })}
        {[0, 1, 2, 3].map((i) => (
          <text
            key={i}
            x={x((record.durationSeconds * i) / 3)}
            y="226"
            textAnchor={i === 0 ? 'start' : i === 3 ? 'end' : 'middle'}
          >
            {formatTime((record.durationSeconds * i) / 3)}
          </text>
        ))}
        {data.links.flatMap((link) =>
          coloredStops(kind, data.values[link.from]!, data.values[link.to]!).map((stop, i) => {
            const x1 = x(midpoint(link.from));
            const x2 = x(midpoint(link.to));
            const y1 = y(data.values[link.from]!);
            const y2 = y(data.values[link.to]!);
            return (
              <line
                key={`${link.from}-${i}`}
                x1={x1 + (x2 - x1) * stop.start}
                x2={x1 + (x2 - x1) * stop.end}
                y1={y1 + (y2 - y1) * stop.start}
                y2={y1 + (y2 - y1) * stop.end}
                stroke={stop.color}
                strokeWidth="3.3"
                strokeLinecap="round"
              />
            );
          }),
        )}
        {data.values.map((value, i) =>
          value === null ? null : (
            <circle
              key={i}
              cx={x(midpoint(i))}
              cy={y(value)}
              r="1.65"
              fill={zoneColors[zoneIndex(kind, value)]}
            />
          ),
        )}
        {selected !== null && (
          <line
            x1={x(midpoint(selected))}
            x2={x(midpoint(selected))}
            y1="10"
            y2="204"
            className={styles.cursor}
          />
        )}
      </svg>
      {expanded && (
        <>
          <input
            className={styles.selector}
            aria-label="选择统计窗口"
            type="range"
            min={0}
            max={samples.length - 1}
            value={selected ?? 0}
            onChange={(event) => setSelected(Number(event.target.value))}
          />
          {selectedPoint && (
            <p role="status" className={styles.selection}>
              {formatTime(selectedPoint.startElapsedSeconds)}–
              {formatTime(selectedPoint.endElapsedSeconds)} ·{' '}
              {formatMetric(data.values[selected!], kind)}
              {data.values[selected!] !== null && (kind === 'heartRate' ? ' 次/分' : ' /公里')} ·
              有效 {data.coverage[selected!]} 秒
            </p>
          )}
          <h3 className={styles.distributionTitle}>
            {kind === 'pace' ? '配速' : '心率'}区间用时分布
          </h3>
          <div className={`${styles.zones} ${kind === 'heartRate' ? styles.heartZones : ''}`}>
            {data.zones.map((zone) => (
              <div key={zone.name} className={styles.zone}>
                <span style={{ color: zone.color }}>{zone.name}</span>
                <span className={styles.range}>{zone.range}</span>
                <span className={styles.barTime}>
                  <span
                    className={styles.bar}
                    style={{
                      background: zone.color,
                      width: `calc((100% - 3.6em) * ${zone.seconds / maxZoneSeconds})`,
                    }}
                  />
                  <span>{formatTime(zone.seconds)}</span>
                </span>
                <span className={styles.percent}>{zone.percent}%</span>
              </div>
            ))}
          </div>
          <details className={styles.explanation}>
            <summary>统计说明</summary>
            <p>
              每五秒统计一次，尾段可能不足五秒；缺失或暂停不连线。心率为有效样本均值，最大/最小及最快配速均为窗口均值，不是瞬时极值。配速区间左闭右开；心率按未取整均值划分。区间仅用于回顾，不改变安全警报。
            </p>
          </details>
        </>
      )}
      {!expanded && (
        <div className={styles.zoneStrip} aria-hidden="true">
          {data.zones.map((zone) => (
            <span key={zone.name} style={{ background: zone.color, flex: zone.seconds }} />
          ))}
        </div>
      )}
      <p className={styles.coverage}>
        有效统计 {formatTime(data.validSeconds)} · 未计入{' '}
        {formatTime(Math.max(0, record.durationSeconds - data.validSeconds))}
        {expanded ? '（缺失或无法计算）' : ' · 五秒均值'}
      </p>
    </>
  );
}
