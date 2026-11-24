import { ArrowRight, Check, Clock, Heartbeat, Warning } from '@phosphor-icons/react';
import { useEffect, useState, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type PostRunVariantId = 'scorecard' | 'receipt' | 'review';
type PostRunPreviewState = 'default' | 'focus' | 'long' | 'saving';

interface PostRunVariantDefinition {
  id: PostRunVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  hierarchy: string;
  theme: string;
  bestFor: string;
  stateLogic: string;
  cssFocus: string;
}

interface RunningCompleteReferenceSectionProps {
  width: '393' | '320';
  state: PostRunPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  onBaselineChange: (name: string | null) => void;
}

const POST_RUN_STORAGE_KEY = 'healthy-agent:ui-reference:running-complete-v1';

const completedRunDemo = {
  distance: '5.02',
  duration: '30:18',
  pace: '6:02',
  heartRate: '137',
  cadence: '169',
  endedAt: '10月28日 06:42',
  coach: '今天前半程更稳，最后阶段略有加速。',
};

const postRunVariants: PostRunVariantDefinition[] = [
  {
    id: 'scorecard',
    letter: 'A',
    name: '冲线成绩单',
    summary:
      '延续跑中夜色驾驶舱，距离占据最大版面，四项核心成绩紧随其后，保存与同步压到底部动作区。',
    hierarchy: '距离 → 成绩 → 保存',
    theme: '固定深色',
    bestFor: '夜跑后的快速成绩确认',
    stateLogic: '保存中禁用记录入口；同步失败只影响 Health Connect，本地记录仍可查看。',
    cssFocus: '超大等宽距离、四列成绩带、暗色动作坞。',
  },
  {
    id: 'receipt',
    letter: 'B',
    name: '训练回执',
    summary:
      '把每次跑步压缩成一张记录回执，先给出结束时间和保存结果，再用紧凑双栏展示配速与身体数据。',
    hierarchy: '保存 → 时间 → 数据',
    theme: '跟随日夜主题',
    bestFor: '跑完立刻离开、稍后回看的人',
    stateLogic: '本地保存和 Health Connect 分成两条独立状态轨；失败不伪装为已同步。',
    cssFocus: '非对称回执、状态时间线、低高度数据行。',
  },
  {
    id: 'review',
    letter: 'C',
    name: '复盘手账',
    summary:
      '把“让流萤复盘”放在成绩之前，先承接本次教练提醒，再把数据作为复盘证据，适合训练后的主动回顾。',
    hierarchy: '复盘 → 证据 → 记录',
    theme: '固定浅色',
    bestFor: '希望理解本次训练的人',
    stateLogic: '复盘入口只在记录保存后进入可用态；同步失败不阻止发起复盘。',
    cssFocus: '浅色手账画布、教练提醒区、横向数据账目。',
  },
];

const postRunTerms = [
  {
    term: 'closure',
    description: '跑步结束后的第一任务是确认“记录安全了”，成绩展示不能盖过保存结果。',
  },
  {
    term: 'provenance',
    description: '本地记录与 Health Connect 是两条独立链路，不能用同一个“成功”状态概括。',
  },
  {
    term: 'review handoff',
    description: '复盘入口应当把本次跑步作为明确上下文交给流萤，而不是打开一段空白聊天。',
  },
  {
    term: 'progressive disclosure',
    description: '先展示成绩和保存结果；完整图表、分段与详情放到下一层，不挤进完成页。',
  },
];

function readStoredVariant(): PostRunVariantId | null {
  try {
    const value = window.localStorage.getItem(POST_RUN_STORAGE_KEY);
    return postRunVariants.some((variant) => variant.id === value)
      ? (value as PostRunVariantId)
      : null;
  } catch {
    return null;
  }
}

function usePostRunDemo(state: PostRunPreviewState) {
  const [notice, setNotice] = useState('合成跑后数据；不会保存记录或触发真实同步。');
  const saving = state === 'saving';
  const syncFailed = state === 'long';

  useEffect(() => {
    setNotice(
      state === 'saving'
        ? '合成保存中：记录入口暂时不可用。'
        : state === 'long'
          ? '合成同步异常：本地记录已保存，Health Connect 未写入路线。'
          : '合成跑后数据；不会保存记录或触发真实同步。',
    );
  }, [state]);

  const announce = (label: string) => setNotice(`已触发“${label}”；正式页面会进入对应流程。`);

  return {
    saving,
    syncFailed,
    notice,
    announce,
    localStatus: saving ? '正在保存' : '已保存',
    syncStatus: saving ? '正在同步' : syncFailed ? '同步失败' : '已同步，包含路线',
  };
}

type PostRunDemo = ReturnType<typeof usePostRunDemo>;

function StatusMark({ state }: { state: 'ready' | 'loading' | 'warning' }) {
  if (state === 'warning') return <Warning size={15} weight="fill" aria-hidden="true" />;
  if (state === 'loading') return <Clock size={15} weight="bold" aria-hidden="true" />;
  return <Check size={15} weight="bold" aria-hidden="true" />;
}

function PostRunActions({
  demo,
  compact = false,
  hideReview = false,
}: {
  demo: PostRunDemo;
  compact?: boolean;
  hideReview?: boolean;
}) {
  return (
    <div className={styles.postRunActions} data-compact={compact || undefined}>
      {!hideReview && (
        <button
          type="button"
          className={styles.postRunReviewAction}
          onClick={() => demo.announce('让流萤复盘本次跑步')}
          disabled={demo.saving}
        >
          让流萤复盘本次跑步
          <ArrowRight size={16} weight="bold" aria-hidden="true" />
        </button>
      )}
      <div>
        <button type="button" onClick={() => demo.announce('查看本次总结')} disabled={demo.saving}>
          查看本次总结
        </button>
        <button type="button" onClick={() => demo.announce('查看记录')} disabled={demo.saving}>
          查看记录
        </button>
        <button type="button" onClick={() => demo.announce('再跑一次')} disabled={demo.saving}>
          再跑一次
        </button>
      </div>
    </div>
  );
}

function PostRunToast({ demo }: { demo: PostRunDemo }) {
  return (
    <p
      className={styles.postRunNotice}
      data-tone={demo.syncFailed ? 'warning' : 'default'}
      role={demo.syncFailed ? 'alert' : 'status'}
    >
      {demo.notice}
    </p>
  );
}

function ScorecardPreview({ width, state }: { width: '393' | '320'; state: PostRunPreviewState }) {
  const demo = usePostRunDemo(state);

  return (
    <div
      className={`${styles.demoCanvas} ${styles.postRunDemoCanvas} ${styles.postRunScorecardCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-post-run-variant="scorecard"
      aria-label="冲线成绩单合成跑后预览"
    >
      <div className={styles.postRunScorecard}>
        <header className={styles.postRunDarkHeader}>
          <span>
            <Check size={15} weight="bold" aria-hidden="true" />
            跑步完成
          </span>
          <time>{completedRunDemo.endedAt}</time>
        </header>

        <section className={styles.postRunDistanceHero}>
          <span>本次距离</span>
          <div>
            <strong>{completedRunDemo.distance}</strong>
            <small>公里</small>
          </div>
        </section>

        <dl className={styles.postRunScoreGrid}>
          <div>
            <dt>用时</dt>
            <dd>{completedRunDemo.duration}</dd>
          </div>
          <div>
            <dt>平均配速</dt>
            <dd>{completedRunDemo.pace}</dd>
          </div>
          <div>
            <dt>平均心率</dt>
            <dd>
              {completedRunDemo.heartRate}
              <small> bpm</small>
            </dd>
          </div>
          <div>
            <dt>平均步频</dt>
            <dd>
              {completedRunDemo.cadence}
              <small> spm</small>
            </dd>
          </div>
        </dl>

        <div className={styles.postRunSyncRail}>
          <span data-tone={demo.saving ? 'loading' : 'ready'}>
            <StatusMark state={demo.saving ? 'loading' : 'ready'} />
            本地记录 {demo.localStatus}
          </span>
          <span data-tone={demo.syncFailed ? 'warning' : demo.saving ? 'loading' : 'ready'}>
            <StatusMark state={demo.syncFailed ? 'warning' : demo.saving ? 'loading' : 'ready'} />
            Health Connect {demo.syncStatus}
          </span>
        </div>

        <aside className={styles.postRunCoachStrip}>
          <Heartbeat size={17} weight="fill" aria-hidden="true" />
          <p>{completedRunDemo.coach}</p>
        </aside>

        <PostRunToast demo={demo} />
        <PostRunActions demo={demo} />
      </div>
    </div>
  );
}

function ReceiptPreview({ width, state }: { width: '393' | '320'; state: PostRunPreviewState }) {
  const demo = usePostRunDemo(state);

  return (
    <div
      className={`${styles.demoCanvas} ${styles.postRunDemoCanvas} ${styles.postRunReceiptCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-post-run-variant="receipt"
      aria-label="训练回执合成跑后预览"
    >
      <div className={styles.postRunReceipt}>
        <header className={styles.postRunReceiptHeader}>
          <div>
            <span>训练回执</span>
            <time>{completedRunDemo.endedAt}</time>
          </div>
          <span className={styles.postRunSavedBadge} data-tone={demo.saving ? 'loading' : 'ready'}>
            <StatusMark state={demo.saving ? 'loading' : 'ready'} />
            {demo.localStatus}
          </span>
        </header>

        <div className={styles.postRunReceiptMain}>
          <div>
            <span>距离</span>
            <strong>{completedRunDemo.distance}</strong>
            <small>KM</small>
          </div>
          <dl>
            <div>
              <dt>用时</dt>
              <dd>{completedRunDemo.duration}</dd>
            </div>
            <div>
              <dt>平均配速</dt>
              <dd>{completedRunDemo.pace}</dd>
            </div>
          </dl>
        </div>

        <div className={styles.postRunReceiptBody}>
          <div>
            <span>平均心率</span>
            <strong>{completedRunDemo.heartRate} bpm</strong>
          </div>
          <div>
            <span>平均步频</span>
            <strong>{completedRunDemo.cadence} spm</strong>
          </div>
        </div>

        <dl className={styles.postRunStateList}>
          <div>
            <dt>本地记录</dt>
            <dd data-tone={demo.saving ? 'loading' : 'ready'}>
              <StatusMark state={demo.saving ? 'loading' : 'ready'} />
              {demo.localStatus}
            </dd>
          </div>
          <div>
            <dt>Health Connect</dt>
            <dd data-tone={demo.syncFailed ? 'warning' : demo.saving ? 'loading' : 'ready'}>
              <StatusMark state={demo.syncFailed ? 'warning' : demo.saving ? 'loading' : 'ready'} />
              {demo.syncStatus}
            </dd>
          </div>
        </dl>

        <aside className={styles.postRunReceiptCoach}>
          <span>本次提醒</span>
          <p>{completedRunDemo.coach}</p>
        </aside>

        <PostRunToast demo={demo} />
        <PostRunActions demo={demo} compact />
      </div>
    </div>
  );
}

function ReviewPreview({ width, state }: { width: '393' | '320'; state: PostRunPreviewState }) {
  const demo = usePostRunDemo(state);

  return (
    <div
      className={`${styles.demoCanvas} ${styles.postRunDemoCanvas} ${styles.postRunReviewCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-post-run-variant="review"
      aria-label="复盘手账合成跑后预览"
    >
      <div className={styles.postRunReview}>
        <header className={styles.postRunReviewHeader}>
          <div>
            <span>10月28日 · 晨跑</span>
            <h4>把这次跑步留下来</h4>
          </div>
          <span className={styles.postRunReviewMark}>
            <Check size={15} weight="bold" aria-hidden="true" />
            已完成
          </span>
        </header>

        <section className={styles.postRunReviewInvite}>
          <span>流萤复盘</span>
          <p>把这次跑步的距离、配速和心率交给流萤，形成一段可以继续追问的训练回顾。</p>
          <button
            type="button"
            onClick={() => demo.announce('让流萤复盘本次跑步')}
            disabled={demo.saving}
          >
            让流萤复盘本次跑步
            <ArrowRight size={16} weight="bold" aria-hidden="true" />
          </button>
        </section>

        <dl className={styles.postRunReviewLedger}>
          <div>
            <dt>距离</dt>
            <dd>
              {completedRunDemo.distance}
              <small> km</small>
            </dd>
          </div>
          <div>
            <dt>用时</dt>
            <dd>{completedRunDemo.duration}</dd>
          </div>
          <div>
            <dt>平均配速</dt>
            <dd>{completedRunDemo.pace}</dd>
          </div>
          <div>
            <dt>平均心率</dt>
            <dd>
              {completedRunDemo.heartRate}
              <small> bpm</small>
            </dd>
          </div>
          <div>
            <dt>平均步频</dt>
            <dd>
              {completedRunDemo.cadence}
              <small> spm</small>
            </dd>
          </div>
        </dl>

        <div className={styles.postRunReviewCoach}>
          <span>本次提醒</span>
          <p>{completedRunDemo.coach}</p>
        </div>

        <div className={styles.postRunReviewStatus}>
          <span data-tone={demo.saving ? 'loading' : 'ready'}>
            <StatusMark state={demo.saving ? 'loading' : 'ready'} />
            {demo.localStatus}
          </span>
          <span data-tone={demo.syncFailed ? 'warning' : demo.saving ? 'loading' : 'ready'}>
            <StatusMark state={demo.syncFailed ? 'warning' : demo.saving ? 'loading' : 'ready'} />
            {demo.syncStatus}
          </span>
        </div>

        <PostRunToast demo={demo} />
        <PostRunActions demo={demo} compact hideReview />
      </div>
    </div>
  );
}

function PostRunPreviewCanvas({
  variant,
  width,
  state,
}: {
  variant: PostRunVariantId;
  width: '393' | '320';
  state: PostRunPreviewState;
}) {
  if (variant === 'scorecard') return <ScorecardPreview width={width} state={state} />;
  if (variant === 'receipt') return <ReceiptPreview width={width} state={state} />;
  return <ReviewPreview width={width} state={state} />;
}

function PostRunVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  selected,
  onSelect,
}: {
  variant: PostRunVariantDefinition;
  width: '393' | '320';
  state: PostRunPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  selected: boolean;
  onSelect: (variant: PostRunVariantId, name: string) => void;
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
        <PostRunPreviewCanvas variant={variant.id} width={width} state={state} />
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
      <dl className={styles.postRunSpecGrid}>
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
            selected ? `${variant.name}已选为跑后页面基线` : `选择${variant.name}作为跑后页面基线`
          }
          onClick={() => onSelect(variant.id, variant.name)}
        >
          {selected ? <Check size={15} weight="bold" aria-hidden="true" /> : null}
          {selected ? '已选为跑后页基线' : '选作跑后页基线'}
        </button>
      </div>
    </article>
  );
}

function PostRunGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="post-run-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="post-run-glossary-heading">跑后页先掌握四个词</h2>
        <p>完成页的任务不是再讲一遍跑步，而是让用户安心离开、保存结果或进入下一层复盘。</p>
      </header>
      <dl className={styles.termGrid}>
        {postRunTerms.map((item) => (
          <div key={item.term} className={styles.termItem}>
            <dt>{item.term}</dt>
            <dd>{item.description}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function RunningCompleteReferenceSection({
  width,
  state,
  showRuler,
  ruler,
  onBaselineChange,
}: RunningCompleteReferenceSectionProps) {
  const [selectedVariant, setSelectedVariant] = useState<PostRunVariantId | null>(
    () => readStoredVariant() ?? 'scorecard',
  );
  const [feedback, setFeedback] = useState(
    'A 冲线成绩单已作为正式跑后页基线接入；B 训练回执和 C 复盘手账保留为对比方案。',
  );

  useEffect(() => {
    const selected = postRunVariants.find((variant) => variant.id === selectedVariant);
    onBaselineChange(selected ? `${selected.letter} ${selected.name}` : null);
  }, [onBaselineChange, selectedVariant]);

  const handleSelect = (variant: PostRunVariantId, name: string) => {
    setSelectedVariant(variant);
    try {
      window.localStorage.setItem(POST_RUN_STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    setFeedback(
      variant === 'scorecard'
        ? 'A 冲线成绩单已作为正式跑后页基线。'
        : `已将“${name}”记为对比候选；正式跑后页仍为 A 冲线成绩单。`,
    );
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="post-run-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="post-run-variants-heading">跑后页的三种收尾方式</h2>
          <p>
            A 先看成绩，B 先确认记录，C 先进入复盘。三案使用同一组合成数据和保存、Health Connect
            状态，不使用契约里不存在的卡路里或分段数据。
          </p>
        </header>
        <div
          className={`${styles.variantGrid} ${styles.postRunVariantGrid}`}
          data-testid="post-run-previews"
        >
          {postRunVariants.map((variant) => (
            <PostRunVariantCard
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
      <PostRunGlossary />
    </>
  );
}
