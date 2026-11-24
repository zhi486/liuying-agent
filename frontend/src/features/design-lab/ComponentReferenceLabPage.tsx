import { ArrowLeft, CaretRight, Check, Moon, Ruler, Sun } from '@phosphor-icons/react';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { BackLink } from '../../design-system/BackControl';
import { MotionReferenceSection } from './MotionReferenceSection';

import styles from './ComponentReferenceLabPage.module.css';
import { ComposerReferenceSection } from './ComposerReferenceSection';
import { ConversationManagementReferenceSection } from './ConversationManagementReferenceSection';
import { ConversationRowReferenceSection } from './ConversationRowReferenceSection';
import { HistoryReferenceSection } from './HistoryReferenceSection';
import { ListRowReferenceSection } from './ListRowReferenceSection';
import { MessageReferenceSection } from './MessageReferenceSection';
import { MemoryPageReferenceSection } from './MemoryPageReferenceSection';
import { UsagePageReferenceSection } from './UsagePageReferenceSection';
import { ConsentPageReferenceSection } from './ConsentPageReferenceSection';
import { MorePagesReferenceSection } from './MorePagesReferenceSection';
import { RunReviewReferenceSection } from './RunReviewReferenceSection';
import { AgentFeedbackReferenceSection } from './AgentFeedbackReferenceSection';
import { ChatControlsReferenceSection } from './ChatControlsReferenceSection';
import { FinalStatesReferenceSection } from './FinalStatesReferenceSection';
import { RunningActiveReferenceSection } from './RunningActiveReferenceSection';
import { RunningCompleteReferenceSection } from './RunningCompleteReferenceSection';
import { RunningReadinessReferenceSection } from './RunningReadinessReferenceSection';
import { SettingStatusReferenceSection } from './SettingStatusReferenceSection';
import { SettingsToggleReferenceSection } from './SettingsToggleReferenceSection';
import { ThemeSelectorReferenceSection } from './ThemeSelectorReferenceSection';

type PreviewTheme = 'morning' | 'night';
type PreviewWidth = '393' | '320';
type PreviewState = 'default' | 'focus' | 'long' | 'saving';
type VariantId = 'capsule' | 'toolbar' | 'rail';
type DetailTab = 'logic' | 'css' | 'a11y';
type ComponentGroupId =
  | 'navigation'
  | 'list'
  | 'appearance'
  | 'composer'
  | 'messages'
  | 'conversations'
  | 'management'
  | 'readiness'
  | 'activeRun'
  | 'postRun'
  | 'history'
  | 'settingStatus'
  | 'settingsToggle'
  | 'memoryPage'
  | 'usagePage'
  | 'consentPage'
  | 'noticesPage'
  | 'pluginsPage'
  | 'runDetail'
  | 'runCharts'
  | 'runControls'
  | 'toolFeedback'
  | 'sourceFeedback'
  | 'recoveryFeedback'
  | 'answerActions'
  | 'latestMessage'
  | 'knowledgePicker'
  | 'loadingEmpty'
  | 'dangerConfirm';

interface VariantDefinition {
  id: VariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  discovery: string;
  density: string;
  narrowScreen: string;
  recommended?: boolean;
}

const STORAGE_KEY = 'healthy-agent:ui-reference:back-navigation';
const COMPOSER_STORAGE_KEY = 'healthy-agent:ui-reference:composer';
const MESSAGE_STORAGE_KEY = 'healthy-agent:ui-reference:messages';
const CONVERSATION_STORAGE_KEY = 'healthy-agent:ui-reference:conversations';
const MANAGEMENT_STORAGE_KEY = 'healthy-agent:ui-reference:management';
const READINESS_STORAGE_KEY = 'healthy-agent:ui-reference:running-readiness-v4';
const ACTIVE_RUN_STORAGE_KEY = 'healthy-agent:ui-reference:running-active-v1';
const POST_RUN_STORAGE_KEY = 'healthy-agent:ui-reference:running-complete-v1';
const HISTORY_STORAGE_KEY = 'healthy-agent:ui-reference:run-history-v1';

const variants: VariantDefinition[] = [
  {
    id: 'capsule',
    letter: 'A',
    name: '文字返回胶囊',
    summary: '动作直白，代价是顶栏更高、长文案更占宽度。',
    discovery: '最强',
    density: '偏低',
    narrowScreen: '注意换行',
    recommended: true,
  },
  {
    id: 'toolbar',
    letter: 'B',
    name: 'Codex 工具条',
    summary: '单行居中标题配 44px 图标按钮，安静且最省纵向空间。',
    discovery: '中等',
    density: '最高',
    narrowScreen: '稳定',
  },
  {
    id: 'rail',
    letter: 'C',
    name: '路径标题轨',
    summary: '把上级路径和当前页面分层显示，层级最清楚。',
    discovery: '中等',
    density: '中等',
    narrowScreen: '空间最紧',
  },
];

const demoSettings = [
  { label: '目标距离', value: '5.00 km', detail: '本周累计 18.4 km' },
  { label: '目标配速', value: `6'00"/km`, detail: '轻松跑区间' },
  { label: '最大心率', value: '185 bpm', detail: '按个人区间估算' },
  { label: '警戒心率', value: '145 bpm', detail: '超过时提醒降速' },
  { label: '语音提醒', value: '公里播报', detail: '温和语气 · 已开启' },
];

const detailTabs: { id: DetailTab; label: string }[] = [
  { id: 'logic', label: 'React 逻辑' },
  { id: 'css', label: 'CSS' },
  { id: 'a11y', label: '无障碍' },
];

const terminology = [
  {
    term: '导航栈',
    description: '用户进入页面的顺序。返回应回到上一级或明确来源，而不是每次都跳回聊天首页。',
  },
  {
    term: '信息层级',
    description: '通过字号、位置和留白告诉眼睛先看什么。层级不是把所有内容都放大。',
  },
  {
    term: '视觉密度',
    description: '一屏能容纳多少信息。运动数据要紧凑，说明文字则需要更多呼吸空间。',
  },
  {
    term: 'affordance',
    description: '控件看起来像什么、能做什么。箭头加动作文字比没有说明的裸图标更容易被发现。',
  },
  {
    term: 'surface / elevation',
    description: '表面与抬升感。固定顶栏、底部操作区和浮层才值得使用玻璃或阴影。',
  },
  {
    term: 'focus ring',
    description: '键盘焦点环。它必须清楚可见，不能被 outline: none 无声删除。',
  },
];

const detailCopy: Record<
  VariantId,
  Record<DetailTab, { heading: string; code: string; notes: string[] }>
