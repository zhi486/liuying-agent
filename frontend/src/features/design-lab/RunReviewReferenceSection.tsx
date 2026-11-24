import { ArrowLeft, Pause, Play, Stop, ArrowCounterClockwise } from '@phosphor-icons/react';
import { useEffect, useRef, useState } from 'react';
import { MetricPanel } from '../history/RunMetricCharts';
import type { RunMetricSample } from '../../bridge/contract';
import shared from './ConsentPageReferenceSection.module.css';
import styles from './RunReviewReferenceSection.module.css';

const groups = {
  detail: {
    title: '跑步详情',
    variants: [
      ['A', '成绩总览', '距离优先，六项数据紧随其后，分段直接浏览。'],
      ['B', '数据手册', '紧凑数据清单与分段切换，便于查数。'],
      ['C', '分段优先', '先看每公里节奏，再展开整体成绩。'],
    ],
  },
  charts: {
    title: '数据图表',
    variants: [
      ['A', '双图纵览', '配速和心率连续展示，直接上下比较。'],
      ['B', '单图聚焦', '配速与心率切换，给单张曲线更多空间。'],
      ['C', '窗口审阅', '按五秒窗口检查数据，曲线与缺失提示一起显示。'],
    ],
  },
  controls: {
    title: '暂停与结束',
    variants: [
      ['A', '展开数据', '四项数据两列展开，底部左结束、右继续。'],
      ['B', '紧凑数据', '三项关键数据横向排列，控制区留出更多呼吸空间。'],
      ['C', '距离聚焦', '距离居中，配速与时长并列，大圆控制保持不变。'],
    ],
  },
} as const;
type Kind = keyof typeof groups;
const splits = [
  ['1', '6:12', '132'],
  ['2', '6:06', '136'],
  ['3', '6:00', '139'],
  ['4', '5:58', '142'],
  ['5', '5:54', '144'],
];
const metrics = [
  ['运动用时', '30:18'],
  ['平均配速', '6:02'],
  ['平均心率', '139 次/分'],
  ['平均步频', '169 步/分'],
  ['最快分段', '5:54'],
  ['已保存分段', '5 段'],
];
const samples: RunMetricSample[] = Array.from({ length: 48 }, (_, index) => ({
  startElapsedSeconds: index * 5,
  endElapsedSeconds: index * 5 + 5,
  segmentId: 0,
  averagePaceSecPerKm: index === 20 ? null : Math.round(362 + Math.sin(index / 4) * 22),
  averageHeartRate: index === 20 ? null : Math.round(138 + Math.sin(index / 7) * 9),
  paceValidSeconds: index === 20 ? 0 : 5,
  heartRateValidSeconds: index === 20 ? 0 : 5,
}));
const record = { id: 'design-review-only', durationSeconds: 240, averagePaceSecPerKm: 362 };
function Metrics({ list = false }: { list?: boolean }) {
  return (
    <dl className={list ? styles.metricList : styles.metrics}>
      {metrics.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
function Splits() {
  return (
    <div className={styles.splits}>
      <div>
        <span>公里</span>
        <span>配速</span>
        <span>心率</span>
      </div>
      {splits.map(([km, pace, heart]) => (
        <div key={km}>
          <strong>{km}</strong>
          <span>{pace}</span>
          <span>{heart}</span>
        </div>
      ))}
    </div>
  );
}
function DetailPreview({ variant }: { variant: string }) {
  const [tab, setTab] = useState('metrics');
  const [expanded, setExpanded] = useState(false);
  return (
    <>
      <p>2026年10月28日 06:12 · 户外跑步</p>
      {variant !== 'C' && (
        <div className={styles.distance}>
          <strong>5.02</strong>
          <span>公里</span>
        </div>
      )}
      {variant === 'B' ? (
        <>
          <div className={styles.tabs} role="group" aria-label="详情视图">
            <button
              type="button"
              aria-pressed={tab === 'metrics'}
              onClick={() => setTab('metrics')}
            >
              运动数据
            </button>
            <button type="button" aria-pressed={tab === 'splits'} onClick={() => setTab('splits')}>
              每公里分段
            </button>
          </div>
          {tab === 'metrics' ? <Metrics list /> : <Splits />}
        </>
      ) : variant === 'C' ? (
        <>
          <h4>每公里节奏</h4>
          <Splits />
          <button
            type="button"
            className={styles.wide}
            aria-expanded={expanded}
            onClick={() => setExpanded(!expanded)}
          >
            整体成绩 · 5.02 公里
          </button>
          {expanded && <Metrics />}
        </>
      ) : (
        <>
          <Metrics />
          <h4>每公里分段</h4>
          <Splits />
        </>
      )}
      <p>以上为本地数据摘要，不提供训练评分或医学判断。</p>
    </>
  );
}
function ChartPreview({ variant }: { variant: string }) {
  const [kind, setKind] = useState<'pace' | 'heartRate'>('pace');
  const [empty, setEmpty] = useState(false);
  return (
    <>
      <p>合成 4 分钟 · 五秒窗口 · 含一处采集缺失</p>
      {variant !== 'A' && (
        <div className={styles.tabs} role="group" aria-label="曲线指标">
          <button type="button" aria-pressed={kind === 'pace'} onClick={() => setKind('pace')}>
            配速
          </button>
          <button
            type="button"
            aria-pressed={kind === 'heartRate'}
            onClick={() => setKind('heartRate')}
          >
            心率
          </button>
        </div>
      )}
      {empty ? (
        <p role="status">本次记录未采集五秒序列，无法还原曲线。</p>
      ) : (
        (variant === 'A' ? (['pace', 'heartRate'] as const) : [kind]).map((metric) => (
          <section className={styles.chart} key={metric}>
            <h4>{metric === 'pace' ? '跑步配速' : '跑步心率'}</h4>
            <MetricPanel
              samples={samples}
              kind={metric}
              record={record}
              expanded={variant === 'C'}
            />
          </section>
        ))
      )}
      <p>缺失区间不连线，不推测缺失数值；区间分类不代表个性化训练建议。</p>
      <button type="button" aria-pressed={empty} onClick={() => setEmpty(!empty)}>
        切换无曲线状态
      </button>
    </>
  );
}
function ControlPreview({ variant }: { variant: string }) {
  const [phase, setPhase] = useState<'running' | 'paused' | 'finished'>('paused');
  const [confirm, setConfirm] = useState(false);
  const [notice, setNotice] = useState('');
  const [holding, setHolding] = useState(false);
  const holdTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (holdTimer.current !== null) clearTimeout(holdTimer.current);
    },
    [],
  );
  const cancelHold = () => {
    if (holdTimer.current !== null) clearTimeout(holdTimer.current);
    holdTimer.current = null;
    setHolding(false);
  };
  const startHold = () => {
    cancelHold();
    setHolding(true);
    holdTimer.current = setTimeout(() => {
      setHolding(false);
      setConfirm(true);
      holdTimer.current = null;
    }, 1200);
  };
  const confirmation = (
    <section className={styles.confirm} aria-label="结束确认">
      <h4>结束本次跑步？</h4>
      <p>本次为演示，不保存记录或同步数据。</p>
      <div className={styles.confirmActions}>
        <button type="button" onClick={() => setConfirm(false)}>
          继续休息
        </button>
        <button
          type="button"
          className={styles.finish}
          onClick={() => {
            setConfirm(false);
            setPhase('finished');
            setNotice('演示已结束，未保存真实记录或同步数据。');
          }}
        >
          确认结束
        </button>
      </div>
    </section>
  );
  return (
    <div className={styles.controlScene} data-layout={variant}>
      <p className={styles.runState}>
        {phase === 'paused' ? '已暂停' : phase === 'finished' ? '演示已结束' : '跑步中'}
      </p>
      {
        <>
          <div className={styles.distance}>
            <strong>5.02</strong>
            <span>公里</span>
          </div>
          <dl className={styles.controlMetrics}>
            <div>
              <dt>运动用时</dt>
              <dd>30:18</dd>
            </div>
            <div>
              <dt>平均配速</dt>
              <dd>6:02</dd>
            </div>
            {variant !== 'C' && (
              <div>
                <dt>实时心率</dt>
                <dd>
                  139 <small>次/分</small>
                </dd>
              </div>
            )}
            {variant === 'A' && (
              <div>
                <dt>步频</dt>
                <dd>
                  169 <small>步/分</small>
                </dd>
              </div>
            )}
          </dl>
        </>
      }
      {phase !== 'finished' ? (
        <div className={styles.runActions}>
          <div className={styles.actionItem}>
            <span className={styles.holdLabel}>{holding ? '松开取消' : '长按结束'}</span>
            <button
              type="button"
              aria-label="结束"
              className={styles.stopButton}
              data-holding={holding}
              disabled={confirm}
              onPointerDown={(event) => {
                if (event.button === 0) startHold();
              }}
              onPointerUp={cancelHold}
              onPointerLeave={cancelHold}
              onPointerCancel={cancelHold}
              onBlur={cancelHold}
              onClick={(event) => {
                if (event.detail === 0) {
                  cancelHold();
                  setConfirm(true);
                }
              }}
            >
              <Stop size={32} weight="fill" aria-hidden="true" />
            </button>
            <span>结束</span>
          </div>
          <div className={styles.actionItem}>
            <button
              type="button"
              aria-label={phase === 'paused' ? '继续跑' : '暂停'}
              disabled={confirm}
              onClick={() => setPhase(phase === 'paused' ? 'running' : 'paused')}
            >
              {phase === 'paused' ? (
                <Play size={24} weight="fill" />
              ) : (
                <Pause size={24} weight="fill" />
              )}
            </button>
            <span>{phase === 'paused' ? '继续跑' : '暂停'}</span>
          </div>
        </div>
      ) : null}
      {confirm && confirmation}
      <p role="status">{notice}</p>
      <button
        type="button"
        className={styles.reset}
        onClick={() => {
          setPhase('paused');
          setConfirm(false);
          setNotice('');
          cancelHold();
        }}
      >
        <ArrowCounterClockwise size={16} aria-hidden="true" />
        重置演示
      </button>
    </div>
  );
}
export function RunReviewReferenceSection({ kind, width }: { kind: Kind; width: '320' | '393' }) {
  const [selected, setSelected] = useState<string | null>(kind === 'charts' ? 'C' : 'A');
  const [notice, setNotice] = useState('');
  return (
    <section className={shared.section}>
      <h2>{groups[kind].title}的三种方案</h2>
      {kind === 'controls' && (
        <p>
          参考 Keep v8.2 户外骑行截图{' '}
          <a href="https://uinotes.com/pin/088398049095622951" target="_blank" rel="noreferrer">
            0634
          </a>
          、
          <a href="https://uinotes.com/pin/528794017236054974" target="_blank" rel="noreferrer">
            0635
          </a>{' '}
          的圆形控制和长按进度；不是跑步实测。演示长按为 1.2 秒，键盘激活也可进入确认。
        </p>
      )}
      {kind === 'detail' && <p>A 已选定并接入正式页；保留分段详情和 C 窗口图表。</p>}
      <p>
        仅合成数据与本地交互，不读取真实记录、不控制跑步、不保存或上传。图表沿用现有绘制与统计组件。
      </p>
      <div className={shared.variants}>
        {groups[kind].variants.map(([id, name, description]) => (
          <article key={id} aria-label={id + ' ' + name}>
            <div className={shared.variantHeading}>
              <h3>
                {id} · {name}
              </h3>
              <p>{description}</p>
            </div>
            <div className={`${shared.phone} ${styles.phone}`} data-width={width}>
              <header>
                <button
                  type="button"
                  className={shared.back}
                  onClick={() => setNotice('此处为预览，正式页保留原有返回层级。')}
                >
                  <ArrowLeft size={16} aria-hidden="true" />
                  {kind === 'controls' ? '跑步' : '记录'}
                </button>
                <span className={styles.demoLabel}>演示数据</span>
              </header>
              <h3>{groups[kind].title}</h3>
              {kind === 'detail' ? (
                <DetailPreview variant={id} />
              ) : kind === 'charts' ? (
                <ChartPreview variant={id} />
              ) : (
                <ControlPreview variant={id} />
              )}
            </div>
            <button
              type="button"
              className={shared.choose}
              aria-pressed={selected === id}
              onClick={() => setSelected(id)}
            >
              {id === (kind === 'charts' ? 'C' : 'A')
                ? id + ' 已接入正式页面'
                : selected === id
                  ? kind === 'charts'
                    ? '对比候选，正式页仍为 C'
                    : '当前候选，尚未接入'
                  : '选择 ' + id + ' 方案'}
            </button>
          </article>
        ))}
      </div>
      <p role="status">{notice}</p>
    </section>
  );
}
