import { Check, DotsThree, MagnifyingGlass } from '@phosphor-icons/react';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type ConversationVariantId = 'command' | 'glass' | 'preview';
type ConversationPreviewState = 'default' | 'focus' | 'long' | 'saving';
type DetailTab = 'logic' | 'css' | 'a11y';

interface ConversationVariantDefinition {
  id: ConversationVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  density: string;
  current: string;
  bestFor: string;
  recommended?: boolean;
}

interface ConversationItem {
  id: string;
  title: string;
  preview: string;
  time: string;
  unread: boolean;
}

interface ConversationReferenceSectionProps {
  width: '393' | '320';
  state: ConversationPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  onBaselineChange: (name: string | null) => void;
}

const CONVERSATION_STORAGE_KEY = 'healthy-agent:ui-reference:conversations';

const conversations: readonly ConversationItem[] = [
  {
    id: 'recovery',
    title: '训练恢复建议',
    preview: '今天起床后大腿有点紧，今天怎么安排更稳妥？',
    time: '12 分钟',
    unread: true,
  },
  {
    id: 'week',
    title: '本周训练安排',
    preview: '按当前状态安排目的与强度，避免连续高强度。',
    time: '2 小时',
    unread: false,
  },
  {
    id: 'review',
    title: '昨晚轻松跑复盘',
    preview: '只看合成摘要，不覆盖原始跑步记录。',
    time: '昨天',
    unread: false,
  },
  {
    id: 'notes',
    title: '本周状态记录',
    preview: '睡眠、腿部紧张和主观疲劳需要一起比较。',
    time: '周三',
    unread: false,
  },
];

const longTitle = '聊天持续授权与历史记录管理的设置确认和归档清理';

const conversationVariants: ConversationVariantDefinition[] = [
  {
    id: 'command',
    letter: 'A',
    name: 'Codex 线程行',
    summary: '无卡片、单行高密度，当前项只用浅底和细轨标记，最接近 Codex 侧栏。',
    density: '高',
    current: '细轨',
    bestFor: '长历史',
    recommended: true,
  },
  {
    id: 'glass',
    letter: 'B',
    name: '玻璃分组行',
    summary: '一组会话共享毛玻璃表面，当前项使用轻量高亮，发现性更强。',
    density: '中',
    current: '高亮',
    bestFor: '少量会话',
  },
  {
    id: 'preview',
    letter: 'C',
    name: '双行预览行',
    summary: '标题下方展示最近问题摘要，适合通过上下文找回旧对话。',
    density: '低',
    current: '预览',
    bestFor: '内容回忆',
  },
];

const detailTabs: { id: DetailTab; label: string }[] = [
  { id: 'logic', label: 'React 逻辑' },
  { id: 'css', label: 'CSS' },
  { id: 'a11y', label: '无障碍' },
];

const detailCopy: Record<
  ConversationVariantId,
  Record<DetailTab, { heading: string; code: string; notes: string[] }>
