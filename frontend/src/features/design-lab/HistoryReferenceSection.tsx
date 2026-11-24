import {
  ArrowRight,
  CalendarBlank,
  CaretRight,
  ChartLineUp,
  Check,
  Clock,
  Heartbeat,
} from '@phosphor-icons/react';
import { useEffect, useState, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type HistoryVariantId = 'recent' | 'timeline' | 'ledger';
type HistoryPreviewState = 'default' | 'focus' | 'long' | 'saving';

interface HistoryVariantDefinition {
  id: HistoryVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  hierarchy: string;
  theme: string;
  bestFor: string;
  stateLogic: string;
  cssFocus: string;
}

interface HistoryReferenceSectionProps {
  width: '393' | '320';
  state: HistoryPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  onBaselineChange: (name: string | null) => void;
}

interface DemoRun {
  id: string;
  date: string;
  month: string;
  weekday: string;
  when: string;
  distance: string;
  duration: string;
  pace: string;
  heartRate: string;
}

const HISTORY_STORAGE_KEY = 'healthy-agent:ui-reference:run-history-v1';

const demoRuns: DemoRun[] = [
  {
    id: 'run-1',
    date: '30',
    month: '9月',
    weekday: '周三',
    when: '9月30日 06:42',
    distance: '5.02',
    duration: '30:18',
    pace: '6:02',
    heartRate: '137',
  },
  {
    id: 'run-2',
    date: '29',
    month: '9月',
    weekday: '周二',
    when: '9月29日 07:10',
    distance: '4.08',
    duration: '25:42',
    pace: '6:18',
    heartRate: '142',
  },
  {
    id: 'run-3',
    date: '27',
    month: '9月',
    weekday: '周日',
    when: '9月27日 17:26',
    distance: '7.12',
    duration: '44:49',
    pace: '6:17',
    heartRate: '145',
  },
  {
    id: 'run-4',
    date: '24',
    month: '9月',
    weekday: '周四',
    when: '9月24日 06:31',
    distance: '3.34',
    duration: '22:11',
    pace: '6:39',
    heartRate: '132',
  },
  {
    id: 'run-5',
    date: '21',
    month: '9月',
    weekday: '周一',
    when: '9月21日 18:04',
    distance: '10.01',
    duration: '1:03:21',
    pace: '6:20',
    heartRate: '149',
  },
];

const historyVariants: HistoryVariantDefinition[] = [
  {
    id: 'recent',
    letter: 'A',
    name: '最近优先',
    summary: '最近一次跑步占据首屏主位，更早记录收进紧凑列表；打开页面先看到最有用的那次成绩。',
    hierarchy: '最近一次 → 更早记录 → 加载',
    theme: '跟随日夜主题',
    bestFor: '高频查看最近成绩的人',
    stateLogic: '空记录只给下一步说明；加载更多时保留已有列表并禁用重复请求。',
    cssFocus: '深色主成绩块、日期行、轻量加载动作。',
  },
  {
    id: 'timeline',
    letter: 'B',
    name: '日期时间线',
    summary: '把跑步记录串成连续时间轴，日期块固定在左侧，距离和配速沿同一条阅读线展开。',
    hierarchy: '月份 → 日期 → 成绩',
    theme: '跟随日夜主题',
    bestFor: '按时间回忆训练节奏',
    stateLogic: '记录按开始时间排序；同一天多条记录仍共享日期块，加载更多接在时间轴末尾。',
    cssFocus: '左侧日期轨、纵向节奏、紧凑行高。',
  },
  {
    id: 'ledger',
    letter: 'C',
    name: '月度训练账本',
    summary: '先用本月累计、次数和平均配速建立总量，再用周次分组列出每次跑步，像一本训练账。',
    hierarchy: '月度总量 → 周次 → 单次记录',
    theme: '固定深色',
    bestFor: '关注累计训练量的人',
    stateLogic: '月度汇总只从已加载记录计算，并标明范围；空记录不伪造 0 次之外的统计。',
    cssFocus: '深色总账、周次分隔、密集数据行。',
  },
];

const historyTerms = [
  {
    term: 'recency bias',
    description: '历史页最先回答“最近一次跑得怎样”，而不是让用户从最早记录向下找。',
  },
  {
    term: 'progressive loading',
    description: '大数据量按页读取；加载更多不能清空已展示记录，也不能看起来像重复提交。',
  },
  {
    term: 'temporal grouping',
    description: '日期分组让连续训练关系可见；分组本身不能盖过单次距离和时长。',
  },
  {
    term: 'honest aggregate',
    description: '汇总必须说明统计范围。只加载了一部分记录时，不能把局部数据写成全部历史。',
  },
];

function readStoredVariant(): HistoryVariantId | null {
  try {
    const value = window.localStorage.getItem(HISTORY_STORAGE_KEY);
    return historyVariants.some((variant) => variant.id === value)
      ? (value as HistoryVariantId)
      : 'recent';
  } catch {
    return 'recent';
  }
}

function useHistoryDemo(state: HistoryPreviewState) {
  const empty = state === 'long';
  const loadingMore = state === 'saving';
  const [notice, setNotice] = useState(
    empty ? '合成空记录；不会读取设备或虚构成绩。' : '合成记录；不会读取或修改本机跑步数据。',
  );

  useEffect(() => {
    setNotice(
      empty
        ? '合成空记录；不会读取设备或虚构成绩。'
        : loadingMore
          ? '正在读取下一页；已有记录保持可见。'
          : '合成记录；不会读取或修改本机跑步数据。',
    );
  }, [empty, loadingMore]);

  const selectRun = (run: DemoRun) =>
    setNotice(`已选中 ${run.when} 的合成记录；正式页面会进入该次跑步总结。`);
  const selectMore = () => setNotice('已请求下一页合成记录；正式页面会沿用 history.query 分页。');

  return {
    runs: empty ? [] : demoRuns,
    empty,
    loadingMore,
    notice,
    selectRun,
    selectMore,
  };
}

type HistoryDemo = ReturnType<typeof useHistoryDemo>;

function HistoryEmptyState({ demo }: { demo: HistoryDemo }) {
  if (!demo.empty) return null;
  return (
    <div className={styles.historyEmpty}>
      <span aria-hidden="true">
        <ChartLineUp size={22} weight="regular" />
      </span>
      <strong>还没有跑步记录</strong>
      <p>完成并保存第一次跑步后，它会自动出现在这里。</p>
    </div>
  );
}

function LoadMoreButton({ demo }: { demo: HistoryDemo }) {
  if (demo.empty) return null;
  return (
    <button
      type="button"
      className={styles.historyLoadMore}
      disabled={demo.loadingMore}
      onClick={demo.selectMore}
    >
      {demo.loadingMore ? (
        <>
          <Clock size={15} weight="bold" aria-hidden="true" />
          正在读取更多记录
        </>
      ) : (
        <>
          加载更多记录
          <ArrowRight size={15} weight="bold" aria-hidden="true" />
        </>
      )}
    </button>
  );
}

function HistoryNotice({ demo }: { demo: HistoryDemo }) {
  return (
    <p className={styles.historyNotice} role="status" aria-live="polite">
      {demo.notice}
    </p>
  );
}

function RecentPreview({ width, state }: { width: '393' | '320'; state: HistoryPreviewState }) {
  const demo = useHistoryDemo(state);
  const latest = demo.runs[0];
  const older = demo.runs.slice(1);

  return (
    <div
      className={`${styles.demoCanvas} ${styles.historyDemoCanvas} ${styles.historyRecentCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-history-variant="recent"
      aria-label="最近优先跑步记录合成预览"
    >
      <div className={styles.historyRecent}>
        <header className={styles.historyHeader}>
          <div>
            <span>跑步记录</span>
            <strong>9月</strong>
          </div>
          <span>{demo.empty ? '暂无记录' : `共 ${demo.runs.length} 次`}</span>
        </header>

        {latest ? (
          <button
            type="button"
            className={styles.historyLatestCard}
            aria-label={`查看 ${latest.when} 的跑步总结`}
            onClick={() => demo.selectRun(latest)}
          >
            <span className={styles.historyLatestMeta}>
              <span>最近一次</span>
              <time>{latest.when}</time>
            </span>
            <span className={styles.historyLatestDistance}>
              <strong>{latest.distance}</strong>
              <small>公里</small>
            </span>
            <span className={styles.historyLatestStats}>
              <span>
                <Clock size={14} aria-hidden="true" />
                {latest.duration}
              </span>
              <span>
                <Heartbeat size={14} aria-hidden="true" />
                {latest.heartRate} bpm
              </span>
              <span>{latest.pace} /km</span>
            </span>
          </button>
        ) : (
          <HistoryEmptyState demo={demo} />
        )}

        {older.length > 0 && (
          <section className={styles.historyOlder} aria-label="更早的跑步记录">
            <header>
              <strong>更早记录</strong>
              <span>按时间排序</span>
            </header>
            <ul>
              {older.map((run) => (
                <li key={run.id}>
                  <button type="button" onClick={() => demo.selectRun(run)}>
                    <span className={styles.historyDateBlock} aria-hidden="true">
                      <strong>{run.date}</strong>
                      <small>{run.month}</small>
                    </span>
                    <span className={styles.historyRunCopy}>
                      <strong>{run.distance} km</strong>
                      <small>
                        {run.when} · {run.duration}
                      </small>
                    </span>
                    <span className={styles.historyRunPace}>
                      {run.pace}
                      <CaretRight size={14} weight="bold" aria-hidden="true" />
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}

        <LoadMoreButton demo={demo} />
        <HistoryNotice demo={demo} />
      </div>
    </div>
  );
}

function TimelinePreview({ width, state }: { width: '393' | '320'; state: HistoryPreviewState }) {
  const demo = useHistoryDemo(state);

  return (
    <div
      className={`${styles.demoCanvas} ${styles.historyDemoCanvas} ${styles.historyTimelineCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-history-variant="timeline"
      aria-label="日期时间线跑步记录合成预览"
    >
      <div className={styles.historyTimeline}>
        <header className={styles.historyTimelineHeader}>
          <div>
            <span>训练时间线</span>
            <strong>2026年9月</strong>
          </div>
          <span>
            <CalendarBlank size={14} aria-hidden="true" />
            {demo.runs.length} 次
          </span>
        </header>

        {demo.runs.length === 0 ? (
          <HistoryEmptyState demo={demo} />
        ) : (
          <ol className={styles.historyTimelineList}>
            {demo.runs.map((run, index) => (
              <li key={run.id} data-latest={index === 0 || undefined}>
                <span className={styles.historyTimelineRail} aria-hidden="true">
                  <i />
                </span>
                <button type="button" onClick={() => demo.selectRun(run)}>
                  <span className={styles.historyTimelineDate}>
                    <strong>{run.date}</strong>
                    <small>
                      {run.month} · {run.weekday}
                    </small>
                  </span>
                  <span className={styles.historyTimelineCopy}>
                    <span>
                      <strong>{run.distance} km</strong>
                      {index === 0 && <em>最近</em>}
                    </span>
                    <small>
                      {run.duration} · {run.pace} /km
                    </small>
                  </span>
                  <CaretRight size={15} weight="bold" aria-hidden="true" />
                </button>
              </li>
            ))}
          </ol>
        )}

        <LoadMoreButton demo={demo} />
        <HistoryNotice demo={demo} />
      </div>
    </div>
  );
}

function LedgerPreview({ width, state }: { width: '393' | '320'; state: HistoryPreviewState }) {
  const demo = useHistoryDemo(state);
  const weeks = demo.empty
    ? []
    : [
        { label: '本周', runs: demo.runs.slice(0, 2), total: '9.10' },
        { label: '上周', runs: demo.runs.slice(2), total: '20.47' },
      ];

  return (
    <div
      className={`${styles.demoCanvas} ${styles.historyDemoCanvas} ${styles.historyLedgerCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-history-variant="ledger"
      aria-label="月度训练账本合成预览"
    >
      <div className={styles.historyLedger}>
        <header className={styles.historyLedgerHeader}>
          <span>月度训练账本</span>
          <strong>9月</strong>
        </header>

        {demo.empty ? (
          <HistoryEmptyState demo={demo} />
        ) : (
          <>
            <section className={styles.historyLedgerSummary} aria-label="本月累计训练数据">
              <span>本月累计</span>
              <div>
                <strong>29.57</strong>
                <small>km</small>
              </div>
              <dl>
                <div>
                  <dt>次数</dt>
                  <dd>5 次</dd>
                </div>
                <div>
                  <dt>平均配速</dt>
                  <dd>6:18</dd>
                </div>
                <div>
                  <dt>平均心率</dt>
                  <dd>141</dd>
                </div>
              </dl>
            </section>

            <div className={styles.historyLedgerWeeks}>
              {weeks.map((week) => (
                <section key={week.label} aria-label={`${week.label}跑步记录`}>
                  <header>
                    <strong>{week.label}</strong>
                    <span>{week.total} km</span>
                  </header>
                  {week.runs.map((run) => (
                    <button key={run.id} type="button" onClick={() => demo.selectRun(run)}>
                      <span>
                        {run.month}
                        {run.date} · {run.weekday}
                      </span>
                      <strong>{run.distance} km</strong>
                      <small>{run.duration}</small>
                    </button>
                  ))}
                </section>
              ))}
            </div>
          </>
        )}

        <LoadMoreButton demo={demo} />
        <HistoryNotice demo={demo} />
      </div>
    </div>
  );
}

function HistoryPreviewCanvas({
  variant,
  width,
  state,
}: {
  variant: HistoryVariantId;
  width: '393' | '320';
  state: HistoryPreviewState;
}) {
  if (variant === 'recent') return <RecentPreview width={width} state={state} />;
  if (variant === 'timeline') return <TimelinePreview width={width} state={state} />;
  return <LedgerPreview width={width} state={state} />;
}

function HistoryVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  selected,
  onSelect,
}: {
  variant: HistoryVariantDefinition;
  width: '393' | '320';
  state: HistoryPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  selected: boolean;
  onSelect: (variant: HistoryVariantId, name: string) => void;
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
            {selected ? <span className={styles.candidateBadge}>已选基线</span> : null}
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <div className={styles.previewFrame}>
        <HistoryPreviewCanvas variant={variant.id} width={width} state={state} />
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
      <dl className={styles.historySpecGrid}>
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
            selected ? `${variant.name}已选为记录列表基线` : `选择${variant.name}作为记录列表基线`
          }
          onClick={() => onSelect(variant.id, variant.name)}
        >
          {selected ? <Check size={15} weight="bold" aria-hidden="true" /> : null}
          {selected ? '已选为记录页基线' : '选作记录页基线'}
        </button>
      </div>
    </article>
  );
}

function HistoryGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="history-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="history-glossary-heading">记录页先掌握四个词</h2>
        <p>历史页的核心是找回一次跑步，并判断本次训练在时间里的位置。</p>
      </header>
      <dl className={styles.termGrid}>
        {historyTerms.map((item) => (
          <div key={item.term} className={styles.termItem}>
            <dt>{item.term}</dt>
            <dd>{item.description}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function HistoryReferenceSection({
  width,
  state,
  showRuler,
  ruler,
  onBaselineChange,
}: HistoryReferenceSectionProps) {
  const [selectedVariant, setSelectedVariant] = useState<HistoryVariantId | null>(
    readStoredVariant,
  );
  const [feedback, setFeedback] = useState(
    '尚未选定记录页基线。可切换空记录、加载更多、320px 与日夜主题再比较。',
  );

  useEffect(() => {
    const selected = historyVariants.find((variant) => variant.id === selectedVariant);
    onBaselineChange(selected ? `${selected.letter} ${selected.name}` : null);
  }, [onBaselineChange, selectedVariant]);

  const handleSelect = (variant: HistoryVariantId, name: string) => {
    setSelectedVariant(variant);
    try {
      window.localStorage.setItem(HISTORY_STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    setFeedback(`已选择“${name}”作为跑步记录页基线。`);
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="history-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="history-variants-heading">跑步记录页的三种浏览方式</h2>
          <p>
            A 先看最近一次，B 沿日期回溯，C
            先看月度总量。三案使用同一组合成记录，并共享空记录与分页加载状态。
          </p>
        </header>
        <div
          className={`${styles.variantGrid} ${styles.historyVariantGrid}`}
          data-testid="history-previews"
        >
          {historyVariants.map((variant) => (
            <HistoryVariantCard
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
      <HistoryGlossary />
    </>
  );
}
