import { useEffect, useRef, useState } from 'react';
import type { z } from 'zod';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import { memoryExportStatusSchema } from '../../bridge/memories';
import styles from './MemoriesPage.module.css';

type Status = z.infer<typeof memoryExportStatusSchema>;
const pending = (status: Status | null) =>
  status?.phase === 'choosing' || status?.phase === 'writing';

export function MemoryDocumentSave({ ready }: { ready: boolean }) {
  const [status, setStatus] = useState<Status | null>(null);
  const [unknown, setUnknown] = useState(false);
  const [busy, setBusy] = useState(false);
  const active = useRef<string | null>(null);
  const epoch = useRef(0);
  useEffect(() => {
    setStatus(null);
    setUnknown(false);
    setBusy(false);
    return () => {
      epoch.current += 1;
      const exportId = active.current;
      active.current = null;
      if (exportId) void sendNativeCommand('memory.exportCancel', { exportId }).catch(() => {});
    };
  }, [ready]);

  async function command(name: 'memory.exportBegin' | 'memory.exportStatus', exportId: string) {
    const request = epoch.current;
    setBusy(true);
    setUnknown(false);
    try {
      const result =
        name === 'memory.exportBegin'
          ? await sendNativeCommand(name, { exportId, confirmed: true })
          : await sendNativeCommand(name, { exportId });
      if (request !== epoch.current) return;
      if (result.status !== 'accepted') throw new Error();
      const next = memoryExportStatusSchema.parse(result.data);
      if (next.exportId !== exportId) throw new Error();
      setStatus(next);
      if (!pending(next)) active.current = null;
    } catch {
      if (request === epoch.current) setUnknown(true);
    } finally {
      if (request === epoch.current) setBusy(false);
    }
  }

  useEffect(() => {
    if (!ready || !pending(status) || unknown || busy) return;
    const timer = setTimeout(() => {
      const exportId = active.current;
      if (exportId) void command('memory.exportStatus', exportId);
    }, 1000);
    return () => clearTimeout(timer);
  }, [ready, status, unknown, busy]);

  return (
    <div className={styles.documentSave}>
      <button
        type="button"
        disabled={!ready || busy || active.current !== null}
        onClick={() => {
          if (!ready || busy || active.current !== null) return;
          const exportId = crypto.randomUUID();
          active.current = exportId;
          setStatus(null);
          void command('memory.exportBegin', exportId);
        }}
      >
        保存完整 Markdown
      </button>
      <p className={styles.muted}>
        选择位置后保存当时的全部有效记忆，不限当前页。副本不会随应用内编辑或删除更新。应用不上传；若选择云盘，文件可能由该存储服务同步。
      </p>
      {ready &&
        (unknown ? (
          <div>
            <p role="alert">
              保存状态未确认，不会自动重做。可查询本次结果；如已退出或重启，请检查目标文件，可能留有空文件或不完整副本。状态失效时可收起文档后重新打开。
            </p>
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                if (active.current) void command('memory.exportStatus', active.current);
              }}
            >
              查询本次保存结果
            </button>
          </div>
        ) : (
          status && (
            <p role="status">
              {status.phase === 'choosing' && '请在系统文件窗口选择保存位置，或取消。'}
              {status.phase === 'writing' && '正在保存完整记忆文档，请暂时留在此页。'}
              {status.phase === 'saved' &&
                `已保存 ${status.totalItems} 条有效记忆，快照时间：${new Date(status.generatedAtMillis!).toLocaleString('zh-CN')}。`}
              {status.phase === 'cancelled' && '已取消保存，未写入记忆正文。'}
              {status.phase === 'failed' &&
                '保存失败，未确认完整写入；目标位置可能留有空文件或不完整副本，请检查后再保存。'}
              {status.phase === 'interrupted' &&
                '保存已中断，目标文件可能不完整，请检查；不会自动续写。'}
            </p>
          )
        ))}
    </div>
  );
}