> = {
  command: {
    logic: {
      heading: '当前会话由稳定 ID 判断，行点击与管理动作完全分离',
      code: `const [selectedId, setSelectedId] = useState(items[0].id);

<li>
  <button
    aria-current={selectedId === item.id ? 'page' : undefined}
    onClick={() => setSelectedId(item.id)}
  >
    {item.title}
  </button>
  <button aria-label={\`管理对话：\${item.title}\`}>
    管理
  </button>
</li>`,
      notes: [
        '选择会话与打开管理菜单是两个独立动作，不能把按钮嵌套在按钮里。',
        '当前项使用 aria-current=page，刷新或切换会话后仍由数据决定。',
        '演示中切换只改变本地 selectedId，不调用真实 conversation API。',
      ],
    },
    css: {
      heading: '单行行高固定，长标题只省略不挤压右侧动作',
      code: `.threadRow {
  display: grid;
  min-height: 52px;
  grid-template-columns: minmax(0, 1fr) 44px;
  align-items: center;
}

.threadTitle {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.threadRow[data-current='true'] {
  background: var(--agent-surface-strong);
}`,
      notes: [
        '行高固定为 52px，时间、未读点和按钮变化不会让列表上下跳动。',
        '分组标题使用 1px 分隔线和留白，不再为每行添加独立卡片。',
        '当前项背景与焦点环是两套状态，同时出现时仍能区分键盘焦点。',
      ],
    },
    a11y: {
      heading: '完整标题是按钮的可访问名称，管理按钮不参与当前项判断',
      code: `<button
  aria-current={current ? 'page' : undefined}
  aria-label={\`打开会话：\${item.title}\${item.unread ? '，未读' : ''}\`}
>
  <strong>{item.title}</strong>
  <time>{item.time}</time>
</button>
<button aria-label={\`管理对话：\${item.title}\`}>
  <DotsThree aria-hidden="true" />
</button>`,
      notes: [
        '视觉上的省略号只截断像素，屏幕阅读器仍拿到完整标题。',
        '未读状态进入可访问名称，不依赖单个彩色圆点。',
        '菜单打开后焦点进入第一项，Esc 关闭并把焦点还给管理按钮。',
      ],
    },
  },
  glass: {
    logic: {
      heading: '分组表面由容器承担，选中状态落在行而不重复套卡片',
      code: `const groups = groupByPeriod(items);

<section className="conversationGroup">
  <h3>30 天内</h3>
  <ul>
    {groups.recent.map((item) => (
      <ConversationRow
        key={item.id}
        selected={selectedId === item.id}
        onSelect={setSelectedId}
      />
    ))}
  </ul>
</section>`,
      notes: [
        '毛玻璃只作用于外层会话组，不为每一行重复叠滤镜。',
        '选中状态仍按会话 ID 判断，动画和背景不承担业务语义。',
        '分组变化由更新时间计算，不在 JSX 中手写固定分组。',
      ],
    },
    css: {
      heading: '外层圆角，内部分隔线，当前行只加一层柔和高亮',
      code: `.conversationGroup {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--glass);
  backdrop-filter: blur(18px);
}

.conversationRow + .conversationRow {
  border-top: 1px solid var(--line);
}

.conversationRow[data-current='true'] {
  background: var(--accent-soft);
}`,
      notes: [
        '圆角只出现在组容器，内部行保持直角，避免卡片套卡片。',
        '行高不低于 52px，操作按钮保持 44px，适合触屏点击。',
        '当前项高亮使用低饱和色调，避免每个会话都变成主按钮。',
      ],
    },
    a11y: {
      heading: '分组标题进入阅读顺序，列表保留明确的 list 语义',
      code: `<section aria-labelledby="recent-title">
  <h3 id="recent-title">30 天内</h3>
  <ul>
    <li>
      <button aria-current="page">训练恢复建议</button>
    </li>
  </ul>
</section>`,
      notes: [
        '组标题与列表关联，读屏用户能知道“30 天内”作用于哪些会话。',
        '选中行不新增嵌套 role=button，整行仍只有一个主要选择动作。',
        '未读角标提供文字替代，不由颜色或毛玻璃透明度表达。',
      ],
    },
  },
  preview: {
    logic: {
      heading: '标题和摘要来自同一条会话数据，第二行不参与点击拆分',
      code: `function ConversationPreview({ item, selected, onSelect }) {
  return (
    <button
      onClick={() => onSelect(item.id)}
      aria-current={selected ? 'page' : undefined}
    >
      <span>{item.title}</span>
      <time>{item.time}</time>
      <small>{item.preview}</small>
    </button>
  );
}`,
      notes: [
        '标题、时间和摘要都在同一个按钮内，不会出现只有标题可点的问题。',
        '摘要使用真实最近问题，不以假称“最后一条回答”混淆数据来源。',
        '长标题状态只改变测试数据，不改变行组件或业务事件。',
      ],
    },
    css: {
      heading: '首行对齐标题与时间，第二行限制为单行摘要',
      code: `.previewCopy {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.25rem 0.6rem;
}

.previewTitle,
.previewSnippet {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.previewSnippet {
  grid-column: 1 / -1;
  color: var(--text-muted);
  font-size: 0.68rem;
}`,
      notes: [
        '两行模式把行高提升到 66px，但摘要仍只占一行，避免列表失控。',
        '时间列宽度由内容决定，标题列用 minmax(0,1fr) 防止长标题撑破。',
        '选中态改变浅底和文字权重，不改变行高与摘要位置。',
      ],
    },
    a11y: {
      heading: '摘要属于会话按钮名称，时间不打断标题阅读',
      code: `<button
  aria-label={\`打开会话：\${item.title}。最近问题：\${item.preview}\`}
>
  <strong>{item.title}</strong>
  <time>{item.time}</time>
  <span>{item.preview}</span>
</button>`,
      notes: [
        '可访问名称包含标题和最近问题，视觉省略不会丢失上下文。',
        '时间保留原生 time 元素；装饰图标全部 aria-hidden。',
        '管理菜单位于选择按钮之外，避免嵌套交互控件。',
      ],
    },
  },
};

