import {
  ArrowClockwise,
  Brain,
  Check,
  CheckCircle,
  CircleNotch,
  Cpu,
  MapPin,
  Microphone,
  Paperclip,
  PaperPlaneRight,
  Plus,
  Sparkle,
  Stop,
  WarningCircle,
  X,
} from '@phosphor-icons/react';
import {
  useId,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type ComposerVariantId = 'dock' | 'task' | 'stack';
type PreviewWidth = '393' | '320';
type PreviewState = 'default' | 'focus' | 'long' | 'saving';
type DetailTab = 'logic' | 'css' | 'a11y';
type SendState = 'idle' | 'sending' | 'success' | 'error';
type ContextMode = 'run' | 'memory';

interface ComposerVariantDefinition {
  id: ComposerVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  discovery: string;
  density: string;
  narrowScreen: string;
}

interface ComposerReferenceSectionProps {
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  ruler: ReactNode;
  onBaselineChange: (baseline: string | null) => void;
}

const COMPOSER_STORAGE_KEY = 'healthy-agent:ui-reference:composer';
const SEND_DEMO_DELAY_MS = 900;
const LONG_DRAFT = '帮我把昨晚的轻松跑整理成今天的恢复建议，并参考我常用的心率区间。';

const composerVariants: ComposerVariantDefinition[] = [
  {
    id: 'dock',
    letter: 'A',
    name: 'Codex 单框悬浮坞',
    summary: '输入、状态和动作收进同一个玻璃框，纵向最省，最接近 Codex 的连续工作感。',
    discovery: '中等',
    density: '最高',
    narrowScreen: '稳定',
  },
  {
    id: 'task',
    letter: 'B',
    name: '双行任务稿纸',
    summary: '跑步与记忆上下文独占一行，任务边界最清楚，适合多来源训练对话。',
    discovery: '强',
    density: '中等',
    narrowScreen: '稳定',
  },
  {
    id: 'stack',
    letter: 'C',
    name: '上下文堆栈卡',
    summary: '本机、在线 Agent、跑步和记忆全部可见，信息最完整但纵向占用最大。',
    discovery: '最强',
    density: '偏低',
    narrowScreen: '注意换行',
  },
];

const detailTabs: { id: DetailTab; label: string }[] = [
  { id: 'logic', label: 'React 逻辑' },
  { id: 'css', label: 'CSS' },
  { id: 'a11y', label: '无障碍' },
];

const detailCopy: Record<
  ComposerVariantId,
  Record<DetailTab, { heading: string; code: string; notes: string[] }>
> = {
  dock: {
    logic: {
      heading: 'Enter 发送，Shift + Enter 换行，组合输入期间不抢回车',
      code: `function handleKeyDown(
  event: KeyboardEvent<HTMLTextAreaElement>,
) {
  const composing = event.nativeEvent.isComposing;
  if (event.key !== 'Enter' || event.shiftKey || composing) return;

  event.preventDefault();
  sendMessage();
}`,
      notes: [
        '中文、日文和韩文输入法确认候选词时也会产生 Enter，必须检查 isComposing。',
        '发送中锁定重复提交，但保留错误后的原文，让用户能直接重试。',
        '参考台使用计时器模拟请求，不调用 Bridge、模型或真实会话。',
      ],
    },
    css: {
      heading: '一个外框承载输入与工具，内部只用一条分隔线',
      code: `.composerDock {
  padding: 0.55rem;
  border: 1px solid var(--line);
  border-radius: 1.2rem;
  background: var(--glass-strong);
  backdrop-filter: blur(var(--blur-glass)) saturate(1.15);
}

.textarea {
  width: 100%;
  min-height: 2.75rem;
  max-height: 7.75rem;
  resize: none;
  border: 0;
  background: transparent;
}`,
      notes: [
        'textarea 自动增高到六行后转为内部滚动，不无限挤压聊天记录。',
        '工具按钮与发送按钮固定 44px 高；状态文字变化不改变底栏几何。',
        '毛玻璃只在外表面做一次，内部输入区不重复叠加滤镜。',
      ],
    },
    a11y: {
      heading: '输入框有稳定名称，发送状态有实时播报',
      code: `<label className="sr-only" htmlFor={inputId}>
  给流萤的消息
</label>
<textarea
  id={inputId}
  rows={1}
  aria-describedby={statusId}
/>
<p id={statusId} role="status">
  {sendStateText}
</p>`,
      notes: [
        '空内容时发送按钮禁用，但输入框仍保留清晰的焦点环。',
        '发送、发送中、成功与失败都同时提供文字，不只用图标或颜色。',
        '图标按钮都有动作名称；装饰图标全部 aria-hidden。',
      ],
    },
  },
  task: {
    logic: {
      heading: '上下文是可切换的任务状态，移除摘要不是清空整段输入',
      code: `const [context, setContext] =
  useState<'run' | 'memory'>('run');
const [runAttached, setRunAttached] = useState(true);

<div role="group" aria-label="选择本次上下文">
  <button aria-pressed={context === 'run'}>跑步</button>
  <button aria-pressed={context === 'memory'}>记忆</button>
</div>
{runAttached
  ? <RunSummary onRemove={() => setRunAttached(false)} />
  : <button onClick={() => setRunAttached(true)}>恢复</button>}`,
      notes: [
        '切换上下文只影响下一次发送所引用的资料，不重写用户已经输入的文字。',
        '移除跑步摘要后仍提供恢复入口，避免一次误触造成不可逆状态。',
        '正式接入时应把上下文编号作为请求的一部分，而不是从可见文案反推。',
      ],
    },
    css: {
      heading: '任务栏与输入区同属一张稿纸，用分隔线建立两行节奏',
      code: `.task {
  border: 1px solid var(--line);
  border-radius: 1.08rem;
  background: var(--glass-strong);
}

.taskContext {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 0.5rem;
  padding: 0.5rem;
  border-bottom: 1px solid var(--line);
}

@container composer-preview (max-width: 20rem) {
  .taskContext {
    grid-template-columns: 1fr;
  }
}`,
      notes: [
        '上下文行在 320px 下改为上下排列，标题和移除按钮不会互相挤压。',
        '模式按钮保持 44px 触控高度，选中态不改变宽度与字重。',
        '摘要卡只做一层浅表面，避免在输入框里形成第二层卡片。',
      ],
    },
    a11y: {
      heading: '上下文切换使用 aria-pressed，摘要移除使用完整动作名',
      code: `<div role="group" aria-label="选择本次上下文">
  <button aria-label="切换到跑步上下文" aria-pressed="true">
    跑步
  </button>
  <button aria-label="切换到记忆上下文" aria-pressed="false">
    记忆
  </button>
</div>
<button aria-label="移除跑步摘要">
  <X aria-hidden="true" />
</button>`,
      notes: [
        '按钮可访问名称包含“切换”或“移除”，屏幕阅读器无需猜测图标含义。',
        'Context chip 的正文和按钮不嵌套交互控件，点击区域互不重叠。',
        '移除摘要后通过 role=status 朗读当前状态，而不是静默消失。',
      ],
    },
  },
  stack: {
    logic: {
      heading: '上下文项目由数据映射，记忆引用是可撤销的独立开关',
      code: `const [memoryEnabled, setMemoryEnabled] = useState(true);
const contextItems = [
  { id: 'local', label: '本机', enabled: true },
  { id: 'agent', label: '在线 Agent', enabled: true },
  { id: 'memory', label: '记忆', enabled: memoryEnabled },
];

<button
  aria-label={memoryEnabled ? '关闭记忆引用' : '开启记忆引用'}
  aria-pressed={memoryEnabled}
  onClick={() => setMemoryEnabled((enabled) => !enabled)}
/>`,
      notes: [
        '堆栈展示的是这次请求会使用的上下文，不是永久账户设置。',
        '记忆开关只改变当前演示引用，不读写本机长期记忆。',
        '上下文项从数组渲染，后续增加位置或知识库时不复制整段 JSX。',
      ],
    },
    css: {
      heading: '堆栈卡依靠紧凑 chip 和固定间距控制信息密度',
      code: `.contextChips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.32rem;
}

.contextChip {
  display: inline-flex;
  min-height: 2.75rem;
  align-items: center;
  gap: 0.28rem;
  padding: 0 0.5rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--canvas-raised);
}

.contextChip[aria-pressed='true'] {
  border-color: var(--accent);
  color: var(--accent);
}`,
      notes: [
        'chip 允许换行但不横向溢出，320px 下优先保证完整文字。',
        '关闭态同时降低背景对比度并改变 aria-pressed，不只依赖颜色。',
        '工具菜单使用绝对定位浮在输入区上方，不推挤聊天内容。',
      ],
    },
    a11y: {
      heading: '上下文 chip 是状态，不是装饰标签',
      code: `<button
  type="button"
  aria-label="关闭记忆引用"
  aria-pressed="true"
  onClick={toggleMemory}
>
  <Brain aria-hidden="true" />
  轻松跑目标心率 145
</button>`,
      notes: [
        '可切换 chip 使用 aria-pressed，可读且可键盘操作。',
        '不可切换的本机与在线 Agent 使用普通文本，不伪装成按钮。',
        '菜单打开时 aria-expanded 为 true，Esc 可关闭并把后续焦点交还触发按钮。',
      ],
    },
  },
};

const terminology = [
  {
    term: 'Composer',
    description: '聊天产品对“输入区”的专业称呼，通常包含文本、附件、语音、上下文和发送状态。',
  },
  {
    term: 'IME 组合态',
    description: '中文输入法尚未确认候选词的状态。此时按 Enter 是确认文字，不应该发送消息。',
  },
  {
    term: '自动增高',
    description: '输入内容变多时 textarea 长高，达到上限后内部滚动，避免吃掉整段聊天记录。',
  },
  {
    term: '渐进披露',
    description: '常用动作直接显示，低频工具收进菜单。降低首屏噪音，但不隐藏核心能力。',
  },
  {
    term: '待发送态',
    description: '请求已经发出、结果尚未返回的中间状态。需要阻止重复提交并持续给出反馈。',
  },
  {
    term: '安全区',
    description: '为手机底部手势条与圆角预留的空间，通常用 env(safe-area-inset-bottom) 处理。',
  },
];

function readStoredVariant(): ComposerVariantId | null {
  try {
    const value = window.localStorage.getItem(COMPOSER_STORAGE_KEY);
    return composerVariants.some((variant) => variant.id === value)
      ? (value as ComposerVariantId)
      : null;
  } catch {
    return null;
  }
}

function useComposerDemo(previewState: PreviewState, variantLabel: string) {
  const [draft, setDraft] = useState(previewState === 'long' ? LONG_DRAFT : '');
  const [sendState, setSendState] = useState<SendState>('idle');
  const [voiceListening, setVoiceListening] = useState(false);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [failNext, setFailNext] = useState(false);
  const [message, setMessage] = useState(`${variantLabel}只演示本地状态，不发送真实消息。`);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const toolsButtonRef = useRef<HTMLButtonElement>(null);

  const clearSendTimer = () => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  const announce = (nextMessage: string) => {
    setMessage(nextMessage);
  };

  const handleDraftChange = (nextDraft: string) => {
    setDraft(nextDraft);
    if (sendState !== 'sending') {
      setSendState('idle');
      setMessage('正在编辑，按 Enter 发送，Shift + Enter 换行。');
    }
  };

  const handleSend = () => {
    const payload = draft.trim();
    if (!payload || sendState === 'sending') return;

    const shouldFail = failNext;
    clearSendTimer();
    setToolsOpen(false);
    setVoiceListening(false);
    setSendState('sending');
    setMessage('正在交给在线 Agent，演示中阻止重复提交…');

    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      setFailNext(false);

      if (shouldFail) {
        setSendState('error');
        setMessage('发送失败：原文已保留，可以直接点击“重试”。');
        return;
      }

      setDraft('');
      setSendState('success');
      setMessage('已发送到演示会话；没有调用真实模型或写入用户数据。');
    }, SEND_DEMO_DELAY_MS);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    handleSend();
  };

  const toggleVoice = () => {
    if (sendState === 'sending') return;
    setVoiceListening((listening) => {
      const next = !listening;
      setMessage(
        next
          ? '语音输入演示已开启（不会录音）。再次点击即可结束。'
          : '语音输入演示已结束，没有访问麦克风。',
      );
      return next;
    });
  };

  const toggleTools = () => {
    if (sendState === 'sending') return;
    setToolsOpen((open) => !open);
  };

  const closeTools = () => setToolsOpen(false);

  const chooseTool = (nextMessage: string) => {
    setToolsOpen(false);
    setMessage(nextMessage);
  };

  const prepareFailure = () => {
    setFailNext(true);
    setToolsOpen(false);
    setMessage('已准备一次失败演示；下一条消息会进入可重试的错误态。');
  };

  const handleToolsKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Escape' || !toolsOpen) return;
    event.preventDefault();
    closeTools();
    toolsButtonRef.current?.focus();
  };

  useLayoutEffect(
    () => () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    },
    [],
  );

  return {
    draft,
    sendState,
    voiceListening,
    toolsOpen,
    message,
    toolsButtonRef,
    handleDraftChange,
    handleSend,
    handleKeyDown,
    toggleVoice,
    toggleTools,
    closeTools,
    chooseTool,
    prepareFailure,
    handleToolsKeyDown,
    announce,
  };
}

