import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ArrowUp, List, Plus, X, MagnifyingGlass, DotsThree, Sparkle } from '@phosphor-icons/react';
import { useBridgeStore } from '../../bridge/bridgeStore';
import type { KnowledgeSelection, SelectedMemoryRef } from '../../bridge/cloudAgent';
import { selectedRunIdSchema } from '../../bridge/cloudAgent';
import { AgentCloudPanel } from './AgentCloudPanel';
import { AgentMemoryPreview } from './AgentMemoryPreview';
import { AgentVoiceInput } from './AgentVoiceInput';
import { useAgentTasks } from './useAgentTasks';
import { useCloudAgent } from './useCloudAgent';
import { useChatDrawer } from './useChatDrawer';
import styles from './AgentPage.module.css';

function conversationPeriod(updatedAtMillis: number) {
  return Date.now() - updatedAtMillis < 30 * 24 * 60 * 60 * 1000
    ? '30 天内'
    : new Date(updatedAtMillis).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long' });
}

export function AgentPage() {
  const navigate = useNavigate();
  const routeState: unknown = useLocation().state;
  const selected = selectedRunIdSchema.safeParse(
    routeState && typeof routeState === 'object' && 'runId' in routeState
      ? routeState.runId
      : undefined,
  );
  const [runId, setRunId] = useState<string | undefined>(
    selected.success ? selected.data : undefined,
  );
  const [draft, setDraft] = useState(
    selected.success
      ? '请根据本次跑步摘要帮我复盘，说明数据能支持的结论和不足，不做医学诊断。'
      : '',
  );
  useEffect(() => {
    // 入口选择只消费一次，刷新或返回页面不重新附带上次的跑步记录。
    if (selected.success) void navigate('/agent', { replace: true, state: null });
  }, [selected.success, navigate]);
  const [accountOpen, setAccountOpen] = useState(false);
  const [managementOpen, setManagementOpen] = useState(false);
  const [conversationSearch, setConversationSearch] = useState('');
  const drawerGesture = useChatDrawer(accountOpen, setAccountOpen);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [savedLocally, setSavedLocally] = useState(false);
  const sidebarTrigger = useRef<HTMLButtonElement>(null);
  const sidebarClose = useRef<HTMLButtonElement>(null);
  const sidebarDialog = useRef<HTMLElement>(null);
  const bridgeStatus = useBridgeStore((state) => state.status);
  const tasks = useAgentTasks(bridgeStatus);
  const ready = bridgeStatus === 'ready';
  const [memoryRefs, setMemoryRefs] = useState<SelectedMemoryRef[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeSelection | undefined>();
  const selectMemories = useCallback((refs: SelectedMemoryRef[]) => {
    setMemoryRefs((old) => (JSON.stringify(old) === JSON.stringify(refs) ? old : refs));
  }, []);
  const cloud = useCloudAgent(bridgeStatus, draft, memoryRefs, knowledge, runId);
  const conversation = useRef<HTMLDivElement>(null);
  const followLatest = useRef(true);
  useLayoutEffect(() => {
    const element = conversation.current;
    if (element && followLatest.current && !cloud.prepared) {
      element.scrollTop = element.scrollHeight;
    }
  }, [cloud.listing, cloud.prepared]);
  const [voiceBusy, setVoiceBusy] = useState(false);
  const actionLocked = tasks.pending || cloud.working || !!cloud.prepared;
  const composerLocked = actionLocked || voiceBusy;
  const drafts = useRef(new Map<string, string>());
  const [conversationTitle, setConversationTitle] = useState('');
  const visibleConversations = cloud.listing?.conversations?.filter((item) =>
    item.title.toLocaleLowerCase().includes(conversationSearch.trim().toLocaleLowerCase()),
  );

  async function switchConversation(id?: string | null) {
    if (composerLocked) return;
    drafts.current.set(cloud.listing?.conversationId ?? 'legacy', draft);
    if (await cloud.changeConversation(id)) {
      setDraft(id === undefined ? '' : (drafts.current.get(id ?? 'legacy') ?? ''));
      setMemoryRefs([]);
      setKnowledge(undefined);
      setRunId(undefined);
      followLatest.current = true;
      setAccountOpen(false);
    }
  }

  async function save() {
    if (await tasks.save(draft)) {
      setDraft('');
      setRunId(undefined);
      setSavedLocally(true);
    }
  }

  useEffect(() => {
    if (!accountOpen) return;
    const trigger = sidebarTrigger.current;
    const shell = document.querySelector<HTMLElement>('.app-shell');
    const previousInert = shell?.inert ?? false;
    if (shell) shell.inert = true;
    sidebarClose.current?.focus({ preventScroll: true });
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setAccountOpen(false);
      if (event.key !== 'Tab') return;
      const focusable = sidebarDialog.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled])',
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

  const sidebar = (
    <div className={styles.sidebarOverlay} data-open={accountOpen} {...drawerGesture}>
      <button
        type="button"
        className={styles.sidebarBackdrop}
        tabIndex={-1}
        aria-label="关闭侧边栏背景"
        aria-hidden={!accountOpen}
        onClick={() => setAccountOpen(false)}
      />
      <aside
        ref={sidebarDialog}
        className={styles.sidebar}
        role="dialog"
        aria-modal="true"
        aria-labelledby="account-title"
        aria-hidden={!accountOpen}
        inert={!accountOpen}
      >
        <header className={styles.accountHeader}>
          <h1 id="account-title" className="sr-only">
            流萤
          </h1>
          {managementOpen ? (
            <span className={styles.managementTitle}>设置与管理</span>
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
          <button
            ref={sidebarClose}
            type="button"
            aria-label="关闭侧边栏"
            onClick={() => setAccountOpen(false)}
          >
            <X size={22} aria-hidden="true" />
          </button>
        </header>
        <div className={styles.sidebarScroll}>
          {managementOpen ? (
            <>
              <nav className={styles.accountLinks} aria-label="账户与管理">
                <Link to="/history">跑步记录</Link>
                <Link to="/memories">管理长期记忆</Link>
                <Link to="/settings">运动与语音设置</Link>
                <Link to="/settings/usage">账单与用量</Link>
                <Link to="/settings/chat">聊天授权</Link>
                <Link to="/settings/notices">用户须知</Link>
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
                disabled={!cloud.listing?.configured || composerLocked}
                onClick={() => void switchConversation()}
              >
                <Plus size={19} aria-hidden="true" /> 新建对话
              </button>
              <h2 className={styles.sidebarSectionTitle}>
                {conversationSearch.trim() ? '搜索结果' : '最近对话'}
              </h2>
              {visibleConversations?.length ? (
                <ul className={styles.sidebarChats}>
                  {visibleConversations.map((item, index) => (
                    <li key={item.id}>
                      {!conversationSearch.trim() &&
                        (index === 0 ||
                          conversationPeriod(item.updatedAtMillis) !==
                            conversationPeriod(
                              visibleConversations[index - 1].updatedAtMillis,
                            )) && (
                          <h3 className={styles.conversationPeriod}>
                            {conversationPeriod(item.updatedAtMillis)}
                          </h3>
                        )}
                      <button
                        type="button"
                        disabled={composerLocked}
                        aria-current={
                          cloud.listing?.conversationId === item.id ? 'page' : undefined
                        }
                        onClick={() => void switchConversation(item.id)}
                      >
                        {item.title}
                      </button>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className={styles.sidebarEmpty}>
                  {conversationSearch.trim() ? '没有匹配的会话标题' : '还没有在线对话'}
                </p>
              )}
            </>
          )}
          {managementOpen && (
            <>
              <button
                type="button"
                className={styles.sidebarAction}
                disabled={!cloud.listing?.configured || composerLocked}
                onClick={() => void switchConversation(null)}
              >
                旧版历史记录
              </button>
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
        <button
          type="button"
          className={styles.sidebarFooter}
          aria-expanded={managementOpen}
          onClick={() => setManagementOpen((current) => !current)}
        >
          <span className={styles.profileMark} aria-hidden="true">
            <Sparkle size={20} />
          </span>
          <span>{managementOpen ? '返回会话列表' : '设置与管理'}</span>
          <DotsThree size={24} aria-hidden="true" />
        </button>
      </aside>
    </div>
  );

  return (
    <section className={styles.page} aria-labelledby="agent-title" {...drawerGesture}>
      {sidebar && createPortal(sidebar, document.body)}
      <div className={styles.chatHeader}>
        <button
          ref={sidebarTrigger}
          type="button"
          aria-label="打开侧边栏"
          onClick={() => {
            (document.activeElement as HTMLElement | null)?.blur();
            setToolsOpen(false);
            setManagementOpen(false);
            setAccountOpen(true);
          }}
        >
          <List size={24} aria-hidden="true" />
        </button>
        <span className={styles.chatTitle}>流萤</span>
        <button
          type="button"
          className={styles.newChatButton}
          aria-label="开始新对话"
          disabled={!cloud.listing?.configured || composerLocked}
          onClick={() => void switchConversation()}
        >
          <Plus size={21} aria-hidden="true" />
        </button>
        <span className={styles.headerSpacer} aria-hidden="true" />
      </div>
      <div
        ref={conversation}
        className={styles.conversation}
        role="region"
        aria-label="聊天内容"
        tabIndex={0}
        onScroll={(event) => {
          const element = event.currentTarget;
          followLatest.current =
            element.scrollHeight - element.scrollTop - element.clientHeight < 48;
        }}
      >
        {!cloud.listing?.items.length ? (
          <header className={styles.intro}>
            <Sparkle className={styles.introMark} size={36} weight="duotone" aria-hidden="true" />
            <h1 id="agent-title">开拓者，想聊些什么？</h1>
            <p>训练、跑步或今天的心情，都可以告诉流萤。</p>
          </header>
        ) : (
          <h1 id="agent-title" className="sr-only">
            与流萤对话
          </h1>
        )}
        <AgentCloudPanel
          cloud={cloud}
          ready={ready && !voiceBusy}
          onSent={() => {
            followLatest.current = true;
            setDraft('');
            setRunId(undefined);
            setKnowledge((current) => (current ? { ...current, allowSpoilers: false } : undefined));
          }}
        />
      </div>
      <div className={styles.composer}>
        {runId && (
          <div className={styles.taskControls}>
            <span>已选择一条跑步摘要，发送前核对</span>
            <button type="button" disabled={composerLocked} onClick={() => setRunId(undefined)}>
              移除跑步摘要
            </button>
          </div>
        )}
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
              <label className="sr-only" htmlFor="agent-draft">
                给流萤的消息
              </label>
              <textarea
                rows={2}
                id="agent-draft"
                value={draft}
                onChange={(event) => {
                  setDraft(event.target.value);
                  setSavedLocally(false);
                }}
                placeholder="给流萤发消息…"
                name="agent-draft"
                autoComplete="off"
                maxLength={2000}
                disabled={composerLocked}
                aria-describedby="agent-availability"
              />
              <div className={styles.composerFooter}>
                <details
                  className={styles.toolsMenu}
                  open={toolsOpen}
                  onToggle={(event) => setToolsOpen(event.currentTarget.open)}
                >
                  <summary aria-label="更多输入选项" title="更多输入选项">
                    <Plus size={22} aria-hidden="true" />
                  </summary>
                  <div className={styles.toolsPanel}>
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
                        <legend>本次参考资料</legend>
                        <label htmlFor="knowledge-library">知识库</label>
                        <select
                          id="knowledge-library"
                          value={knowledge?.library ?? 'none'}
                          disabled={!ready}
                          onChange={(event) => {
                            const value = event.target.value;
                            setKnowledge(
                              value === 'health' || value === 'firefly'
                                ? {
                                    version: 'curated_knowledge_v1',
                                    library: value,
                                    allowSpoilers: false,
                                  }
                                : undefined,
                            );
                          }}
                        >
                          <option value="none">不使用知识库</option>
                          <option value="health">运动健康 · WHO 精选</option>
                          <option value="firefly">流萤官方资料</option>
                        </select>
                        {knowledge?.library === 'firefly' && (
                          <label>
                            <input
                              type="checkbox"
                              checked={knowledge.allowSpoilers}
                              disabled={!ready}
                              onChange={(event) =>
                                setKnowledge({ ...knowledge, allowSpoilers: event.target.checked })
                              }
                            />
                            本次允许身份与身世剧透
                          </label>
                        )}
                        <p>只用于在线执行。健康资料不代替诊断，角色设定不作为健康依据。</p>
                      </fieldset>
                      <fieldset className={styles.selectionFieldset} disabled={composerLocked}>
                        <AgentMemoryPreview
                          status={bridgeStatus}
                          prompt={draft}
                          onSelectionChange={selectMemories}
                        />
                      </fieldset>
                      <Link className={styles.onlineInfo} to="/settings/notices">
                        查看用户须知
                      </Link>
                    </details>
                  </div>
                </details>
                {voiceTrigger}
                <button
                  type="button"
                  className={styles.sendButton}
                  aria-describedby="agent-online-status"
                  disabled={!ready || composerLocked || !cloud.listing?.configured || !draft.trim()}
                  onClick={() =>
                    void cloud.prepare().then((sent) => {
                      if (sent) {
                        followLatest.current = true;
                        setDraft('');
                      }
                    })
                  }
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
          {cloud.listing?.configured
            ? cloud.listing.chatAuthorized && !memoryRefs.length && !knowledge && !runId
              ? '持续授权已开启，点击发送'
              : '核对后单次授权'
            : '需单独配置在线 Agent'}
        </span>
        <p
          id="agent-availability"
          className={cloud.listing?.configured ? 'sr-only' : styles.availability}
        >
          {cloud.listing?.configured
            ? cloud.listing.chatAuthorized && !memoryRefs.length && !knowledge && !runId
              ? '持续授权已开启，普通文字点击即发送'
              : '在线发送前会再次确认'
            : '在线服务未配置，可在账户详情中设置'}
        </p>
      </div>
      {ready && !accountOpen && tasks.mutationError && <p role="alert">{tasks.mutationError}</p>}
      {savedLocally && (
        <p role="status" className={styles.saveNotice}>
          已保存到本机，可在账户详情查看；没有发送给流萤。
        </p>
      )}
    </section>
  );
}