const terminology = [
  {
    term: 'current item',
    description: '当前正在阅读的会话。视觉高亮必须与 aria-current 和真实会话 ID 同步。',
  },
  {
    term: 'row density',
    description: '一屏能容纳多少会话。高密度适合长历史，双行预览适合内容回忆。',
  },
  {
    term: 'ellipsis',
    description: '长标题的省略策略。视觉可以截断，但按钮名称必须保留完整标题。',
  },
  {
    term: 'group surface',
    description: '一组会话共享的表面。圆角和毛玻璃应放在组容器，而不是每一行。',
  },
  {
    term: 'action overflow',
    description: '低频管理动作的收纳方式。它应独立成按钮，不能嵌套在选择按钮中。',
  },
  {
    term: 'roving focus',
    description: '用键盘上下键在列表中移动焦点、用 Enter 激活。比逐行按 Tab 更高效。',
  },
];

function readStoredVariant(): ConversationVariantId | null {
  try {
    const value = window.localStorage.getItem(CONVERSATION_STORAGE_KEY);
    return conversationVariants.some((variant) => variant.id === value)
      ? (value as ConversationVariantId)
      : null;
  } catch {
    return null;
  }
}

function ConversationMenu({
  item,
  open,
  menuId,
  onToggle,
  onClose,
  triggerRef,
}: {
  item: ConversationItem;
  open: boolean;
  menuId: string;
  onToggle: () => void;
  onClose: () => void;
  triggerRef: (element: HTMLButtonElement | null) => void;
}) {
  const firstItem = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (open) firstItem.current?.focus({ preventScroll: true });
  }, [open]);

  return (
    <div className={styles.conversationActionWrap}>
      <button
        ref={triggerRef}
        type="button"
        className={styles.conversationRowAction}
        aria-label={`管理对话：${item.title}`}
        aria-expanded={open}
        aria-controls={menuId}
        onClick={onToggle}
      >
        <DotsThree size={20} weight="bold" aria-hidden="true" />
      </button>
      {open && (
        <div
          id={menuId}
          className={styles.conversationRowMenu}
          role="menu"
          aria-label={`管理 ${item.title}`}
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault();
              onClose();
            }
          }}
        >
          <button ref={firstItem} type="button" role="menuitem" onClick={onClose}>
            重命名会话
          </button>
          <button type="button" role="menuitem" onClick={onClose}>
            归档会话
          </button>
        </div>
      )}
    </div>
  );
}