type ComposerDemo = ReturnType<typeof useComposerDemo>;

interface AutoGrowTextareaProps {
  id: string;
  describedBy: string;
  value: string;
  disabled: boolean;
  forceFocus: boolean;
  onChange: (event: ChangeEvent<HTMLTextAreaElement>) => void;
  onKeyDown: (event: KeyboardEvent<HTMLTextAreaElement>) => void;
}

function AutoGrowTextarea({
  id,
  describedBy,
  value,
  disabled,
  forceFocus,
  onChange,
  onKeyDown,
}: AutoGrowTextareaProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea || textarea.scrollHeight <= 0) return;

    textarea.style.height = 'auto';
    const maxHeight = Number.parseFloat(window.getComputedStyle(textarea).maxHeight);
    const nextHeight = Number.isFinite(maxHeight)
      ? Math.min(textarea.scrollHeight, maxHeight)
      : textarea.scrollHeight;
    textarea.style.height = `${nextHeight}px`;
  }, [value]);

  return (
    <>
      <label className="sr-only" htmlFor={id}>
        给流萤的消息
      </label>
      <textarea
        ref={textareaRef}
        id={id}
        className={`${styles.composerTextarea} ${forceFocus ? styles.forceFocus : ''}`}
        rows={1}
        value={value}
        placeholder="和流萤聊聊今天的训练…"
        name={`composer-${id}`}
        autoComplete="off"
        maxLength={2000}
        disabled={disabled}
        aria-describedby={describedBy}
        data-auto-grow="true"
        onChange={onChange}
        onKeyDown={onKeyDown}
      />
    </>
  );
}

