import { Bluetooth, Check, CheckCircle, MapPin, Play, Warning } from '@phosphor-icons/react';
import { useEffect, useState, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type ReadinessVariantId = 'console' | 'target' | 'checklist';
type ReadinessPreviewState = 'default' | 'focus' | 'long' | 'saving';

interface ReadinessVariantDefinition {
  id: ReadinessVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  hierarchy: string;
  density: string;
  bestFor: string;
  recommended?: boolean;
}

interface RunningReadinessReferenceSectionProps {
  width: '393' | '320';
  state: ReadinessPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  onBaselineChange: (name: string | null) => void;
}

const READINESS_STORAGE_KEY = 'healthy-agent:ui-reference:running-readiness-v4';
const shortDeviceName = 'Keep B5 2A19';
const longDeviceName = 'Keep B5 2A19 · 客厅充电位';

// 结构依据 Keep 公开跑步截图：地图/轨迹优先、白色浮层数据卡、深炭色实时页、绿色动作色。
const readinessVariants: ReadinessVariantDefinition[] = [
  {
    id: 'console',
    letter: 'A',
    name: '地图起跑',
    summary: '以路线地图为第一视觉，白色数据卡从地图下缘浮出，对应 Keep 跑步记录的阅读方式。',
    hierarchy: '路线 → 数据 → 开跑',
    density: '高',
    bestFor: '户外跑',
  },
  {
    id: 'target',
    letter: 'B',
    name: '里程仪表',
    summary: '深炭色全屏仪表配白色大数字和绿色开始圆键，对应 Keep 跑中页的高对比数据节奏。',
    hierarchy: '里程 → 状态 → 开跑',
    density: '中低',
    bestFor: '夜跑',
    recommended: true,
  },
  {
    id: 'checklist',
    letter: 'C',
    name: '训练清单',
    summary: '浅色训练卡把目标、定位、手环和安全压成一眼扫完的三列状态，最像 Keep 的训练任务页。',
    hierarchy: '目标 → 检查 → 开跑',
    density: '中',
    bestFor: '首次使用',
  },
];

const readinessTerminology = [
  {
    term: 'readiness gate',
    description: '开跑前必须先通过的设备与安全条件。未满足时要说明原因和恢复方式。',
  },
  {
    term: 'glanceability',
    description: '扫一眼就能读出状态。跑前页不能要求用户逐字阅读长说明才知道是否可开跑。',
  },
  {
    term: 'target lock',
    description: '开跑前的目标与被冻结的本次目标分开，避免跑步中设置变化影响当前会话。',
  },
  {
    term: 'progressive disclosure',
    description: '先呈现能不能跑；电量和连接细节只在需要时展开，不抢占主操作。',
  },
];

function readStoredVariant(): ReadinessVariantId {
  try {
    const value = window.localStorage.getItem(READINESS_STORAGE_KEY);
    return readinessVariants.some((variant) => variant.id === value)
      ? (value as ReadinessVariantId)
      : 'target';
  } catch {
    return 'target';
  }
}

function useReadinessDemo(state: ReadinessPreviewState) {
  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(state === 'saving');
  const [notice, setNotice] = useState('合成跑前状态：手机 GPS 已就绪，Keep B5 可选。');
  const locationReady = state !== 'long';
  const deviceName = state === 'long' ? longDeviceName : shortDeviceName;

  useEffect(() => {
    setConnected(false);
    setConnecting(state === 'saving');
    setNotice(
      state === 'long'
        ? '合成异常状态：定位信号较弱，开跑前需要先移到开阔区域。'
        : state === 'saving'
          ? '正在连接 Keep B5，连接完成前不会改变开跑条件。'
          : '合成跑前状态：手机 GPS 已就绪，Keep B5 可选。',
    );
  }, [state]);

  useEffect(() => {
    if (!connecting || state === 'saving') return;
    const timer = window.setTimeout(() => {
      setConnecting(false);
      setConnected(true);
      setNotice(`${deviceName} 已连接，电量 82%（合成演示）。`);
    }, 800);
    return () => window.clearTimeout(timer);
  }, [connecting, deviceName, state]);

  const connect = () => {
    if (connected || connecting || state === 'saving') return;
    setConnecting(true);
    setNotice('正在连接 Keep B5，不会开始或保存跑步。');
  };

  const start = () => {
    if (!locationReady) return;
    setNotice('已通过合成跑前检查；正式页面才会请求开始跑步。');
  };

  return {
    connected,
    connecting: connecting || state === 'saving',
    deviceName,
    locationReady,
    notice,
    connect,
    start,
  };
}

type ReadinessDemo = ReturnType<typeof useReadinessDemo>;

function StatusDot({ ready }: { ready: boolean }) {
  return (
    <span
      className={styles.readinessStatusDot}
      data-tone={ready ? 'ready' : 'warning'}
      aria-hidden="true"
    />
  );
}

function ConnectionButton({ demo, className = '' }: { demo: ReadinessDemo; className?: string }) {
  const label = demo.connected
    ? 'Keep B5 已连接'
    : demo.connecting
      ? '正在连接手环…'
      : '连接 Keep B5';
  return (
    <button
      type="button"
      className={`${styles.readinessSecondary} ${className}`.trim()}
      aria-label={label}
      aria-busy={demo.connecting}
      disabled={demo.connected || demo.connecting}
      onClick={demo.connect}
    >
      <Bluetooth size={16} aria-hidden="true" />
      {label}
    </button>
  );
}

function ConnectionOrbButton({ demo }: { demo: ReadinessDemo }) {
  const label = demo.connected
    ? 'Keep B5 已连接'
    : demo.connecting
      ? '正在连接手环…'
      : '连接 Keep B5';
  const shortLabel = demo.connected ? '已连接' : demo.connecting ? '连接中' : '连接手环';
  const connectionState = demo.connected ? 'connected' : demo.connecting ? 'connecting' : 'idle';

  return (
    <div className={styles.readinessStartOrbGroup}>
      <button
        type="button"
        className={styles.readinessConnectionOrb}
        data-readiness-control="connection-orb"
        data-state={connectionState}
        aria-label={label}
        aria-busy={demo.connecting}
        disabled={demo.connected || demo.connecting}
        onClick={demo.connect}
      >
        {demo.connected ? (
          <span className={styles.readinessOrbGlyph} data-readiness-glyph="check">
            <Check size={28} weight="bold" aria-hidden="true" />
          </span>
        ) : (
          <span className={styles.readinessOrbGlyph} data-readiness-glyph="bluetooth">
            <Bluetooth size={28} aria-hidden="true" />
          </span>
        )}
      </button>
      <span>{shortLabel}</span>
    </div>
  );
}

function StartButton({ demo, className = '' }: { demo: ReadinessDemo; className?: string }) {
  return (
    <button
      type="button"
      className={`${styles.readinessPrimary} ${className}`.trim()}
      disabled={!demo.locationReady}
      onClick={demo.start}
    >
      {demo.locationReady ? (
        <Play size={17} weight="fill" aria-hidden="true" />
      ) : (
        <Warning size={17} weight="fill" aria-hidden="true" />
      )}
      {demo.locationReady ? '开始跑步' : '等待定位'}
    </button>
  );
}

function StartOrbButton({ demo }: { demo: ReadinessDemo }) {
  return (
    <div className={styles.readinessStartOrbGroup}>
      <button
        type="button"
        className={styles.readinessStartOrb}
        data-readiness-control="start-orb"
        aria-label={demo.locationReady ? '开始跑步' : '等待定位'}
        disabled={!demo.locationReady}
        onClick={demo.start}
      >
        {demo.locationReady ? (
          <span className={styles.readinessOrbGlyph} data-readiness-glyph="play">
            <Play size={28} weight="fill" aria-hidden="true" />
          </span>
        ) : (
          <span className={styles.readinessOrbGlyph} data-readiness-glyph="warning">
            <Warning size={28} weight="fill" aria-hidden="true" />
          </span>
        )}
      </button>
      <span>{demo.locationReady ? '开始跑步' : '等待定位'}</span>
    </div>
  );
}

function ConsoleReadiness({ demo }: { demo: ReadinessDemo }) {
  return (
    <div className={`${styles.readinessScene} ${styles.readinessRouteScene}`}>
      <div className={styles.readinessMapSurface}>
        <div className={styles.readinessMapStatus}>
          <StatusDot ready={demo.locationReady} />
          <span>{demo.locationReady ? '定位已就绪' : '等待定位'}</span>
        </div>
        <svg
          className={styles.readinessMapArtwork}
          viewBox="0 0 360 220"
          preserveAspectRatio="xMidYMin meet"
          role="img"
          aria-label="模拟跑步路线示意图"
        >
          <path
            className={styles.readinessMapRoad}
            d="M-20 152 C46 132 72 66 137 77 S226 159 293 126 S335 48 382 44"
          />
          <path
            className={styles.readinessMapRoadMinor}
            d="M38 -20 C61 47 47 117 89 238 M246 -18 C230 50 259 153 222 238"
          />
          <path
            className={styles.readinessRouteLine}
            d="M48 154 C83 116 96 73 146 82 C190 91 194 145 239 146 C282 147 284 93 319 68"
          />
          <circle className={styles.readinessRouteStart} cx="48" cy="154" r="7" />
          <circle className={styles.readinessRouteEnd} cx="319" cy="68" r="8" />
        </svg>
        <span className={styles.readinessMapCaption}>
          <MapPin size={14} aria-hidden="true" />
          起终点 · 模拟路线
        </span>
      </div>

      <section className={styles.readinessConsoleSurface} aria-label="跑前准备卡">
        <header className={styles.readinessConsoleHeader}>
          <span>
            <StatusDot ready={demo.locationReady} />
            户外跑步
          </span>
          <small>目标 · 轻松跑</small>
        </header>

        <dl className={styles.readinessConsoleTargets}>
          <div>
            <dt>目标距离</dt>
            <dd>
              5.00 <small>公里</small>
            </dd>
          </div>
          <div>
            <dt>目标配速</dt>
            <dd>
              6'00" <small>/km</small>
            </dd>
          </div>
        </dl>

        <div className={styles.readinessConsoleRows}>
          <div>
            <MapPin size={17} aria-hidden="true" />
            <span>
              <strong>手机定位</strong>
              <small>
                {demo.locationReady ? '信号稳定，可以记录路线' : '信号较弱，请移到开阔区域'}
              </small>
            </span>
            <StatusDot ready={demo.locationReady} />
          </div>
          <div>
            <Bluetooth size={17} aria-hidden="true" />
            <span>
              <strong>{demo.deviceName}</strong>
              <small>
                {demo.connected ? '心率数据可用 · 电量 82%' : '未连接，不影响手机 GPS 跑步'}
              </small>
            </span>
            {demo.connected ? <CheckCircle size={18} aria-hidden="true" /> : <small>可选</small>}
          </div>
        </div>

        <p
          className={styles.readinessSafetyLine}
          data-tone={demo.locationReady ? 'normal' : 'warning'}
        >
          <Warning size={15} aria-hidden="true" />
          {demo.locationReady
            ? '安全状态正常，未发现需要处理的提醒。'
            : '开跑前必须等待定位恢复；手环连接不是前置条件。'}
        </p>

        <div className={styles.readinessConsoleActions}>
          <StartButton demo={demo} />
          <ConnectionButton demo={demo} />
        </div>
      </section>
      <ReadinessFeedback notice={demo.notice} />
    </div>
  );
}

function TargetReadiness({ demo }: { demo: ReadinessDemo }) {
  return (
    <div
      className={`${styles.readinessScene} ${styles.readinessTargetScene} ${styles.readinessInstrumentScene}`}
    >
      <header className={styles.readinessTargetHeader}>
        <span>
          <StatusDot ready={demo.locationReady} />
          {demo.locationReady ? '定位已就绪' : '等待定位'}
        </span>
        <small>{demo.connected ? demo.deviceName : 'Keep B5 未连接'}</small>
      </header>

      <div className={styles.readinessTargetHero}>
        <p>今日目标 · 5.00 公里</p>
        <div>
          <strong>0.00</strong>
          <span>km</span>
        </div>
        <dl>
          <div>
            <dt>目标配速</dt>
            <dd>6'00" /km</dd>
          </div>
          <div>
            <dt>预计用时</dt>
            <dd>30:00</dd>
          </div>
        </dl>
      </div>

      <ol className={styles.readinessTargetTrack} aria-label="跑前检查轨">
        <li data-tone={demo.locationReady ? 'ready' : 'warning'}>
          <span aria-hidden="true">{demo.locationReady ? <Check size={13} /> : '!'}</span>
          <div>
            <strong>手机定位</strong>
            <small>{demo.locationReady ? '信号稳定，路线记录可用' : '信号较弱，暂不可开跑'}</small>
          </div>
        </li>
        <li data-tone={demo.connected ? 'ready' : 'optional'}>
          <span aria-hidden="true">{demo.connected ? <Check size={13} /> : '2'}</span>
          <div>
            <strong>{demo.deviceName}</strong>
            <small>
              {demo.connected ? '心率数据已连接，电量 82%' : '可选心率源，不阻止手机跑步'}
            </small>
          </div>
        </li>
        <li data-tone="ready">
          <span aria-hidden="true">3</span>
          <div>
            <strong>安全状态</strong>
            <small>暂无需要处理的安全提醒</small>
          </div>
        </li>
      </ol>

      <div className={`${styles.readinessTargetActions} ${styles.readinessInstrumentActions}`}>
        <ConnectionOrbButton demo={demo} />
        <StartOrbButton demo={demo} />
      </div>
      <ReadinessFeedback notice={demo.notice} />
    </div>
  );
}

function ChecklistReadiness({ demo }: { demo: ReadinessDemo }) {
  const passedCount = (demo.locationReady ? 1 : 0) + (demo.connected ? 1 : 0) + 1;
  return (
    <div className={`${styles.readinessScene} ${styles.readinessPlanScene}`}>
      <header className={styles.readinessChecklistHeader}>
        <div>
          <p>今日训练</p>
          <h4>轻松跑 5 公里</h4>
        </div>
        <strong>{demo.locationReady ? `${passedCount}/3 已就绪` : '定位待处理'}</strong>
      </header>

      <div className={styles.readinessPlanCover} aria-label="五公里轻松跑训练封面">
        <div className={styles.readinessPlanCoverCopy}>
          <span>户外跑步</span>
          <strong>5K</strong>
          <small>轻松跑 · 约 30 分钟</small>
        </div>
        <svg className={styles.readinessPlanTrack} viewBox="0 0 220 150" aria-hidden="true">
          <path d="M-10 118 C38 72 98 74 126 112 S193 132 236 64" />
          <path d="M-6 138 C48 91 97 91 132 125 S201 145 238 81" />
        </svg>
        <dl className={styles.readinessPlanCoverMeta}>
          <div>
            <dt>目标配速</dt>
            <dd>6'00"</dd>
          </div>
          <div>
            <dt>预计用时</dt>
            <dd>30:00</dd>
          </div>
        </dl>
      </div>

      <ul className={styles.readinessChecklist}>
        <li data-tone={demo.locationReady ? 'ready' : 'warning'}>
          <span className={styles.readinessCheckIcon} aria-hidden="true">
            {demo.locationReady ? <Check size={16} weight="bold" /> : <Warning size={16} />}
          </span>
          <div>
            <strong>手机定位</strong>
            <small>{demo.locationReady ? '路线记录可用' : '请移到开阔区域'}</small>
          </div>
          <em>{demo.locationReady ? '通过' : '待处理'}</em>
        </li>
        <li data-tone={demo.connected ? 'ready' : 'optional'}>
          <span className={styles.readinessCheckIcon} aria-hidden="true">
            <Bluetooth size={16} />
          </span>
          <div>
            <strong>{demo.deviceName}</strong>
            <small>{demo.connected ? '心率可用 · 82%' : '可选心率源'}</small>
          </div>
          <em>{demo.connected ? '已连接' : '可选'}</em>
        </li>
        <li data-tone="ready">
          <span className={styles.readinessCheckIcon} aria-hidden="true">
            <CheckCircle size={17} />
          </span>
          <div>
            <strong>安全状态</strong>
            <small>暂无安全提醒</small>
          </div>
          <em>通过</em>
        </li>
      </ul>

      <p
        className={styles.readinessSafetyLine}
        data-tone={demo.locationReady ? 'normal' : 'warning'}
      >
        <Warning size={15} aria-hidden="true" />
        {demo.locationReady
          ? '手环不是必需项；缺少手环时仍可使用手机 GPS 开跑。'
          : '开跑按钮会保持禁用，直到手机定位恢复。'}
      </p>

      <div className={styles.readinessChecklistActions}>
        <StartButton demo={demo} />
        <ConnectionButton demo={demo} />
      </div>
      <ReadinessFeedback notice={demo.notice} />
    </div>
  );
}

function ReadinessFeedback({ notice }: { notice: string }) {
  return (
    <p className={styles.readinessFeedback} role="status" aria-live="polite">
      {notice}
    </p>
  );
}

function ReadinessPreviewCanvas({
  variant,
  width,
  state,
  showRuler,
  ruler,
}: {
  variant: ReadinessVariantId;
  width: '393' | '320';
  state: ReadinessPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
}) {
  const demo = useReadinessDemo(state);
  return (
    <div
      className={`${styles.demoCanvas} ${styles.readinessDemoCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-readiness-variant={variant}
    >
      {showRuler ? ruler : null}
      {variant === 'console' ? (
        <ConsoleReadiness demo={demo} />
      ) : variant === 'target' ? (
        <TargetReadiness demo={demo} />
      ) : (
        <ChecklistReadiness demo={demo} />
      )}
    </div>
  );
}

function ReadinessVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  selected,
  onSelect,
}: {
  variant: ReadinessVariantDefinition;
  width: '393' | '320';
  state: ReadinessPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  selected: boolean;
  onSelect: (variant: ReadinessVariantId, name: string) => void;
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
            {variant.recommended ? <span className={styles.recommendedBadge}>建议方向</span> : null}
            {selected ? <span className={styles.candidateBadge}>本次候选</span> : null}
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <div className={styles.previewFrame}>
        <ReadinessPreviewCanvas
          variant={variant.id}
          width={width}
          state={state}
          showRuler={showRuler}
          ruler={ruler}
        />
      </div>
      <dl className={styles.factGrid}>
        <div>
          <dt>判断顺序</dt>
          <dd>{variant.hierarchy}</dd>
        </div>
        <div>
          <dt>信息密度</dt>
          <dd>{variant.density}</dd>
        </div>
        <div>
          <dt>更适合</dt>
          <dd>{variant.bestFor}</dd>
        </div>
      </dl>
      <div className={styles.variantActions}>
        <button
          type="button"
          className={styles.selectButton}
          aria-pressed={selected}
          aria-label={
            selected ? `${variant.name}已选为跑前页面基线` : `选择${variant.name}作为跑前页面基线`
          }
          onClick={() => onSelect(variant.id, variant.name)}
        >
          {selected ? <Check size={15} weight="bold" aria-hidden="true" /> : null}
          {selected ? '已选为本次基线' : '选作下一版基线'}
        </button>
      </div>
    </article>
  );
}

function ReadinessGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="readiness-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="readiness-glossary-heading">跑前页先掌握四个词</h2>
        <p>这四个词决定页面是在“展示信息”，还是真的帮助用户完成开跑判断。</p>
      </header>
      <dl className={styles.termGrid}>
        {readinessTerminology.map((item) => (
          <div key={item.term} className={styles.termItem}>
            <dt>{item.term}</dt>
            <dd>{item.description}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function RunningReadinessReferenceSection({
  width,
  state,
  showRuler,
  ruler,
  onBaselineChange,
}: RunningReadinessReferenceSectionProps) {
  const [selectedVariant, setSelectedVariant] = useState<ReadinessVariantId>(readStoredVariant);
  const [feedback, setFeedback] = useState(
    'B 里程仪表已作为正式跑前页基线接入；A 地图起跑和 C 训练清单保留为对比方案。',
  );

  useEffect(() => {
    const selected = readinessVariants.find((variant) => variant.id === selectedVariant);
    onBaselineChange(selected ? `${selected.letter} ${selected.name}` : 'B 里程仪表');
  }, [onBaselineChange, selectedVariant]);

  const handleSelect = (variant: ReadinessVariantId, name: string) => {
    setSelectedVariant(variant);
    try {
      window.localStorage.setItem(READINESS_STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    setFeedback(
      variant === 'target'
        ? 'B 里程仪表已作为正式跑前页基线。'
        : `已将“${name}”记为对比候选；正式跑前页仍为 B 里程仪表。`,
    );
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="readiness-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="readiness-variants-heading">跑前页的三种 Keep 式结构</h2>
          <p>
            参考 Keep 的真实跑步页面：A 用地图承载路线，B 用深炭色仪表突出里程，C
            用轻量训练卡组织目标与检查项。三案共享同一份合成数据。
          </p>
        </header>
        <div
          className={`${styles.variantGrid} ${styles.readinessVariantGrid}`}
          data-testid="readiness-previews"
        >
          {readinessVariants.map((variant) => (
            <ReadinessVariantCard
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
      <ReadinessGlossary />
    </>
  );
}