function CommandConversation({
  selectedId,
  onSelect,
  long,
}: {
  selectedId: string;
  onSelect: (id: string) => void;
  long: boolean;
}) {
  const [menuId, setMenuId] = useState<string | null>(null);
  const menuIdBase = useId();
  const actionRefs = useRef(new Map<string, HTMLButtonElement>());

  return (
    <ul className={styles.commandConversationList} aria-label="最近对话">
      {conversations.map((item, index) => {
        const title = long && index === 0 ? longTitle : item.title;
        const selected = selectedId === item.id;
        return (
          <li key={item.id} className={styles.commandConversationRow} data-current={selected}>
            <button
              type="button"
              className={styles.commandConversationSelect}
              aria-current={selected ? 'page' : undefined}
              aria-label={`打开会话：${title}${item.unread ? '，未读' : ''}`}
              title={title}
              onClick={() => onSelect(item.id)}
            >
              <span className={styles.commandConversationCopy}>
                <strong>{title}</strong>
                <small>{item.unread ? '未读 · 合成会话' : '合成会话'}</small>
              </span>
              <time>{item.time}</time>
            </button>
            <ConversationMenu
              item={{ ...item, title }}
              open={menuId === item.id}
              menuId={`${menuIdBase}-${item.id}`}
              triggerRef={(element) => {
                if (element) actionRefs.current.set(item.id, element);
                else actionRefs.current.delete(item.id);
              }}
              onToggle={() => setMenuId((current) => (current === item.id ? null : item.id))}
              onClose={() => {
                setMenuId(null);
                actionRefs.current.get(item.id)?.focus({ preventScroll: true });
              }}
            />
          </li>
        );
      })}
    </ul>
  );
}

