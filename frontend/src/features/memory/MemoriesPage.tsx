import { CaretDown, ClockCounterClockwise, PencilSimple, Trash } from '@phosphor-icons/react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { memoryContentSchema, type MemoryRecord, type MemoryRevision } from '../../bridge/memories';
import { useMemories } from './useMemories';
import { MemoryAuthorization } from './MemoryAuthorization';
import { MemoryHistory } from './MemoryHistory';
import { MemoryDocumentPanel } from './MemoryDocumentPanel';
import styles from './MemoriesPage.module.css';
import { LoadingSkeleton } from '../../design-system/LoadingSkeleton';

function memoryGroup(content: string) {
  if (/跑步|夜跑|晨跑|配速|心率|训练|热身|公里|长距离|恢复/.test(content)) return '跑步相关';
  if (/表达|语气|回答|简洁|术语|解释|鼓励|称呼|建议/.test(content)) return '表达偏好';
  return '其他记忆';
}

export function MemoriesPage() {
  const status = useBridgeStore((state) => state.status);
  const memories = useMemories(status);
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState<{ memory: MemoryRecord; content: string } | null>(null);
  const [confirmation, setConfirmation] = useState<
    | { kind: 'delete'; memory: MemoryRecord }
    | { kind: 'restore'; memory: MemoryRecord; revision: MemoryRevision }
    | null
  >(null);
  const [historyId, setHistoryId] = useState<string | null>(null);
  const [documentOpen, setDocumentOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [expandedId, setExpandedId] = useState<string | null | undefined>(undefined);
  const dialog = useRef<HTMLDialogElement>(null);
  const editInput = useRef<HTMLTextAreaElement>(null);
  const editingId = editing?.memory.id;
  const ready = status === 'ready';
  const busy = !ready || memories.pending;
  const filtered =
    memories.page?.items.filter((memory) =>
      memory.content.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()),
    ) ?? [];
  const activeId = expandedId === undefined ? memories.page?.items[0]?.id : expandedId;

  useEffect(() => {
    const popup = dialog.current;
    if (!popup) return;
    if (!confirmation) {
      if (popup.open) popup.close();
      return;
    }
    // 旧版 Android WebView 的顶层弹窗可能把 vh/dvh 算成 0，使用实际视口像素。
    const resize = () => {
      const height = window.visualViewport?.height || window.innerHeight;
      popup.style.maxHeight = `${Math.max(0, height - 32)}px`;
      popup.style.setProperty('--memory-content-height', `${Math.max(0, height * 0.3)}px`);
    };
    resize();
    if (!popup.open) popup.showModal();
    window.addEventListener('resize', resize);
    const viewport = window.visualViewport;
    viewport?.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
      viewport?.removeEventListener('resize', resize);
    };
  }, [confirmation]);

  useEffect(() => {
    if (editingId) {
      editInput.current?.focus();
      editInput.current?.scrollIntoView?.({ block: 'center' });
    }
  }, [editingId]);

  async function add() {
    if (await memories.change({ kind: 'create', content: draft })) setDraft('');
  }
  async function update() {
    if (editing && (await memories.change({ kind: 'update', ...editing }))) setEditing(null);
  }
  async function confirm() {
    if (confirmation && (await memories.change(confirmation))) {
      setConfirmation(null);
      setHistoryId(null);
    }
  }

  return (
    <section className={`standard-page ${styles.page}`}>
      <p className={styles.scope}>
        记忆保存在本机。请勿保存密钥、密码等秘密。{' '}
        <Link className="notices-link" to="/settings/notices" state={{ from: '/memories' }}>
          用户须知
        </Link>
      </p>
      {!ready && <p role="status">请在手机 App 内管理记忆。</p>}
      <MemoryAuthorization ready={ready} />
      <div className={styles.documentAccess}>
        <button
          type="button"
          disabled={busy || !!editing || !!confirmation}
          aria-expanded={documentOpen}
          onClick={() => setDocumentOpen((value) => !value)}
        >
          查看记忆文档
        </button>
      </div>
      {documentOpen && !memories.pending && !editing && !confirmation && (
        <MemoryDocumentPanel ready={ready} onClose={() => setDocumentOpen(false)} />
      )}
      <form
        className={styles.composer}
        onSubmit={(event) => {
          event.preventDefault();
          void add();
        }}
      >
        <label htmlFor="memory-draft">新增记忆</label>
        <textarea
          id="memory-draft"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          maxLength={500}
          disabled={busy}
          placeholder="例如：我更喜欢晚上跑步，鼓励语气温柔一些。"
        />
        <div className={styles.controls}>
          <small>{draft.length} / 500</small>
          <button
            type="submit"
            className={styles.primary}
            disabled={busy || !!editing || !memoryContentSchema.safeParse(draft).success}
          >
            保存记忆
          </button>
        </div>
      </form>
      <p role="status" className={styles.muted}>
        {memories.pending ? '正在处理…' : memories.notice}
      </p>
      {memories.error && !confirmation && <p role="alert">{memories.error}</p>}
      {editing && (
        <form
          className={styles.composer}
          onSubmit={(event) => {
            event.preventDefault();
            void update();
          }}
        >
          <label htmlFor="memory-edit">修改记忆</label>
          <textarea
            id="memory-edit"
            ref={editInput}
            value={editing.content}
            maxLength={500}
            disabled={busy}
            onChange={(event) => setEditing({ ...editing, content: event.target.value })}
          />
          <div className={styles.controls}>
            <small>{editing.content.length} / 500</small>
            <button type="button" disabled={memories.pending} onClick={() => setEditing(null)}>
              取消编辑
            </button>
            <button
              type="submit"
              disabled={busy || !memoryContentSchema.safeParse(editing.content).success}
            >
              保存修改
            </button>
          </div>
        </form>
      )}
      <section aria-labelledby="memory-list-title">
        <div className={styles.controls}>
          <h2 id="memory-list-title">已保存{memories.page ? ` · ${memories.page.total}` : ''}</h2>
          <button
            type="button"
            onClick={memories.refresh}
            disabled={busy || memories.loading || !!editing}
          >
            刷新记忆
          </button>
        </div>
        {memories.loading && <LoadingSkeleton label="正在读取本机记忆…" />}
        {memories.page?.total === 0 && (
          <p className={styles.empty}>还没有记忆。先写下一件你想让流萤记住的事吧。</p>
        )}
        <label className={styles.searchLabel}>
          搜索当前页
          <input
            type="search"
            value={query}
            disabled={busy || !!editing || !!confirmation}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <p className={styles.groupHint}>关键词辅助分组，仅当前页；不修改记忆原文或授权。</p>
        {memories.page && memories.page.total > 0 && filtered.length === 0 && (
          <p className={styles.empty}>当前页没有匹配的记忆。</p>
        )}
        {['跑步相关', '表达偏好', '其他记忆'].map((group) => {
          const items = filtered.filter((memory) => memoryGroup(memory.content) === group);
          if (!items.length) return null;
          return (
            <section key={group} className={styles.memoryGroup} aria-label={group}>
              <h3>
                {group}
                <span>{items.length} 条</span>
              </h3>
              <ul className={styles.list}>
                {items.map((memory) => (
                  <li key={memory.id}>
                    <button
                      type="button"
                      className={styles.recordSummary}
                      aria-label={'审阅记忆：' + memory.content}
                      aria-expanded={activeId === memory.id}
                      aria-controls={'memory-detail-' + memory.id}
                      disabled={busy || memories.loading || !!editing || !!confirmation}
                      onClick={() => {
                        setExpandedId(activeId === memory.id ? null : memory.id);
                        setHistoryId(null);
                      }}
                    >
                      <span>{activeId === memory.id ? '正在审阅' : memory.content}</span>
                      <CaretDown size={16} aria-hidden="true" />
                    </button>
                    <div
                      id={'memory-detail-' + memory.id}
                      hidden={activeId !== memory.id}
                      className={styles.recordDetail}
                    >
                      <p className={styles.content}>{memory.content}</p>
                      <small className={styles.muted}>
                        版本 {memory.version} ·{' '}
                        {new Date(memory.updatedAtMillis).toLocaleString('zh-CN')}
                      </small>
                      <div className={styles.itemActions}>
                        <button
                          type="button"
                          disabled={busy || memories.loading || editing !== null}
                          onClick={() => setHistoryId(historyId === memory.id ? null : memory.id)}
                          title="版本历史"
                          aria-expanded={historyId === memory.id}
                          aria-label={`版本历史：${memory.content}`}
                        >
                          <ClockCounterClockwise size={18} aria-hidden="true" />
                        </button>
                        <button
                          type="button"
                          disabled={busy || memories.loading || editing !== null}
                          onClick={() => setEditing({ memory, content: memory.content })}
                          title="编辑记忆"
                          aria-label={`编辑记忆：${memory.content}`}
                        >
                          <PencilSimple size={18} aria-hidden="true" />
                        </button>
                        <button
                          type="button"
                          disabled={busy || memories.loading || editing !== null}
                          onClick={() => setConfirmation({ kind: 'delete', memory })}
                          title="删除记忆"
                          aria-label={`删除记忆：${memory.content}`}
                        >
                          <Trash size={18} aria-hidden="true" />
                        </button>
                      </div>
                      {historyId === memory.id && (
                        <MemoryHistory
                          key={`${memory.id}:${memory.version}`}
                          memory={memory}
                          ready={ready}
                          busy={memories.pending || !!editing || !!confirmation}
                          onClose={() => setHistoryId(null)}
                          onRestore={(revision) =>
                            setConfirmation({ kind: 'restore', memory, revision })
                          }
                        />
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          );
        })}
        {memories.page && (memories.page.offset > 0 || memories.page.total > 20) && (
          <div className={styles.controls}>
            <button
              type="button"
              onClick={memories.previous}
              disabled={busy || memories.loading || !!editing || memories.page.offset === 0}
            >
              上一页
            </button>
            <small>第 {Math.floor(memories.page.offset / 20) + 1} 页</small>
            <button
              type="button"
              onClick={memories.next}
              disabled={
                busy ||
                memories.loading ||
                !!editing ||
                memories.page.offset + 20 >= memories.page.total
              }
            >
              下一页
            </button>
          </div>
        )}
      </section>
      <dialog
        ref={dialog}
        className={
          styles.dialog + (confirmation?.kind === 'delete' ? ' ' + styles.dangerSheet : '')
        }
        aria-labelledby="memory-confirm-title"
        onCancel={(event) => {
          if (memories.pending) event.preventDefault();
          else setConfirmation(null);
        }}
      >
        <h2 id="memory-confirm-title">
          {confirmation?.kind === 'restore'
            ? `恢复到版本 ${confirmation.revision.version}？`
            : '删除这条记忆？'}
        </h2>
        <p className={styles.content}>
          {confirmation?.kind === 'restore'
            ? confirmation.revision.content
            : confirmation?.memory.content}
        </p>
        <p className={styles.muted}>
          {confirmation?.kind === 'restore'
            ? `这段内容将另存为版本 ${confirmation.memory.version + 1}，原有历史保留。不会恢复已删除的记忆。`
            : '正文及全部版本历史将从本机记忆库清空，不能在 App 内撤销。仅保留不含正文的删除标记，避免旧请求恢复它。'}
        </p>
        {memories.error && confirmation && <p role="alert">{memories.error}</p>}
        <div className={styles.controls}>
          <button type="button" disabled={memories.pending} onClick={() => setConfirmation(null)}>
            {confirmation?.kind === 'restore' ? '取消恢复' : '保留记忆'}
          </button>
          <button
            className={confirmation?.kind === 'delete' ? styles.dangerAction : undefined}
            type="button"
            disabled={busy}
            onClick={() => void confirm()}
          >
            {confirmation?.kind === 'restore' ? '确认恢复' : '确认删除'}
          </button>
        </div>
      </dialog>
    </section>
  );
}
