import { useEffect, useRef, useState } from 'react';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import { memoryHistorySchema, type MemoryRecord, type MemoryRevision } from '../../bridge/memories';
import type { z } from 'zod';
import styles from './MemoriesPage.module.css';

const labels = { manual: '手动编辑', agent: '流萤记住', restored: '恢复版本', legacy: '旧版记录' };
export function MemoryHistory({
  memory,
  ready,
  busy,
  onRestore,
  onClose,
}: {
  memory: MemoryRecord;
  ready: boolean;
  busy: boolean;
  onRestore: (revision: MemoryRevision) => void;
  onClose: () => void;
}) {
  const [offset, setOffset] = useState(0);
  const [revision, setRevision] = useState(0);
  const [page, setPage] = useState<z.infer<typeof memoryHistorySchema> | null>(null);
  const [error, setError] = useState('');
  const epoch = useRef(0);
  useEffect(() => {
    const request = ++epoch.current;
    setPage(null);
    setError('');
    if (ready)
      void sendNativeCommand('memory.history', { memoryId: memory.id, offset, limit: 10 })
        .then((result) => {
          if (request !== epoch.current) return;
          if (result.status !== 'accepted') throw new Error();
          const next = memoryHistorySchema.parse(result.data);
          if (
            next.memoryId !== memory.id ||
            next.currentVersion !== memory.version ||
            next.offset !== offset ||
            next.limit !== 10
          )
            throw new Error();
          setPage(next);
        })
        .catch(() => {
          if (request === epoch.current)
            setError('版本历史未确认。可重试；若记忆已变化，请关闭历史并刷新记忆。');
        });
    return () => {
      epoch.current = request + 1;
    };
  }, [ready, memory.id, memory.version, offset, revision]);
  const disabled = !ready || busy || !page;
  return (
    <section className={styles.history} aria-label="记忆版本历史">
      <div className={styles.controls}>
        <h3>版本历史</h3>
        <button type="button" disabled={busy} onClick={onClose}>
          收起历史
        </button>
      </div>
      {!ready ? (
        <p role="status">连接断开，重连后重新读取历史。</p>
      ) : error ? (
        <>
          <p role="alert">{error}</p>
          <button type="button" disabled={busy} onClick={() => setRevision((value) => value + 1)}>
            重试读取历史
          </button>
        </>
      ) : !page ? (
        <p role="status">正在读取版本历史…</p>
      ) : (
        <>
          {page.totalItems === 0 && <p>没有可用的历史版本。</p>}
          <ol className={styles.revisions}>
            {page.items.map((item) => (
              <li key={item.version}>
                <p className={styles.muted}>
                  版本 {item.version}
                  {item.version === memory.version ? ' · 当前' : ''} · {labels[item.changeSource]}
                </p>
                <p className={styles.content}>{item.content}</p>
                <small className={styles.muted}>
                  {new Date(item.changedAtMillis).toLocaleString('zh-CN')}
                </small>
                {item.version < memory.version && (
                  <button type="button" disabled={disabled} onClick={() => onRestore(item)}>
                    恢复版本 {item.version}
                  </button>
                )}
              </li>
            ))}
          </ol>
          {(offset > 0 || page.totalItems > 10) && (
            <div className={styles.controls}>
              <button
                type="button"
                disabled={disabled || offset === 0}
                onClick={() => setOffset((value) => Math.max(0, value - 10))}
              >
                历史上一页
              </button>
              <small>第 {Math.floor(offset / 10) + 1} 页</small>
              <button
                type="button"
                disabled={disabled || offset + 10 >= page.totalItems}
                onClick={() => setOffset((value) => value + 10)}
              >
                历史下一页
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
