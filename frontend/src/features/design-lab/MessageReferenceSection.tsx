import { Check, Play } from '@phosphor-icons/react';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type MessageVariantId = 'rail' | 'paper' | 'bubbles';
type MessagePreviewState = 'default' | 'focus' | 'long' | 'saving';
type DetailTab = 'logic' | 'css' | 'a11y';

interface MessageVariantDefinition {
  id: MessageVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  readingWidth: string;
  identity: string;
  bestFor: string;
}

interface MessageReferenceSectionProps {
  width: '393' | '320';
  state: MessagePreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  onBaselineChange: (name: string | null) => void;
}

const MESSAGE_STORAGE_KEY = 'healthy-agent:ui-reference:messages';
const prompt = '昨晚 5 公里轻松跑，平均心率 161，今天起床后大腿有点紧。今天怎么安排更稳妥？';
const shortAnswer = [
  '今天先以恢复为主，不急着追配速。轻微紧张时可以安排 20-30 分钟轻松慢跑，保持能完整说话的强度；如果只想休息，也不必补跑。',
  '如果出现明显疼痛、跛行或不适持续加重，先停止训练并咨询专业人士。这只是基于合成记录的训练建议，不替代诊断。',
];
const longAnswer = [
  ...shortAnswer,
  '补给上先正常喝水，训练后吃一顿包含碳水、蛋白质和蔬菜的正餐即可，不需要为了这次轻松跑额外增加补剂。',
  '下一次训练先记录主观疲劳 1-10 分、腿部紧张范围和睡眠感受。三项都比今天更稳定，再恢复节奏跑或间歇。',
  '复盘时只比较同类训练的平均心率、配速和主观感受。单次心率会受到天气、睡眠与咖啡因影响，不建议据此直接判断体能下降。',
];

const messageVariants: MessageVariantDefinition[] = [
  {
    id: 'rail',
    letter: 'A',
    name: '轨道通栏',
    summary: '用户问题压缩为上下文轨，流萤回答通栏展开，最接近 Codex 的阅读节奏。',
    readingWidth: '宽',
    identity: '细轨',
    bestFor: '长回答',
  },
  {
    id: 'paper',
    letter: 'B',
    name: '对话稿纸',
    summary: '头像固定身份，问题与回答各自落在轻量稿纸上，温度和秩序更平衡。',
    readingWidth: '中宽',
    identity: '头像',
    bestFor: '混合问答',
  },
  {
    id: 'bubbles',
    letter: 'C',
    name: '双列气泡',
    summary: '用户右、流萤左，轮次一眼可分；短问答最快，长回答的阅读感稍弱。',
    readingWidth: '中',
    identity: '气泡',
    bestFor: '短问答',
  },
];

const detailTabs: { id: DetailTab; label: string }[] = [
  { id: 'logic', label: 'React 逻辑' },
  { id: 'css', label: 'CSS' },
  { id: 'a11y', label: '无障碍' },
];

const detailCopy: Record<
  MessageVariantId,
  Record<DetailTab, { heading: string; code: string; notes: string[] }>