function ComposerStatusLine({ demo, id }: { demo: ComposerDemo; id: string }) {
  const StateIcon =
    demo.sendState === 'sending'
      ? CircleNotch
      : demo.sendState === 'success'
        ? CheckCircle
        : demo.sendState === 'error'
          ? WarningCircle
          : Sparkle;

  return (
    <div
      id={id}
      className={styles.composerStatusLine}
      data-state={demo.sendState}
      role="status"
      aria-live="polite"
    >
      <StateIcon
        className={demo.sendState === 'sending' ? styles.composerSpinner : ''}
        size={13}
        weight={demo.sendState === 'success' ? 'fill' : 'bold'}
        aria-hidden="true"
      />
      <span>{demo.message}</span>
    </div>
  );
}

interface ToolsMenuProps {
  demo: ComposerDemo;
  variantName: string;
  id: string;
}

function ToolsMenu({ demo, variantName, id }: ToolsMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!demo.toolsOpen) return;
    menuRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
  }, [demo.toolsOpen]);

  const handleMenuKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(
      menuRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [],
    );
    if (items.length === 0) return;

    const currentIndex = items.indexOf(document.activeElement as HTMLButtonElement);
    let nextIndex = currentIndex;
    if (event.key === 'ArrowDown') nextIndex = (currentIndex + 1) % items.length;
    if (event.key === 'ArrowUp') nextIndex = (currentIndex - 1 + items.length) % items.length;
    if (event.key === 'Home') nextIndex = 0;
    if (event.key === 'End') nextIndex = items.length - 1;
    if (nextIndex === currentIndex && !['Home', 'End'].includes(event.key)) return;

    event.preventDefault();
    items[nextIndex]?.focus();
  };

  if (!demo.toolsOpen) return null;

  return (
    <div
      ref={menuRef}
      id={id}
      className={styles.composerToolMenu}
      role="menu"
      aria-label={`${variantName}输入工具`}
      onKeyDown={handleMenuKeyDown}
    >
      <div className={styles.composerToolMenuHeading}>
        <strong>输入工具</strong>
        <small>仅演示</small>
      </div>
      <button
        type="button"
        role="menuitem"
        onClick={() => demo.chooseTool('已选择跑步摘要来源；不会读取真实运动记录。')}
      >
        <MapPin size={16} weight="regular" aria-hidden="true" />
        引用跑步摘要
      </button>
      <button
        type="button"
        role="menuitem"
        onClick={() => demo.chooseTool('已准备记忆引用：轻松跑目标心率 145（合成数据）。')}
      >
        <Brain size={16} weight="regular" aria-hidden="true" />
        引用长期记忆
      </button>
      <button
        type="button"
        role="menuitem"
        onClick={() => demo.chooseTool('已打开附件选择演示；没有读取本机文件。')}
      >
        <Paperclip size={16} weight="regular" aria-hidden="true" />
        添加训练附件
      </button>
      <button type="button" role="menuitem" onClick={demo.prepareFailure}>
        <WarningCircle size={16} weight="regular" aria-hidden="true" />
        模拟一次发送失败
      </button>
    </div>
  );
}

