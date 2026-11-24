import { Archive, ArrowLeft, Check, PencilSimple, Trash } from '@phosphor-icons/react';
import { useEffect, useRef, useState, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type ManagementVariantId = 'command' | 'grouped' | 'confirm';
type ManagementPreviewState = 'default' | 'focus' | 'long' | 'saving';
type DetailTab = 'logic' | 'css' | 'a11y';

interface ManagementVariantDefinition {
  id: ManagementVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  density: string;
  deletePattern: string;
  bestFor: string;
  recommended?: boolean;
}

interface ManagementReferenceSectionProps {
  width: '393' | '320';
  state: ManagementPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  onBaselineChange: (name: string | null) => void;
}

const MANAGEMENT_STORAGE_KEY = 'healthy-agent:ui-reference:management';
const shortTitle = '训练恢复建议';
const longTitle = '聊天持续授权与历史记录管理的设置确认和归档清理';

const managementVariants: ManagementVariantDefinition[] = [
  {
    id: 'command',
    letter: 'A',
    name: 'Codex 命令单',
    summary: '标题、状态和动作排成紧凑命令列表，删除留在最后一行，最接近 Codex 侧栏。',
    density: '高',
    deletePattern: '行内确认',
    bestFor: '长历史',
  },
  {
    id: 'grouped',
    letter: 'B',
    name: '分组玻璃面板',
    summary: '普通操作和危险操作分组呈现，动作说明更清楚，也能保持足够密度。',
    density: '中',
    deletePattern: '危险分组',
    bestFor: '首次使用',
    recommended: true,
  },
  {
    id: 'confirm',
    letter: 'C',
    name: '安全确认栈',
    summary: '删除后切换到独立确认页，明确不可恢复范围，最适合高风险敏感数据。',
    density: '低',
    deletePattern: '独立确认',
    bestFor: '谨慎删除',
  },
];

const detailTabs: { id: DetailTab; label: string }[] = [
  { id: 'logic', label: 'React 逻辑' },
  { id: 'css', label: 'CSS' },
  { id: 'a11y', label: '无障碍' },
];

const detailCopy: Record<
  ManagementVariantId,
  Record<DetailTab, { heading: string; code: string; notes: string[] }>
> = {
  command: {
    logic: {
      heading: '管理页是一次独立子状态，返回动作和业务动作互不复用',
      code: `const [deleteConfirm, setDeleteConfirm] = useState(false);
const [archived, setArchived] = useState(item.archived);

function toggleArchive() {
  setArchived((current) => !current);
}

function requestDelete() {
  setDeleteConfirm(true);
}`,
      notes: [
        '进入管理页只记录会话稳定 ID，不能用当前视觉标题反查目标会话。',
        '归档/恢复可以立即执行，删除必须先进入二次确认状态。',
        '返回动作只关闭管理子状态，不删除草稿、记忆或会话正文。',
      ],
    },
    css: {
      heading: '用连续分隔线承载命令，不使用三个同尺寸按钮',
      code: `.managementCommand {
  display: grid;
  border-top: 1px solid var(--line);
}

.managementCommandRow {
  display: grid;
  min-height: 56px;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: center;
  border-bottom: 1px solid var(--line);
}

.managementCommandRow[data-danger='true'] {
  color: var(--danger);
}`,
      notes: [
        '每个动作行至少 56px，高频操作与删除拥有清晰但一致的点击区域。',
        '图标列、文案列和结果列固定，长标题不会推动右侧状态。',
        '删除确认在当前列表下展开，不额外打开多层弹窗。',
      ],
    },
    a11y: {
      heading: '每个动作使用完整名称，确认状态通过 region 和实时状态播报',
      code: `<section aria-label={confirm ? '确认删除对话' : '对话操作'}>
  <h2>{conversationTitle}</h2>
  <button type="button">归档对话</button>
  <button type="button">删除对话</button>
  {confirm && <p role="status">删除后无法恢复</p>}
</section>`,
      notes: [
        '确认态切换 section 的 aria-label，读屏用户能知道当前处于删除确认。',
        '危险按钮用文字明确“永久删除”，不只依赖红色。',
        '重命名输入有 label，保存和取消按钮保持 44px 命中区。',
      ],
    },
  },
  grouped: {
    logic: {
      heading: '动作按风险分组，危险操作只接受明确命令',
      code: `const groups = [
  { id: 'normal', title: '会话操作', tone: 'normal' },
  { id: 'danger', title: '危险操作', tone: 'danger' },
];

<ManagementGroup
  items={groups.map((group) => ({
    ...group,
    actions: actions.filter((action) => action.tone === group.tone),
  }))}
/>`,
      notes: [
        '分组由动作风险字段生成，避免在 JSX 中复制普通和危险按钮。',
        '删除仍然进入二次确认；分组只提升发现性，不降低安全门槛。',
        '重命名和归档共享会话 ID，任何动作完成后都回到列表对应行。',
      ],
    },
    css: {
      heading: '毛玻璃只放在动作组，不在每个按钮下重复阴影',
      code: `.managementGroup {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--glass);
  backdrop-filter: blur(18px);
}

.managementGroup h3 {
  border-bottom: 1px solid var(--line);
  padding: 0.65rem 0.75rem;
}

.managementAction + .managementAction {
  border-top: 1px solid var(--line);
}`,
      notes: [
        '组标题和动作行使用同一左右内边距，视觉轴线稳定。',
        '普通组与危险组之间保留更大间距，不只靠颜色区分。',
        '在 320px 下动作说明换行，但图标和标题列不挤压。',
      ],
    },
    a11y: {
      heading: '分组标题与动作列表关联，风险说明进入可访问名称',
      code: `<section aria-labelledby="danger-heading">
  <h3 id="danger-heading">危险操作</h3>
  <button
    aria-label="永久删除对话：训练恢复建议"
  >
    删除对话
  </button>
</section>`,
      notes: [
        'section 与标题关联，避免读屏时丢失动作所属风险组。',
        '危险按钮的可访问名称包含不可逆动作词。',
        '颜色只强化风险，文字和确认步骤仍是主要提示。',
      ],
    },
  },
  confirm: {
    logic: {
      heading: '删除使用显式状态机，不把“点击过一次”当作确认',
      code: `type OperationState =
  | { stage: 'manage' }
  | { stage: 'delete'; targetId: string };

const [stage, setStage] = useState<OperationState>({
  stage: 'manage',
});

function confirmDelete() {
  if (stage.stage !== 'delete') return;
  deleteConversation(stage.targetId);
}`,
      notes: [
        '确认状态携带目标会话 ID，避免切换会话后误删其他记录。',
        '“取消删除”回到管理页，不直接关闭整个侧栏。',
        '确认页展示不会删除的长期记忆、跑步记录和用量记录。',
      ],
    },
    css: {
      heading: '危险操作使用独立确认画布，而不是缩小红色按钮',
      code: `.deleteStage {
  display: grid;
  gap: 0.85rem;
  padding: 1rem;
  border: 1px solid var(--danger-line);
  border-radius: 16px;
  background: var(--danger-soft);
}

.deleteStage .primaryAction {
  min-height: 48px;
  margin-top: 0.35rem;
}`,
      notes: [
        '确认阶段替换原操作列表，避免危险按钮继续占据主要位置。',
        '信息按“后果、保留项、取消、确认”顺序排列。',
        '320px 下确认页单独滚动，不依赖背景内容是否可见。',
      ],
    },
    a11y: {
      heading: '确认页从标题开始阅读，取消动作先于永久删除',
      code: `<section aria-labelledby="delete-title">
  <h2 id="delete-title">永久删除这条对话？</h2>
  <p>本机聊天正文会清除，云端清理请求无法撤回。</p>
  <button type="button">保留对话</button>
  <button type="button">确认永久删除</button>
</section>`,
      notes: [
        '取消按钮位于确认按钮之前，键盘用户不会先经过危险动作。',
        '确认页标题明确不可逆结果，不使用含糊的“确定”。',
        '操作完成后焦点回到原会话行，而不是跳回页面顶部。',
      ],
    },
  },
};

const terminology = [
  {
    term: 'destructive action',
    description: '会删除或不可逆改变数据的动作。必须与普通操作分开，并要求明确确认。',
  },
  {
    term: 'guardrail',
    description: '防止误操作的约束，例如二次确认、目标 ID 校验和取消路径。',
  },
  {
    term: 'dirty state',
    description: '用户已修改但尚未保存的状态。离开页面前应明确保留、丢弃还是继续编辑。',
  },
  {
    term: 'focus return',
    description: '操作或弹层结束后，把键盘焦点送回触发它的控件，保持空间记忆。',
  },
  {
    term: 'danger semantics',
    description: '危险语义同时由颜色、文案和确认步骤表达，不能只使用红色。',
  },
  {
    term: 'scope disclosure',
    description: '明确删除会影响什么、不会影响什么，减少用户对记忆和跑步数据的误解。',
  },
];

function readStoredVariant(): ManagementVariantId | null {
  try {
    const value = window.localStorage.getItem(MANAGEMENT_STORAGE_KEY);
    return managementVariants.some((variant) => variant.id === value)
      ? (value as ManagementVariantId)
      : null;
  } catch {
    return null;
  }
}

function useManagementDemo(long: boolean) {
  const [renamedTitle, setRenamedTitle] = useState<string | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [draftName, setDraftName] = useState('');
  const [archived, setArchived] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [notice, setNotice] = useState('合成会话 · 所有操作只影响参考台状态');
  const inputRef = useRef<HTMLInputElement>(null);
  const title = renamedTitle ?? (long ? longTitle : shortTitle);

  useEffect(() => {
    setRenamedTitle(null);
    setRenaming(false);
    setDeleteConfirm(false);
  }, [long]);

  useEffect(() => {
    if (renaming) inputRef.current?.focus({ preventScroll: true });
  }, [renaming]);

  const beginRename = () => {
    setDraftName(title);
    setRenaming(true);
    setDeleteConfirm(false);
    setNotice('正在重命名合成会话，尚未保存');
  };

  const saveRename = () => {
    const next = draftName.trim();
    if (!next) {
      setNotice('会话名称不能为空');
      return;
    }
    setRenamedTitle(next);
    setRenaming(false);
    setNotice(`已保存为“${next}”（合成演示）`);
  };

  const toggleArchive = () => {
    setArchived((current) => !current);
    setDeleteConfirm(false);
    setNotice(archived ? '已恢复合成会话' : '已归档合成会话');
  };

  const requestDelete = () => {
    setDeleteConfirm(true);
    setRenaming(false);
    setNotice('等待确认永久删除，当前尚未修改会话');
  };

  const cancelDelete = () => {
    setDeleteConfirm(false);
    setNotice('已取消删除，会话内容未改变');
  };

  return {
    title,
    renaming,
    draftName,
    archived,
    deleteConfirm,
    notice,
    inputRef,
    setDraftName,
    setRenaming,
    setDeleteConfirm,
    setNotice,
    beginRename,
    saveRename,
    toggleArchive,
    requestDelete,
    cancelDelete,
  };
}

type ManagementDemo = ReturnType<typeof useManagementDemo>;

function RenameForm({ demo }: { demo: ManagementDemo }) {
  return (
    <form
      className={styles.managementRenameForm}
      onSubmit={(event) => {
        event.preventDefault();
        demo.saveRename();
      }}
    >
      <label>
        会话名称
        <input
          ref={demo.inputRef}
          value={demo.draftName}
          maxLength={80}
          onChange={(event) => demo.setDraftName(event.target.value)}
        />
      </label>
      <div>
        <button type="button" onClick={() => demo.setRenaming(false)}>
          取消
        </button>
        <button type="submit">保存名称</button>
      </div>
    </form>
  );
}

function DeleteConfirm({ demo, variant }: { demo: ManagementDemo; variant: 'inline' | 'stage' }) {
  return (
    <section
      className={variant === 'stage' ? styles.managementDeleteStage : styles.managementDeleteInline}
      aria-label="确认删除对话"
    >
      <span>永久删除</span>
      <h4>{variant === 'stage' ? `永久删除“${demo.title}”？` : '这条对话将永久删除'}</h4>
      <p>本机聊天正文会清除，并请求云端清理。已保存的长期记忆、跑步记录和必要用量不会删除。</p>
      <p>已产生的费用不会退还，已完成的外部操作不会撤销。</p>
      <div>
        {variant === 'stage' ? (
          <button type="button" onClick={demo.cancelDelete}>
            保留对话
          </button>
        ) : (
          <button type="button" onClick={demo.cancelDelete}>
            取消删除
          </button>
        )}
        <button
          type="button"
          className={styles.managementDangerButton}
          onClick={() => demo.setNotice('已发出永久删除演示命令；不修改真实会话')}
        >
          确认永久删除
        </button>
      </div>
    </section>
  );
}

function ManagementActionRow({
  icon,
  title,
  detail,
  danger = false,
  onClick,
}: {
  icon: ReactNode;
  title: string;
  detail: string;
  danger?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={styles.managementActionRow}
      data-danger={danger}
      onClick={onClick}
    >
      <span className={styles.managementActionIcon} aria-hidden="true">
        {icon}
      </span>
      <span>
        <strong>{title}</strong>
        <small>{detail}</small>
      </span>
      <span aria-hidden="true">›</span>
    </button>
  );
}

function CommandManagement({ demo }: { demo: ManagementDemo }) {
  return (
    <div className={styles.managementCommandScene}>
      <header className={styles.managementSceneTitle}>
        <button type="button" onClick={() => demo.setNotice('已返回会话列表（演示）')}>
          <ArrowLeft size={17} aria-hidden="true" />
          返回会话列表
        </button>
        <div>
          <span>对话操作</span>
          <h4 title={demo.title}>{demo.title}</h4>
          <small>{demo.archived ? '已归档' : '当前会话'}</small>
        </div>
      </header>
      {demo.renaming ? (
        <RenameForm demo={demo} />
      ) : (
        <div className={styles.managementCommandList}>
          <ManagementActionRow
            icon={<PencilSimple size={18} />}
            title="重命名对话"
            detail="修改侧栏中显示的标题"
            onClick={demo.beginRename}
          />
          <ManagementActionRow
            icon={<Archive size={18} />}
            title={demo.archived ? '恢复对话' : '归档对话'}
            detail={demo.archived ? '回到最近对话列表' : '从最近列表收起，可随时恢复'}
            onClick={demo.toggleArchive}
          />
          <ManagementActionRow
            icon={<Trash size={18} />}
            title="删除对话"
            detail="清除本机正文并请求云端清理"
            danger
            onClick={demo.requestDelete}
          />
        </div>
      )}
      {demo.deleteConfirm && <DeleteConfirm demo={demo} variant="inline" />}
    </div>
  );
}

function GroupedManagement({ demo }: { demo: ManagementDemo }) {
  return (
    <div className={styles.managementGroupedScene}>
      <button
        type="button"
        className={styles.managementBack}
        onClick={() => demo.setNotice('已返回会话列表（演示）')}
      >
        <ArrowLeft size={17} aria-hidden="true" />
        返回会话列表
      </button>
      <div className={styles.managementGroupedTitle}>
        <span>对话操作</span>
        <h4 title={demo.title}>{demo.title}</h4>
        <small>{demo.archived ? '已归档' : '当前会话'}</small>
      </div>
      {demo.renaming ? (
        <div className={styles.managementGlassGroup}>
          <RenameForm demo={demo} />
        </div>
      ) : (
        <>
          <section className={styles.managementGlassGroup} aria-labelledby="normal-actions-heading">
            <h5 id="normal-actions-heading">会话操作</h5>
            <ManagementActionRow
              icon={<PencilSimple size={18} />}
              title="重命名对话"
              detail="更新标题，聊天正文不会改变"
              onClick={demo.beginRename}
            />
            <ManagementActionRow
              icon={<Archive size={18} />}
              title={demo.archived ? '恢复对话' : '归档对话'}
              detail={demo.archived ? '移回最近对话' : '保留内容，从最近列表收起'}
              onClick={demo.toggleArchive}
            />
          </section>
          <section
            className={`${styles.managementGlassGroup} ${styles.managementDangerGroup}`}
            aria-labelledby="danger-actions-heading"
          >
            <h5 id="danger-actions-heading">危险操作</h5>
            <ManagementActionRow
              icon={<Trash size={18} />}
              title="删除对话"
              detail="聊天正文清除后无法恢复"
              danger
              onClick={demo.requestDelete}
            />
          </section>
        </>
      )}
      {demo.deleteConfirm && <DeleteConfirm demo={demo} variant="inline" />}
    </div>
  );
}

function ConfirmManagement({ demo }: { demo: ManagementDemo }) {
  if (demo.deleteConfirm) {
    return (
      <div className={styles.managementConfirmScene}>
        <button type="button" className={styles.managementBack} onClick={demo.cancelDelete}>
          <ArrowLeft size={17} aria-hidden="true" />
          返回对话操作
        </button>
        <DeleteConfirm demo={demo} variant="stage" />
      </div>
    );
  }

  return (
    <div className={styles.managementConfirmScene}>
      <button
        type="button"
        className={styles.managementBack}
        onClick={() => demo.setNotice('已返回会话列表（演示）')}
      >
        <ArrowLeft size={17} aria-hidden="true" />
        返回会话列表
      </button>
      <section className={styles.managementConversationSummary}>
        <span>{demo.archived ? '已归档对话' : '当前对话'}</span>
        <h4 title={demo.title}>{demo.title}</h4>
        <p>归档不会删除聊天正文。永久删除会清除本机内容，并请求云端清理。</p>
      </section>
      {demo.renaming ? (
        <RenameForm demo={demo} />
      ) : (
        <>
          <div className={styles.managementStackActions}>
            <button type="button" onClick={demo.beginRename}>
              <PencilSimple size={19} aria-hidden="true" />
              <span>
                <strong>重命名对话</strong>
                <small>只修改侧栏标题</small>
              </span>
            </button>
            <button type="button" onClick={demo.toggleArchive}>
              <Archive size={19} aria-hidden="true" />
              <span>
                <strong>{demo.archived ? '恢复对话' : '归档对话'}</strong>
                <small>{demo.archived ? '回到最近对话' : '保留正文，暂不显示'}</small>
              </span>
            </button>
          </div>
          <section className={styles.managementDangerPanel} aria-labelledby="delete-panel-title">
            <h5 id="delete-panel-title">永久删除</h5>
            <p>此动作不可逆。下一步会说明会删除和不会删除的范围。</p>
            <button type="button" onClick={demo.requestDelete}>
              <Trash size={19} aria-hidden="true" />
              删除对话
            </button>
          </section>
        </>
      )}
    </div>
  );
}

function ManagementPreviewCanvas({
  variant,
  width,
  state,
  showRuler,
  ruler,
}: {
  variant: ManagementVariantId;
  width: '393' | '320';
  state: ManagementPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
}) {
  const demo = useManagementDemo(state === 'long');
  return (
    <div
      className={`${styles.demoCanvas} ${styles.managementDemoCanvas}`}
      data-width={width}
      data-preview-state={state}
      data-management-variant={variant}
    >
      {showRuler ? ruler : null}
      {variant === 'command' ? (
        <CommandManagement demo={demo} />
      ) : variant === 'grouped' ? (
        <GroupedManagement demo={demo} />
      ) : (
        <ConfirmManagement demo={demo} />
      )}
      <p className={styles.managementFeedback} role="status">
        {demo.notice}
      </p>
    </div>
  );
}

function ManagementVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  selected,
  onSelect,
  onInspect,
}: {
  variant: ManagementVariantDefinition;
  width: '393' | '320';
  state: ManagementPreviewState;
  showRuler: boolean;
  ruler?: ReactNode;
  selected: boolean;
  onSelect: (variant: ManagementVariantId, name: string) => void;
  onInspect: (variant: ManagementVariantId) => void;
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
        <ManagementPreviewCanvas
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
          <dt>删除模式</dt>
          <dd>{variant.deletePattern}</dd>
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
            selected ? `${variant.name}已选为会话管理基线` : `选择${variant.name}作为会话管理基线`
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

function ManagementImplementationDetails({
  activeVariant,
  activeTab,
  onVariantChange,
  onTabChange,
}: {
  activeVariant: ManagementVariantId;
  activeTab: DetailTab;
  onVariantChange: (variant: ManagementVariantId) => void;
  onTabChange: (tab: DetailTab) => void;
}) {
  const tabRefs = useRef<Record<DetailTab, HTMLButtonElement | null>>({
    logic: null,
    css: null,
    a11y: null,
  });
  const activeDefinition =
    managementVariants.find((variant) => variant.id === activeVariant) ?? managementVariants[0];
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
      id="management-implementation"
      className={styles.implementation}
      aria-labelledby="management-implementation-heading"
      tabIndex={-1}
    >
      <header className={styles.sectionHeading}>
        <h2 id="management-implementation-heading">同一个管理动作，三套实现细节</h2>
        <p>先看动作分组和删除安全层级，再核对重命名、取消、焦点与数据范围说明。</p>
      </header>
      <div className={styles.implementationControls}>
        <div className={styles.variantSwitch} role="group" aria-label="查看哪个管理方案实现">
          {managementVariants.map((variant) => (
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
        <div className={styles.detailTabs} role="tablist" aria-label="会话管理实现明细">
          {detailTabs.map((tab) => (
            <button
              key={tab.id}
              ref={(element) => {
                tabRefs.current[tab.id] = element;
              }}
              id={`management-detail-tab-${tab.id}`}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              aria-controls={`management-detail-panel-${tab.id}`}
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
        id={`management-detail-panel-${activeTab}`}
        className={styles.detailPanel}
        role="tabpanel"
        aria-labelledby={`management-detail-tab-${activeTab}`}
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

function ManagementGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="management-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="management-glossary-heading">会话管理再掌握六个词</h2>
        <p>这些词能帮你判断问题出在危险动作、确认流程，还是操作后的焦点恢复。</p>
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

export function ConversationManagementReferenceSection({
  width,
  state,
  showRuler,
  ruler,
  onBaselineChange,
}: ManagementReferenceSectionProps) {
  const [selectedVariant, setSelectedVariant] = useState<ManagementVariantId | null>(
    () => readStoredVariant() ?? 'grouped',
  );
  const [activeVariant, setActiveVariant] = useState<ManagementVariantId>(
    () => readStoredVariant() ?? 'grouped',
  );
  const [activeTab, setActiveTab] = useState<DetailTab>('logic');
  const [feedback, setFeedback] = useState(
    'B 分组玻璃面板已接入正式会话管理；A、C 保留作对照。三案只模拟前端状态，不归档或删除真实对话。',
  );

  useEffect(() => {
    const selected = managementVariants.find((variant) => variant.id === selectedVariant);
    onBaselineChange(selected ? `${selected.letter} ${selected.name}` : null);
  }, [onBaselineChange, selectedVariant]);

  const handleSelect = (variant: ManagementVariantId, name: string) => {
    setSelectedVariant(variant);
    setActiveVariant(variant);
    try {
      window.localStorage.setItem(MANAGEMENT_STORAGE_KEY, variant);
    } catch {
      setFeedback('浏览器阻止了本地存储，本次选择只保留到页面关闭。');
      return;
    }
    setFeedback(
      variant === 'grouped'
        ? 'B 分组玻璃面板已作为正式会话管理基线接入。'
        : `已将“${name}”记为候选；正式会话管理仍使用 B 分组玻璃面板。`,
    );
  };

  const handleInspect = (variant: ManagementVariantId) => {
    setActiveVariant(variant);
    setActiveTab('logic');
    const section = document.getElementById('management-implementation');
    try {
      section?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch {
      // jsdom 没有布局引擎；真实浏览器仍会正常滚动。
    }
    section?.focus({ preventScroll: true });
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="management-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="management-variants-heading">会话管理的三种取舍</h2>
          <p>
            B 已接入正式会话管理：普通操作与危险操作分组，删除保留二次确认。A、C
            继续作为密度和确认流程的对照方案。
          </p>
        </header>
        <div className={styles.variantGrid} data-testid="management-previews">
          {managementVariants.map((variant) => (
            <ManagementVariantCard
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
      <ManagementImplementationDetails
        activeVariant={activeVariant}
        activeTab={activeTab}
        onVariantChange={setActiveVariant}
        onTabChange={setActiveTab}
      />
      <ManagementGlossary />
    </>
  );
}
