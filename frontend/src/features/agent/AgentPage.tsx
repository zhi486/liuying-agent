import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import {
  ArrowUp,
  ArrowDown,
  Archive,
  CaretRight,
  CaretDown,
  MapPin,
  PencilSimple,
  Plus,
  Trash,
  X,
  MagnifyingGlass,
  DotsThree,
  Sparkle,
} from '@phosphor-icons/react';
import { useBridgeStore } from '../../bridge/bridgeStore';
import type { SelectedMemoryRef } from '../../bridge/cloudAgent';
import { useKnowledgePreferences, selectedKnowledge } from '../settings/useKnowledgePreferences';
import { selectedRunIdSchema } from '../../bridge/cloudAgent';
import { AgentCloudPanel } from './AgentCloudPanel';
import { AgentMemoryPreview } from './AgentMemoryPreview';
import { AgentMcpPicker } from './AgentMcpPicker';
import { AppNavigation } from '../../app/AppNavigation';
import { AgentVoiceInput } from './AgentVoiceInput';
import { useAgentTasks } from './useAgentTasks';
import { useCloudAgent } from './useCloudAgent';
import { useChatDrawer } from './useChatDrawer';
import styles from './AgentPage.module.css';
import { BackButton } from '../../design-system/BackControl';
import { MenuGlyph } from '../../design-system/MenuGlyph';
import { AgentWelcome } from './AgentWelcome';
import { useNewConversationReveal } from './useNewConversationReveal';

function conversationPeriod(updatedAtMillis: number) {
  return Date.now() - updatedAtMillis < 30 * 24 * 60 * 60 * 1000
    ? '30 天内'
    : new Date(updatedAtMillis).toLocaleDateString('zh-CN', {
        year: 'numeric',
        month: 'long',
      });
}

function conversationUpdatedLabel(updatedAtMillis: number) {
  const elapsedMinutes = Math.max(0, Math.floor((Date.now() - updatedAtMillis) / 60_000));
  if (elapsedMinutes < 1) return '刚刚';
  if (elapsedMinutes < 60) return `${elapsedMinutes} 分钟前`;
  const elapsedHours = Math.floor(elapsedMinutes / 60);
  if (elapsedHours < 24) return `${elapsedHours} 小时前`;
  const elapsedDays = Math.floor(elapsedHours / 24);
  if (elapsedDays < 7) return `${elapsedDays} 天前`;
  return new Date(updatedAtMillis).toLocaleDateString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
  });
}

function ConversationListRow({
  title,
  updatedAtMillis,
  preview,
  current,
  openDisabled,
  manageDisabled,
  dataOpen,
  dataAction,
  onOpen,
  onManage,
}: {
  title: string;
  updatedAtMillis?: number;
  preview?: string;
  current: boolean;
  openDisabled: boolean;
  manageDisabled: boolean;
  dataOpen: string;
  dataAction: string;
  onOpen: () => void;
  onManage: () => void;
}) {
  const updatedLabel = updatedAtMillis ? conversationUpdatedLabel(updatedAtMillis) : '本地记录';
  const secondary = preview?.trim() || `最近更新 · ${updatedLabel}`;
  return (
    <div
      className={styles.conversationRowC}
      data-current={current}
      data-testid="conversation-row-c"
    >
      <button
        type="button"
        className={styles.conversationOpenC}
        data-testid="conversation-open-c"
        data-conversation-open={dataOpen}
        aria-current={current ? 'page' : undefined}
        aria-label={`打开会话：${title}`}
        title={title}
        disabled={openDisabled}
        onClick={onOpen}
      >
        <span className={styles.conversationTopC}>
          <strong>{title}</strong>
          {updatedAtMillis ? (
            <time dateTime={new Date(updatedAtMillis).toISOString()}>{updatedLabel}</time>
          ) : null}
        </span>
        <span className={styles.conversationPreviewC} data-testid="conversation-preview-c">
          {secondary}
        </span>
      </button>
      <button
        type="button"
        className={styles.conversationActionC}
        aria-label={`管理对话：${title}`}
        data-conversation-action={dataAction}
        disabled={manageDisabled}
        onClick={onManage}
      >
        <DotsThree size={20} weight="bold" aria-hidden="true" />
        <span className="sr-only">管理对话</span>
      </button>
    </div>
  );
}

function SidebarOperationAction({
  icon,
  title,
  detail,
  disabled,
  danger = false,
  onClick,
}: {
  icon: ReactNode;
  title: string;
  detail: string;
  disabled: boolean;
  danger?: boolean;
  onClick: () => void;
}) {
  const detailId = useId();
  return (
    <button
      type="button"
      className={styles.operationActionB}
      data-danger={danger}
      aria-label={title}
      aria-describedby={detailId}
      disabled={disabled}
      onClick={onClick}
    >
      <span className={styles.operationActionIconB} aria-hidden="true">
        {icon}
      </span>
      <span>
        <strong>{title}</strong>
        <small id={detailId}>{detail}</small>
      </span>
      <CaretRight size={15} weight="bold" aria-hidden="true" />
    </button>
  );
}