function ComposerSendButton({ demo }: { demo: ComposerDemo }) {
  const disabled = demo.sendState === 'sending' || !demo.draft.trim();
  const label =
    demo.sendState === 'sending'
      ? '发送中'
      : demo.sendState === 'error'
        ? '重试'
        : demo.sendState === 'success'
          ? '完成'
          : '发送';
  const accessibleLabel =
    demo.sendState === 'sending'
      ? '正在发送消息'
      : demo.sendState === 'error'
        ? '重试发送'
        : demo.sendState === 'success'
          ? '消息已发送'
          : '发送消息';
  const SendIcon =
    demo.sendState === 'sending'
      ? CircleNotch
      : demo.sendState === 'error'
        ? ArrowClockwise
        : demo.sendState === 'success'
          ? CheckCircle
          : PaperPlaneRight;

  return (
    <button
      type="button"
      className={styles.composerSendButton}
      data-state={demo.sendState}
      aria-label={accessibleLabel}
      aria-busy={demo.sendState === 'sending'}
      disabled={disabled}
      onClick={demo.handleSend}
    >
      <SendIcon
        className={demo.sendState === 'sending' ? styles.composerSpinner : ''}
        size={16}
        weight={demo.sendState === 'success' ? 'fill' : 'bold'}
        aria-hidden="true"
      />
      <span>{label}</span>
    </button>
  );
}