> = {
  capsule: {
    logic: {
      heading: '点击返回时先决定目标，再执行一次导航',
      code: `const navigation = useAppBackNavigation();

<button type="button" onClick={navigation.back}>
  <ArrowLeft size={20} aria-hidden="true" />
  <span>{navigation.label}</span>
</button>
<h1>{title}</h1>`,
      notes: [
        '目标按固定父级、显式来源、内部上一页、聊天兜底依次解析。',
        '新窗口、组合键点击保留浏览器原生行为。',
        '返回文案由解析出的父级产生，不在页面里硬编码。',
      ],
    },
    css: {
      heading: '胶囊是独立控件，标题另起一行',
      code: `.back {
  min-height: 44px;
  padding: 8px 14px 8px 10px;
  border-radius: 999px;
  border: 1px solid var(--line);
}
.title {
  margin: 16px 0 0;
  font-size: 24px;
  line-height: 1.25;
}`,
      notes: [
        '44px 是 iOS 触控建议值；Android 材料规范通常以 48dp 为目标。',
        '胶囊只用于控件，不拿来包整段页面内容。',
        '标题自然换行，不使用负字距硬塞长文案。',
      ],
    },
    a11y: {
      heading: '可见文字就是动作名称',
      code: `<button aria-label="返回设置">
  返回设置
</button>
<h1>运动与语音</h1>`,
      notes: [
        '可见的“返回设置”与可访问名称一致，语音控制更稳定。',
        '箭头设为 aria-hidden，避免重复朗读。',
        '焦点环至少 2px，并与背景保持 3:1 对比度。',
      ],
    },
  },
  toolbar: {
    logic: {
      heading: '三列网格让标题真正居中，而不是靠左右碰巧对齐',
      code: `const navigation = useAppBackNavigation();

<header className={styles.toolbar}>
  <button aria-label={navigation.label} onClick={navigation.back}>
    <ArrowLeft size={20} aria-hidden="true" />
  </button>
  <h1 title={fullTitle}>{fullTitle}</h1>
  <span aria-hidden="true" />
</header>`,
      notes: [
        '列宽为 44px / minmax(0, 1fr) / 44px，左右操作区尺寸固定。',
        '标题单行省略，title 与 aria-label 保留完整名称。',
        '返回目标复用统一导航 Hook，工具条只负责呈现。',
      ],
    },
    css: {
      heading: '固定顶栏用一层玻璃，正文不被遮住',
      code: `.toolbar {
  position: sticky;
  top: 0;
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) 44px;
  align-items: center;
  min-height: 62px;
  padding: env(safe-area-inset-top) 12px 0;
  backdrop-filter: blur(18px) saturate(1.2);
  background: var(--glass);
}
.toolbar h1 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}`,
      notes: [
        '玻璃只属于固定导航表面，不拿来做普通内容卡。',
        'sticky 元素仍占据文档流高度，不会遮住正文第一行。',
        '标题居中与左右图标尺寸解耦，长标题不会推歪网格。',
      ],
    },
    a11y: {
      heading: '图标按钮需要动作名称，空白列不参与朗读',
      code: `<button aria-label="返回设置">
  <ArrowLeft aria-hidden="true" />
</button>
<h1 aria-label="聊天持续授权与历史记录管理">
  聊天持续授权与历史记录管理
</h1>
<span aria-hidden="true" />`,
      notes: [
        '按钮名称描述结果，不读成“左箭头”。',
        '右侧平衡占位必须是装饰节点，否则会多出一个无意义焦点。',
        '键盘焦点不被标题省略裁切；焦点环可向按钮外偏移。',
      ],
    },
  },
  rail: {
    logic: {
      heading: '上级路径来自同一份路由映射，避免文案漂移',
      code: `const navigation = useAppBackNavigation();
const parentLabel = getBackTargetLabel(navigation.target);

<nav aria-label="面包屑">
  <span>{parentLabel}</span>
  <CaretRight aria-hidden="true" />
</nav>
<h1>{currentTitle}</h1>`,
      notes: [
        '返回按钮与路径轨共用同一目标数据，不能一处写“设置”、另一处写“账户”。',
        '若选择 C，需要让导航 Hook 提供目标名称，不能用 replace() 从按钮文案猜。',
        '路径是方向提示，不是第二套点击热区，避免重复 affordance。',
        '标题最多两行，超过时保留完整可访问名称。',
      ],
    },
    css: {
      heading: '路径和标题各占一行，纵向空间必须真实留出来',
      code: `.rail {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) 44px;
  align-items: center;
  min-height: 70px;
}
.path {
  color: var(--text-faint);
  font-size: 11px;
  line-height: 1.2;
}
.rail h1 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 16px;
  line-height: 1.3;
}`,
      notes: [
        '用行数限制控制最大高度，不能靠固定高度把第二行裁掉。',
        '路径字号更小但仍需达到可读对比度。',
        '320px 下需要单独截图检查，空间比 B 明显更紧。',
      ],
    },
    a11y: {
      heading: '面包屑是位置说明，按钮仍独立命名',
      code: `<nav aria-label="面包屑">
  <span>设置</span>
  <CaretRight aria-hidden="true" />
</nav>
<h1>运动与语音</h1>
<button aria-label="返回设置">
  <ArrowLeft aria-hidden="true" />
</button>`,
      notes: [
        'nav 名称让辅助技术知道这是路径信息。',
        '图标不提供额外文字气泡，避免与按钮名称冲突。',
        '两行标题不会覆盖返回按钮；键盘焦点仍完整可见。',
      ],
    },
  },
};

interface PreviewProps {
  title: string;
  forceFocus: boolean;
  onBack: () => void;
}

function DemoContent() {
  return (
    <div className={styles.demoBody}>
      <div className={styles.demoSummary}>
        <span>本周训练目标</span>
        <strong>5.00 km</strong>
        <small>已完成 3 次 · 累计 18.4 km</small>
      </div>
      <div className={styles.demoListHeading}>
        <span>跑步参数</span>
        <span>仅本机</span>
      </div>
      <dl className={styles.demoList}>
        {demoSettings.map((setting) => (
          <div key={setting.label} className={styles.demoRow}>
            <div>
              <dt>{setting.label}</dt>
              <small>{setting.detail}</small>
            </div>
            <dd>{setting.value}</dd>
          </div>
        ))}
      </dl>
      <p className={styles.demoFootnote}>语音播报只在跑步过程中生效。</p>
    </div>
  );
}

