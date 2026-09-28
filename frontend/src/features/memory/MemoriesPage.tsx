import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useBridgeStore } from '../../bridge/bridgeStore';
import { memoryContentSchema, type MemoryRecord } from '../../bridge/memories';
import { useMemories } from './useMemories';
import styles from './MemoriesPage.module.css';

export function MemoriesPage() {
  const status = useBridgeStore((state) => state.status);
  const memories = useMemories(status);
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState<{ memory: MemoryRecord; content: string } | null>(null);
  const [deleting, setDeleting] = useState<MemoryRecord | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const editInput = useRef<HTMLTextAreaElement>(null);
  const editingId = editing?.memory.id;
  const ready = status === 'ready';
  const busy = !ready || memories.pending;

  useEffect(() => {
    const popup = dialog.current;
    if (!popup) return;
    if (!deleting) {
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
    popup.showModal();
    window.addEventListener('resize', resize);
    const viewport = window.visualViewport;
    viewport?.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
      viewport?.removeEventListener('resize', resize);
    };
  }, [deleting]);

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
  async function remove() {
    if (deleting && (await memories.change({ kind: 'delete', memory: deleting })))
      setDeleting(null);
  }

  return (
    <section className={styles.page} aria-labelledby="memory-title">
      <Link to="/agent" className={styles.back}>
        返回首页
      </Link>
      <header>
        <p className="eyebrow">由你决定记住什么</p>
        <h1 id="memory-title">我的记忆</h1>
        <p className={styles.muted}>保存你的跑步偏好、目标和希望流萤记住的事。</p>
      </header>
      <p className={styles.scope}>
        目前仅保存在这台手机，不自动提取对话、不上传，也尚未用于 Agent
        回答。请勿保存密钥、密码等秘密。
      </p>
      {!ready && <p role="status">请在手机 App 内管理记忆。</p>}
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
      {memories.error && !deleting && <p role="alert">{memories.error}</p>}
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
        {memories.loading && <p role="status">正在读取本机记忆…</p>}
        {memories.page?.total === 0 && (
          <p className={styles.empty}>还没有记忆。先写下一件你想让流萤记住的事吧。</p>
        )}
        <ul className={styles.list}>
          {memories.page?.items.map((memory) => (
            <li key={memory.id}>
              <p className={styles.content}>{memory.content}</p>
              <small className={styles.muted}>
                你手动添加 · {new Date(memory.updatedAtMillis).toLocaleString('zh-CN')}
              </small>
              <div className={styles.itemActions}>
                <button
                  type="button"
                  disabled={busy || memories.loading || editing !== null}
                  onClick={() => setEditing({ memory, content: memory.content })}
                  aria-label={`编辑记忆：${memory.content}`}
                >
                  编辑
                </button>
                <button
                  type="button"
                  disabled={busy || memories.loading || editing !== null}
                  onClick={() => setDeleting(memory)}
                  aria-label={`删除记忆：${memory.content}`}
                >
                  删除
                </button>
              </div>
            </li>
          ))}
        </ul>
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
      <p className={styles.scope}>
        记忆不会随系统自动备份。卸载或清除 App 数据后可能丢失；当前未提供跨设备同步。
      </p>
      <dialog
        ref={dialog}
        className={styles.dialog}
        aria-labelledby="memory-delete-title"
        onCancel={(event) => {
          if (memories.pending) event.preventDefault();
          else setDeleting(null);
        }}
      >
        <h2 id="memory-delete-title">删除这条记忆？</h2>
        <p className={styles.content}>{deleting?.content}</p>
        <p className={styles.muted}>
          正文将从本机记忆库清空，不能在 App 内撤销。仅保留不含正文的删除标记，避免旧请求恢复它。
        </p>
        {memories.error && deleting && <p role="alert">{memories.error}</p>}
        <div className={styles.controls}>
          <button type="button" disabled={memories.pending} onClick={() => setDeleting(null)}>
            保留记忆
          </button>
          <button type="button" disabled={busy} onClick={() => void remove()}>
            确认删除
          </button>
        </div>
      </dialog>
    </section>
  );
}