interface ComposerFooterProps {
  demo: ComposerDemo;
  variantName: string;
  authorityLabel: string;
}

function ComposerFooter({ demo, variantName, authorityLabel }: ComposerFooterProps) {
  const menuId = useId();

  return (
    <div className={styles.composerFooter} onKeyDown={demo.handleToolsKeyDown}>
      <div className={styles.composerFooterLeading}>
        <div className={styles.composerToolsWrap}>
          <button
            ref={demo.toolsButtonRef}
            type="button"
            className={styles.composerIconButton}
            aria-label="打开输入工具"
            title="输入工具"
            aria-haspopup="menu"
            aria-expanded={demo.toolsOpen}
            aria-controls={demo.toolsOpen ? menuId : undefined}
            disabled={demo.sendState === 'sending'}
            onClick={demo.toggleTools}
          >
            <Plus size={20} weight="regular" aria-hidden="true" />
          </button>
          <ToolsMenu demo={demo} variantName={variantName} id={menuId} />
        </div>
        <button
          type="button"
          className={`${styles.composerIconButton} ${
            demo.voiceListening ? styles.composerListening : ''
          }`}
          aria-label={demo.voiceListening ? '结束语音输入演示' : '开始语音输入演示'}
          aria-pressed={demo.voiceListening}
          title={demo.voiceListening ? '结束语音演示' : '语音输入演示'}
          disabled={demo.sendState === 'sending'}
          onClick={demo.toggleVoice}
        >
          {demo.voiceListening ? (
            <Stop size={18} weight="fill" aria-hidden="true" />
          ) : (
            <Microphone size={18} weight="regular" aria-hidden="true" />
          )}
        </button>
        <span className={styles.composerAuthority} title={authorityLabel}>
          <Cpu size={13} weight="regular" aria-hidden="true" />
          <span className={styles.composerAuthorityText}>{authorityLabel}</span>
        </span>
      </div>
      <ComposerSendButton demo={demo} />
    </div>
  );
}

function ComposerScene({ variant }: { variant: ComposerVariantId }) {
  return (
    <div className={styles.composerScene} data-variant={variant} aria-hidden="true">
      <div className={styles.composerSceneHeader}>
        <span className={styles.composerSceneAvatar}>萤</span>
        <span>
          <strong>流萤</strong>
          <small>训练助手 · 在线</small>
        </span>
        <time>07:42</time>
      </div>
      <div className={styles.composerSceneMessage}>
        <small>昨晚训练摘要</small>
        <p>
          轻松跑 <strong>5.20 km</strong>，用时 <strong>31:42</strong>，平均配速{' '}
          <strong>6'06"</strong>。
        </p>
        <span>今天以恢复训练为主，注意把心率保持在目标区间内。</span>
      </div>
    </div>
  );
}

function RunContextChip({
  onRemove,
  compact = false,
}: {
  onRemove: () => void;
  compact?: boolean;
}) {
  return (
    <div className={`${styles.composerRunChip} ${compact ? styles.composerChipCompact : ''}`}>
      <MapPin size={14} weight="regular" aria-hidden="true" />
      <span>
        <strong>昨晚轻松跑</strong>
        <small>5.20 km · 6'06" / km</small>
      </span>
      <button type="button" aria-label="移除跑步摘要" onClick={onRemove}>
        <X size={13} weight="bold" aria-hidden="true" />
      </button>
    </div>
  );
}