function GlassConversation({
  selectedId,
  onSelect,
  long,
}: {
  selectedId: string;
  onSelect: (id: string) => void;
  long: boolean;
}) {
  const [menuId, setMenuId] = useState<string | null>(null);
  const menuIdBase = useId();
  const actionRefs = useRef(new Map<string, HTMLButtonElement>());

  return (
    <section className={styles.glassConversationGroup} aria-labelledby="glass-conversation-heading">
      <h3 id="glass-conversation-heading">30 天内</h3>
      <ul aria-label="最近对话">
        {conversations.map((item, index) => {
          const title = long && index === 0 ? longTitle : item.title;
          const selected = selectedId === item.id;
          return (
            <li key={item.id} className={styles.glassConversationRow} data-current={selected}>
              <span className={styles.glassConversationMark} aria-hidden="true" />
              <button
                type="button"
                className={styles.glassConversationSelect}
                aria-current={selected ? 'page' : undefined}
                aria-label={`打开会话：${title}${item.unread ? '，未读' : ''}`}
                title={title}
                onClick={() => onSelect(item.id)}
              >
                <span>
                  <strong>{title}</strong>
                  <small>{item.preview}</small>
                </span>
                <time>{item.time}</time>
              </button>
              <ConversationMenu
                item={{ ...item, title }}
                open={menuId === item.id}
                menuId={`${menuIdBase}-${item.id}`}
                triggerRef={(element) => {
                  if (element) actionRefs.current.set(item.id, element);
                  else actionRefs.current.delete(item.id);
                }}
                onToggle={() => setMenuId((current) => (current === item.id ? null : item.id))}
                onClose={() => {
                  setMenuId(null);
                  actionRefs.current.get(item.id)?.focus({ preventScroll: true });
                }}
              />
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function PreviewConversation({
  selectedId,
  onSelect,
  long,
}: {
  selectedId: string;
  onSelect: (id: string) => void;
  long: boolean;
}) {
  const [menuId, setMenuId] = useState<string | null>(null);
  const menuIdBase = useId();
  const actionRefs = useRef(new Map<string, HTMLButtonElement>());

  return (
    <ul className={styles.previewConversationList} aria-label="最近对话">
      {conversations.map((item, index) => {
        const title = long && index === 0 ? longTitle : item.title;
        const selected = selectedId === item.id;
        return (
          <li key={item.id} className={styles.previewConversationRow} data-current={selected}>
            <button
              type="button"
              className={styles.previewConversationSelect}
              aria-current={selected ? 'page' : undefined}
              aria-label={`打开会话：${title}。最近问题：${item.preview}${item.unread ? '。未读' : ''}`}
              title={title}
              onClick={() => onSelect(item.id)}
            >
              <span className={styles.previewConversationTop}>
                <strong>{title}</strong>
                <time>{item.time}</time>
              </span>
              <span className={styles.previewConversationSnippet}>{item.preview}</span>
            </button>
            <ConversationMenu
              item={{ ...item, title }}
              open={menuId === item.id}
              menuId={`${menuIdBase}-${item.id}`}
              triggerRef={(element) => {
                if (element) actionRefs.current.set(item.id, element);
                else actionRefs.current.delete(item.id);
              }}
              onToggle={() => setMenuId((current) => (current === item.id ? null : item.id))}
              onClose={() => {
                setMenuId(null);
                actionRefs.current.get(item.id)?.focus({ preventScroll: true });
              }}
            />
          </li>
        );
      })}
    </ul>
  );
}

function ConversationPreviewCanvas({
  variant,
  width,
  state,
  showRuler,
  ruler,
}: {
  variant: ConversationVariantId;
  width: '393' | '320';
  state: ConversationPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
}) {
  const [selectedId, setSelectedId] = useState('recovery');
  const long = state === 'long';
  return (
    <div
      className={`${styles.demoCanvas} ${styles.conversationDemoCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-conversation-variant={variant}
    >
      {showRuler ? ruler : null}
      <header className={styles.conversationSceneHeader}>
        <span>
          <strong>最近对话</strong>
          <small>4 个线程 · 合成数据</small>
        </span>
        <span>30 天内</span>
      </header>
      <div className={styles.conversationSceneSearch} aria-hidden="true">
        <MagnifyingGlass size={16} />
        搜索会话标题
      </div>
      <div className={styles.conversationSceneBody}>
        {variant === 'command' ? (
          <CommandConversation selectedId={selectedId} onSelect={setSelectedId} long={long} />
        ) : variant === 'glass' ? (
          <GlassConversation selectedId={selectedId} onSelect={setSelectedId} long={long} />
        ) : (
          <PreviewConversation selectedId={selectedId} onSelect={setSelectedId} long={long} />
        )}
      </div>
      <p className={styles.conversationSceneFeedback} role="status">
        当前会话：{selectedId === 'recovery' ? '训练恢复建议' : '已切换合成会话'}
      </p>
    </div>
  );
}

function ConversationVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  selected,
  onSelect,
  onInspect,
}: {
  variant: ConversationVariantDefinition;
  width: '393' | '320';
  state: ConversationPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  selected: boolean;
  onSelect: (variant: ConversationVariantId, name: string) => void;
  onInspect: (variant: ConversationVariantId) => void;
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
            {selected ? <span className={styles.candidateBadge}>已选为基线</span> : null}
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <div className={styles.previewFrame}>
        <ConversationPreviewCanvas
          variant={variant.id}
          width={width}
          state={state}
          showRuler={showRuler}
          ruler={ruler}
        />
      </div>
      <dl className={styles.factGrid}>
        <div>
          <dt>视觉密度</dt>
          <dd>{variant.density}</dd>
        </div>
        <div>
          <dt>当前项</dt>
          <dd>{variant.current}</dd>
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
            selected ? `${variant.name}已选为会话侧栏基线` : `选择${variant.name}作为会话侧栏基线`
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

function ConversationImplementationDetails({
  activeVariant,
  activeTab,
  onVariantChange,
  onTabChange,
}: {
  activeVariant: ConversationVariantId;
  activeTab: DetailTab;
  onVariantChange: (variant: ConversationVariantId) => void;
  onTabChange: (tab: DetailTab) => void;
}) {
  const tabRefs = useRef<Record<DetailTab, HTMLButtonElement | null>>({
    logic: null,
    css: null,
    a11y: null,
  });
  const activeDefinition =
    conversationVariants.find((variant) => variant.id === activeVariant) ?? conversationVariants[0];
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
      id="conversation-implementation"
      className={styles.implementation}
      aria-labelledby="conversation-implementation-heading"
      tabIndex={-1}
    >
      <header className={styles.sectionHeading}>
        <h2 id="conversation-implementation-heading">同一个会话行，三套实现细节</h2>
        <p>先看信息密度和当前项表达，再核对长标题、菜单回焦、键盘路径与触控尺寸。</p>
      </header>
      <div className={styles.implementationControls}>
        <div className={styles.variantSwitch} role="group" aria-label="查看哪个会话行参考的实现">
          {conversationVariants.map((variant) => (
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
        <div className={styles.detailTabs} role="tablist" aria-label="会话侧栏实现明细">
          {detailTabs.map((tab) => (
            <button
              key={tab.id}
              ref={(element) => {
                tabRefs.current[tab.id] = element;
              }}
              id={`conversation-detail-tab-${tab.id}`}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              aria-controls={`conversation-detail-panel-${tab.id}`}
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
        id={`conversation-detail-panel-${activeTab}`}
        className={styles.detailPanel}
        role="tabpanel"
        aria-labelledby={`conversation-detail-tab-${activeTab}`}
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

function ConversationGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="conversation-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="conversation-glossary-heading">会话侧栏再掌握六个词</h2>
        <p>这些词能帮你判断问题出在当前项、密度、标题截断，还是操作可发现性。</p>
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

export function ConversationRowReferenceSection({
  width,
  state,
  showRuler,
  ruler,
  onBaselineChange,
}: ConversationReferenceSectionProps) {
  const [selectedVariant, setSelectedVariant] = useState<ConversationVariantId | null>(
    () => readStoredVariant() ?? 'preview',
  );
  const [activeVariant, setActiveVariant] = useState<ConversationVariantId>(
    () => readStoredVariant() ?? 'preview',
  );
  const [activeTab, setActiveTab] = useState<DetailTab>('logic');
  const [feedback, setFeedback] = useState(
    'C「双行预览行」已确认并接入正式会话侧栏；A、B 保留为对照，三案都只使用合成会话。',
  );

  useEffect(() => {
    const selected = conversationVariants.find((variant) => variant.id === selectedVariant);
    onBaselineChange(selected ? `${selected.letter} ${selected.name}` : null);
  }, [onBaselineChange, selectedVariant]);

  const handleSelect = (variant: ConversationVariantId, name: string) => {
    setSelectedVariant(variant);
    setActiveVariant(variant);
    try {
      window.localStorage.setItem(CONVERSATION_STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    setFeedback(
      variant === 'preview'
        ? 'C「双行预览行」已确认并接入正式会话侧栏；A、B 保留为对照。'
        : `已将“${name}”记为候选；正式会话侧栏仍采用 C「双行预览行」。`,
    );
  };

  const handleInspect = (variant: ConversationVariantId) => {
    setActiveVariant(variant);
    setActiveTab('logic');
    const section = document.getElementById('conversation-implementation');
    try {
      section?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch {
      // jsdom 没有布局引擎；真实浏览器仍会正常滚动。
    }
    section?.focus({ preventScroll: true });
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="conversation-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="conversation-variants-heading">会话侧栏行的三种取舍</h2>
          <p>
            A 最安静、最适合长历史；B 用分组玻璃提升发现性；C
            用最近问题帮助找回旧会话。三案都能切换当前项、打开管理菜单和检查长标题。
          </p>
        </header>
        <div className={styles.variantGrid} data-testid="conversation-row-previews">
          {conversationVariants.map((variant) => (
            <ConversationVariantCard
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
      <ConversationImplementationDetails
        activeVariant={activeVariant}
        activeTab={activeTab}
        onVariantChange={setActiveVariant}
        onTabChange={setActiveTab}
      />
      <ConversationGlossary />
    </>
  );
}