function CapsulePreview({ title, forceFocus, onBack }: PreviewProps) {
  return (
    <header className={`${styles.demoHeader} ${styles.capsuleHeader}`}>
      <button
        type="button"
        className={`${styles.capsuleBack} ${forceFocus ? styles.forceFocus : ''}`}
        aria-label="返回设置"
        onClick={onBack}
      >
        <ArrowLeft size={18} weight="regular" aria-hidden="true" />
        <span>返回设置</span>
      </button>
      <span className={styles.capsuleTitle} role="heading" aria-level={4} aria-label={title}>
        {title}
      </span>
    </header>
  );
}

function ToolbarPreview({ title, forceFocus, onBack }: PreviewProps) {
  return (
    <header className={`${styles.demoHeader} ${styles.toolbarHeader}`}>
      <button
        type="button"
        className={`${styles.toolbarButton} ${forceFocus ? styles.forceFocus : ''}`}
        aria-label="返回设置"
        onClick={onBack}
      >
        <ArrowLeft size={20} weight="regular" aria-hidden="true" />
      </button>
      <span
        className={styles.toolbarTitle}
        role="heading"
        aria-level={4}
        aria-label={title}
        title={title}
      >
        {title}
      </span>
      <span className={styles.toolbarSpacer} aria-hidden="true" />
    </header>
  );
}

function RailPreview({ title, forceFocus, onBack }: PreviewProps) {
  return (
    <header className={`${styles.demoHeader} ${styles.railHeader}`}>
      <button
        type="button"
        className={`${styles.railButton} ${forceFocus ? styles.forceFocus : ''}`}
        aria-label="返回设置"
        onClick={onBack}
      >
        <ArrowLeft size={20} weight="regular" aria-hidden="true" />
      </button>
      <div className={styles.railCopy}>
        <nav className={styles.railPath} aria-label="面包屑">
          <span>设置</span>
          <CaretRight size={11} weight="bold" aria-hidden="true" />
        </nav>
        <span
          className={styles.railTitle}
          role="heading"
          aria-level={4}
          aria-label={title}
          title={title}
        >
          {title}
        </span>
      </div>
      <span className={styles.railSpacer} aria-hidden="true" />
    </header>
  );
}

function RulerOverlay() {
  return (
    <div
      className={styles.rulerOverlay}
      role="img"
      aria-label="尺寸标尺：左右内容边距 16 像素，按钮触控目标高度 44 像素"
    >
      <span className={`${styles.rulerEdge} ${styles.rulerEdgeLeft}`} aria-hidden="true" />
      <span className={`${styles.rulerEdge} ${styles.rulerEdgeRight}`} aria-hidden="true" />
      <span className={styles.edgeLabelLeft} aria-hidden="true">
        16px
      </span>
      <span className={styles.edgeLabelRight} aria-hidden="true">
        16px
      </span>
      <span className={styles.touchTargetRuler} aria-hidden="true">
        <span>44px</span>
      </span>
    </div>
  );
}

interface VariantPreviewProps {
  variant: VariantId;
  title: string;
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  onBack: () => void;
}

function VariantPreview({ variant, title, width, state, showRuler, onBack }: VariantPreviewProps) {
  const props = { title, forceFocus: state === 'focus', onBack };

  return (
    <div className={styles.previewFrame}>
      <div className={styles.demoCanvas} data-width={width}>
        {variant === 'capsule' ? <CapsulePreview {...props} /> : null}
        {variant === 'toolbar' ? <ToolbarPreview {...props} /> : null}
        {variant === 'rail' ? <RailPreview {...props} /> : null}
        <DemoContent />
        {showRuler ? <RulerOverlay /> : null}
      </div>
    </div>
  );
}

interface VariantCardProps {
  variant: VariantDefinition;
  title: string;
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  selected: boolean;
  onBack: (name: string) => void;
  onSelect: (id: VariantId) => void;
  onInspect: (id: VariantId) => void;
}

function VariantCard({
  variant,
  title,
  width,
  state,
  showRuler,
  selected,
  onBack,
  onSelect,
  onInspect,
}: VariantCardProps) {
  return (
    <article className={`${styles.variantCard} ${selected ? styles.variantSelected : ''}`}>
      <header className={styles.variantHeader}>
        <span className={styles.variantMark} aria-hidden="true">
          {variant.letter}
        </span>
        <div className={styles.variantHeading}>
          <div className={styles.variantNameRow}>
            <h3>{variant.name}</h3>
            {variant.recommended ? (
              <span className={styles.recommendedBadge}>下一版基线</span>
            ) : null}
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <VariantPreview
        variant={variant.id}
        title={title}
        width={width}
        state={state}
        showRuler={showRuler}
        onBack={() => onBack(variant.name)}
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
            selected ? `${variant.name}已选为下一版基线` : `选择${variant.name}作为下一版基线`
          }
          onClick={() => onSelect(variant.id)}
        >
          {selected ? <Check size={17} weight="bold" aria-hidden="true" /> : null}
          {selected ? '已选为基线' : '选作下一版基线'}
        </button>
        <button
          type="button"
          className={styles.inspectButton}
          onClick={() => onInspect(variant.id)}
        >
          看实现
          <CaretRight size={15} weight="bold" aria-hidden="true" />
        </button>
      </footer>
    </article>
  );
}

interface ImplementationDetailsProps {
  activeVariant: VariantId;
  activeTab: DetailTab;
  onVariantChange: (variant: VariantId) => void;
  onTabChange: (tab: DetailTab) => void;
}