> = {
  rail: {
    logic: {
      heading: '同一轮问答只保留一个语义容器，状态从消息数据派生',
      code: `const state =
  item.busy ? 'streaming'
  : item.error ? 'error'
  : item.answer ? 'complete'
  : 'empty';

<article aria-label="在线任务" aria-busy={item.busy}>
  <UserContext text={item.prompt} />
  <AssistantReading
    state={state}
    answer={item.answer ?? item.draftAnswer}
  />
</article>`,
      notes: [
        '聊天气泡不是数据模型；一条在线任务仍同时包含问题、回答、来源和状态。',
        '流式回答与完成回答共用同一块阅读区，结束时只替换文字，不让整段布局跳动。',
        '操作与来源从任务对象渲染，不从可见文案反推权限或任务编号。',
      ],
    },
    css: {
      heading: '用 2px 轨道和留白分段，不用卡片堆叠对话',
      code: `.messageRailTurn {
  display: grid;
  grid-template-columns: 2px minmax(0, 1fr);
  gap: 0.75rem;
}

.messageRailTurn::before {
  content: '';
  border-radius: 999px;
  background: var(--accent);
}

.messageRailBody {
  max-width: 68ch;
  line-height: 1.75;
}`,
      notes: [
        '回答正文控制在适合中文长文阅读的宽度，来源与操作再降一级字号。',
        '身份标记使用线而不是彩色左边框，避免旧式引用块和警告语义。',
        '长回答只增加内容高度，不改变轨道、状态行或操作区宽度。',
      ],
    },
    a11y: {
      heading: '轮次是 article，正文和状态分别可被辅助技术理解',
      code: `<article aria-label="流萤的回答" aria-busy={streaming}>
  <p>{visibleAnswer}</p>
  <p role="status" aria-live="polite">
    {streaming ? '流萤正在回答' : '回答已完成'}
  </p>
</article>`,
      notes: [
        '状态使用 aria-live=polite，避免流式 token 每个字都打断读屏。',
        '回答完成后再播报一次结果状态，不依赖闪烁光标或颜色。',
        '来源披露使用原生 details/summary，键盘和读屏行为稳定。',
      ],
    },
  },
  paper: {
    logic: {
      heading: '头像只表达身份，消息内容仍由独立数据结构驱动',
      code: `const turns = items.flatMap((item) => [
  { id: \`\${item.id}-you\`, role: 'user', text: item.prompt },
  { id: \`\${item.id}-agent\`, role: 'agent', text: item.answer },
]);

turns.map((turn) =>
  turn.role === 'user'
    ? <UserPaper key={turn.id} turn={turn} />
    : <AgentPaper key={turn.id} turn={turn} />
);`,
      notes: [
        '拆分视觉轮次可以让用户问题与回答拥有不同的间距和表面，但不拆散任务状态。',
        '头像使用角色字段生成，不能根据用户姓名或本机资料拼接。',
        '流式回答仍写回原任务，不额外创建一条临时消息污染历史。',
      ],
    },
    css: {
      heading: '头像成为固定锚点，内容表面只上浮半层',
      code: `.paperTurn {
  display: grid;
  grid-template-columns: 2rem minmax(0, 1fr);
  align-items: start;
  gap: 0.65rem;
}

.paperSurface {
  border: 1px solid var(--line);
  border-radius: 1rem;
  background: color-mix(
    in srgb,
    var(--surface) 82%,
    transparent
  );
}`,
      notes: [
        '毛玻璃只用于回答主表面，头像和操作按钮保持实色，减少层层透明。',
        '用户与流萤共享 2rem 锚点列，窄屏下不会因头像宽度变化而错轴。',
        '操作行和来源行位于表面内部底部，不使用悬浮按钮遮挡正文。',
      ],
    },
    a11y: {
      heading: '视觉头像不重复朗读，角色由文本标签提供',
      code: `<span aria-hidden="true">萤</span>
<article aria-label="流萤的回答">
  <header>
    <strong>流萤</strong>
    <span>已完成</span>
  </header>
  <p>{answer}</p>
</article>`,
      notes: [
        '头像字符只是视觉锚点，article 的可访问名称已经说明发送者。',
        '时间、耗时和来源顺序固定，读屏不会先读图标再读正文。',
        '展开来源仍使用原生 details，展开状态会参与键盘顺序。',
      ],
    },
  },
  bubbles: {
    logic: {
      heading: '气泡方向由角色映射，内容和来源不重复渲染',
      code: `const align =
  message.role === 'user'
    ? 'messageBubbleUser'
    : 'messageBubbleAgent';

<div
  className={\`\${styles.messageBubble} \${styles[align]}\`}
  data-role={message.role}
>
  <MessageText message={message} />
</div>`,
      notes: [
        '角色只决定方向和表面语义，不使用内联 style 或重复 JSX。',
        '长回答与流式回答始终使用同一气泡，不切换到另一种组件。',
        '来源与状态放在气泡外，避免把权限信息伪装成消息正文。',
      ],
    },
    css: {
      heading: '两侧气泡共用圆角尺度，只改变尖角方向',
      code: `.messageBubble {
  max-width: 86%;
  padding: 0.72rem 0.82rem;
  border-radius: 1rem;
  overflow-wrap: anywhere;
}

.messageBubbleAgent {
  justify-self: start;
  border-bottom-left-radius: 0.28rem;
}

.messageBubbleUser {
  justify-self: end;
  border-bottom-right-radius: 0.28rem;
  background: var(--accent-soft);
}`,
      notes: [
        '气泡最大宽度限制在 86%，长中文仍保留左右呼吸空间。',
        '用户气泡用 accent-soft，不使用高饱和实色，避免每轮都形成视觉警报。',
        '流式光标只占一个字符宽度，不改变气泡底部与输入框位置。',
      ],
    },
    a11y: {
      heading: '方向不能是唯一身份线索，消息必须带角色名称',
      code: `<article aria-label="你的消息">...</article>
<article aria-label="流萤的回答" aria-busy={streaming}>
  <p>{answer}</p>
  <span className="sr-only" role="status">
    {streaming ? '正在回答' : '回答完成'}
  </span>
</article>`,
      notes: [
        '左右分列对读屏不可见，所以每轮都必须有角色可访问名称。',
        '状态文字放在视觉气泡之外，不干扰正文连续朗读。',
        '来源披露、播放演示和选择按钮均保持至少 44px 命中区。',
      ],
    },
  },
};

