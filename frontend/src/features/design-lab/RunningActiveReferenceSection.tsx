import {
  Bluetooth,
  Check,
  Heartbeat,
  MapPin,
  Pause,
  Play,
  Stop,
  Warning,
} from '@phosphor-icons/react';
import { useEffect, useRef, useState, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type ActiveRunVariantId = 'cockpit' | 'odometer' | 'tempo';
type ActiveRunPreviewState = 'default' | 'focus' | 'long' | 'saving';

interface ActiveRunVariantDefinition {
  id: ActiveRunVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  hierarchy: string;
  theme: string;
  bestFor: string;
  stateLogic: string;
  cssFocus: string;
}

interface RunningActiveReferenceSectionProps {
  width: '393' | '320';
  state: ActiveRunPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  onBaselineChange: (name: string | null) => void;
}

const ACTIVE_RUN_STORAGE_KEY = 'healthy-agent:ui-reference:running-active-v1';

const runDemo = {
  pace: `6'12"`,
  distance: '2.41',
  targetDistance: '5.00',
  elapsed: '12:36',
  heartRate: '148',
  cadence: '172',
  targetPace: `6'00"`,
  progress: 48.2,
  intensity: 72,
  zone: '有氧进阶',
};

const activeRunVariants: ActiveRunVariantDefinition[] = [
  {
    id: 'cockpit',
    letter: 'A',
    name: '夜色驾驶舱',
    summary: '固定深色单列仪表，配速占据唯一视觉主位，数据源压在顶行，提醒和控制贴着视野下缘。',
    hierarchy: '配速 → 状态 → 控制',
    theme: '固定深色',
    bestFor: '夜跑与强光下快速扫读',
    stateLogic: '暂停与继续共用同一状态；主数字去饱和，结束进入二次确认。',
    cssFocus: '等宽超大数字、顶行数据源、底部固定控制坞。',
  },
  {
    id: 'odometer',
    letter: 'B',
    name: '双窗里程表',
    summary: '里程与配速并列成两块不等宽仪表，目标进度夹在中间，心率与步频放在下方四宫格。',
    hierarchy: '里程 → 配速 → 身体状态',
    theme: '跟随日夜主题',
    bestFor: '有明确距离目标的训练',
    stateLogic: '进度按 distance / targetDistance 计算；暂停后数值冻结并显示恢复动作。',
    cssFocus: '非对称双窗、主题令牌、进度轨与四宫格指标。',
  },
  {
    id: 'tempo',
    letter: 'C',
    name: '节奏轨带',
    summary:
      '浅色训练台上横贯一条节奏带，配速、强度区间和实时指标沿同一水平轴排列，像一条正在推进的跑道。',
    hierarchy: '节奏 → 区间 → 提醒',
    theme: '固定浅色',
    bestFor: '日间轻松跑和节奏跑',
    stateLogic: '强度指针由心率占最大心率的比例定位；暂停时整条节奏带停止推进。',
    cssFocus: '横向数据带、分段强度轨、低矮控制区。',
  },
];

const activeRunTerms = [
  {
    term: 'single-glance metric',
    description: '跑中只允许一个超大读数。其他指标必须退后，否则运动中无法在一秒内读懂。',
  },
  {
    term: 'data freshness',
    description: 'GPS 和心率的新鲜度贴着数据源显示；延迟要说明，不能继续把旧值当成实时值。',
  },
  {
    term: 'state transition',
    description: '暂停不是换页。页面保留当前上下文，只改变状态点、主读数饱和度和主操作名称。',
  },
  {
    term: 'destructive confirmation',
    description: '结束跑步会停止记录，因此必须经过明确确认；暂停与继续则应当即时响应。',
  },
];

function readStoredVariant(): ActiveRunVariantId | null {
  try {
    const value = window.localStorage.getItem(ACTIVE_RUN_STORAGE_KEY);
    return activeRunVariants.some((variant) => variant.id === value)
      ? (value as ActiveRunVariantId)
      : null;
  } catch {
    return null;
  }
}

function useActiveRunDemo(state: ActiveRunPreviewState) {
  const [paused, setPaused] = useState(state === 'saving');
  const [confirmingEnd, setConfirmingEnd] = useState(false);
  const [notice, setNotice] = useState('合成跑中数据；不会请求设备或保存记录。');
  const degraded = state === 'long';

  useEffect(() => {
    setPaused(state === 'saving');
    setConfirmingEnd(false);
    setNotice(
      state === 'long'
        ? '合成弱信号状态：GPS 更新延迟，心率仍在同步。'
        : state === 'saving'
          ? '合成暂停状态：当前里程与计时保持不变。'
          : '合成跑中数据；不会请求设备或保存记录。',
    );
  }, [state]);

  const togglePause = () => {
    setPaused((current) => {
      const next = !current;
      setNotice(
        next
          ? '已暂停：正式页面会发送 run.pause，当前读数保持不变。'
          : '已继续：正式页面会发送 run.resume，实时读数恢复推进。',
      );
      return next;
    });
  };

  const confirmEnd = () => {
    setConfirmingEnd(false);
    setNotice('已确认结束：正式页面会发送 run.stop，并进入保存与同步流程。');
  };

  return {
    paused,
    degraded,
    confirmingEnd,
    notice,
    togglePause,
    requestEnd: () => setConfirmingEnd(true),
    cancelEnd: () => setConfirmingEnd(false),
    confirmEnd,
  };
}

function SourceStatus({ degraded }: { degraded: boolean }) {
  return (
    <span className={styles.activeRunSource} data-tone={degraded ? 'warning' : 'ready'}>
      {degraded ? (
        <Warning size={13} weight="fill" aria-hidden="true" />
      ) : (
        <MapPin size={13} weight="fill" aria-hidden="true" />
      )}
      <span>{degraded ? 'GPS 延迟 6 秒' : 'GPS 正常'}</span>
      <Bluetooth size={13} weight="bold" aria-hidden="true" />
      <span>B5</span>
      <Heartbeat size={13} weight="bold" aria-hidden="true" />
      <span>148</span>
    </span>
  );
}

function EndRunDialog({ onCancel, onConfirm }: { onCancel: () => void; onConfirm: () => void }) {
  const continueRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    continueRef.current?.focus();
  }, []);

  return (
    <div className={styles.activeRunDialogScrim}>
      <div
        className={styles.activeRunDialog}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="active-run-end-title"
        aria-describedby="active-run-end-description"
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault();
            onCancel();
            return;
          }
          if (event.key !== 'Tab') return;

          const focusable = Array.from(
            event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not([disabled])'),
          );
          const first = focusable[0];
          const last = focusable.at(-1);
          if (!first || !last) return;

          if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
          } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
          } else if (!focusable.includes(document.activeElement as HTMLButtonElement)) {
            event.preventDefault();
            first.focus();
          }
        }}
      >
        <h3 id="active-run-end-title">结束本次跑步？</h3>
        <p id="active-run-end-description">
          结束后会保存当前数据，Health Connect 同步结果单独显示。
        </p>
        <div>
          <button ref={continueRef} type="button" onClick={onCancel}>
            继续跑
          </button>
          <button type="button" className={styles.activeRunConfirmEnd} onClick={onConfirm}>
            确认结束
          </button>
        </div>
      </div>
    </div>
  );
}

