import { useEffect, useRef, useState } from 'react';
import type { z } from 'zod';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import { memoryDocumentPageSchema } from '../../bridge/memories';
import styles from './MemoriesPage.module.css';
import { MemoryDocumentSave } from './MemoryDocumentSave';

const sources = {
  manual: '手动保存或编辑',
  agent: 'Agent 经授权写入',
  restored: '用户恢复历史版本',
  legacy: '旧记录（来源未记录）',
};
const categories = { preference: '偏好', goal: '目标', fact: '事实' };

export function MemoryDocumentPanel({ ready, onClose }: { ready: boolean; onClose: () => void }) {
  const [offset, setOffset] = useState(0);
  const [revision, setRevision] = useState(0);
  const [page, setPage] = useState<z.infer<typeof memoryDocumentPageSchema> | null>(null);
  const [error, setError] = useState(false);
  const epoch = useRef(0);
  useEffect(() => {
    const request = ++epoch.current;
    setPage(null);
    setError(false);
    if (ready)
      void sendNativeCommand('memory.document', { offset, limit: 20 })
        .then((result) => {
          if (request !== epoch.current) return;
          if (result.status !== 'accepted') throw new Error();
          const next = memoryDocumentPageSchema.parse(result.data);
          if (next.offset !== offset || next.limit !== 20) throw new Error();
          setPage(next);
        })
        .catch(() => {
          if (request === epoch.current) setError(true);
        });
    return () => {
      epoch.current = request + 1;
    };
  }, [ready, offset, revision]);
  const loading = ready && !page && !error;
  return (
    <section className={styles.document} aria-labelledby="memory-document-title">
      <div className={styles.controls}>
        <h2 id="memory-document-title">记忆文档</h2>
        <button type="button" onClick={onClose}>
          收起文档
        </button>
      </div>
      <p className={styles.muted}>
        只读查看当前有效记忆，不含已删除内容或历史正文。每页重新读取，编辑或删除后请刷新；不会上传或调用模型。
      </p>
      <MemoryDocumentSave ready={ready} />
      {!ready ? (
        <p role="status">连接断开，请重新连接手机后读取文档。</p>
      ) : error ? (
        <p role="alert">文档未读取成功。请确认手机已安装支持文档的新版本，再重新读取。</p>
      ) : loading ? (
        <p role="status">正在读取记忆文档…</p>
      ) : (
        page && (
          <>
            <p className={styles.muted}>
              共 {page.totalItems} 条 · 本页读取时间：
              {new Date(page.generatedAtMillis).toLocaleString('zh-CN')}
            </p>
            {page.totalItems === 0 && <p>暂无有效记忆。</p>}
            {page.totalItems > 0 && page.items.length === 0 && (
              <p>记录已变化，本页没有内容，请重新读取。</p>
            )}
            <ol className={styles.documentEntries} start={offset + 1}>
              {page.items.map((entry) => (
                <li key={entry.memory.id}>
                  <p className={styles.content}>{entry.memory.content}</p>
                  <p className={styles.muted}>
                    {entry.category ? categories[entry.category] : '未分类'} · 版本{' '}
                    {entry.memory.version} ·{' '}
                    {entry.changeSource ? sources[entry.changeSource] : sources.legacy}
                  </p>
                  <dl className={styles.documentMetadata}>
                    <dt>创建</dt>
                    <dd>{new Date(entry.memory.createdAtMillis).toLocaleString('zh-CN')}</dd>
                    <dt>更新</dt>
                    <dd>{new Date(entry.memory.updatedAtMillis).toLocaleString('zh-CN')}</dd>
                    <dt>来源消息</dt>
                    <dd>{entry.sourceTurnId ?? '未记录或不适用'}</dd>
                  </dl>
                </li>
              ))}
            </ol>
            {(offset > 0 || page.totalItems > 20) && (
              <div className={styles.controls}>
                <button
                  type="button"
                  disabled={offset === 0}
                  onClick={() => setOffset((value) => Math.max(0, value - 20))}
                >
                  文档上一页
                </button>
                <small>第 {Math.floor(offset / 20) + 1} 页</small>
                <button
                  type="button"
                  disabled={offset + 20 >= page.totalItems}
                  onClick={() => setOffset((value) => value + 20)}
                >
                  文档下一页
                </button>
              </div>
            )}
          </>
        )
      )}
      <button
        className={styles.reload}
        type="button"
        disabled={!ready || loading}
        onClick={() => {
          setOffset(0);
          setRevision((value) => value + 1);
        }}
      >
        重新读取文档
      </button>
    </section>
  );
}