const terminology = [
  {
    term: 'turn',
    description: '对话中的一个问答轮次。视觉可以拆分问题与回答，但业务状态仍属于同一条任务。',
  },
  {
    term: 'reading width',
    description: '正文每行适合阅读的宽度。通栏不等于无限拉宽，长回答仍应限制行长。',
  },
  {
    term: 'identity anchor',
    description: '头像、轨道或标签等稳定线索，让用户快速知道谁在说话，而不是靠颜色猜。',
  },
  {
    term: 'streaming cursor',
    description: '流式输出时的位置提示。它只表达“仍在写入”，不能代替状态文字。',
  },
  {
    term: 'provenance',
    description: '来源是否可追溯。来源应放在回答之后，可展开查看，不混进正文伪装成结论。',
  },
  {
    term: 'progressive disclosure',
    description: '默认只展示正文，把来源、操作和诊断信息折叠到明确的披露控件中。',
  },
];

function readStoredVariant(): MessageVariantId | null {
  try {
    const value = window.localStorage.getItem(MESSAGE_STORAGE_KEY);
    return messageVariants.some((variant) => variant.id === value)
      ? (value as MessageVariantId)
      : null;
  } catch {
    return null;
  }
}

function AnswerContent({ long, streaming, id }: { long: boolean; streaming: boolean; id: string }) {
  const paragraphs = streaming ? shortAnswer.slice(0, 1) : long ? longAnswer : shortAnswer;
  return (
    <div className={styles.messageAnswerContent} id={id}>
      {paragraphs.map((paragraph) => (
        <p key={paragraph}>{paragraph}</p>
      ))}
      {streaming && <span className={styles.messageStreamingCaret} aria-hidden="true" />}
    </div>
  );
}

function MessageControls({
  streaming,
  onPlay,
  compact = false,
}: {
  streaming: boolean;
  onPlay: () => void;
  compact?: boolean;
}) {
  const sourceId = useId();
  return (
    <div className={`${styles.messageControls} ${compact ? styles.messageControlsCompact : ''}`}>
      <details className={styles.messageSourceDisclosure}>
        <summary aria-describedby={sourceId}>来源与操作</summary>
        <p id={sourceId}>合成演示资料，仅用于检查来源披露和操作层级，不构成医学建议。</p>
      </details>
      <button
        type="button"
        className={styles.messagePlayButton}
        disabled={streaming}
        aria-label={streaming ? '正在播放流式回答' : '播放流式回答'}
        onClick={onPlay}
      >
        {streaming ? (
          <span className={styles.messageSpinner} aria-hidden="true" />
        ) : (
          <Play size={14} weight="fill" aria-hidden="true" />
        )}
        {streaming ? '正在回答' : '演示流式'}
      </button>
    </div>
  );
}

