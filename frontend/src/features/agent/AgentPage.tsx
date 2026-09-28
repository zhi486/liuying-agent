import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowUp, List, Plus, X } from '@phosphor-icons/react';
import { useBridgeStore } from '../../bridge/bridgeStore';
import type { KnowledgeSelection, SelectedMemoryRef } from '../../bridge/cloudAgent';
import { AgentCloudPanel } from './AgentCloudPanel';
import { AgentMemoryPreview } from './AgentMemoryPreview';
import { AgentVoiceInput } from './AgentVoiceInput';
import { useAgentTasks } from './useAgentTasks';
import { useCloudAgent } from './useCloudAgent';
import styles from './AgentPage.module.css';

export function AgentPage() {
  const [draft, setDraft] = useState('');
  const [accountOpen, setAccountOpen] = useState(false);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [savedLocally, setSavedLocally] = useState(false);
  const composer = useRef<HTMLDivElement>(null);
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
  const cloud = useCloudAgent(bridgeStatus, draft, memoryRefs, knowledge);
  const [voiceBusy, setVoiceBusy] = useState(false);
  const actionLocked = tasks.pending || cloud.working || !!cloud.prepared;
  const composerLocked = actionLocked || voiceBusy;

  useEffect(() => {
    const revealInput = () => {
      if (composer.current?.contains(document.activeElement))
        composer.current.scrollIntoView({ block: 'center' });
    };
    const viewport = window.visualViewport;
    window.addEventListener('resize', revealInput);
    viewport?.addEventListener('resize', revealInput);
    return () => {
      window.removeEventListener('resize', revealInput);
      viewport?.removeEventListener('resize', revealInput);
    };
  }, []);

  async function save() {
    if (await tasks.save(draft)) {
      setDraft('');
      setSavedLocally(true);
    }
  }

  useEffect(() => {
    if (!accountOpen) return;
    const trigger = sidebarTrigger.current;
    sidebarClose.current?.focus();
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setAccountOpen(false);
      if (event.key !== 'Tab') return;
      const focusable = sidebarDialog.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled])',
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
      trigger?.focus();
    };
  }, [accountOpen]);

  const sidebar = accountOpen ? (
    <div className={styles.sidebarOverlay}>
      <button
        type="button"
        className={styles.sidebarBackdrop}
        aria-label="关闭侧边栏背景"
        onClick={() => setAccountOpen(false)}
      />
      <aside
        ref={sidebarDialog}
        className={styles.sidebar}
        role="dialog"
        aria-modal="true"
        aria-labelledby="account-title"
      >
        <header className={styles.accountHeader}>
          <h1 id="account-title">流萤</h1>
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
          <h2 className={styles.sidebarSectionTitle}>最近对话</h2>
          {cloud.listing?.items.length ? (
            <ul className={styles.sidebarChats}>
              {cloud.listing.items.map((item) => (
                <li key={item.requestId}>
                  <button
                    type="button"
                    onClick={() => {
                      setAccountOpen(false);
                      requestAnimationFrame(() =>
                        document
                          .getElementById(`chat-${item.requestId}`)
                          ?.scrollIntoView({ block: 'center' }),
                      );
                    }}
                  >
                    {item.prompt}
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className={styles.sidebarEmpty}>还没有在线对话</p>
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
          <h2 className={styles.sidebarSectionTitle}>账户详情</h2>
          <nav className={styles.accountLinks} aria-label="账户与管理">
            <Link to="/memories">管理长期记忆</Link>
            <Link to="/history">跑步记录</Link>
            <Link to="/settings">运动与语音设置</Link>
          </nav>
          <section className={styles.accountSection} aria-label="在线 Agent 设置">
            <h2>在线 Agent</h2>
            {cloud.error && <p role="alert">{cloud.error}</p>}
            <p>
              {cloud.listing?.configured
                ? `已配置：${cloud.listing.origin}（不代表当前已联网）`
                : '尚未配置在线 Agent；不会使用语音 API 授权。'}
            </p>
            <div className={styles.taskControls}>
              <button
                type="button"
                disabled={!ready || cloud.working}
                onClick={() => void cloud.act('cloud.configure')}
              >
                配置在线 Agent
              </button>
              <button
                type="button"
                disabled={!ready || cloud.working}
                onClick={() => void cloud.refresh()}
              >
                刷新在线记录
              </button>
            </div>
          </section>
        </div>
      </aside>
    </div>
  ) : null;

  return (
    <section className={styles.page} aria-labelledby="agent-title">
      {sidebar}
      <div className={styles.chatHeader}>
        <button
          ref={sidebarTrigger}
          type="button"
          aria-label="打开侧边栏"
          onClick={() => setAccountOpen(true)}
        >
          <List size={24} aria-hidden="true" />
        </button>
        <span>流萤</span>
        <span className={styles.headerSpacer} aria-hidden="true" />
      </div>
      <div className={styles.conversation}>
        {!cloud.listing?.items.length ? (
          <header className={styles.intro}>
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
            setDraft('');
            setKnowledge((current) => (current ? { ...current, allowSpoilers: false } : undefined));
          }}
        />
      </div>
      <div className={styles.composer} ref={composer}>
        <label className="sr-only" htmlFor="agent-draft">
          给流萤的消息
        </label>
        <textarea
          id="agent-draft"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value);
            setSavedLocally(false);
          }}
          onFocus={() => composer.current?.scrollIntoView({ block: 'center' })}
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
                <details className={styles.onlineInfo}>
                  <summary>在线执行会上传什么？</summary>
                  <p>
                    每次执行都需你确认，仅发送本次输入文字及你明确选中并授权的记忆，可能消耗模型额度。不自动读取或上传历史任务、全部记忆、跑步轨迹和连续心率。
                  </p>
                  <p>输入中包含的个人信息也会随文字发送，请勿填写密钥等秘密。</p>
                  <p>已有语音 API 配置不代表此功能可用；在线记录与本机任务分开保存。</p>
                </details>
              </details>
            </div>
          </details>
          <AgentVoiceInput
            disabled={!ready || actionLocked || !cloud.listing?.configured}
            onBusy={setVoiceBusy}
            onText={(text) => {
              const next = draft ? `${draft}\n${text}` : text;
              if (next.length > 2000) return false;
              setDraft(next);
              return true;
            }}
          />
          <button
            type="button"
            className={styles.sendButton}
            aria-describedby="agent-online-status"
            disabled={!ready || composerLocked || !cloud.listing?.configured || !draft.trim()}
            onClick={() => void cloud.prepare()}
          >
            <ArrowUp size={22} weight="bold" aria-hidden="true" />
            <span className="sr-only">在线执行</span>
          </button>
        </div>
        <span id="agent-local-scope" className="sr-only">
          只存本机 · 不调用模型
        </span>
        <span id="agent-online-status" className="sr-only">
          {cloud.listing?.configured ? '核对后单次授权' : '需单独配置在线 Agent'}
        </span>
        <p id="agent-availability" className={styles.availability}>
          {cloud.listing?.configured
            ? '在线发送前会再次确认'
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
