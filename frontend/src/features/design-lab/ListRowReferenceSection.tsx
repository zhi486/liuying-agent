import {
  Brain,
  CaretRight,
  ChartBar,
  Check,
  PlugsConnected,
  ShieldCheck,
  type Icon,
} from '@phosphor-icons/react';
import { useRef, useState, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type ListVariantId = 'grouped' | 'ledger' | 'action';
type PreviewWidth = '393' | '320';
type PreviewState = 'default' | 'focus' | 'long' | 'saving';
type DetailTab = 'logic' | 'css' | 'a11y';
type RowTone = 'ready' | 'loading' | 'disabled';

interface ListDemoItem {
  id: string;
  title: string;
  description: string;
  meta: string;
  actionLabel: string;
  tone: RowTone;
  icon: Icon;
}

interface ListVariantDefinition {
  id: ListVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  discovery: string;
  density: string;
  narrowScreen: string;
}

interface ListRowReferenceSectionProps {
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  ruler: ReactNode;
}

const CONFIRMED_VARIANT: ListVariantId = 'grouped';

const listVariants: ListVariantDefinition[] = [
  {
    id: 'grouped',
    letter: 'A',
    name: '分组玻璃行',
    summary: '一个分组只做一层毛玻璃，图标、说明和状态在整行内形成稳定节奏。',
    discovery: '最强',
    density: '中等',
    narrowScreen: '稳定',
  },
  {
    id: 'ledger',
    letter: 'B',
    name: '无容器账本行',
    summary: '去掉外层卡片，用细分隔线承载高密度信息，最接近 Codex 的工具列表。',
    discovery: '中等',
    density: '最高',
    narrowScreen: '稳定',
  },
  {
    id: 'action',
    letter: 'C',
    name: '显式动作行',
    summary: '每行只让右侧动作按钮可点，删除箭头，操作意图最明确但纵向占用最大。',
    discovery: '最强',
    density: '偏低',
    narrowScreen: '注意长动作',
  },
];

const baseDemoItems: ListDemoItem[] = [
  {
    id: 'consent',
    title: '聊天授权',
    description: '查看哪些会话可以发送给在线 Agent',
    meta: '已授权',
    actionLabel: '查看',
    tone: 'ready',
    icon: ShieldCheck,
  },
  {
    id: 'memory',
    title: '长期记忆',
    description: '管理跑步习惯、表达偏好和已保存事实',
    meta: '12 条',
    actionLabel: '管理',
    tone: 'ready',
    icon: Brain,
  },
  {
    id: 'mcp',
    title: 'MCP 插件',
    description: '连接工具与数据源',
    meta: '同步中',
    actionLabel: '同步中',
    tone: 'loading',
    icon: PlugsConnected,
  },
  {
    id: 'usage',
    title: '账单与用量',
    description: '查看本月模型调用和剩余额度',
    meta: '¥12.40',
    actionLabel: '查看',
    tone: 'ready',
    icon: ChartBar,
  },
];

const detailTabs: { id: DetailTab; label: string }[] = [
  { id: 'logic', label: 'React 逻辑' },
  { id: 'css', label: 'CSS' },
  { id: 'a11y', label: '无障碍' },
];

const detailCopy: Record<
  ListVariantId,
  Record<DetailTab, { heading: string; code: string; notes: string[] }>
> = {
  grouped: {
    logic: {
      heading: '导航行用 Link，忙碌或禁用行不进入 Tab 顺序',
      code: `type ListItem = {
  id: string;
  to: string;
  title: string;
  meta: string;
  state: 'ready' | 'loading' | 'disabled';
};

function SettingRow({ item }: { item: ListItem }) {
  const content = <RowContent item={item} />;

  if (item.state !== 'ready') {
    return (
      <div aria-busy={item.state === 'loading'} data-disabled>
        {content}
      </div>
    );
  }

  return <Link to={item.to}>{content}</Link>;
}`,
      notes: [
        '页面导航使用 Link，保留新窗口、组合键点击和浏览器原生语义。',
        '同步中的行退出点击与 Tab 顺序，但保留文字状态，不能只靠变灰表达。',
        '视觉行高至少 64px；图标、标题、说明和右值共用同一垂直中心。',
      ],
    },
    css: {
      heading: '毛玻璃属于分组表面，不属于每一行',
      code: `.rowGroup {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 1rem;
  background: var(--glass-strong);
  backdrop-filter: blur(18px) saturate(1.16);
}

.row {
  display: grid;
  min-height: 4.25rem;
  grid-template-columns: 2.35rem minmax(0, 1fr) auto 1rem;
  align-items: center;
  gap: 0.62rem;
  padding: 0.62rem 0.7rem;
}

.rowTitle {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}`,
      notes: [
        '连续叠多层 backdrop-filter 会让 Android WebView 掉帧，只在分组外表面做一次。',
        '标题单行省略，说明最多两行；长文本不推挤右侧状态。',
        '分隔线贯穿内容区，行本身不做独立卡片，避免形成卡中卡。',
      ],
    },
    a11y: {
      heading: '整行是一个命中区，视觉子元素不制造多个焦点',
      code: `<ul aria-label="Agent 与数据">
  <li>
    <Link
      to="/settings/chat"
      aria-label="聊天授权，已授权"
      title="聊天授权"
    >
      <ShieldCheck aria-hidden="true" />
      <span>聊天授权</span>
      <span aria-hidden="true">已授权</span>
      <CaretRight aria-hidden="true" />
    </Link>
  </li>
  <li>
    <div aria-busy="true" aria-disabled="true">
      <span>MCP 插件</span>
      <span>同步中</span>
    </div>
  </li>
</ul>`,
      notes: [
        '可访问名称包含对象和当前状态，避免只听到一排“查看”。',
        '图标与箭头全部 aria-hidden，装饰不能重复朗读。',
        '加载行保留可读文字，用 aria-busy 告诉辅助技术正在更新。',
      ],
    },
  },
  ledger: {
    logic: {
      heading: '先分组数据，再渲染稳定行，不在 JSX 深处临时判断顺序',
      code: `const groups = useMemo(
  () => [
    { id: 'agent', label: 'Agent 与数据', items: agentItems },
    { id: 'run', label: '跑步', items: runItems },
  ],
  [agentItems, runItems],
);

return groups.map((group) => (
  <section key={group.id} aria-labelledby={group.id}>
    <h2 id={group.id}>{group.label}</h2>
    <ul>{group.items.map((item) => (
      <SettingRow key={item.id} item={item} />
    ))}</ul>
  </section>
));`,
      notes: [
        '分组在数据层完成，标题和列表共用同一个 group id，ARIA 关系稳定。',
        '所有行使用同一套排序与筛选结果，不在渲染时重复计算。',
        '行状态由数据模型给出，样式不猜测“看起来像加载中”。',
      ],
    },
    css: {
      heading: '用发丝分隔线和留白建立密度，不用更小的字号硬塞',
      code: `.ledger {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.ledgerRow {
  display: grid;
  min-height: 3.4rem;
  grid-template-columns: 1.15rem minmax(0, 1fr) auto 1rem;
  align-items: center;
  gap: 0.5rem;
  padding: 0.48rem 0.35rem;
  border-bottom: 1px solid var(--line);
}

.ledgerRow:last-child {
  border-bottom: 0;
}`,
      notes: [
        '账本行仍保留 54px 以上命中高度，密度不靠压缩触控目标换取。',
        '说明只在第二行占一行，超过时省略，完整内容留给详情页。',
        '外层不再加圆角卡片和阴影，页面结构因此更像工作区而不是设置商城。',
      ],
    },
    a11y: {
      heading: '列表标题和每一行都保留稳定的可访问关系',
      code: `<section aria-labelledby="agent-settings">
  <h2 id="agent-settings">Agent 与数据</h2>
  <ul>
    <li>
      <Link to="/settings/usage" aria-label="账单与用量，本月 12.40 元">
        <ChartBar aria-hidden="true" />
        <strong>账单与用量</strong>
        <span aria-hidden="true">¥12.40</span>
        <CaretRight aria-hidden="true" />
      </Link>
    </li>
  </ul>
</section>`,
      notes: [
        '分组标题使用真实 h2，不把大号正文伪装成标题。',
        '金额和条数提供完整朗读文本，避免读成只有符号。',
        '焦点环覆盖整行而不是只包住标题，键盘用户能看到当前命中区。',
      ],
    },
  },
  action: {
    logic: {
      heading: '异步动作由行状态驱动，重复点击在请求完成前被忽略',
      code: `function AsyncSettingRow({ item }: { item: ListItem }) {
  const [pending, setPending] = useState(false);

  async function runAction() {
    if (pending) return;
    setPending(true);
    try {
      await item.onOpen();
    } finally {
      setPending(false);
    }
  }

  return (
    <div className={styles.row}>
      <RowCopy item={item} />
      <button
        type="button"
        aria-label={\`\${item.actionLabel}\${item.title}\`}
        aria-busy={pending}
        disabled={pending || item.state === 'disabled'}
        onClick={runAction}
      >
        {pending ? '同步中' : item.actionLabel}
      </button>
    </div>
  );
}`,
      notes: [
        'pending 只锁本次动作，不会冻结整个设置页。',
        '请求失败时恢复 pending 并在行下方显示同一动作的错误与重试。',
        '按钮文案说明动作，行标题说明对象，避免出现十二个没有上下文的“查看”。',
      ],
    },
    css: {
      heading: '行是信息容器，右侧 44px 按钮才是唯一可点击动作',
      code: `.actionRow {
  display: grid;
  min-height: 4.5rem;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 0.7rem;
  padding: 0.72rem 0.78rem;
  border: 1px solid var(--line);
  border-radius: 0.95rem;
  background: var(--glass-strong);
}

.actionButton {
  min-width: 4.25rem;
  min-height: 2.75rem;
  padding: 0 0.82rem;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
}`,
      notes: [
        '只有动作按钮有 hover、按下和焦点，整行不再暗示可点击。',
        '320px 下动作按钮不换行，标题和说明负责让出空间。',
        '忙碌时按钮保留原宽度，避免状态文字变化导致整行横向抖动。',
      ],
    },
    a11y: {
      heading: '常驻可见按钮比悬停后才出现的动作更容易发现',
      code: `<div className={styles.actionRow}>
  <div>
    <strong>长期记忆</strong>
    <p>管理跑步习惯和已保存事实</p>
  </div>
  <button
    type="button"
    aria-label="管理长期记忆"
    aria-busy={pending}
    disabled={pending}
  >
    {pending ? '正在打开' : '管理'}
  </button>
</div>`,
      notes: [
        '按钮在触屏设备上始终可见，不依赖 hover 或长按。',
        '禁用态不替代错误提示；失败后必须说明问题并提供恢复动作。',
        '按钮宽度固定，loading、默认、禁用三种状态共用同一几何。',
      ],
    },
  },
};

const terminology = [
  {
    term: '整行命中区',
    description: '整行都可点击时，用户不必精确点中文字；同时只能产生一个键盘焦点。',
  },
  {
    term: '信息线索',
    description: '标题、说明和状态共同告诉用户点进去会看到什么，而不是只靠箭头暗示。',
  },
  {
    term: '视觉权重',
    description: '主标题更重、说明更轻、状态居中；不是把每个元素都放大或加粗。',
  },
  {
    term: '发丝分隔线',
    description: '1px 低对比分隔线组织高密度列表，比每行一张卡片更安静。',
  },
  {
    term: '忙碌态',
    description: '表示操作已开始但尚未完成。必须配文字，并阻止重复提交。',
  },
  {
    term: '容器查询',
    description: '按组件自身宽度调整布局，而不是猜测整个浏览器窗口有多宽。',
  },
];

function getDemoItems(state: PreviewState): ListDemoItem[] {
  if (state !== 'long') return baseDemoItems;

  return baseDemoItems.map((item) =>
    item.id === 'consent'
      ? {
          ...item,
          title: '聊天持续授权与历史记录管理',
          description: '查看哪些会话可以发送给在线 Agent，并管理已经授予的长期访问范围',
          meta: '已授权',
        }
      : item,
  );
}

function isDisabled(item: ListDemoItem) {
  return item.tone !== 'ready';
}

interface ListPreviewProps {
  items: ListDemoItem[];
  state: PreviewState;
  onAction: (item: ListDemoItem) => void;
}

function RowIcon({ item, compact = false }: { item: ListDemoItem; compact?: boolean }) {
  const ItemIcon = item.icon;

  return (
    <span className={compact ? styles.ledgerIcon : styles.listRowIcon} aria-hidden="true">
      <ItemIcon size={compact ? 17 : 19} weight="regular" />
    </span>
  );
}

function RowCopy({ item, ledger = false }: { item: ListDemoItem; ledger?: boolean }) {
  return (
    <span className={ledger ? styles.ledgerCopy : styles.listRowCopy}>
      <strong className={styles.listRowTitle} title={item.title}>
        {item.title}
      </strong>
      <small className={styles.listRowDescription}>{item.description}</small>
    </span>
  );
}

function RowMeta({ item, compact = false }: { item: ListDemoItem; compact?: boolean }) {
  return (
    <span className={compact ? styles.ledgerMeta : styles.rowStatus} data-tone={item.tone}>
      <span className={styles.rowStatusDot} aria-hidden="true" />
      {item.meta}
    </span>
  );
}

function getActionAccessibleLabel(item: ListDemoItem) {
  if (item.tone === 'loading') return `${item.title}正在同步`;
  if (item.tone === 'disabled') return `${item.title}暂不可用`;
  return `${item.actionLabel}${item.title}`;
}

function GroupedList({ items, state, onAction }: ListPreviewProps) {
  return (
    <div className={styles.listDemoBody}>
      <div className={styles.listGroupHeading}>
        <strong>Agent 与数据</strong>
        <span>本机</span>
      </div>
      <div className={styles.groupedListSurface}>
        <ul className={styles.groupedList} aria-label="Agent 与数据">
          {items.map((item, index) => (
            <li key={item.id}>
              <button
                type="button"
                className={`${styles.groupedListRow} ${
                  state === 'focus' && index === 0 ? styles.forceFocus : ''
                }`}
                disabled={isDisabled(item)}
                aria-busy={item.tone === 'loading'}
                aria-label={`${item.title}，${item.meta}`}
                onClick={() => onAction(item)}
              >
                <RowIcon item={item} />
                <RowCopy item={item} />
                <RowMeta item={item} />
                <CaretRight className={styles.rowCaret} size={15} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function LedgerList({ items, state, onAction }: ListPreviewProps) {
  return (
    <div className={styles.listDemoBody}>
      <div className={styles.ledgerHeading}>
        <strong>Agent 与数据</strong>
        <span>4 个入口</span>
      </div>
      <div className={styles.ledgerSurface}>
        <ul className={styles.ledgerList} aria-label="Agent 与数据">
          {items.map((item, index) => (
            <li key={item.id}>
              <button
                type="button"
                className={`${styles.ledgerRow} ${
                  state === 'focus' && index === 0 ? styles.forceFocus : ''
                }`}
                disabled={isDisabled(item)}
                aria-busy={item.tone === 'loading'}
                aria-label={`${item.title}，${item.meta}`}
                onClick={() => onAction(item)}
              >
                <RowIcon item={item} compact />
                <RowCopy item={item} ledger />
                <RowMeta item={item} compact />
                <CaretRight className={styles.rowCaret} size={14} aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function ActionList({ items, state, onAction }: ListPreviewProps) {
  return (
    <div className={styles.listDemoBody}>
      <div className={styles.actionListHeading}>
        <strong>Agent 与数据</strong>
        <span>选择右侧动作</span>
      </div>
      <ul className={styles.actionList} aria-label="Agent 与数据">
        {items.map((item, index) => (
          <li key={item.id} className={styles.actionRow} data-tone={item.tone}>
            <span className={styles.actionCopy}>
              <RowIcon item={item} />
              <RowCopy item={item} />
            </span>
            <span className={styles.actionControls}>
              <RowMeta item={item} />
              <button
                type="button"
                className={`${styles.actionButton} ${
                  state === 'focus' && index === 0 ? styles.forceFocus : ''
                }`}
                aria-label={getActionAccessibleLabel(item)}
                aria-busy={item.tone === 'loading'}
                disabled={isDisabled(item)}
                onClick={() => onAction(item)}
              >
                {item.actionLabel}
              </button>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

interface ListPreviewCanvasProps extends ListPreviewProps {
  variant: ListVariantId;
  width: PreviewWidth;
  showRuler: boolean;
  ruler: ReactNode;
}

function ListPreviewCanvas({
  variant,
  items,
  width,
  state,
  showRuler,
  ruler,
  onAction,
}: ListPreviewCanvasProps) {
  return (
    <div className={styles.previewFrame}>
      <div
        className={`${styles.demoCanvas} ${styles.listDemoCanvas}`}
        data-width={width}
        data-variant={variant}
      >
        {variant === 'grouped' ? (
          <GroupedList items={items} state={state} onAction={onAction} />
        ) : null}
        {variant === 'ledger' ? (
          <LedgerList items={items} state={state} onAction={onAction} />
        ) : null}
        {variant === 'action' ? (
          <ActionList items={items} state={state} onAction={onAction} />
        ) : null}
        {showRuler ? ruler : null}
      </div>
    </div>
  );
}

interface ListVariantCardProps {
  variant: ListVariantDefinition;
  items: ListDemoItem[];
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  ruler: ReactNode;
  isBaseline: boolean;
  onAction: (item: ListDemoItem) => void;
  onInspect: (variant: ListVariantId) => void;
}

function ListVariantCard({
  variant,
  items,
  width,
  state,
  showRuler,
  ruler,
  isBaseline,
  onAction,
  onInspect,
}: ListVariantCardProps) {
  return (
    <article className={`${styles.variantCard} ${isBaseline ? styles.variantSelected : ''}`}>
      <header className={styles.variantHeader}>
        <span className={styles.variantMark} aria-hidden="true">
          {variant.letter}
        </span>
        <div className={styles.variantHeading}>
          <div className={styles.variantNameRow}>
            <h3>{variant.name}</h3>
            <span className={isBaseline ? styles.recommendedBadge : styles.candidateBadge}>
              {isBaseline ? '已确认基线' : '候选'}
            </span>
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <ListPreviewCanvas
        variant={variant.id}
        items={items}
        width={width}
        state={state}
        showRuler={showRuler}
        ruler={ruler}
        onAction={onAction}
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
        {isBaseline ? (
          <span className={styles.baselineStatus}>
            <Check size={17} weight="bold" aria-hidden="true" />
            已选为下一版基线
          </span>
        ) : (
          <span className={styles.candidateStatus}>候选仅供对照</span>
        )}
        <button
          type="button"
          className={styles.inspectButton}
          aria-label={`查看${variant.name}的实现`}
          onClick={() => onInspect(variant.id)}
        >
          看实现
          <CaretRight size={15} weight="bold" aria-hidden="true" />
        </button>
      </footer>
    </article>
  );
}

interface ListImplementationDetailsProps {
  activeVariant: ListVariantId;
  activeTab: DetailTab;
  onVariantChange: (variant: ListVariantId) => void;
  onTabChange: (tab: DetailTab) => void;
}

function ListImplementationDetails({
  activeVariant,
  activeTab,
  onVariantChange,
  onTabChange,
}: ListImplementationDetailsProps) {
  const tabRefs = useRef<Record<DetailTab, HTMLButtonElement | null>>({
    logic: null,
    css: null,
    a11y: null,
  });
  const activeDefinition =
    listVariants.find((variant) => variant.id === activeVariant) ?? listVariants[0];
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
      id="list-implementation"
      className={styles.implementation}
      aria-labelledby="list-implementation-heading"
      tabIndex={-1}
    >
      <header className={styles.sectionHeading}>
        <h2 id="list-implementation-heading">同一组列表，三套实现细节</h2>
        <p>A 的 React、CSS 与键盘行为作为下一版基线；B、C 保留为对照实现。</p>
      </header>

      <div className={styles.implementationControls}>
        <div className={styles.variantSwitch} role="group" aria-label="查看哪个列表参考的实现">
          {listVariants.map((variant) => (
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
        <div className={styles.detailTabs} role="tablist" aria-label="列表实现明细">
          {detailTabs.map((tab) => (
            <button
              key={tab.id}
              ref={(element) => {
                tabRefs.current[tab.id] = element;
              }}
              id={`list-detail-tab-${tab.id}`}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              aria-controls={`list-detail-panel-${tab.id}`}
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
        id={`list-detail-panel-${activeTab}`}
        className={styles.detailPanel}
        role="tabpanel"
        aria-labelledby={`list-detail-tab-${activeTab}`}
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

function ListGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="list-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="list-glossary-heading">列表行再掌握六个词</h2>
        <p>这些词能帮你准确指出“是太挤、太碎，还是根本不知道哪里能点”。</p>
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

export function ListRowReferenceSection({
  width,
  state,
  showRuler,
  ruler,
}: ListRowReferenceSectionProps) {
  const [activeVariant, setActiveVariant] = useState<ListVariantId>(CONFIRMED_VARIANT);
  const [activeTab, setActiveTab] = useState<DetailTab>('logic');
  const [feedback, setFeedback] = useState(
    'A「分组玻璃行」已选为列表行下一版基线；B、C 保留为对照，不会覆盖当前决定。',
  );
  const items = getDemoItems(state);

  const handleAction = (variantName: string, item: ListDemoItem) => {
    setFeedback(
      `已触发“${variantName}”中的“${item.title}”：这里只演示行级交互，不会修改授权、记忆、插件或账单数据。`,
    );
  };

  const handleInspect = (variant: ListVariantId) => {
    setActiveVariant(variant);
    setActiveTab('logic');
    const section = document.getElementById('list-implementation');
    try {
      section?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch {
      // jsdom 没有布局引擎；真实浏览器仍会正常滚动。
    }
    section?.focus({ preventScroll: true });
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="list-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="list-variants-heading">列表行动的三种取舍</h2>
          <p>
            A 已选为下一版基线，强调分组与发现性；B、C 分别保留 Codex
            式高密度与显式动作路径，作为后续细节对照。
          </p>
        </header>
        <div className={styles.variantGrid} data-testid="list-row-previews">
          {listVariants.map((variant) => (
            <ListVariantCard
              key={variant.id}
              variant={variant}
              items={items}
              width={width}
              state={state}
              showRuler={showRuler}
              ruler={ruler}
              isBaseline={variant.id === CONFIRMED_VARIANT}
              onAction={(item) => handleAction(variant.name, item)}
              onInspect={handleInspect}
            />
          ))}
        </div>
        <p className={styles.feedback} role="status" aria-live="polite">
          {feedback}
        </p>
      </section>

      <ListImplementationDetails
        activeVariant={activeVariant}
        activeTab={activeTab}
        onVariantChange={setActiveVariant}
        onTabChange={setActiveTab}
      />
      <ListGlossary />
    </>
  );
}