export function AgentPage() {
  const revealNewConversation = useNewConversationReveal();
  const navigate = useNavigate();
  const routeState: unknown = useLocation().state;
  const routeStateRecord =
    routeState && typeof routeState === 'object' ? (routeState as Record<string, unknown>) : null;
  const selected = selectedRunIdSchema.safeParse(
    routeStateRecord && 'runId' in routeStateRecord ? routeStateRecord.runId : undefined,
  );
  const restoreManagement = routeStateRecord?.restore === 'management';
  const [runId, setRunId] = useState<string | undefined>(
    selected.success ? selected.data : undefined,
  );
  const [draft, setDraft] = useState(
    selected.success
      ? '请根据本次跑步摘要帮我复盘，说明数据能支持的结论和不足，不做医学诊断。'
      : '',
  );
  const [accountOpen, setAccountOpen] = useState(false);
  const [managementOpen, setManagementOpen] = useState(false);
  const [managementSettingsOpen, setManagementSettingsOpen] = useState(false);
  const [archivedOpen, setArchivedOpen] = useState(false);
  const [archivedSearch, setArchivedSearch] = useState('');
  const [archiveSource, setArchiveSource] = useState(false);
  const [operation, setOperation] = useState<{
    id: string | null;
    title: string;
    archived: boolean;
  } | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [operationRenameOpen, setOperationRenameOpen] = useState(false);
  const [lifecycleNotice, setLifecycleNotice] = useState('');
  const operationKey = useRef<string | null>(null);
  const archiveReturn = useRef<{ key: string; scrollTop: number } | null>(null);
  const archiveReturnPending = useRef(false);
  const operationBack = useRef<HTMLButtonElement>(null);
  const operationRenameInput = useRef<HTMLInputElement>(null);
  const sidebarScroll = useRef<HTMLDivElement>(null);
  const operationScroll = useRef(0);
  const sidebarState = useRef({ operation, archivedOpen, managementOpen });
  sidebarState.current = { operation, archivedOpen, managementOpen };
  const [conversationSearch, setConversationSearch] = useState('');
  const drawerGesture = useChatDrawer(accountOpen, setAccountOpen);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [savedLocally, setSavedLocally] = useState(false);
  const sidebarTrigger = useRef<HTMLButtonElement>(null);
  const menuAnchor = useRef<HTMLSpanElement>(null);
  const sidebarDialog = useRef<HTMLDivElement>(null);
  const toolsTrigger = useRef<HTMLButtonElement>(null);
  const toolsDialog = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const bridgeStatus = useBridgeStore((state) => state.status);
  useEffect(() => {
    // 路由状态只消费一次；返回聊天时恢复管理抽屉，刷新后不重复带出旧状态。
    if (restoreManagement) {
      setArchivedOpen(false);
      setOperation(null);
      setManagementOpen(true);
      setManagementSettingsOpen(true);
      setAccountOpen(true);
    }
    if (restoreManagement || selected.success) {
      void navigate('/agent', { replace: true, state: null });
    }
  }, [navigate, restoreManagement, selected.success]);
  const tasks = useAgentTasks(bridgeStatus);
  const ready = bridgeStatus === 'ready';
  const [memoryRefs, setMemoryRefs] = useState<SelectedMemoryRef[]>([]);
  const knowledgePreferences = useKnowledgePreferences();
  const knowledge = selectedKnowledge(knowledgePreferences);
  const [mcpServices, setMcpServices] = useState<string[]>([]);
  const selectMemories = useCallback((refs: SelectedMemoryRef[]) => {
    setMemoryRefs((old) => (JSON.stringify(old) === JSON.stringify(refs) ? old : refs));
  }, []);
  const cloud = useCloudAgent(bridgeStatus, draft, memoryRefs, knowledge, runId, mcpServices);
  const pluginScope = JSON.stringify([
    bridgeStatus,
    cloud.listing?.origin,
    cloud.listing?.chatConsentContext,
  ]);
  useEffect(() => {
    setMcpServices([]);
  }, [pluginScope]);
  const conversation = useRef<HTMLDivElement>(null);
  const followLatest = useRef(true);
  const [awayFromLatest, setAwayFromLatest] = useState(false);
  useLayoutEffect(() => {
    const element = conversation.current;
    if (element && followLatest.current && !cloud.prepared && cloud.listing?.items.length) {
      element.scrollTop = element.scrollHeight;
    }
  }, [cloud.listing, cloud.prepared]);
  const [voiceBusy, setVoiceBusy] = useState(false);
  const actionLocked = tasks.pending || cloud.working || !!cloud.prepared;
  const composerLocked = actionLocked || voiceBusy;
  const readOnly = cloud.readOnly;
  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea || textarea.scrollHeight <= 0) return;

    textarea.style.height = 'auto';
    const maxHeight = Number.parseFloat(window.getComputedStyle(textarea).maxHeight);
    const nextHeight = Number.isFinite(maxHeight)
      ? Math.min(textarea.scrollHeight, maxHeight)
      : textarea.scrollHeight;
    textarea.style.height = `${nextHeight}px`;
    textarea.style.overflowY =
      Number.isFinite(maxHeight) && textarea.scrollHeight > maxHeight ? 'auto' : 'hidden';
  }, [draft, readOnly]);
  const agentConfigured = Boolean(cloud.listing?.configured);
  const continuousAuthorization =
    agentConfigured &&
    Boolean(cloud.listing?.chatAuthorized) &&
    !memoryRefs.length &&
    !runId &&
    !mcpServices.length;
  const bridgeStatusText = ready
    ? '本机已连接'
    : bridgeStatus === 'error'
      ? '本机连接异常'
      : '等待本机连接';
  const bridgeTone = ready ? 'good' : bridgeStatus === 'error' ? 'danger' : 'muted';
  const authorizationModeText = !agentConfigured
    ? '需配置在线 Agent'
    : continuousAuthorization
      ? '持续授权'
      : '发送前确认';
  const drafts = useRef(new Map<string, string>());
  const [conversationTitle, setConversationTitle] = useState('');
  const visibleConversations = cloud.listing?.conversations?.filter(
    (item) =>
      !item.archived &&
      item.title.toLocaleLowerCase().includes(conversationSearch.trim().toLocaleLowerCase()),
  );
  const archivedConversations = cloud.listing?.conversations?.filter(
    (item) =>
      item.archived &&
      item.title.toLocaleLowerCase().includes(archivedSearch.trim().toLocaleLowerCase()),
  );

  function closeOperation() {
    setOperation(null);
    setDeleteConfirm(false);
    setOperationRenameOpen(false);
    window.requestAnimationFrame(() => {
      if (sidebarScroll.current) sidebarScroll.current.scrollTop = operationScroll.current;
      const trigger = Array.from(sidebarDialog.current?.querySelectorAll('button') ?? []).find(
        (button) =>
          operationKey.current && button.dataset.conversationAction === operationKey.current,
      );
      (trigger ?? sidebarTrigger.current)?.focus({ preventScroll: true });
    });
  }

  function startFromPrompt(prompt: string) {
    setDraft(prompt);
    textareaRef.current?.focus({ preventScroll: true });
  }

  function sendOnline() {
    if (!ready || composerLocked || !cloud.listing?.configured || !draft.trim()) return;
    void cloud.prepare().then((sent) => {
      if (sent) {
        followLatest.current = true;
        setDraft('');
      }
    });
  }

  useLayoutEffect(() => {
    if (operation) operationBack.current?.focus({ preventScroll: true });
  }, [operation, deleteConfirm]);

  useEffect(() => {
    if (operationRenameOpen) {
      operationRenameInput.current?.focus({ preventScroll: true });
    }
  }, [operationRenameOpen]);

  function openOperation(item: { id: string | null; title: string; archived: boolean }) {
    operationKey.current = item.id ?? 'legacy';
    operationScroll.current = sidebarScroll.current?.scrollTop ?? 0;
    setOperation(item);
    setDeleteConfirm(false);
    setOperationRenameOpen(false);
    setLifecycleNotice('');
  }

  async function manageConversation(
    name: 'cloud.archiveConversation' | 'cloud.restoreConversation' | 'cloud.deleteConversation',
    item = operation,
  ) {
    if (!item) return;
    const current = cloud.listing?.conversationId === item.id;
    if (!(await cloud.manageConversation(name, item.id))) {
      if (!accountOpen) {
        setManagementOpen(true);
        setArchivedOpen(true);
        setAccountOpen(true);
      }
      return;
    }
    if (name === 'cloud.deleteConversation') drafts.current.delete(item.id ?? 'legacy');
    if (name !== 'cloud.restoreConversation' && current) {
      drafts.current.delete(item.id ?? 'legacy');
      setDraft('');
      setMemoryRefs([]);
      setRunId(undefined);
      setMcpServices([]);
      setArchiveSource(false);
    }
    setLifecycleNotice(
      name === 'cloud.archiveConversation'
        ? '已归档，可在设置与管理中查找。'
        : name === 'cloud.restoreConversation'
          ? '已恢复，可继续对话。'
          : '本机聊天内容已删除。',
    );
    closeOperation();
  }

  async function switchConversation(id?: string | null) {
    if (composerLocked) return false;
    const fromArchive =
      id !== undefined &&
      (cloud.listing?.conversations?.some((item) => item.id === id && item.archived) ||
        (id === null && cloud.listing?.legacyArchived)) === true;
    const sourceScroll = sidebarScroll.current?.scrollTop ?? 0;
    drafts.current.set(cloud.listing?.conversationId ?? 'legacy', draft);
    if (await cloud.changeConversation(id)) {
      setDraft(id === undefined ? '' : (drafts.current.get(id ?? 'legacy') ?? ''));
      setMemoryRefs([]);
      setRunId(undefined);
      setMcpServices([]);
      setArchiveSource(fromArchive);
      archiveReturn.current = fromArchive ? { key: id ?? 'legacy', scrollTop: sourceScroll } : null;
      followLatest.current = true;
      setAccountOpen(false);
      return true;
    }
    return false;
  }

  async function save() {
    if (await tasks.save(draft)) {
      setDraft('');
      setRunId(undefined);
      setSavedLocally(true);
    }
  }

  useLayoutEffect(() => {
    const anchor = menuAnchor.current;
    const overlay = sidebarDialog.current;
    if (!anchor || !overlay) return;
    const update = () => {
      const box = anchor.getBoundingClientRect();
      const shell = document.querySelector('.app-shell')?.getBoundingClientRect();
      // 抽屉会推移聊天外壳；按钮留在原位，不跟着背景滑走。
      overlay.style.setProperty('--menu-left', `${box.left - (shell?.left ?? 0)}px`);
      overlay.style.setProperty('--menu-top', `${box.top}px`);
    };
    update();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    observer?.observe(anchor);
    if (anchor.parentElement) observer?.observe(anchor.parentElement);
    window.addEventListener('resize', update);
    window.visualViewport?.addEventListener('resize', update);
    window.visualViewport?.addEventListener('scroll', update);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('scroll', update);
    };
  }, []);

  useEffect(() => {
    if (!accountOpen) return;
    const trigger = sidebarTrigger.current;
    const shell = document.querySelector<HTMLElement>('.app-shell');
    const previousInert = shell?.inert ?? false;
    if (shell) shell.inert = true;
    sidebarTrigger.current?.focus({ preventScroll: true });
    if (archiveReturnPending.current) {
      archiveReturnPending.current = false;
      if (sidebarScroll.current && archiveReturn.current)
        sidebarScroll.current.scrollTop = archiveReturn.current.scrollTop;
      const source = Array.from(sidebarDialog.current?.querySelectorAll('button') ?? []).find(
        (button) =>
          archiveReturn.current && button.dataset.conversationOpen === archiveReturn.current.key,
      );
      source?.focus({ preventScroll: true });
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (sidebarState.current.operation) closeOperation();
        else if (sidebarState.current.archivedOpen) setArchivedOpen(false);
        else if (sidebarState.current.managementOpen) setManagementOpen(false);
        else setAccountOpen(false);
      }
      if (event.key !== 'Tab') return;
      const focusable = sidebarDialog.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]):not([tabindex="-1"]), input:not([disabled])',
      );
      if (!focusable?.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => {
      window.removeEventListener('keydown', closeOnEscape);
      if (shell) shell.inert = previousInert;
      trigger?.focus({ preventScroll: true });
    };
  }, [accountOpen]);

  useLayoutEffect(() => {
    if (!toolsOpen) return;
    const trigger = toolsTrigger.current;
    const shell = document.querySelector<HTMLElement>('.app-shell');
    const previousInert = shell?.inert ?? false;
    if (shell) shell.inert = true;
    const overlay = toolsDialog.current?.parentElement;
    const viewport = window.visualViewport;
    // Portal 不继承聊天容器的可视高度，使用实测值避免 WebView 将 dvh 算为零。
    const resize = () => {
      if (viewport && viewport.scale !== 1) return;
      overlay?.style.setProperty(
        '--tools-viewport-height',
        `${viewport?.height ?? window.innerHeight}px`,
      );
      overlay?.style.setProperty('--tools-viewport-top', `${viewport?.offsetTop ?? 0}px`);
    };
    resize();
    window.addEventListener('resize', resize);
    viewport?.addEventListener('resize', resize);
    viewport?.addEventListener('scroll', resize);
    const focusableElements = () =>
      Array.from(
        toolsDialog.current?.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary',
        ) ?? [],
      );
    focusableElements()[0]?.focus({ preventScroll: true });
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setToolsOpen(false);
        return;
      }
      if (event.key !== 'Tab') return;
      const focusable = focusableElements();
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!toolsDialog.current?.contains(document.activeElement)) {
        event.preventDefault();
        first.focus();
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('resize', resize);
      viewport?.removeEventListener('resize', resize);
      viewport?.removeEventListener('scroll', resize);
      if (shell) shell.inert = previousInert;
      trigger?.focus({ preventScroll: true });
    };
  }, [toolsOpen]);

  const sidebar = (
    <div
      ref={sidebarDialog}
      className={styles.sidebarOverlay}
      data-open={accountOpen}
      role={accountOpen ? 'dialog' : undefined}
      aria-modal={accountOpen ? true : undefined}
      aria-labelledby={accountOpen ? 'account-title' : undefined}
      aria-hidden={accountOpen ? false : undefined}
      {...drawerGesture}
    >
      <button
        ref={sidebarTrigger}
        className={styles.menuToggle}
        type="button"
        aria-label={accountOpen ? '关闭侧边栏' : '打开侧边栏'}
        title={accountOpen ? '关闭侧边栏' : '打开侧边栏'}
        aria-expanded={accountOpen}
        aria-controls="agent-sidebar"
        inert={toolsOpen}
        onClick={() => {
          if (accountOpen) {
            setAccountOpen(false);
            return;
          }
          (document.activeElement as HTMLElement | null)?.blur();
          setToolsOpen(false);
          setManagementOpen(false);
          setArchivedOpen(false);
          setOperation(null);
          setDeleteConfirm(false);
          setAccountOpen(true);
        }}
      >
        <MenuGlyph open={accountOpen} />
      </button>
      <button
        type="button"
        className={styles.sidebarBackdrop}
        tabIndex={-1}
        aria-label="关闭侧边栏背景"
        aria-hidden={!accountOpen}
        onClick={() => setAccountOpen(false)}
      />
      <aside
        id="agent-sidebar"
        className={styles.sidebar}
        aria-hidden={!accountOpen}
        inert={!accountOpen}
      >
        <header className={styles.accountHeader}>
          <h1 id="account-title" className="sr-only">
            流萤
          </h1>
          {managementOpen || operation ? (
            <span className={styles.managementTitle}>
              {operation ? '管理对话' : archivedOpen ? '已归档对话' : '设置与管理'}
            </span>
          ) : (
            <label className={styles.conversationSearch}>
              <MagnifyingGlass size={18} aria-hidden="true" />
              <input
                aria-label="搜索会话标题"
                placeholder="搜索会话标题…"
                value={conversationSearch}
                onChange={(event) => setConversationSearch(event.target.value)}
              />
            </label>
          )}
        </header>
        <div
          ref={sidebarScroll}
          className={styles.sidebarScroll}
          data-confirming={deleteConfirm || undefined}
        >
          {cloud.lifecycleError && (
            <p role="alert" className={styles.lifecycleMessage}>
              {cloud.lifecycleError}
            </p>
          )}
          {lifecycleNotice && (
            <p role="status" className={styles.lifecycleMessage}>
              {lifecycleNotice}
            </p>
          )}
          {operation ? (
            <section
              className={`${styles.conversationOperation} ${styles.conversationOperationB}`}
              aria-label={deleteConfirm ? '确认删除对话' : '对话操作'}
              data-testid="conversation-operation-b"
              data-confirming={deleteConfirm || undefined}
            >
              {deleteConfirm ? (
                <>
                  <header className={styles.operationHeaderB}>
                    <span>危险操作</span>
                    <h2 title={operation.title}>{operation.title}</h2>
                  </header>
                  <div className={styles.operationDeleteConfirmB}>
                    <h3>永久删除这条对话？</h3>
                    <p>删除后无法恢复。此对话的全部聊天内容会从本机清除，并请求云端清理。</p>
                    <p>
                      已保存的长期记忆、跑步记录及必要用量记录不会删除。已产生的费用不会退还，已完成的外部操作不会撤销。
                    </p>
                  </div>
                  <div className={styles.operationConfirmActionsB}>
                    <button
                      ref={operationBack}
                      type="button"
                      disabled={composerLocked}
                      onClick={closeOperation}
                    >
                      取消删除
                    </button>
                    <button
                      className={styles.operationDangerButtonB}
                      type="button"
                      disabled={composerLocked}
                      onClick={() => void manageConversation('cloud.deleteConversation')}
                    >
                      {cloud.working ? '正在删除…' : '确认永久删除'}
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <BackButton
                    ref={operationBack}
                    label="返回对话列表"
                    sticky
                    onClick={closeOperation}
                  />
                  <header className={styles.operationHeaderB}>
                    <span>对话操作</span>
                    <h2 title={operation.title}>{operation.title}</h2>
                    <small>{operation.archived ? '已归档对话' : '当前会话'}</small>
                  </header>
                  <div
                    className={styles.operationGroupB}
                    aria-labelledby="operation-normal-heading"
                    role="region"
                    data-testid="operation-normal-group"
                  >
                    <h3 id="operation-normal-heading">会话操作</h3>
                    {operation.id &&
                      (operationRenameOpen ? (
                        <form
                          className={styles.operationRenameB}
                          onSubmit={(event) => {
                            event.preventDefault();
                            const title = conversationTitle.trim();
                            if (!operation.id || !title) return;
                            void cloud.changeConversation(operation.id, title).then((ok) => {
                              if (!ok) return;
                              setOperation((current) =>
                                current ? { ...current, title } : current,
                              );
                              setOperationRenameOpen(false);
                              window.requestAnimationFrame(() =>
                                operationBack.current?.focus({ preventScroll: true }),
                              );
                            });
                          }}
                        >
                          <label>
                            重命名当前对话
                            <input
                              ref={operationRenameInput}
                              value={conversationTitle}
                              maxLength={80}
                              onChange={(event) => setConversationTitle(event.target.value)}
                            />
                          </label>
                          <div>
                            <button
                              type="button"
                              onClick={() => {
                                setOperationRenameOpen(false);
                                window.requestAnimationFrame(() =>
                                  operationBack.current?.focus({ preventScroll: true }),
                                );
                              }}
                            >
                              取消
                            </button>
                            <button
                              type="submit"
                              disabled={composerLocked || !conversationTitle.trim()}
                            >
                              保存名称
                            </button>
                          </div>
                        </form>
                      ) : (
                        <SidebarOperationAction
                          icon={<PencilSimple size={18} />}
                          title="重命名对话"
                          detail="更新侧栏标题，聊天正文不会改变"
                          disabled={composerLocked}
                          onClick={() => {
                            setConversationTitle(operation.title);
                            setOperationRenameOpen(true);
                          }}
                        />
                      ))}
                    <SidebarOperationAction
                      icon={<Archive size={18} />}
                      title={operation.archived ? '恢复对话' : '归档对话'}
                      detail={
                        operation.archived ? '回到最近对话列表' : '保留聊天正文，从最近列表收起'
                      }
                      disabled={composerLocked}
                      onClick={() =>
                        void manageConversation(
                          operation.archived
                            ? 'cloud.restoreConversation'
                            : 'cloud.archiveConversation',
                        )
                      }
                    />
                  </div>
                  <div
                    className={`${styles.operationGroupB} ${styles.operationDangerGroupB}`}
                    aria-labelledby="operation-danger-heading"
                    role="region"
                    data-testid="operation-danger-group"
                  >
                    <h3 id="operation-danger-heading">危险操作</h3>
                    <SidebarOperationAction
                      icon={<Trash size={18} />}
                      title="删除对话"
                      detail="清除本机正文并请求云端清理"
                      disabled={composerLocked}
                      danger
                      onClick={() => {
                        setOperationRenameOpen(false);
                        setDeleteConfirm(true);
                      }}
                    />
                  </div>
                </>
              )}
            </section>
          ) : archivedOpen ? (
            <>
              <BackButton label="返回设置与管理" sticky onClick={() => setArchivedOpen(false)} />
              <label className={styles.conversationSearch}>
                <MagnifyingGlass size={18} aria-hidden="true" />
                <input
                  aria-label="搜索已归档会话标题"
                  placeholder="搜索已归档会话…"
                  value={archivedSearch}
                  onChange={(event) => setArchivedSearch(event.target.value)}
                />
              </label>
              <ul className={styles.sidebarChats}>
                {archivedConversations?.map((item) => (
                  <li key={item.id} className={styles.conversationItemC}>
                    <ConversationListRow
                      title={item.title}
                      updatedAtMillis={item.updatedAtMillis}
                      current={cloud.listing?.conversationId === item.id}
                      openDisabled={composerLocked}
                      manageDisabled={composerLocked}
                      dataOpen={item.id}
                      dataAction={item.id}
                      onOpen={() => void switchConversation(item.id)}
                      onManage={() => openOperation({ ...item, archived: true })}
                    />
                  </li>
                ))}
                {cloud.listing?.legacyAvailable &&
                  cloud.listing.legacyArchived &&
                  '旧版历史记录'.includes(archivedSearch.trim()) && (
                    <li className={styles.conversationItemC}>
                      <ConversationListRow
                        title="旧版历史记录"
                        preview="旧版本地记录"
                        current={cloud.listing?.conversationId === null}
                        openDisabled={composerLocked}
                        manageDisabled={composerLocked}
                        dataOpen="legacy"
                        dataAction="legacy"
                        onOpen={() => void switchConversation(null)}
                        onManage={() =>
                          openOperation({
                            id: null,
                            title: '旧版历史记录',
                            archived: true,
                          })
                        }
                      />
                    </li>
                  )}
              </ul>
              {!archivedConversations?.length &&
                !(
                  cloud.listing?.legacyAvailable &&
                  cloud.listing.legacyArchived &&
                  '旧版历史记录'.includes(archivedSearch.trim())
                ) && (
                  <p className={styles.sidebarEmpty}>
                    {archivedSearch.trim() ? '没有匹配的归档会话' : '还没有已归档对话'}
                  </p>
                )}
            </>
          ) : managementOpen ? (
            <>
              <nav className={styles.accountLinks} aria-label="账户与管理">
                <button type="button" onClick={() => setArchivedOpen(true)}>
                  已归档对话
                </button>
                <Link to="/history" state={{ from: '/agent', restore: 'management' }}>
                  跑步记录
                </Link>
                <div className={styles.managementSettings}>
                  <header>
                    <Link to="/settings" state={{ from: '/agent', restore: 'management' }}>
                      设置
                    </Link>
                    <button
                      type="button"
                      aria-label="展开设置入口"
                      aria-expanded={managementSettingsOpen}
                      aria-controls="management-settings-links"
                      onClick={() => setManagementSettingsOpen((open) => !open)}
                    >
                      <CaretDown size={18} aria-hidden="true" />
                    </button>
                  </header>
                  <div id="management-settings-links" hidden={!managementSettingsOpen}>
                    {[
                      ['/settings/usage', '账单与用量'],
                      ['/settings/chat', '聊天授权'],
                      ['/settings/plugins', 'MCP 插件'],
                      ['/memories', '长期记忆'],
                      ['/settings/notices', '用户须知'],
                    ].map(([to, label]) => (
                      <Link key={to} to={to} state={{ from: '/agent', restore: 'management' }}>
                        {label}
                      </Link>
                    ))}
                  </div>
                </div>
                <button
                  type="button"
                  disabled={!ready || cloud.working}
                  onClick={() => void cloud.act('cloud.configure')}
                >
                  配置在线 Agent
                </button>
              </nav>
            </>
          ) : (
            <>
              <button
                type="button"
                className={styles.sidebarAction}
                data-suppress-ripple="true"
                disabled={!cloud.listing?.configured || composerLocked}
                onClick={(event) =>
                  void revealNewConversation(event.currentTarget, () => switchConversation())
                }
              >
                <Plus size={19} aria-hidden="true" /> 新建对话
              </button>
              <h2 className={styles.sidebarSectionTitle}>
                {conversationSearch.trim() ? '搜索结果' : '最近对话'}
              </h2>
              {visibleConversations?.length ? (
                <ul className={styles.sidebarChats}>
                  {visibleConversations.map((item, index) => {
                    const current = cloud.listing?.conversationId === item.id;
                    const preview = current ? cloud.listing?.items[0]?.prompt : undefined;
                    return (
                      <li key={item.id} className={styles.conversationItemC}>
                        {!conversationSearch.trim() &&
                          (index === 0 ||
                            conversationPeriod(item.updatedAtMillis) !==
                              conversationPeriod(
                                visibleConversations[index - 1].updatedAtMillis,
                              )) && (
                            <h3 className={styles.conversationPeriodC}>
                              {conversationPeriod(item.updatedAtMillis)}
                            </h3>
                          )}
                        <ConversationListRow
                          title={item.title}
                          updatedAtMillis={item.updatedAtMillis}
                          preview={preview}
                          current={current}
                          openDisabled={composerLocked}
                          manageDisabled={composerLocked}
                          dataOpen={item.id}
                          dataAction={item.id}
                          onOpen={() => void switchConversation(item.id)}
                          onManage={() => openOperation({ ...item, archived: false })}
                        />
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <p className={styles.sidebarEmpty}>
                  {conversationSearch.trim() ? '没有匹配的会话标题' : '还没有在线对话'}
                </p>
              )}
            </>
          )}
          {managementOpen && !archivedOpen && !operation && (
            <>
              {cloud.listing?.legacyAvailable && !cloud.listing.legacyArchived && (
                <ConversationListRow
                  title="旧版历史记录"
                  preview="旧版本地记录"
                  current={cloud.listing.conversationId === null}
                  openDisabled={!cloud.listing.configured || composerLocked}
                  manageDisabled={composerLocked}
                  dataOpen="legacy"
                  dataAction="legacy"
                  onOpen={() => void switchConversation(null)}
                  onManage={() =>
                    openOperation({
                      id: null,
                      title: '旧版历史记录',
                      archived: false,
                    })
                  }
                />
              )}
              {(cloud.listing?.cleanupPending ?? 0) > 0 && (
                <section className={styles.cleanupStatus} aria-label="云端清理待办">
                  <h2>云端清理待完成</h2>
                  <p>本机内容已清理，{cloud.listing?.cleanupPending} 条云端清理待完成。</p>
                  <button
                    className={styles.sidebarAction}
                    type="button"
                    disabled={composerLocked || cloud.listing?.cleanupBusy}
                    onClick={() => void cloud.manageConversation('cloud.retryConversationCleanup')}
                  >
                    {cloud.listing?.cleanupBusy ? '正在清理…' : '重试清理'}
                  </button>
                </section>
              )}
              {cloud.listing?.conversationId && (
                <form
                  className={styles.conversationRename}
                  onSubmit={(event) => {
                    event.preventDefault();
                    void cloud
                      .changeConversation(cloud.listing?.conversationId, conversationTitle)
                      .then((ok) => {
                        if (ok) setConversationTitle('');
                      });
                  }}
                >
                  <label>
                    重命名当前对话
                    <input
                      value={conversationTitle}
                      maxLength={80}
                      onChange={(event) => setConversationTitle(event.target.value)}
                    />
                  </label>
                  <button
                    className={styles.sidebarAction}
                    type="submit"
                    disabled={composerLocked || !conversationTitle.trim()}
                  >
                    保存名称
                  </button>
                </form>
              )}
              <h2 className={styles.sidebarSectionTitle}>最近保存</h2>
              <section className={styles.recent} aria-labelledby="recent-tasks">
                <h2 id="recent-tasks" className="sr-only">
                  本机任务
                </h2>
                {tasks.mutationError && <p role="alert">{tasks.mutationError}</p>}
                {!ready ? (
                  <p>请在手机 App 内保存和查看任务。</p>
                ) : (
                  <>
                    {tasks.loading && <p role="status">正在读取本机任务…</p>}
                    {tasks.listError && <p role="alert">{tasks.listError}</p>}
                    {!tasks.loading && tasks.page?.total === 0 && <p>还没有本机任务</p>}
                    <ul className={styles.taskList}>
                      {tasks.page?.items.map((task) => (
                        <li key={task.id}>
                          <button
                            type="button"
                            aria-label={`查看任务：${task.title}`}
                            disabled={tasks.pending}
                            onClick={() => tasks.open(task.id)}
                          >
                            <span>{task.title}</span>
                            <small>{task.status === 'cancelled' ? '已取消' : '已存本机'}</small>
                          </button>
                        </li>
                      ))}
                    </ul>
                    <div className={styles.taskControls}>
                      <button
                        type="button"
                        onClick={tasks.refresh}
                        disabled={tasks.loading || tasks.pending}
                      >
                        刷新任务
                      </button>
                      {tasks.page && tasks.page.nextOffset < tasks.page.total && (
                        <button
                          type="button"
                          onClick={tasks.loadMore}
                          disabled={tasks.loading || tasks.pending}
                        >
                          加载更多任务
                        </button>
                      )}
                    </div>
                  </>
                )}
              </section>
              {ready && tasks.selectedId && (
                <section className={styles.detail} aria-label="任务详情">
                  <div className={styles.detailHeader}>
                    <h2>任务详情</h2>
                    <button type="button" onClick={tasks.close} disabled={tasks.pending}>
                      关闭详情
                    </button>
                  </div>
                  {tasks.detailError ? (
                    <p role="alert">
                      {tasks.detailError}
                      <button type="button" onClick={() => tasks.open(tasks.selectedId!)}>
                        重试详情
                      </button>
                    </p>
                  ) : !tasks.detail ? (
                    <p role="status">正在读取任务详情…</p>
                  ) : (
                    <>
                      <p className={styles.message}>{tasks.detail.messages[0].text}</p>
                      <p className={styles.availability}>
                        {tasks.detail.task.status === 'cancelled'
                          ? '已取消'
                          : '已保存在本机，未上传，也未执行。之后接通云端仍不会自动提交。'}
                      </p>
                      <ol className={styles.actions} aria-label="任务行动记录">
                        {tasks.detail.actions.map((action) => (
                          <li key={action.sequence}>
                            {action.kind === 'saved_locally' ? '保存到本机' : '取消本机任务'} ·{' '}
                            {new Date(action.createdAtMillis).toLocaleString('zh-CN')}
                          </li>
                        ))}
                      </ol>
                      {tasks.detail.task.status === 'blocked' && (
                        <button type="button" onClick={tasks.cancel} disabled={tasks.pending}>
                          取消任务
                        </button>
                      )}
                    </>
                  )}
                </section>
              )}
              <section className={styles.accountSection} aria-label="在线 Agent 设置">
                <h2>在线 Agent</h2>
                {accountOpen && cloud.error && <p role="alert">{cloud.error}</p>}
                <p>
                  {cloud.listing?.configured
                    ? `已配置：${cloud.listing.origin}（不代表当前已联网）`
                    : '尚未配置在线 Agent；不会使用语音 API 授权。'}
                </p>
                <div className={styles.taskControls}>
                  <button
                    type="button"
                    disabled={!ready || cloud.working}
                    onClick={() => void cloud.refresh()}
                  >
                    刷新在线记录
                  </button>
                </div>
              </section>
            </>
          )}
        </div>
        {!operation && managementOpen ? (
          <div className={styles.sidebarReturn}>
            <BackButton
              label="返回会话列表"
              onClick={() => {
                setArchivedOpen(false);
                setManagementOpen(false);
              }}
            />
          </div>
        ) : (
          !operation && (
            <button
              type="button"
              className={styles.sidebarFooter}
              aria-expanded={managementOpen}
              onClick={() => {
                if (operation) closeOperation();
                setArchivedOpen(false);
                setManagementOpen((current) => !current);
              }}
            >
              <span className={styles.profileMark} aria-hidden="true">
                <Sparkle size={20} />
              </span>
              <span>设置与管理</span>
              <DotsThree size={24} aria-hidden="true" />
            </button>
          )
        )}
      </aside>
    </div>
  );

  return (
    <section className={styles.page} aria-labelledby="agent-title" {...drawerGesture}>
      {sidebar && createPortal(sidebar, document.body)}
      <div className={styles.chatHeader}>
        <span ref={menuAnchor} className={styles.menuAnchor} aria-hidden="true" />
        <div className={styles.chatHeaderIdentity}>
          <span className={styles.chatTitle}>流萤</span>
          <span className={styles.chatSubtitle}>跑步与状态</span>
        </div>
        <button
          type="button"
          className={styles.newChatButton}
          aria-label="开始新对话"
          disabled={!cloud.listing?.configured || composerLocked}
          onClick={(event) =>
            void revealNewConversation(event.currentTarget, () => switchConversation())
          }
        >
          <Plus size={21} aria-hidden="true" />
        </button>
        <AppNavigation />
      </div>
      <div
        ref={conversation}
        className={styles.conversation}
        data-empty={(!cloud.listing?.items.length && !cloud.prepared && !cloud.error) || undefined}
        role="region"
        aria-label="聊天内容"
        tabIndex={0}
        onScroll={(event) => {
          const element = event.currentTarget;
          followLatest.current =
            element.scrollHeight - element.scrollTop - element.clientHeight < 48;
          setAwayFromLatest(!followLatest.current);
        }}
      >
        {!cloud.listing?.items.length ? (
          <AgentWelcome
            bridgeTone={bridgeTone}
            bridgeStatus={bridgeStatusText}
            configured={agentConfigured}
            authorization={authorizationModeText}
            continuous={continuousAuthorization}
            voiceBusy={voiceBusy}
            showPrompts={!runId}
            onPrompt={startFromPrompt}
          />
        ) : (
          <h1 id="agent-title" className="sr-only">
            与流萤对话
          </h1>
        )}
        <AgentCloudPanel
          cloud={cloud}
          ready={ready && !voiceBusy && !readOnly}
          onSent={() => {
            followLatest.current = true;
            setDraft('');
            setRunId(undefined);
            setMcpServices([]);
          }}
        />
      </div>
      <div className={styles.composer}>
        {awayFromLatest && (
          <button
            type="button"
            className={styles.latestOrb}
            aria-label="回到最新消息"
            title="回到最新消息"
            onClick={() => {
              const element = conversation.current;
              if (element) element.scrollTop = element.scrollHeight;
              followLatest.current = true;
              setAwayFromLatest(false);
            }}
          >
            <ArrowDown size={20} aria-hidden="true" />
          </button>
        )}
        {readOnly ? (
          <section className={styles.archivedNotice} aria-label="归档对话只读">
            <p>此对话已归档，恢复后可继续发送。</p>
            <div className={styles.taskControls}>
              <button
                type="button"
                disabled={composerLocked}
                onClick={() =>
                  void manageConversation('cloud.restoreConversation', {
                    id: cloud.listing?.conversationId ?? null,
                    title: '',
                    archived: true,
                  })
                }
              >
                恢复对话
              </button>
              {archiveSource && (
                <BackButton
                  label="返回已归档对话"
                  onClick={() => {
                    archiveReturnPending.current = true;
                    setManagementOpen(true);
                    setArchivedOpen(true);
                    setAccountOpen(true);
                  }}
                />
              )}
            </div>
          </section>
        ) : (
          <>
            <AgentVoiceInput
              disabled={!ready || actionLocked || !cloud.listing?.configured}
              onBusy={setVoiceBusy}
              onText={(text) => {
                const next = draft ? `${draft}\n${text}` : text;
                if (next.length > 2000) return false;
                setDraft(next);
                return true;
              }}
            >
              {(voiceTrigger) => (
                <>
                  {runId && (
                    <div className={styles.composerContextChip}>
                      <span className={styles.composerContextIcon} aria-hidden="true">
                        <MapPin size={16} weight="regular" />
                      </span>
                      <span className={styles.composerContextCopy} aria-hidden="true">
                        <strong>跑步摘要</strong>
                        <span>/</span>
                        <small>发送前核对</small>
                      </span>
                      <span className="sr-only">已选择一条跑步摘要，发送前核对</span>
                      <button
                        type="button"
                        title="移除跑步摘要"
                        aria-label="移除跑步摘要"
                        disabled={composerLocked}
                        onClick={() => setRunId(undefined)}
                      >
                        <X size={16} weight="bold" aria-hidden="true" />
                      </button>
                    </div>
                  )}
                  <label className="sr-only" htmlFor="agent-draft">
                    给流萤的消息
                  </label>
                  <textarea
                    ref={textareaRef}
                    rows={1}
                    id="agent-draft"
                    value={draft}
                    onChange={(event) => {
                      setDraft(event.target.value);
                      setSavedLocally(false);
                    }}
                    onKeyDown={(event) => {
                      if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing)
                        return;
                      event.preventDefault();
                      sendOnline();
                    }}
                    placeholder="给流萤发消息…"
                    name="agent-draft"
                    autoComplete="off"
                    maxLength={2000}
                    disabled={composerLocked}
                    aria-describedby="agent-availability"
                  />
                  <div className={styles.composerFooter}>
                    <button
                      ref={toolsTrigger}
                      type="button"
                      className={styles.toolsTrigger}
                      aria-label="更多输入选项"
                      title="更多输入选项"
                      aria-haspopup="dialog"
                      aria-expanded={toolsOpen}
                      onClick={() => {
                        setAccountOpen(false);
                        setToolsOpen(true);
                      }}
                    >
                      <Plus size={22} aria-hidden="true" />
                    </button>
                    {voiceTrigger}
                    <button
                      type="button"
                      className={styles.sendButton}
                      aria-describedby="agent-online-status"
                      disabled={
                        !ready || composerLocked || !cloud.listing?.configured || !draft.trim()
                      }
                      onClick={sendOnline}
                    >
                      <ArrowUp size={22} weight="bold" aria-hidden="true" />
                      <span className="sr-only">在线执行</span>
                    </button>
                  </div>
                </>
              )}
            </AgentVoiceInput>
            <span id="agent-local-scope" className="sr-only">
              只存本机 · 不调用模型
            </span>
            <span id="agent-online-status" className="sr-only">
              {agentConfigured
                ? continuousAuthorization
                  ? '持续授权已开启，点击发送'
                  : '核对后单次授权'
                : '需单独配置在线 Agent'}
            </span>
            <p
              id="agent-availability"
              className={agentConfigured ? 'sr-only' : styles.availability}
            >
              {agentConfigured
                ? continuousAuthorization
                  ? '持续授权已开启，普通文字点击即发送'
                  : '在线发送前会再次确认'
                : '在线服务未配置，可在账户详情中设置'}
            </p>
          </>
        )}
      </div>
      {ready && !accountOpen && tasks.mutationError && <p role="alert">{tasks.mutationError}</p>}
      {savedLocally && (
        <p role="status" className={styles.saveNotice}>
          已保存到本机，可在账户详情查看；没有发送给流萤。
        </p>
      )}
      {toolsOpen &&
        createPortal(
          <div className={styles.toolsOverlay}>
            <button
              type="button"
              className={styles.toolsBackdrop}
              tabIndex={-1}
              aria-label="关闭输入选项背景"
              onClick={() => setToolsOpen(false)}
            />
            <div
              ref={toolsDialog}
              className={styles.toolsSheet}
              role="dialog"
              aria-modal="true"
              aria-labelledby="agent-tools-title"
            >
              <header className={styles.toolsSheetHeader}>
                <h2 id="agent-tools-title">输入选项</h2>
                <button type="button" aria-label="关闭输入选项" onClick={() => setToolsOpen(false)}>
                  <X size={22} aria-hidden="true" />
                </button>
              </header>
              <div className={styles.toolsSheetBody}>
                <button
                  type="button"
                  className={styles.saveButton}
                  aria-label="仅保存"
                  aria-describedby="agent-local-scope"
                  disabled={!ready || composerLocked || !draft.trim() || draft.length > 2000}
                  onClick={() => {
                    void save();
                    setToolsOpen(false);
                  }}
                >
                  {tasks.pending ? '处理中…' : '仅保存到本机'}
                </button>
                <details className={styles.referencePicker}>
                  <summary>本次参考{knowledge || memoryRefs.length ? ' · 已选择' : ''}</summary>
                  <fieldset className={styles.selectionFieldset} disabled={composerLocked}>
                    <AgentMemoryPreview
                      status={bridgeStatus}
                      prompt={draft}
                      onSelectionChange={selectMemories}
                    />
                  </fieldset>
                  <Link
                    className={styles.onlineInfo}
                    to="/settings/notices"
                    state={{ from: '/agent', restore: 'management' }}
                  >
                    查看用户须知
                  </Link>
                </details>
                <AgentMcpPicker
                  selected={mcpServices}
                  onChange={setMcpServices}
                  disabled={!ready || composerLocked}
                  scopeKey={pluginScope}
                />
              </div>
            </div>
          </div>,
          document.body,
        )}
    </section>
  );
}