function DockComposer({ previewState }: { previewState: PreviewState }) {
  const demo = useComposerDemo(previewState, '单框悬浮坞');
  const inputId = useId();
  const statusId = useId();
  const forceFocus = previewState === 'focus';

  return (
    <div className={styles.composerLayout}>
      <ComposerScene variant="dock" />
      <section className={styles.composerDock} aria-label="单框悬浮坞聊天输入区">
        <AutoGrowTextarea
          id={inputId}
          describedBy={statusId}
          value={demo.draft}
          disabled={demo.sendState === 'sending'}
          forceFocus={forceFocus}
          onChange={(event) => demo.handleDraftChange(event.target.value)}
          onKeyDown={demo.handleKeyDown}
        />
        <ComposerStatusLine demo={demo} id={statusId} />
        <ComposerFooter demo={demo} variantName="单框悬浮坞" authorityLabel="本机" />
      </section>
    </div>
  );
}

function TaskComposer({ previewState }: { previewState: PreviewState }) {
  const demo = useComposerDemo(previewState, '双行任务稿纸');
  const [contextMode, setContextMode] = useState<ContextMode>('run');
  const [runAttached, setRunAttached] = useState(true);
  const inputId = useId();
  const statusId = useId();
  const forceFocus = previewState === 'focus';

  const chooseContext = (mode: ContextMode) => {
    setContextMode(mode);
    demo.announce(
      mode === 'run'
        ? '已切换到跑步上下文；本次不会读取真实运动记录。'
        : '已切换到记忆上下文；目标心率 145 来自合成数据。',
    );
  };

  const removeRun = () => {
    setRunAttached(false);
    demo.announce('已移除跑步摘要，输入内容保持不变，可随时恢复。');
  };

  const restoreRun = () => {
    setRunAttached(true);
    demo.announce('已恢复昨晚轻松跑摘要；本次不会读取真实运动记录。');
  };

  return (
    <div className={styles.composerLayout}>
      <ComposerScene variant="task" />
      <section className={styles.composerTask} aria-label="双行任务稿纸聊天输入区">
        <div className={styles.composerTaskContext}>
          <div className={styles.composerContextSwitch} role="group" aria-label="选择本次上下文">
            <button
              type="button"
              aria-label="切换到跑步上下文"
              aria-pressed={contextMode === 'run'}
              onClick={() => chooseContext('run')}
            >
              跑步
            </button>
            <button
              type="button"
              aria-label="切换到记忆上下文"
              aria-pressed={contextMode === 'memory'}
              onClick={() => chooseContext('memory')}
            >
              记忆
            </button>
          </div>
          {contextMode === 'run' ? (
            runAttached ? (
              <RunContextChip compact onRemove={removeRun} />
            ) : (
              <div className={styles.composerContextEmpty}>
                <span>未附加跑步摘要</span>
                <button type="button" onClick={restoreRun}>
                  恢复跑步摘要
                </button>
              </div>
            )
          ) : (
            <div className={styles.composerContextChip}>
              <Brain size={14} weight="regular" aria-hidden="true" />
              <span>
                <strong>长期记忆</strong>
                <small>轻松跑目标心率 145</small>
              </span>
            </div>
          )}
        </div>
        <div className={styles.composerTaskEditor}>
          <AutoGrowTextarea
            id={inputId}
            describedBy={statusId}
            value={demo.draft}
            disabled={demo.sendState === 'sending'}
            forceFocus={forceFocus}
            onChange={(event) => demo.handleDraftChange(event.target.value)}
            onKeyDown={demo.handleKeyDown}
          />
          <ComposerStatusLine demo={demo} id={statusId} />
          <ComposerFooter
            demo={demo}
            variantName="双行任务稿纸"
            authorityLabel={contextMode === 'run' ? '跑步' : '记忆'}
          />
        </div>
      </section>
    </div>
  );
}