function ImplementationDetails({
  activeVariant,
  activeTab,
  onVariantChange,
  onTabChange,
}: ImplementationDetailsProps) {
  const tabRefs = useRef<Record<DetailTab, HTMLButtonElement | null>>({
    logic: null,
    css: null,
    a11y: null,
  });
  const activeDefinition = variants.find((variant) => variant.id === activeVariant) ?? variants[0];
  const copy = detailCopy[activeVariant][activeTab];

  const moveTabFocus = (key: string) => {
    const currentIndex = detailTabs.findIndex((tab) => tab.id === activeTab);
    let nextIndex = currentIndex;

    if (key === 'ArrowRight') nextIndex = (currentIndex + 1) % detailTabs.length;
    if (key === 'ArrowLeft') nextIndex = (currentIndex - 1 + detailTabs.length) % detailTabs.length;
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
      id="implementation"
      className={styles.implementation}
      aria-labelledby="implementation-heading"
      tabIndex={-1}
    >
      <header className={styles.sectionHeading}>
        <h2 id="implementation-heading">同一个动作，三套实现细节</h2>
        <p>切换参考与标签，直接比较结构、样式和键盘行为，不用靠想象补全。</p>
      </header>

      <div className={styles.implementationControls}>
        <div className={styles.variantSwitch} role="group" aria-label="查看哪个参考的实现">
          {variants.map((variant) => (
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
        <div className={styles.detailTabs} role="tablist" aria-label="实现明细">
          {detailTabs.map((tab) => (
            <button
              key={tab.id}
              ref={(element) => {
                tabRefs.current[tab.id] = element;
              }}
              id={`detail-tab-${tab.id}`}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              aria-controls={`detail-panel-${tab.id}`}
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
        id={`detail-panel-${activeTab}`}
        className={styles.detailPanel}
        role="tabpanel"
        aria-labelledby={`detail-tab-${activeTab}`}
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

function ComponentGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="glossary-heading">这轮先掌握六个词</h2>
        <p>看懂这些词，就能更准确地说出“哪里不对”，而不是只说“感觉不够高级”。</p>
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

function readStoredVariant(): VariantId | null {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return variants.some((variant) => variant.id === value) ? (value as VariantId) : null;
  } catch {
    return null;
  }
}

function readComposerBaseline() {
  try {
    const value = window.localStorage.getItem(COMPOSER_STORAGE_KEY);
    if (value === 'task') return 'B 双行任务稿纸';
    if (value === 'stack') return 'C 上下文堆栈卡';
  } catch {
    return 'A Codex 单框悬浮坞';
  }
  return 'A Codex 单框悬浮坞';
}

function readMessageBaseline() {
  try {
    const value = window.localStorage.getItem(MESSAGE_STORAGE_KEY);
    if (value === 'rail') return 'A 轨道通栏';
    if (value === 'paper') return 'B 对话稿纸';
    if (value === 'bubbles') return 'C 双列气泡';
  } catch {
    return 'C 双列气泡';
  }
  return 'C 双列气泡';
}

function readConversationBaseline() {
  try {
    const value = window.localStorage.getItem(CONVERSATION_STORAGE_KEY);
    if (value === 'command') return 'A Codex 线程行';
    if (value === 'glass') return 'B 玻璃分组行';
    if (value === 'preview') return 'C 双行预览行';
  } catch {
    return 'C 双行预览行';
  }
  return 'C 双行预览行';
}

function readManagementBaseline() {
  try {
    const value = window.localStorage.getItem(MANAGEMENT_STORAGE_KEY);
    if (value === 'command') return 'A Codex 命令单';
    if (value === 'grouped') return 'B 分组玻璃面板';
    if (value === 'confirm') return 'C 安全确认栈';
  } catch {
    return 'B 分组玻璃面板';
  }
  return 'B 分组玻璃面板';
}

function readReadinessBaseline() {
  try {
    const value = window.localStorage.getItem(READINESS_STORAGE_KEY);
    if (value === 'console') return 'A 地图起跑';
    if (value === 'target') return 'B 里程仪表';
    if (value === 'checklist') return 'C 训练清单';
  } catch {
    return 'B 里程仪表';
  }
  return 'B 里程仪表';
}

function readActiveRunBaseline() {
  try {
    const value = window.localStorage.getItem(ACTIVE_RUN_STORAGE_KEY);
    if (value === 'cockpit') return 'A 夜色驾驶舱';
    if (value === 'odometer') return 'B 双窗里程表';
    if (value === 'tempo') return 'C 节奏轨带';
  } catch {
    return 'A 夜色驾驶舱';
  }
  return 'A 夜色驾驶舱';
}

function readPostRunBaseline() {
  try {
    const value = window.localStorage.getItem(POST_RUN_STORAGE_KEY);
    if (value === 'scorecard') return 'A 冲线成绩单';
    if (value === 'receipt') return 'B 训练回执';
    if (value === 'review') return 'C 复盘手账';
  } catch {
    return 'A 冲线成绩单';
  }
  return 'A 冲线成绩单';
}

function readHistoryBaseline() {
  try {
    const value = window.localStorage.getItem(HISTORY_STORAGE_KEY);
    if (value === 'recent') return 'A 最近优先';
    if (value === 'timeline') return 'B 日期时间线';
    if (value === 'ledger') return 'C 月度训练账本';
  } catch {
    return 'A 最近优先';
  }
  return 'A 最近优先';
}

function readInitialTheme(): PreviewTheme {
  return document.documentElement.dataset.theme === 'night' ? 'night' : 'morning';
}

export function ComponentReferenceLabPage() {
  const [params] = useSearchParams();
  return params.get('preview') === 'motion' ? (
    <MotionReferenceSection />
  ) : (
    <ComponentReferenceLabContent />
  );
}

function ComponentReferenceLabContent() {
  const [activeGroup, setActiveGroup] = useState<ComponentGroupId>('settingStatus');
  const [composerBaseline, setComposerBaseline] = useState<string | null>(readComposerBaseline);
  const [messagesBaseline, setMessagesBaseline] = useState<string | null>(readMessageBaseline);
  const [conversationsBaseline, setConversationsBaseline] = useState<string | null>(
    readConversationBaseline,
  );
  const [managementBaseline, setManagementBaseline] = useState<string | null>(
    readManagementBaseline,
  );
  const [readinessBaseline, setReadinessBaseline] = useState<string | null>(readReadinessBaseline);
  const [activeRunBaseline, setActiveRunBaseline] = useState<string | null>(readActiveRunBaseline);
  const [postRunBaseline, setPostRunBaseline] = useState<string | null>(readPostRunBaseline);
  const [historyBaseline, setHistoryBaseline] = useState<string | null>(readHistoryBaseline);
  const [theme, setTheme] = useState<PreviewTheme>(readInitialTheme);
  const [previewWidth, setPreviewWidth] = useState<PreviewWidth>('393');
  const [previewState, setPreviewState] = useState<PreviewState>('default');
  const [showRuler, setShowRuler] = useState(false);
  const [feedback, setFeedback] = useState(
    '第 11 组正在比较跑步记录；A、B、C 都只使用合成记录，可切换空记录、加载更多和日夜主题。',
  );
  const [selectedVariant, setSelectedVariant] = useState<VariantId>(
    () => readStoredVariant() ?? 'capsule',
  );
  const [activeVariant, setActiveVariant] = useState<VariantId>(
    () => readStoredVariant() ?? 'capsule',
  );
  const [activeTab, setActiveTab] = useState<DetailTab>('logic');

  useEffect(() => {
    const root = document.documentElement;
    const previousTheme = root.dataset.theme;
    root.dataset.theme = theme;
    return () => {
      if (previousTheme) root.dataset.theme = previousTheme;
      else delete root.dataset.theme;
    };
  }, [theme]);

  const title = previewState === 'long' ? '聊天持续授权与历史记录管理' : '运动与语音';

  const handleGroupChange = (group: ComponentGroupId) => {
    setActiveGroup(group);
    if (
      group !== 'appearance' &&
      group !== 'readiness' &&
      group !== 'activeRun' &&
      group !== 'postRun' &&
      group !== 'history' &&
      group !== 'settingsToggle' &&
      previewState === 'saving'
    ) {
      setPreviewState('default');
    }
  };

  const handleBack = (variantName: string) => {
    setFeedback(
      `已触发“${variantName}”的返回动作：正式页面会通过 useAppBackNavigation() 返回解析出的设置上级，不会跳回聊天首页。`,
    );
  };

  const handleSelect = (variant: VariantId) => {
    setSelectedVariant(variant);
    setActiveVariant(variant);
    try {
      window.localStorage.setItem(STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    const name = variants.find((item) => item.id === variant)?.name ?? variant;
    setFeedback(
      variant === 'capsule'
        ? '已将“文字返回胶囊”记为下一版基线；正式二级页已按 A 方案接入。'
        : `已将“${name}”记为候选记录；正式二级页当前仍采用 A「文字返回胶囊」。`,
    );
  };

  const handleInspect = (variant: VariantId) => {
    setActiveVariant(variant);
    setActiveTab('logic');
    const section = document.getElementById('implementation');
    try {
      section?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch {
      // jsdom 没有布局引擎；真实浏览器仍会正常滚动。
    }
    section?.focus({ preventScroll: true });
  };

  const selectedName = variants.find((variant) => variant.id === selectedVariant)?.name;
  const activeGroupLabel =
    activeGroup === 'loadingEmpty'
      ? '第 28 组 · 加载与空状态'
      : activeGroup === 'dangerConfirm'
        ? '第 29 组 · 危险操作确认'
        : activeGroup === 'answerActions'
          ? '第 25 组 · 回答操作'
          : activeGroup === 'latestMessage'
            ? '第 26 组 · 回到最新消息'
            : activeGroup === 'knowledgePicker'
              ? '第 27 组 · 知识库选择'
              : activeGroup === 'toolFeedback'
                ? '第 22 组 · 工具执行状态'
                : activeGroup === 'sourceFeedback'
                  ? '第 23 组 · 来源引用'
                  : activeGroup === 'recoveryFeedback'
                    ? '第 24 组 · 发送失败与恢复'
                    : activeGroup === 'history'
                      ? '第 11 组 · 跑步记录列表'
                      : activeGroup === 'postRun'
                        ? '第 10 组 · 跑步跑后总结'
                        : activeGroup === 'activeRun'
                          ? '第 09 组 · 跑步跑中工作区'
                          : activeGroup === 'readiness'
                            ? '第 08 组 · 跑步跑前工作区'
                            : activeGroup === 'management'
                              ? '第 07 组 · 会话管理抽屉'
                              : activeGroup === 'conversations'
                                ? '第 06 组 · 会话侧栏行'
                                : activeGroup === 'messages'
                                  ? '第 05 组 · 消息流'
                                  : activeGroup === 'composer'
                                    ? '第 04 组 · 聊天输入区'
                                    : activeGroup === 'navigation'
                                      ? '第 01 组 · 顶栏与返回控件'
                                      : activeGroup === 'list'
                                        ? '第 02 组 · 设置与列表行'
                                        : activeGroup === 'appearance'
                                          ? '第 03 组 · 主题与外观'
                                          : activeGroup === 'settingStatus'
                                            ? '第 12 组 · 设置行状态值'
                                            : activeGroup === 'settingsToggle'
                                              ? '第 13 组 · 设置项开关'
                                              : activeGroup === 'memoryPage'
                                                ? '第 14 组 · 长期记忆页'
                                                : activeGroup === 'usagePage'
                                                  ? '第 15 组 · 账单与用量页'
                                                  : activeGroup === 'consentPage'
                                                    ? '第 16 组 · 聊天授权页'
                                                    : activeGroup === 'noticesPage'
                                                      ? '第 17 组 · 用户须知页'
                                                      : activeGroup === 'pluginsPage'
                                                        ? '第 18 组 · 插件管理页'
                                                        : activeGroup === 'runDetail'
                                                          ? '第 19 组 · 跑步详情'
                                                          : activeGroup === 'runCharts'
                                                            ? '第 20 组 · 数据图表'
                                                            : '第 21 组 · 暂停与结束';
  const baselineLabel =
    activeGroup === 'loadingEmpty' || activeGroup === 'dangerConfirm'
      ? '最后一批已确认：12 C、28 B、29 B，进入整体收尾'
      : activeGroup === 'answerActions' ||
          activeGroup === 'latestMessage' ||
          activeGroup === 'knowledgePicker'
        ? 'A 已接入正式聊天页'
        : activeGroup === 'toolFeedback' ||
            activeGroup === 'sourceFeedback' ||
            activeGroup === 'recoveryFeedback'
          ? '已接入：工具 B、消息级来源 A、原位恢复 A'
          : activeGroup === 'runDetail' ||
              activeGroup === 'runCharts' ||
              activeGroup === 'runControls'
            ? activeGroup === 'runCharts'
              ? '已确认并接入：C 窗口审阅'
              : activeGroup === 'runDetail'
                ? '已确认并接入：A 成绩总览'
                : '已确认并接入：A 展开数据，64px 圆形控制'
            : activeGroup === 'noticesPage' || activeGroup === 'pluginsPage'
              ? activeGroup === 'noticesPage'
                ? '已确认并接入：A 连续阅读'
                : '已确认并接入：C 接入流程'
              : activeGroup === 'consentPage'
                ? '已确认并接入：B 范围清单'
                : activeGroup === 'usagePage'
                  ? '已确认并接入：B 费用与用量'
                  : activeGroup === 'memoryPage'
                    ? '已确认并接入：B 分组审阅（关键词辅助分组）'
                    : activeGroup === 'history'
                      ? historyBaseline
                        ? historyBaseline.startsWith('A')
                          ? `已确认并接入：${historyBaseline}`
                          : `对比候选：${historyBaseline}；正式历史页仍为 A 最近优先`
                        : '尚未选定记录页基线'
                      : activeGroup === 'postRun'
                        ? postRunBaseline
                          ? postRunBaseline.startsWith('A')
                            ? `已确认并接入：${postRunBaseline}`
                            : `对比候选：${postRunBaseline}；正式跑后页仍为 A 冲线成绩单`
                          : '已确认并接入：A 冲线成绩单'
                        : activeGroup === 'activeRun'
                          ? activeRunBaseline
                            ? activeRunBaseline.startsWith('A')
                              ? `已确认并接入：${activeRunBaseline}`
                              : `对比候选：${activeRunBaseline}；正式跑中页仍为 A 夜色驾驶舱`
                            : '已确认并接入：A 夜色驾驶舱'
                          : activeGroup === 'readiness'
                            ? readinessBaseline
                              ? readinessBaseline.startsWith('B')
                                ? `已确认并接入：${readinessBaseline}`
                                : `对比候选：${readinessBaseline}；正式跑前页仍为 B 里程仪表`
                              : '已确认并接入：B 里程仪表'
                            : activeGroup === 'management'
                              ? managementBaseline
                                ? managementBaseline.startsWith('B')
                                  ? `已确认并接入：${managementBaseline}`
                                  : `当前候选：${managementBaseline}；正式管理仍为 B 分组玻璃面板`
                                : '已确认并接入：B 分组玻璃面板'
                              : activeGroup === 'conversations'
                                ? conversationsBaseline
                                  ? conversationsBaseline.startsWith('C')
                                    ? `已确认并接入：${conversationsBaseline}`
                                    : `当前候选：${conversationsBaseline}；正式侧栏仍为 C 双行预览行`
                                  : '已确认并接入：C 双行预览行'
                                : activeGroup === 'messages'
                                  ? messagesBaseline
                                    ? messagesBaseline.startsWith('C')
                                      ? `已确认并接入：${messagesBaseline}`
                                      : `当前候选：${messagesBaseline}；正式页仍为 C 双列气泡`
                                    : '已确认并接入：C 双列气泡'
                                  : activeGroup === 'composer'
                                    ? composerBaseline
                                      ? composerBaseline.startsWith('A')
                                        ? `已确认并接入：${composerBaseline}`
                                        : `当前候选：${composerBaseline}；正式页仍为 A 单框悬浮坞`
                                      : '已确认并接入：A Codex 单框悬浮坞'
                                    : activeGroup === 'list'
                                      ? '当前基线：A 分组玻璃行'
                                      : activeGroup === 'appearance'
                                        ? '已确认基线：C 双画布主题窗'
                                        : selectedName
                                          ? `当前基线：${selectedName}`
                                          : '尚未选定基线';
  const longStateLabel =
    activeGroup === 'history'
      ? '空记录'
      : activeGroup === 'postRun'
        ? '同步异常'
        : activeGroup === 'activeRun'
          ? '弱信号'
          : activeGroup === 'readiness'
            ? '异常状态'
            : activeGroup === 'composer'
              ? '长草稿'
              : activeGroup === 'messages'
                ? '长回答'
                : '长标题';

  return (
    <main className={styles.page}>
      <div className={styles.pageShell}>
        <header className={styles.pageHeader}>
          <BackLink to="/agent" label="返回应用" />
          <div className={styles.titleLine}>
            <h1>组件参考台</h1>
            <span>{activeGroupLabel}</span>
          </div>
          <p>
            这次不比较整页风格，只把同一个组件拆到像素、状态和代码。选定后再进入正式页面，避免大范围返工。
          </p>
          <Link to="/design/reference?preview=motion">打开交互动效预览</Link>
        </header>

        <nav className={styles.componentGroups} aria-label="组件分组">
          <button
            type="button"
            aria-label="查看第 01 组：顶栏与返回"
            aria-pressed={activeGroup === 'navigation'}
            onClick={() => handleGroupChange('navigation')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              01
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>顶栏与返回</strong>
              <small>已记录文字返回胶囊基线</small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 02 组：设置与列表行"
            aria-pressed={activeGroup === 'list'}
            onClick={() => handleGroupChange('list')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              02
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>设置与列表行</strong>
              <small>A 分组玻璃行已定基线</small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 03 组：主题与外观"
            aria-pressed={activeGroup === 'appearance'}
            onClick={() => handleGroupChange('appearance')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              03
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>主题与外观</strong>
              <small>C 双画布主题窗已定基线</small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 04 组：聊天输入区"
            aria-pressed={activeGroup === 'composer'}
            onClick={() => handleGroupChange('composer')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              04
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>聊天输入区</strong>
              <small>
                {composerBaseline
                  ? `${composerBaseline} ${composerBaseline.startsWith('A') ? '已定基线' : '候选'}`
                  : 'A Codex 单框悬浮坞 已定基线'}
              </small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 05 组：消息流"
            aria-pressed={activeGroup === 'messages'}
            onClick={() => handleGroupChange('messages')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              05
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>消息流</strong>
              <small>
                {messagesBaseline
                  ? `${messagesBaseline} ${messagesBaseline.startsWith('C') ? '已定基线' : '候选'}`
                  : 'C 双列气泡 已定基线'}
              </small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 06 组：会话侧栏行"
            aria-pressed={activeGroup === 'conversations'}
            onClick={() => handleGroupChange('conversations')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              06
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>会话侧栏行</strong>
              <small>
                {conversationsBaseline
                  ? `${conversationsBaseline} ${
                      conversationsBaseline.startsWith('C') ? '已定基线' : '候选'
                    }`
                  : 'C 双行预览行 已定基线'}
              </small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 07 组：会话管理抽屉"
            aria-pressed={activeGroup === 'management'}
            onClick={() => handleGroupChange('management')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              07
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>会话管理抽屉</strong>
              <small>
                {managementBaseline?.startsWith('B')
                  ? `${managementBaseline} · 已接入`
                  : managementBaseline
                    ? `${managementBaseline} · 候选`
                    : 'B 分组玻璃面板 · 已接入'}
              </small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 08 组：跑步跑前"
            aria-pressed={activeGroup === 'readiness'}
            onClick={() => handleGroupChange('readiness')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              08
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>跑步跑前</strong>
              <small>
                {readinessBaseline
                  ? readinessBaseline.startsWith('B')
                    ? `${readinessBaseline} · 已接入`
                    : `${readinessBaseline} · 对比候选`
                  : 'B 里程仪表 · 已接入'}
              </small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 09 组：跑步跑中"
            aria-pressed={activeGroup === 'activeRun'}
            onClick={() => handleGroupChange('activeRun')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              09
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>跑步跑中</strong>
              <small>
                {activeRunBaseline?.startsWith('A')
                  ? `${activeRunBaseline} · 已接入`
                  : activeRunBaseline
                    ? `${activeRunBaseline} · 对比候选`
                    : 'A 夜色驾驶舱 · 已接入'}
              </small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 10 组：跑步跑后"
            aria-pressed={activeGroup === 'postRun'}
            onClick={() => handleGroupChange('postRun')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              10
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>跑步跑后</strong>
              <small>
                {postRunBaseline?.startsWith('A')
                  ? `${postRunBaseline} · 已接入`
                  : postRunBaseline
                    ? `${postRunBaseline} · 对比候选`
                    : 'A 冲线成绩单 · 已接入'}
              </small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 11 组：跑步记录"
            aria-pressed={activeGroup === 'history'}
            onClick={() => handleGroupChange('history')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              11
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>跑步记录</strong>
              <small>{historyBaseline ? `${historyBaseline} · 候选` : '待选择 A / B / C'}</small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 12 组：设置行状态值"
            aria-pressed={activeGroup === 'settingStatus'}
            onClick={() => handleGroupChange('settingStatus')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              12
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>设置行状态值</strong>
              <small>C · 已接入真实状态与入口右值</small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 13 组：设置项开关"
            aria-pressed={activeGroup === 'settingsToggle'}
            onClick={() => handleGroupChange('settingsToggle')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              13
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>设置项开关</strong>
              <small>C 状态文字 · 已接入</small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 14 组：长期记忆页"
            aria-pressed={activeGroup === 'memoryPage'}
            onClick={() => handleGroupChange('memoryPage')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              14
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>长期记忆页</strong>
              <small>B 分组审阅 · 已接入</small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 15 组：账单与用量页"
            aria-pressed={activeGroup === 'usagePage'}
            onClick={() => handleGroupChange('usagePage')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              15
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>账单与用量页</strong>
              <small>已接入 B 费用与用量</small>
            </span>
          </button>
          <button
            type="button"
            aria-label="查看第 16 组：聊天授权页"
            aria-pressed={activeGroup === 'consentPage'}
            onClick={() => handleGroupChange('consentPage')}
          >
            <span className={styles.componentGroupIndex} aria-hidden="true">
              16
            </span>
            <span className={styles.componentGroupCopy}>
              <strong>聊天授权页</strong>
              <small>已接入 B 范围清单</small>
            </span>
          </button>
          {(['noticesPage', 'pluginsPage'] as const).map((group, index) => (
            <button
              key={group}
              type="button"
              aria-label={`查看第 ${17 + index} 组：${index === 0 ? '用户须知页' : '插件管理页'}`}
              aria-pressed={activeGroup === group}
              onClick={() => handleGroupChange(group)}
            >
              <span className={styles.componentGroupIndex} aria-hidden="true">
                {17 + index}
              </span>
              <span className={styles.componentGroupCopy}>
                <strong>{index === 0 ? '用户须知页' : '插件管理页'}</strong>
                <small>{index === 0 ? '已接入 A 连续阅读' : '已接入 C 接入流程'}</small>
              </span>
            </button>
          ))}
          {(['runDetail', 'runCharts', 'runControls'] as const).map((group, index) => (
            <button
              key={group}
              type="button"
              aria-label={`查看第 ${19 + index} 组：${['跑步详情', '数据图表', '暂停与结束'][index]}`}
              aria-pressed={activeGroup === group}
              onClick={() => handleGroupChange(group)}
            >
              <span className={styles.componentGroupIndex} aria-hidden="true">
                {19 + index}
              </span>
              <span className={styles.componentGroupCopy}>
                <strong>{['跑步详情', '数据图表', '暂停与结束'][index]}</strong>
                <small>{index === 1 ? 'C 窗口审阅 · 已接入' : 'A · 已接入'}</small>
              </span>
            </button>
          ))}
          {(['toolFeedback', 'sourceFeedback', 'recoveryFeedback'] as const).map((group, index) => (
            <button
              key={group}
              type="button"
              aria-label={
                '查看第 ' +
                (22 + index) +
                ' 组：' +
                ['工具执行状态', '来源引用', '发送失败与恢复'][index]
              }
              aria-pressed={activeGroup === group}
              onClick={() => handleGroupChange(group)}
            >
              <span className={styles.componentGroupIndex} aria-hidden="true">
                {22 + index}
              </span>
              <span className={styles.componentGroupCopy}>
                <strong>{['工具执行状态', '来源引用', '发送失败与恢复'][index]}</strong>
                <small>{['B · 已接入', 'A · 已接入', 'A · 已接入'][index]}</small>
              </span>
            </button>
          ))}
          {(['answerActions', 'latestMessage', 'knowledgePicker'] as const).map((group, index) => (
            <button
              key={group}
              type="button"
              aria-label={
                '查看第 ' +
                (25 + index) +
                ' 组：' +
                ['回答操作', '回到最新消息', '知识库选择'][index]
              }
              aria-pressed={activeGroup === group}
              onClick={() => handleGroupChange(group)}
            >
              <span className={styles.componentGroupIndex}>{25 + index}</span>
              <span className={styles.componentGroupCopy}>
                <strong>{['回答操作', '回到最新消息', '知识库选择'][index]}</strong>
                <small>A · 已接入</small>
              </span>
            </button>
          ))}
          {(['loadingEmpty', 'dangerConfirm'] as const).map((group, index) => (
            <button
              key={group}
              type="button"
              aria-label={
                '查看第 ' + (28 + index) + ' 组：' + ['加载与空状态', '危险操作确认'][index]
              }
              aria-pressed={activeGroup === group}
              onClick={() => handleGroupChange(group)}
            >
              <span className={styles.componentGroupIndex}>{28 + index}</span>
              <span className={styles.componentGroupCopy}>
                <strong>{['加载与空状态', '危险操作确认'][index]}</strong>
                <small>B · 已接入，进入整体收尾</small>
              </span>
            </button>
          ))}
        </nav>

        <section className={styles.reviewControls} aria-labelledby="controls-heading">
          <div className={styles.controlsHeading}>
            <h2 id="controls-heading">预览条件</h2>
            <span>{baselineLabel}</span>
          </div>
          <div className={styles.controlGrid}>
            <div className={styles.controlGroup}>
              <span className={styles.controlLabel}>主题</span>
              <div className={styles.segmentedControl} role="group" aria-label="预览主题">
                <button
                  type="button"
                  aria-pressed={theme === 'morning'}
                  onClick={() => setTheme('morning')}
                >
                  <Sun size={15} weight="regular" aria-hidden="true" />
                  Morning
                </button>
                <button
                  type="button"
                  aria-pressed={theme === 'night'}
                  onClick={() => setTheme('night')}
                >
                  <Moon size={15} weight="regular" aria-hidden="true" />
                  Night
                </button>
              </div>
            </div>
            <div className={styles.controlGroup}>
              <span className={styles.controlLabel}>宽度</span>
              <div className={styles.segmentedControl} role="group" aria-label="预览宽度">
                <button
                  type="button"
                  aria-pressed={previewWidth === '393'}
                  onClick={() => setPreviewWidth('393')}
                >
                  393px
                </button>
                <button
                  type="button"
                  aria-pressed={previewWidth === '320'}
                  onClick={() => setPreviewWidth('320')}
                >
                  320px
                </button>
              </div>
            </div>
            <div className={styles.controlGroup}>
              <span className={styles.controlLabel}>状态</span>
              <div className={styles.segmentedControl} role="group" aria-label="预览状态">
                <button
                  type="button"
                  aria-pressed={previewState === 'default'}
                  onClick={() => setPreviewState('default')}
                >
                  常态
                </button>
                <button
                  type="button"
                  aria-pressed={previewState === 'focus'}
                  onClick={() => setPreviewState('focus')}
                >
                  键盘焦点
                </button>
                <button
                  type="button"
                  aria-pressed={previewState === 'long'}
                  onClick={() => setPreviewState('long')}
                >
                  {longStateLabel}
                </button>
                {activeGroup === 'appearance' ||
                activeGroup === 'readiness' ||
                activeGroup === 'activeRun' ||
                activeGroup === 'postRun' ||
                activeGroup === 'history' ||
                activeGroup === 'settingStatus' ||
                activeGroup === 'settingsToggle' ? (
                  <button
                    type="button"
                    aria-pressed={previewState === 'saving'}
                    onClick={() => setPreviewState('saving')}
                  >
                    {activeGroup === 'activeRun'
                      ? '暂停态'
                      : activeGroup === 'readiness'
                        ? '连接中'
                        : activeGroup === 'history'
                          ? '加载更多'
                          : activeGroup === 'settingStatus' || activeGroup === 'settingsToggle'
                            ? '同步中'
                            : '保存中'}
                  </button>
                ) : null}
              </div>
            </div>
            <button
              type="button"
              className={styles.rulerToggle}
              aria-pressed={showRuler}
              onClick={() => setShowRuler((visible) => !visible)}
            >
              <Ruler size={17} weight="regular" aria-hidden="true" />
              {showRuler ? '隐藏尺寸标尺' : '显示尺寸标尺'}
            </button>
          </div>
        </section>

        {activeGroup === 'loadingEmpty' || activeGroup === 'dangerConfirm' ? (
          <FinalStatesReferenceSection key={activeGroup} kind={activeGroup} width={previewWidth} />
        ) : activeGroup === 'answerActions' ||
          activeGroup === 'latestMessage' ||
          activeGroup === 'knowledgePicker' ? (
          <ChatControlsReferenceSection key={activeGroup} kind={activeGroup} width={previewWidth} />
        ) : activeGroup === 'toolFeedback' ||
          activeGroup === 'sourceFeedback' ||
          activeGroup === 'recoveryFeedback' ? (
          <AgentFeedbackReferenceSection
            key={activeGroup}
            kind={activeGroup}
            width={previewWidth}
          />
        ) : activeGroup === 'runDetail' ||
          activeGroup === 'runCharts' ||
          activeGroup === 'runControls' ? (
          <RunReviewReferenceSection
            key={activeGroup}
            kind={
              activeGroup === 'runDetail'
                ? 'detail'
                : activeGroup === 'runCharts'
                  ? 'charts'
                  : 'controls'
            }
            width={previewWidth}
          />
        ) : activeGroup === 'noticesPage' || activeGroup === 'pluginsPage' ? (
          <MorePagesReferenceSection
            key={activeGroup}
            kind={activeGroup === 'noticesPage' ? 'notices' : 'plugins'}
            width={previewWidth}
          />
        ) : activeGroup === 'consentPage' ? (
          <ConsentPageReferenceSection width={previewWidth} />
        ) : activeGroup === 'usagePage' ? (
          <UsagePageReferenceSection width={previewWidth} />
        ) : activeGroup === 'memoryPage' ? (
          <MemoryPageReferenceSection width={previewWidth} />
        ) : activeGroup === 'settingsToggle' ? (
          <SettingsToggleReferenceSection width={previewWidth} />
        ) : activeGroup === 'settingStatus' ? (
          <SettingStatusReferenceSection width={previewWidth} state={previewState} />
        ) : activeGroup === 'history' ? (
          <HistoryReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
            onBaselineChange={setHistoryBaseline}
          />
        ) : activeGroup === 'postRun' ? (
          <RunningCompleteReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
            onBaselineChange={setPostRunBaseline}
          />
        ) : activeGroup === 'activeRun' ? (
          <RunningActiveReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
            onBaselineChange={setActiveRunBaseline}
          />
        ) : activeGroup === 'readiness' ? (
          <RunningReadinessReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
            onBaselineChange={setReadinessBaseline}
          />
        ) : activeGroup === 'management' ? (
          <ConversationManagementReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
            onBaselineChange={setManagementBaseline}
          />
        ) : activeGroup === 'conversations' ? (
          <ConversationRowReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
            onBaselineChange={setConversationsBaseline}
          />
        ) : activeGroup === 'messages' ? (
          <MessageReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
            onBaselineChange={setMessagesBaseline}
          />
        ) : activeGroup === 'composer' ? (
          <ComposerReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
            onBaselineChange={setComposerBaseline}
          />
        ) : activeGroup === 'navigation' ? (
          <>
            <section className={styles.variantsSection} aria-labelledby="variants-heading">
              <header className={styles.sectionHeading}>
                <h2 id="variants-heading">同一组件的三种取舍</h2>
                <p>
                  A 最容易被发现，B 最安静，C
                  的路径层级最清楚。三个预览使用完全相同的设置数据和交互状态。
                </p>
              </header>
              <div className={styles.variantGrid} data-testid="reference-previews">
                {variants.map((variant) => (
                  <VariantCard
                    key={variant.id}
                    variant={variant}
                    title={title}
                    width={previewWidth}
                    state={previewState}
                    showRuler={showRuler}
                    selected={selectedVariant === variant.id}
                    onBack={handleBack}
                    onSelect={handleSelect}
                    onInspect={handleInspect}
                  />
                ))}
              </div>
              <p className={styles.feedback} role="status" aria-live="polite">
                {feedback}
              </p>
            </section>

            <ImplementationDetails
              activeVariant={activeVariant}
              activeTab={activeTab}
              onVariantChange={setActiveVariant}
              onTabChange={setActiveTab}
            />
            <ComponentGlossary />
          </>
        ) : activeGroup === 'list' ? (
          <ListRowReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
          />
        ) : (
          <ThemeSelectorReferenceSection
            width={previewWidth}
            state={previewState}
            showRuler={showRuler}
            ruler={showRuler ? <RulerOverlay /> : null}
          />
        )}
      </div>
    </main>
  );
}