function RailConversation({
  long,
  streaming,
  onPlay,
  statusId,
}: {
  long: boolean;
  streaming: boolean;
  onPlay: () => void;
  statusId: string;
}) {
  return (
    <div className={styles.messageRailConversation}>
      <article className={styles.messageRailUser} aria-label="你的消息">
        <span>你</span>
        <p>{prompt}</p>
      </article>
      <article
        className={styles.messageRailAssistant}
        aria-label="流萤的回答"
        aria-busy={streaming}
      >
        <header>
          <span className={styles.messageRailMark} aria-hidden="true">
            萤
          </span>
          <strong>流萤</strong>
          <span id={statusId} role="status">
            {streaming ? '正在回答' : '已完成 · 合成回答'}
          </span>
        </header>
        <div className={styles.messageRailBody}>
          <span className={styles.messageRailLine} aria-hidden="true" />
          <div>
            <AnswerContent long={long} streaming={streaming} id={`${statusId}-answer`} />
            <MessageControls streaming={streaming} onPlay={onPlay} compact />
          </div>
        </div>
      </article>
    </div>
  );
}

function PaperConversation({
  long,
  streaming,
  onPlay,
  statusId,
}: {
  long: boolean;
  streaming: boolean;
  onPlay: () => void;
  statusId: string;
}) {
  return (
    <div className={styles.messagePaperConversation}>
      <article className={styles.messagePaperTurn} aria-label="你的消息">
        <span className={styles.messagePaperAvatar} aria-hidden="true">
          你
        </span>
        <div className={styles.messagePaperUser}>
          <header>
            <strong>你</strong>
            <time>09:41</time>
          </header>
          <p>{prompt}</p>
        </div>
      </article>
      <article className={styles.messagePaperTurn} aria-label="流萤的回答" aria-busy={streaming}>
        <span
          className={`${styles.messagePaperAvatar} ${styles.messagePaperAgentAvatar}`}
          aria-hidden="true"
        >
          萤
        </span>
        <div className={styles.messagePaperAgent}>
          <header>
            <strong>流萤</strong>
            <span id={statusId} role="status">
              {streaming ? '正在回答' : '已完成'}
            </span>
          </header>
          <AnswerContent long={long} streaming={streaming} id={`${statusId}-answer`} />
          <MessageControls streaming={streaming} onPlay={onPlay} />
        </div>
      </article>
    </div>
  );
}

function BubbleConversation({
  long,
  streaming,
  onPlay,
  statusId,
}: {
  long: boolean;
  streaming: boolean;
  onPlay: () => void;
  statusId: string;
}) {
  return (
    <div className={styles.messageBubbleConversation}>
      <article
        className={`${styles.messageBubbleTurn} ${styles.messageBubbleUserTurn}`}
        aria-label="你的消息"
      >
        <span>你</span>
        <div className={`${styles.messageBubble} ${styles.messageBubbleUser}`}>{prompt}</div>
      </article>
      <article
        className={`${styles.messageBubbleTurn} ${styles.messageBubbleAgentTurn}`}
        aria-label="流萤的回答"
        aria-busy={streaming}
      >
        <span>流萤</span>
        <div className={`${styles.messageBubble} ${styles.messageBubbleAgent}`}>
          <AnswerContent long={long} streaming={streaming} id={`${statusId}-answer`} />
        </div>
        <p id={statusId} role="status">
          {streaming ? '正在回答' : '已完成 · 合成回答'}
        </p>
        <MessageControls streaming={streaming} onPlay={onPlay} compact />
      </article>
    </div>
  );
}