function StackComposer({ previewState }: { previewState: PreviewState }) {
  const demo = useComposerDemo(previewState, '上下文堆栈卡');
  const [memoryEnabled, setMemoryEnabled] = useState(true);
  const [runAttached, setRunAttached] = useState(true);
  const inputId = useId();
  const statusId = useId();
  const forceFocus = previewState === 'focus';
  const contextCount = 2 + Number(memoryEnabled) + Number(runAttached);

  const toggleMemory = () => {
    setMemoryEnabled((enabled) => {
      const next = !enabled;
      demo.announce(
        next
          ? '已开启长期记忆引用；仅使用合成心率偏好。'
          : '已关闭长期记忆引用；本机与在线 Agent 上下文保持不变。',
      );
      return next;
    });
  };

  const removeRun = () => {
    setRunAttached(false);
    demo.announce('已移除本次跑步摘要；输入草稿未改变，可随时恢复。');
  };

  const restoreRun = () => {
    setRunAttached(true);
    demo.announce('已恢复昨晚轻松跑摘要；本次不会读取真实运动记录。');
  };

  return (
    <div className={styles.composerLayout}>
      <ComposerScene variant="stack" />
      <section className={styles.composerStack} aria-label="上下文堆栈聊天输入区">
        <div className={styles.composerStackHeader}>
          <span>
            <strong>本次上下文</strong>
            <small>仅用于这一条消息</small>
          </span>
          <span className={styles.composerStackCount}>{contextCount} 项</span>
        </div>
        <div className={styles.composerContextChips} aria-label="本次消息引用">
          <span className={styles.composerContextChip}>
            <MapPin size={13} weight="regular" aria-hidden="true" />
            本机训练
          </span>
          <span className={styles.composerContextChip} data-tone="agent">
            <Cpu size={13} weight="regular" aria-hidden="true" />
            在线 Agent
          </span>
          <button
            type="button"
            className={styles.composerContextChip}
            aria-label={memoryEnabled ? '关闭记忆引用' : '开启记忆引用'}
            aria-pressed={memoryEnabled}
            onClick={toggleMemory}
          >
            <Brain size={13} weight="regular" aria-hidden="true" />
            记忆
          </button>
        </div>
        {runAttached ? (
          <RunContextChip onRemove={removeRun} />
        ) : (
          <div className={styles.composerContextEmpty}>
            <span>未附加跑步摘要</span>
            <button type="button" onClick={restoreRun}>
              恢复跑步摘要
            </button>
          </div>
        )}
        <div className={styles.composerStackEditor}>
          <AutoGrowTextarea
            id={inputId}
            describedBy={statusId}
            value={demo.draft}
            disabled={demo.sendState === 'sending'}
            forceFocus={forceFocus}
            onChange={(event) => demo.handleDraftChange(event.target.value)}
            onKeyDown={demo.handleKeyDown}
          />
          <ComposerStatusLine demo={demo} id={statusId} />
          <ComposerFooter
            demo={demo}
            variantName="上下文堆栈卡"
            authorityLabel={`${contextCount} 项`}
          />
        </div>
      </section>
    </div>
  );
}

interface ComposerPreviewCanvasProps {
  variant: ComposerVariantId;
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  ruler: ReactNode;
}

function ComposerPreviewCanvas({
  variant,
  width,
  state,
  showRuler,
  ruler,
}: ComposerPreviewCanvasProps) {
  return (
    <div className={styles.previewFrame}>
      <div
        className={`${styles.demoCanvas} ${styles.composerDemoCanvas}`}
        data-width={width}
        data-variant={variant}
      >
        {variant === 'dock' ? <DockComposer previewState={state} /> : null}
        {variant === 'task' ? <TaskComposer previewState={state} /> : null}
        {variant === 'stack' ? <StackComposer previewState={state} /> : null}
        {showRuler ? ruler : null}
      </div>
    </div>
  );
}

interface ComposerVariantCardProps {
  variant: ComposerVariantDefinition;
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  ruler: ReactNode;
  selected: boolean;
  onSelect: (variant: ComposerVariantId, name: string) => void;
  onInspect: (variant: ComposerVariantId) => void;
}

function ComposerVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  selected,
  onSelect,
  onInspect,
}: ComposerVariantCardProps) {
  return (
    <article className={`${styles.variantCard} ${selected ? styles.variantSelected : ''}`}>
      <header className={styles.variantHeader}>
        <span className={styles.variantMark} aria-hidden="true">
          {variant.letter}
        </span>
        <div className={styles.variantHeading}>
          <div className={styles.variantNameRow}>
            <h3>{variant.name}</h3>
            <span className={selected ? styles.recommendedBadge : styles.candidateBadge}>
              {selected ? '本次选择' : '待选择'}
            </span>
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <ComposerPreviewCanvas
        key={`${variant.id}-${state}`}
        variant={variant.id}
        width={width}
        state={state}
        showRuler={showRuler}
        ruler={ruler}
      />
      <dl className={styles.factGrid}>
        <div>
          <dt>发现性</dt>
          <dd>{variant.discovery}</dd>
        </div>
        <div>
          <dt>视觉密度</dt>
          <dd>{variant.density}</dd>
        </div>
        <div>
          <dt>320px</dt>
          <dd>{variant.narrowScreen}</dd>
        </div>
      </dl>
      <footer className={styles.variantActions}>
        <button
          type="button"
          className={styles.selectButton}
          aria-pressed={selected}
          aria-label={
            selected ? `${variant.name}已选为本次基线` : `选择${variant.name}作为聊天输入区基线`
          }
          onClick={() => onSelect(variant.id, variant.name)}
        >
          {selected ? <Check size={17} weight="bold" aria-hidden="true" /> : null}
          {selected ? '已选为基线' : '选作下一版基线'}
        </button>
        <button
          type="button"
          className={styles.inspectButton}
          aria-label={`查看${variant.name}的实现`}
          onClick={() => onInspect(variant.id)}
        >
          看实现
          <CheckCircle size={15} weight="regular" aria-hidden="true" />
        </button>
      </footer>
    </article>
  );
}

