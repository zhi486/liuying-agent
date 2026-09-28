import { useEffect, useRef, useState } from 'react';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import { memoryPreviewSchema, type MemoryRecord } from '../../bridge/memories';
import { useMemories } from '../memory/useMemories';
import styles from './AgentPage.module.css';
import type { SelectedMemoryRef } from '../../bridge/cloudAgent';

type Props = {
  status: 'unavailable' | 'connecting' | 'ready' | 'error';
  prompt: string;
  onSelectionChange?: (refs: SelectedMemoryRef[]) => void;
};

export function AgentMemoryPreview(props: Props) {
  const [open, setOpen] = useState(false);
  return (
    <div className={styles.memorySelection}>
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? '收起记忆选择' : '选择本次记忆'}
      </button>
      {open && <MemorySelection {...props} />}
    </div>
  );
}

function MemorySelection({ status, prompt, onSelectionChange }: Props) {
  const memories = useMemories(status);
  const [selected, setSelected] = useState<MemoryRecord[]>([]);
  const [preview, setPreview] = useState<MemoryRecord[] | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const sequence = useRef(0);
  const locked = useRef(false);
  useEffect(() => {
    onSelectionChange?.(selected.map(({ id, version }) => ({ id, version })));
  }, [selected, onSelectionChange]);
  useEffect(() => () => onSelectionChange?.([]), [onSelectionChange]);

  useEffect(() => {
    sequence.current++;
    locked.current = false;
    setPreview(null);
    setPending(false);
    setError('');
    return () => {
      sequence.current += 1;
    };
  }, [status, prompt]);

  useEffect(() => {
    setSelected([]);
  }, [status]);

  function clear() {
    sequence.current++;
    locked.current = false;
    setPending(false);
    setPreview(null);
    setError('');
    setSelected([]);
  }

  function toggle(memory: MemoryRecord) {
    if (locked.current) return;
    setPreview(null);
    setError('');
    setSelected((previous) =>
      previous.some((item) => item.id === memory.id)
        ? previous.filter((item) => item.id !== memory.id)
        : previous.length < 5
          ? [...previous, memory]
          : previous,
    );
  }

  async function check() {
    if (status !== 'ready' || locked.current || selected.length === 0) return;
    locked.current = true;
    const request = ++sequence.current;
    setPending(true);
    setPreview(null);
    setError('');
    try {
      const result = await sendNativeCommand('memory.preview', {
        memoryRefs: selected.map(({ id, version }) => ({ id, version })),
      });
      if (request !== sequence.current) return;
      if (result.status === 'rejected') throw new Error();
      const parsed = memoryPreviewSchema.safeParse(result.data);
      if (
        !parsed.success ||
        parsed.data.items.length !== selected.length ||
        parsed.data.items.some(
          (item, index) =>
            item.id !== selected[index].id ||
            item.version !== selected[index].version ||
            item.content !== selected[index].content,
        )
      )
        throw new Error();
      setPreview(parsed.data.items);
    } catch {
      if (request === sequence.current)
        setError('记忆可能已修改或删除，核对失败。请刷新后重新选择；没有上传。');
    } finally {
      if (request === sequence.current) {
        locked.current = false;
        setPending(false);
      }
    }
  }

  return (
    <section aria-labelledby="memory-selection-title">
      <h2 id="memory-selection-title">本次记忆 · 本机预览</h2>
      <p>默认不选，最多 5 条。选择和预览不会上传，也不代表同意云端使用。</p>
      <p>在线执行前还需确认本次问题与记忆；仅保存不会附带这些记忆。</p>
      {status !== 'ready' ? (
        <p>请在手机 App 内选择记忆。</p>
      ) : (
        <>
          {memories.loading && <p role="status">正在读取可选记忆…</p>}
          {memories.error && <p role="alert">{memories.error}</p>}
          {!memories.loading && memories.page?.total === 0 && (
            <p>还没有记忆，可先到“管理记忆”添加。</p>
          )}
          <ul className={styles.memoryList}>
            {memories.page?.items.map((memory) => (
              <li key={memory.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={selected.some((item) => item.id === memory.id)}
                    disabled={
                      pending ||
                      memories.loading ||
                      (selected.length >= 5 && !selected.some((item) => item.id === memory.id))
                    }
                    onChange={() => toggle(memory)}
                  />
                  <span>
                    {memory.content}
                    <small>你手动添加 · 版本 {memory.version}</small>
                  </span>
                </label>
              </li>
            ))}
          </ul>
          <p aria-live="polite">已选择 {selected.length} / 5 条</p>
          <div className={styles.taskControls}>
            <button
              type="button"
              onClick={() => {
                clear();
                memories.refresh();
              }}
              disabled={pending || memories.loading}
            >
              刷新可选记忆
            </button>
            <button
              type="button"
              onClick={() => {
                clear();
                memories.previous();
              }}
              disabled={pending || memories.loading || !memories.page || memories.page.offset === 0}
            >
              上一页记忆
            </button>
            <button
              type="button"
              onClick={() => {
                clear();
                memories.next();
              }}
              disabled={
                pending ||
                memories.loading ||
                !memories.page ||
                memories.page.offset + 20 >= memories.page.total
              }
            >
              下一页记忆
            </button>
            <button type="button" onClick={clear} disabled={pending || selected.length === 0}>
              清空选择
            </button>
            <button
              type="button"
              onClick={() => void check()}
              disabled={pending || memories.loading || selected.length === 0}
            >
              {pending ? '正在核对…' : '核对本次记忆'}
            </button>
          </div>
          <p>换页、刷新或收起会清空选择。</p>
          {error && <p role="alert">{error}</p>}
          {preview && (
            <section aria-label="已核对的记忆预览">
              <h3>本机核对通过 · 尚未发送</h3>
              <ul>
                {preview.map((memory) => (
                  <li key={memory.id}>
                    {memory.content}（版本 {memory.version}）
                  </li>
                ))}
              </ul>
              <p>这里只确认了当前内容。发送前仍需重新检查版本，不会自动沿用本次预览。</p>
            </section>
          )}
        </>
      )}
    </section>
  );
}