function MessagePreviewCanvas({
  variant,
  width,
  state,
  showRuler,
  ruler,
}: {
  variant: MessageVariantId;
  width: '393' | '320';
  state: MessagePreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
}) {
  const [streaming, setStreaming] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const statusId = useId();
  useEffect(
    () => () => {
      clearTimeout(timer.current);
    },
    [],
  );

  const startStreaming = () => {
    clearTimeout(timer.current);
    setStreaming(true);
    timer.current = setTimeout(() => setStreaming(false), 1100);
  };

  return (
    <div
      className={`${styles.demoCanvas} ${styles.messageDemoCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-message-variant={variant}
    >
      {showRuler ? ruler : null}
      <header className={styles.messageSceneHeader}>
        <span>
          <strong>跑步复盘</strong>
          <small>合成对话 · 仅检查界面</small>
        </span>
        <span>09:41</span>
      </header>
      <div className={styles.messageSceneBody}>
        {variant === 'rail' ? (
          <RailConversation
            long={state === 'long'}
            streaming={streaming}
            onPlay={startStreaming}
            statusId={statusId}
          />
        ) : variant === 'paper' ? (
          <PaperConversation
            long={state === 'long'}
            streaming={streaming}
            onPlay={startStreaming}
            statusId={statusId}
          />
        ) : (
          <BubbleConversation
            long={state === 'long'}
            streaming={streaming}
            onPlay={startStreaming}
            statusId={statusId}
          />
        )}
      </div>
    </div>
  );
}

function MessageVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  selected,
  onSelect,
  onInspect,
}: {
  variant: MessageVariantDefinition;
  width: '393' | '320';
  state: MessagePreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  selected: boolean;
  onSelect: (variant: MessageVariantId, name: string) => void;
  onInspect: (variant: MessageVariantId) => void;
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
            {selected ? <span className={styles.recommendedBadge}>已选为基线</span> : null}
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <div className={styles.previewFrame}>
        <MessagePreviewCanvas
          variant={variant.id}
          width={width}
          state={state}
          showRuler={showRuler}
          ruler={ruler}
        />
      </div>
      <dl className={styles.factGrid}>
        <div>
          <dt>阅读宽度</dt>
          <dd>{variant.readingWidth}</dd>
        </div>
        <div>
          <dt>身份锚点</dt>
          <dd>{variant.identity}</dd>
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
            selected ? `${variant.name}已选为消息流基线` : `选择${variant.name}作为消息流基线`
          }
          onClick={() => onSelect(variant.id, variant.name)}
        >
          {selected ? <Check size={15} weight="bold" aria-hidden="true" /> : null}
          {selected ? '已选为本次基线' : '选作下一版基线'}
        </button>
        <button
          type="button"
          className={styles.inspectButton}
          aria-label={`查看${variant.name}实现细节`}
          onClick={() => onInspect(variant.id)}
        >
          看实现
        </button>
      </div>
    </article>
  );
}

function MessageImplementationDetails({
  activeVariant,
  activeTab,
  onVariantChange,
  onTabChange,
}: {
  activeVariant: MessageVariantId;
  activeTab: DetailTab;
  onVariantChange: (variant: MessageVariantId) => void;
  onTabChange: (tab: DetailTab) => void;
}) {
  const tabRefs = useRef<Record<DetailTab, HTMLButtonElement | null>>({
    logic: null,
    css: null,
    a11y: null,
  });
  const activeDefinition =
    messageVariants.find((variant) => variant.id === activeVariant) ?? messageVariants[0];
  const copy = detailCopy[activeVariant][activeTab];

  const moveTabFocus = (key: string) => {
    const currentIndex = detailTabs.findIndex((tab) => tab.id === activeTab);
    let nextIndex = currentIndex;
    if (key === 'ArrowRight') nextIndex = (currentIndex + 1) % detailTabs.length;
    if (key === 'ArrowLeft') {
      nextIndex = (currentIndex - 1 + detailTabs.length) % detailTabs.length;
    }
    if (key === 'Home') nextIndex = 0;
    if (key === 'End') nextIndex = detailTabs.length - 1;
    if (nextIndex === currentIndex && !['Home', 'End'].includes(key)) return false;
    const nextTab = detailTabs[nextIndex].id;
    onTabChange(nextTab);
    tabRefs.current[nextTab]?.focus();
    return true;
  };

  return (
    <section
      id="message-implementation"
      className={styles.implementation}
      aria-labelledby="message-implementation-heading"
      tabIndex={-1}
    >
      <header className={styles.sectionHeading}>
        <h2 id="message-implementation-heading">同一轮回答，三套实现细节</h2>
        <p>先看身份和阅读节奏，再核对流式状态、来源披露、键盘焦点和长回答。</p>
      </header>
      <div className={styles.implementationControls}>
        <div className={styles.variantSwitch} role="group" aria-label="查看哪个消息流参考的实现">
          {messageVariants.map((variant) => (
            <button
              key={variant.id}
              type="button"
              aria-pressed={activeVariant === variant.id}
              onClick={() => onVariantChange(variant.id)}
            >
              <span aria-hidden="true">{variant.letter}</span>
              {variant.name}
            </button>
          ))}
        </div>
        <div className={styles.detailTabs} role="tablist" aria-label="消息流实现明细">
          {detailTabs.map((tab) => (
            <button
              key={tab.id}
              ref={(element) => {
                tabRefs.current[tab.id] = element;
              }}
              id={`message-detail-tab-${tab.id}`}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              aria-controls={`message-detail-panel-${tab.id}`}
              tabIndex={activeTab === tab.id ? 0 : -1}
              onClick={() => onTabChange(tab.id)}
              onKeyDown={(event) => {
                if (moveTabFocus(event.key)) event.preventDefault();
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>
      <div
        id={`message-detail-panel-${activeTab}`}
        className={styles.detailPanel}
        role="tabpanel"
        aria-labelledby={`message-detail-tab-${activeTab}`}
        tabIndex={0}
      >
        <div className={styles.detailIntro}>
          <span>{activeDefinition.letter}</span>
          <div>
            <p>{activeDefinition.name}</p>
            <h3>{copy.heading}</h3>
          </div>
        </div>
        <pre className={styles.codeBlock}>
          <code>{copy.code}</code>
        </pre>
        <ul className={styles.implementationNotes}>
          {copy.notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function MessageGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="message-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="message-glossary-heading">消息流再掌握六个词</h2>
        <p>这些词能帮你判断问题出在身份、阅读宽度、状态反馈，还是来源披露。</p>
      </header>
      <dl className={styles.termGrid}>
        {terminology.map((item) => (
          <div key={item.term} className={styles.termItem}>
            <dt>{item.term}</dt>
            <dd>{item.description}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function MessageReferenceSection({
  width,
  state,
  showRuler,
  ruler,
  onBaselineChange,
}: MessageReferenceSectionProps) {
  const [selectedVariant, setSelectedVariant] = useState<MessageVariantId | null>(
    () => readStoredVariant() ?? 'bubbles',
  );
  const [activeVariant, setActiveVariant] = useState<MessageVariantId>(
    () => readStoredVariant() ?? 'bubbles',
  );
  const [activeTab, setActiveTab] = useState<DetailTab>('logic');
  const [feedback, setFeedback] = useState(
    'C「双列气泡」已确认并接入正式消息流；A、B 保留为对照，三案都只使用合成问答。',
  );

  useEffect(() => {
    const selected = messageVariants.find((variant) => variant.id === selectedVariant);
    onBaselineChange(selected ? `${selected.letter} ${selected.name}` : null);
  }, [onBaselineChange, selectedVariant]);

  const handleSelect = (variant: MessageVariantId, name: string) => {
    setSelectedVariant(variant);
    setActiveVariant(variant);
    try {
      window.localStorage.setItem(MESSAGE_STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    setFeedback(
      variant === 'bubbles'
        ? 'C「双列气泡」已确认并接入正式消息流；A、B 保留为对照。'
        : `已将“${name}”记为候选；正式消息流仍采用 C「双列气泡」。`,
    );
  };

  const handleInspect = (variant: MessageVariantId) => {
    setActiveVariant(variant);
    setActiveTab('logic');
    const section = document.getElementById('message-implementation');
    try {
      section?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch {
      // jsdom 没有布局引擎；真实浏览器仍会正常滚动。
    }
    section?.focus({ preventScroll: true });
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="message-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="message-variants-heading">消息流的三种取舍</h2>
          <p>
            A 把长回答当文档读，B 用头像建立秩序，C
            用气泡让短问答最快分清双方。三案都能播放流式状态、展开来源并检查长回答。
          </p>
        </header>
        <div className={styles.variantGrid} data-testid="message-previews">
          {messageVariants.map((variant) => (
            <MessageVariantCard
              key={variant.id}
              variant={variant}
              width={width}
              state={state}
              showRuler={showRuler}
              ruler={ruler}
              selected={selectedVariant === variant.id}
              onSelect={handleSelect}
              onInspect={handleInspect}
            />
          ))}
        </div>
        <p className={styles.feedback} role="status" aria-live="polite">
          {feedback}
        </p>
      </section>
      <MessageImplementationDetails
        activeVariant={activeVariant}
        activeTab={activeTab}
        onVariantChange={setActiveVariant}
        onTabChange={setActiveTab}
      />
      <MessageGlossary />
    </>
  );
}