interface ComposerImplementationDetailsProps {
  activeVariant: ComposerVariantId;
  activeTab: DetailTab;
  onVariantChange: (variant: ComposerVariantId) => void;
  onTabChange: (tab: DetailTab) => void;
}

function ComposerImplementationDetails({
  activeVariant,
  activeTab,
  onVariantChange,
  onTabChange,
}: ComposerImplementationDetailsProps) {
  const tabRefs = useRef<Record<DetailTab, HTMLButtonElement | null>>({
    logic: null,
    css: null,
    a11y: null,
  });
  const activeDefinition =
    composerVariants.find((variant) => variant.id === activeVariant) ?? composerVariants[0];
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
      id="composer-implementation"
      className={styles.implementation}
      aria-labelledby="composer-implementation-heading"
      tabIndex={-1}
    >
      <header className={styles.sectionHeading}>
        <h2 id="composer-implementation-heading">同一个输入动作，三套实现细节</h2>
        <p>先比较输入区怎样组织上下文，再核对键盘、焦点、发送与失败恢复是否一致。</p>
      </header>

      <div className={styles.implementationControls}>
        <div className={styles.variantSwitch} role="group" aria-label="查看哪个输入区参考的实现">
          {composerVariants.map((variant) => (
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
        <div className={styles.detailTabs} role="tablist" aria-label="聊天输入区实现明细">
          {detailTabs.map((tab) => (
            <button
              key={tab.id}
              ref={(element) => {
                tabRefs.current[tab.id] = element;
              }}
              id={`composer-detail-tab-${tab.id}`}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              aria-controls={`composer-detail-panel-${tab.id}`}
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
        id={`composer-detail-panel-${activeTab}`}
        className={styles.detailPanel}
        role="tabpanel"
        aria-labelledby={`composer-detail-tab-${activeTab}`}
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

function ComposerGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="composer-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="composer-glossary-heading">输入区再掌握六个词</h2>
        <p>这些词能帮你直接判断问题出在输入行为、上下文密度，还是状态反馈。</p>
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

export function ComposerReferenceSection({
  width,
  state,
  showRuler,
  ruler,
  onBaselineChange,
}: ComposerReferenceSectionProps) {
  const [selectedVariant, setSelectedVariant] = useState<ComposerVariantId | null>(
    () => readStoredVariant() ?? 'dock',
  );
  const [activeVariant, setActiveVariant] = useState<ComposerVariantId>(
    () => readStoredVariant() ?? 'dock',
  );
  const [activeTab, setActiveTab] = useState<DetailTab>('logic');
  const [feedback, setFeedback] = useState(
    'A「Codex 单框悬浮坞」已确认并接入正式聊天页；B、C 保留为对照，三案都不会调用真实模型。',
  );

  useEffect(() => {
    const selected = composerVariants.find((variant) => variant.id === selectedVariant);
    onBaselineChange(selected ? `${selected.letter} ${selected.name}` : null);
  }, [onBaselineChange, selectedVariant]);

  const handleSelect = (variant: ComposerVariantId, name: string) => {
    setSelectedVariant(variant);
    setActiveVariant(variant);
    try {
      window.localStorage.setItem(COMPOSER_STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    setFeedback(
      variant === 'dock'
        ? 'A「Codex 单框悬浮坞」已确认并接入正式聊天页；B、C 保留为对照。'
        : `已将“${name}”记为候选；正式聊天页仍采用 A「Codex 单框悬浮坞」。`,
    );
  };

  const handleInspect = (variant: ComposerVariantId) => {
    setActiveVariant(variant);
    setActiveTab('logic');
    const section = document.getElementById('composer-implementation');
    try {
      section?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch {
      // jsdom 没有布局引擎；真实浏览器仍会正常滚动。
    }
    section?.focus({ preventScroll: true });
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="composer-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="composer-variants-heading">聊天输入区的三种取舍</h2>
          <p>
            A 最安静、最省空间；B 把任务上下文拆成清晰的一行；C 把本机、在线
            Agent、跑步和记忆全部摊开。三案都可以输入、发送、失败和重试。
          </p>
        </header>
        <div className={styles.variantGrid} data-testid="composer-previews">
          {composerVariants.map((variant) => (
            <ComposerVariantCard
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

      <ComposerImplementationDetails
        activeVariant={activeVariant}
        activeTab={activeTab}
        onVariantChange={setActiveVariant}
        onTabChange={setActiveTab}
      />
      <ComposerGlossary />
    </>
  );
}