function ActiveRunCanvas({
  variant,
  width,
  state,
  hidden,
  children,
  demo,
}: {
  variant: ActiveRunVariantId;
  width: '393' | '320';
  state: ActiveRunPreviewState;
  hidden: boolean;
  children: ReactNode;
  demo: ReturnType<typeof useActiveRunDemo>;
}) {
  return (
    <div
      className={`${styles.demoCanvas} ${styles.activeRunDemoCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-active-run-variant={variant}
      data-paused={demo.paused || undefined}
      aria-label={`${activeRunVariants.find((item) => item.id === variant)?.name}合成跑中预览`}
    >
      <div
        className={styles.activeRunScene}
        inert={demo.confirmingEnd ? true : undefined}
        aria-hidden={hidden || undefined}
      >
        {children}
      </div>
      {demo.confirmingEnd && <EndRunDialog onCancel={demo.cancelEnd} onConfirm={demo.confirmEnd} />}
    </div>
  );
}

function RunControls({
  demo,
  variant,
}: {
  demo: ReturnType<typeof useActiveRunDemo>;
  variant: ActiveRunVariantId;
}) {
  return (
    <div className={styles.activeRunControls} data-variant={variant}>
      <button type="button" onClick={demo.togglePause}>
        {demo.paused ? (
          <Play size={18} weight="fill" aria-hidden="true" />
        ) : (
          <Pause size={18} weight="fill" aria-hidden="true" />
        )}
        <span>{demo.paused ? '继续' : '暂停'}</span>
      </button>
      <button type="button" onClick={demo.requestEnd}>
        <Stop size={18} weight="fill" aria-hidden="true" />
        <span>结束</span>
      </button>
    </div>
  );
}

function CockpitPreview({ width, state }: { width: '393' | '320'; state: ActiveRunPreviewState }) {
  const demo = useActiveRunDemo(state);

  return (
    <ActiveRunCanvas variant="cockpit" width={width} state={state} hidden={false} demo={demo}>
      <div className={styles.activeCockpit}>
        <header className={styles.activeRunHeader}>
          <span className={styles.activeRunStatus} data-paused={demo.paused || undefined}>
            <i aria-hidden="true" />
            {demo.paused ? '已暂停' : '跑步中'}
          </span>
          <time>{runDemo.elapsed}</time>
        </header>
        <SourceStatus degraded={demo.degraded} />

        <section className={styles.activePaceHero} data-paused={demo.paused || undefined}>
          <span>当前配速</span>
          <div>
            <strong>{runDemo.pace}</strong>
            <small>/公里</small>
          </div>
          <p>目标 {runDemo.targetPace} · 比目标快 8 秒</p>
        </section>

        <dl className={styles.activeCockpitMetrics}>
          <div>
            <dt>距离</dt>
            <dd>
              {runDemo.distance}
              <small> km</small>
            </dd>
          </div>
          <div>
            <dt>心率</dt>
            <dd>
              {runDemo.heartRate}
              <small> bpm</small>
            </dd>
          </div>
          <div>
            <dt>步频</dt>
            <dd>
              {runDemo.cadence}
              <small> spm</small>
            </dd>
          </div>
        </dl>

        <div className={styles.activeZoneRail} aria-label={`当前强度 ${runDemo.intensity}%`}>
          <span style={{ width: `${runDemo.intensity}%` }} />
          <em>
            {runDemo.zone} · {runDemo.intensity}%
          </em>
        </div>

        <aside className={styles.activeCoachLine}>
          <strong>流萤提醒</strong>
          <p>
            {demo.degraded
              ? '前方信号较弱，距离可能短暂停止更新；心率仍在同步。'
              : '配速比目标快 8 秒，保持呼吸。'}
          </p>
        </aside>

        <p className={styles.activeRunNotice} role="status">
          {demo.notice}
        </p>
        <RunControls demo={demo} variant="cockpit" />
      </div>
    </ActiveRunCanvas>
  );
}

function OdometerPreview({ width, state }: { width: '393' | '320'; state: ActiveRunPreviewState }) {
  const demo = useActiveRunDemo(state);

  return (
    <ActiveRunCanvas variant="odometer" width={width} state={state} hidden={false} demo={demo}>
      <div className={styles.activeOdometer}>
        <header className={styles.activeRunHeader}>
          <span className={styles.activeRunStatus} data-paused={demo.paused || undefined}>
            <i aria-hidden="true" />
            {demo.paused ? '已暂停' : '训练中'}
          </span>
          <time>{runDemo.elapsed}</time>
        </header>
        <SourceStatus degraded={demo.degraded} />

        <div className={styles.activeOdometerWindows}>
          <section className={styles.activeDistanceWindow}>
            <span>累计里程</span>
            <div>
              <strong>{runDemo.distance}</strong>
              <small>km</small>
            </div>
            <p>目标 {runDemo.targetDistance} km</p>
            <div
              className={styles.activeTargetBar}
              role="progressbar"
              aria-label="目标距离进度"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(runDemo.progress)}
            >
              <span style={{ width: `${runDemo.progress}%` }} />
            </div>
          </section>
          <section className={styles.activePaceWindow} data-paused={demo.paused || undefined}>
            <span>当前配速</span>
            <strong>{runDemo.pace}</strong>
            <small>/公里</small>
            <p>目标 {runDemo.targetPace}</p>
          </section>
        </div>

        <dl className={styles.activeBodyGrid}>
          <div>
            <dt>心率</dt>
            <dd>
              {runDemo.heartRate}
              <small> bpm</small>
            </dd>
          </div>
          <div>
            <dt>步频</dt>
            <dd>
              {runDemo.cadence}
              <small> spm</small>
            </dd>
          </div>
          <div>
            <dt>强度</dt>
            <dd>
              {runDemo.intensity}
              <small>%</small>
            </dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd className={styles.activeBodyStatus}>{demo.degraded ? '等待 GPS' : runDemo.zone}</dd>
          </div>
        </dl>

        <aside className={styles.activeCoachBand}>
          <span>流萤</span>
          <p>
            {demo.degraded
              ? '定位更新变慢，先维持当前节奏，不要按旧距离加速。'
              : '节奏稳定，接下来 400 米保持呼吸频率。'}
          </p>
        </aside>

        <p className={styles.activeRunNotice} role="status">
          {demo.notice}
        </p>
        <RunControls demo={demo} variant="odometer" />
      </div>
    </ActiveRunCanvas>
  );
}

function TempoPreview({ width, state }: { width: '393' | '320'; state: ActiveRunPreviewState }) {
  const demo = useActiveRunDemo(state);

  return (
    <ActiveRunCanvas variant="tempo" width={width} state={state} hidden={false} demo={demo}>
      <div className={styles.activeTempo}>
        <header className={styles.activeTempoHeader}>
          <span className={styles.activeRunStatus} data-paused={demo.paused || undefined}>
            <i aria-hidden="true" />
            {demo.paused ? '节奏已暂停' : '节奏进行中'}
          </span>
          <SourceStatus degraded={demo.degraded} />
        </header>

        <section className={styles.activeTempoBand} data-paused={demo.paused || undefined}>
          <div>
            <span>当前配速</span>
            <strong>{runDemo.pace}</strong>
            <small>/KM</small>
          </div>
          <time>{runDemo.elapsed}</time>
        </section>

        <div className={styles.activeTempoScale} aria-label={`当前强度 ${runDemo.intensity}%`}>
          <span className={styles.activeTempoScaleLabel}>热身</span>
          <div>
            {[1, 2, 3, 4, 5].map((segment) => (
              <i key={segment} data-active={segment <= 4 || undefined} />
            ))}
            <em style={{ left: `${runDemo.intensity}%` }} aria-hidden="true" />
          </div>
          <span className={styles.activeTempoScaleLabel}>冲刺</span>
        </div>

        <dl className={styles.activeTempoTicker}>
          <div>
            <dt>KM</dt>
            <dd>{runDemo.distance}</dd>
          </div>
          <div>
            <dt>BPM</dt>
            <dd>{runDemo.heartRate}</dd>
          </div>
          <div>
            <dt>SPM</dt>
            <dd>{runDemo.cadence}</dd>
          </div>
          <div>
            <dt>区间</dt>
            <dd>{runDemo.zone}</dd>
          </div>
        </dl>

        <aside className={styles.activeTempoCoach}>
          <Heartbeat size={18} weight="fill" aria-hidden="true" />
          <p>
            {demo.degraded
              ? 'GPS 延迟，心率稳定；先以心率和呼吸维持节奏。'
              : '呼吸顺畅，配速比目标快 8 秒；保持这一条节奏带。'}
          </p>
        </aside>

        <p className={styles.activeRunNotice} role="status">
          {demo.notice}
        </p>
        <RunControls demo={demo} variant="tempo" />
      </div>
    </ActiveRunCanvas>
  );
}

function ActiveRunPreviewCanvas({
  variant,
  width,
  state,
}: {
  variant: ActiveRunVariantId;
  width: '393' | '320';
  state: ActiveRunPreviewState;
}) {
  if (variant === 'cockpit') return <CockpitPreview width={width} state={state} />;
  if (variant === 'odometer') return <OdometerPreview width={width} state={state} />;
  return <TempoPreview width={width} state={state} />;
}

function ActiveRunVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  selected,
  onSelect,
}: {
  variant: ActiveRunVariantDefinition;
  width: '393' | '320';
  state: ActiveRunPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  selected: boolean;
  onSelect: (variant: ActiveRunVariantId, name: string) => void;
}) {
  return (
    <article className={`${styles.variantCard} ${selected ? styles.variantSelected : ''}`}>
      <header className={styles.variantHeader}>
        <span className={styles.variantMark} aria-hidden="true">
          {variant.letter}
        </span>
        <div className={styles.variantHeading}>
          <div className={styles.variantNameRow}>
            <h3>{variant.name}</h3>
            {selected ? <span className={styles.candidateBadge}>本次候选</span> : null}
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <div className={styles.previewFrame}>
        <ActiveRunPreviewCanvas variant={variant.id} width={width} state={state} />
        {showRuler ? ruler : null}
      </div>
      <dl className={styles.factGrid}>
        <div>
          <dt>判断顺序</dt>
          <dd>{variant.hierarchy}</dd>
        </div>
        <div>
          <dt>主题策略</dt>
          <dd>{variant.theme}</dd>
        </div>
        <div>
          <dt>更适合</dt>
          <dd>{variant.bestFor}</dd>
        </div>
      </dl>
      <dl className={styles.activeRunSpecGrid}>
        <div>
          <dt>JS 状态</dt>
          <dd>{variant.stateLogic}</dd>
        </div>
        <div>
          <dt>CSS 重心</dt>
          <dd>{variant.cssFocus}</dd>
        </div>
      </dl>
      <div className={styles.variantActions}>
        <button
          type="button"
          className={styles.selectButton}
          aria-pressed={selected}
          aria-label={
            selected ? `${variant.name}已选为跑中页面基线` : `选择${variant.name}作为跑中页面基线`
          }
          onClick={() => onSelect(variant.id, variant.name)}
        >
          {selected ? <Check size={15} weight="bold" aria-hidden="true" /> : null}
          {selected ? '已选为跑中页基线' : '选作跑中页基线'}
        </button>
      </div>
    </article>
  );
}

function ActiveRunGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="active-run-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="active-run-glossary-heading">跑中页先掌握四个词</h2>
        <p>跑中页的目标不是展示更多数据，而是让用户在呼吸和移动中快速做对下一件事。</p>
      </header>
      <dl className={styles.termGrid}>
        {activeRunTerms.map((item) => (
          <div key={item.term} className={styles.termItem}>
            <dt>{item.term}</dt>
            <dd>{item.description}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function RunningActiveReferenceSection({
  width,
  state,
  showRuler,
  ruler,
  onBaselineChange,
}: RunningActiveReferenceSectionProps) {
  const [selectedVariant, setSelectedVariant] = useState<ActiveRunVariantId | null>(
    () => readStoredVariant() ?? 'cockpit',
  );
  const [feedback, setFeedback] = useState(
    'A 夜色驾驶舱已作为正式跑中页基线接入；B 双窗里程表和 C 节奏轨带保留为对比方案。',
  );

  useEffect(() => {
    const selected = activeRunVariants.find((variant) => variant.id === selectedVariant);
    onBaselineChange(selected ? `${selected.letter} ${selected.name}` : null);
  }, [onBaselineChange, selectedVariant]);

  const handleSelect = (variant: ActiveRunVariantId, name: string) => {
    setSelectedVariant(variant);
    try {
      window.localStorage.setItem(ACTIVE_RUN_STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    setFeedback(
      variant === 'cockpit'
        ? 'A 夜色驾驶舱已作为正式跑中页基线。'
        : `已将“${name}”记为对比候选；正式跑中页仍为 A 夜色驾驶舱。`,
    );
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="active-run-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="active-run-variants-heading">跑中页的三种信息节奏</h2>
          <p>
            A 把配速放在唯一主位，B 把里程目标拆成双窗，C
            用横向节奏带组织数据。三案使用同一份合成跑中状态和同一套暂停、结束逻辑。
          </p>
        </header>
        <div
          className={`${styles.variantGrid} ${styles.activeRunVariantGrid}`}
          data-testid="active-run-previews"
        >
          {activeRunVariants.map((variant) => (
            <ActiveRunVariantCard
              key={variant.id}
              variant={variant}
              width={width}
              state={state}
              showRuler={showRuler}
              ruler={ruler}
              selected={selectedVariant === variant.id}
              onSelect={handleSelect}
            />
          ))}
        </div>
        <p className={styles.feedback} role="status" aria-live="polite">
          {feedback}
        </p>
      </section>
      <ActiveRunGlossary />
    </>
  );
}
