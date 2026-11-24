import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import type { HistoryDetailResult, RunMetricSample } from '../../bridge/contract';
import { historyDetailResultSchema } from '../../bridge/schemas';
import styles from './RunDetailPage.module.css';
import { RunMetricCharts } from './RunMetricCharts';
import { formatMetric } from './runMetricChart';

export function RunDetailPage() {
  const { sessionId = '' } = useParams();
  const status = useBridgeStore((state) => state.status);
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{
    id: string;
    data?: HistoryDetailResult;
    error?: string;
  } | null>(null);

  useEffect(() => {
    if (status !== 'ready') return;
    let active = true;
    setResult(null);
    void (async () => {
      try {
        const response = await sendNativeCommand('history.detail', { sessionId });
        if (response.status === 'rejected') {
          throw new Error(
            response.error.code === 'INVALID_MESSAGE'
              ? '当前版本不支持此详情或记录标识无效，请更新 App 后重试。'
              : response.error.message,
          );
        }
        const parsed = historyDetailResultSchema.safeParse(response.data);
        if (!parsed.success) throw new Error('总结数据格式无效，请重试');
        if (parsed.data.record.id !== sessionId) throw new Error('返回的记录与当前跑步不匹配');
        if (active) setResult({ id: sessionId, data: parsed.data });
      } catch (error) {
        if (active)
          setResult({
            id: sessionId,
            error: error instanceof Error ? error.message : '读取总结失败',
          });
      }
    })();
    return () => {
      active = false;
    };
  }, [sessionId, status, attempt]);

  const current = status === 'ready' && result?.id === sessionId ? result : null;
  return (
    <section className="standard-page">
      {status !== 'ready' ? (
        <p className={styles.notice} role="status">
          {status === 'unavailable'
            ? '请在流萤 App 内查看已保存的真实跑步总结。'
            : status === 'connecting'
              ? '正在连接本地记录…'
              : '本地连接不可用，请重新打开此页。'}
        </p>
      ) : current?.error ? (
        <div className={styles.notice} role="alert">
          <p>{current.error}</p>
          <button className="secondary-button" onClick={() => setAttempt((value) => value + 1)}>
            重新读取
          </button>
        </div>
      ) : current?.data ? (
        <RunSummary data={current.data} />
      ) : (
        <p className={styles.notice} role="status">
          正在读取本次总结…
        </p>
      )}
    </section>
  );
}

export function RunSummary({
  data,
  previewSamples,
}: {
  data: HistoryDetailResult;
  previewSamples?: RunMetricSample[];
}) {
  const { record } = data;
  const splits = [...data.splits].sort((a, b) => a.index - b.index);
  const [splitsOpen, setSplitsOpen] = useState(false);
  const fastestPace = splits.length
    ? Math.min(...splits.map((split) => split.averagePaceSecPerKm))
    : null;
  return (
    <>
      <article className={styles.hero}>
        <p>
          {new Intl.DateTimeFormat('zh-CN', {
            year: 'numeric',
            month: 'long',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
            hour12: false,
          }).format(new Date(record.startedAt))}
        </p>
        <h2 className={styles.activity}>户外跑步</h2>
        <div className="distance-lockup">
          <strong>{(record.distanceMeters / 1000).toFixed(2)}</strong>
          <span>公里</span>
        </div>
      </article>
      <section className={styles.section} aria-labelledby="metrics-title">
        <h2 id="metrics-title">运动数据</h2>
        <dl className={styles.metrics}>
          <div>
            <dt>运动用时</dt>
            <dd>{duration(record.durationSeconds)}</dd>
          </div>
          <div>
            <dt>平均配速</dt>
            <dd>{formatMetric(record.averagePaceSecPerKm, 'pace')}</dd>
          </div>
          <div>
            <dt>平均心率</dt>
            <dd>
              {optional(record.averageHeartRate)}
              <small> 次/分</small>
            </dd>
          </div>
          <div>
            <dt>平均步频</dt>
            <dd>
              {optional(record.averageCadence)}
              <small> 步/分</small>
            </dd>
          </div>
          <div>
            <dt>最快分段配速</dt>
            <dd>{formatMetric(fastestPace, 'pace')}</dd>
          </div>
          <div>
            <dt>已保存分段</dt>
            <dd>
              {splits.length}
              <small> 段</small>
            </dd>
          </div>
        </dl>
      </section>
      <section className={styles.section} aria-labelledby="splits-title">
        <header className={styles.sectionHeader}>
          <h2 id="splits-title">分段详情</h2>
          {splits.length > 0 && (
            <button onClick={() => setSplitsOpen(true)} aria-label="查看分段详情">
              查看详情 ›
            </button>
          )}
        </header>
        {splits.length === 0 ? (
          <p className={styles.muted}>未保存分段数据</p>
        ) : (
          <SplitTable splits={splits} fastestPace={fastestPace} />
        )}
      </section>
      {splitsOpen && (
        <SplitDialog
          splits={splits}
          fastestPace={fastestPace}
          onClose={() => setSplitsOpen(false)}
          simulated={previewSamples !== undefined}
        />
      )}
      <RunMetricCharts key={record.id} record={record} previewSamples={previewSamples} />
      {!previewSamples && (
        <section className={styles.recap} aria-labelledby="recap-title">
          <p className="eyebrow">流萤 · 本次记录</p>
          <h2 id="recap-title">为这一程留个记号</h2>
          <p>
            你完成了 {(record.distanceMeters / 1000).toFixed(2)} 公里，用时{' '}
            {duration(record.durationSeconds)}。本次保存了 {splits.length} 个分段。
          </p>
          <p className={styles.muted}>以上为本地数据摘要，暂不提供训练评分或 AI 健康判断。</p>
          <Link className="secondary-button" to="/agent" state={{ runId: record.id }}>
            让流萤复盘本次跑步
          </Link>
          <p className={styles.muted}>先到对话页核对概要并单次授权；点击不会上传或调用模型。</p>
        </section>
      )}
    </>
  );
}

function SplitTable({
  splits,
  fastestPace,
  expanded = false,
}: {
  splits: HistoryDetailResult['splits'];
  fastestPace: number | null;
  expanded?: boolean;
}) {
  let cumulativeSeconds = 0;
  return (
    <div className={styles.tableScroll} tabIndex={0} role="region" aria-label="分段表，可横向滚动">
      <table aria-label="每公里分段">
        <thead>
          <tr>
            <th scope="col">段</th>
            <th scope="col">公里点</th>
            <th scope="col">用时</th>
            {expanded && <th scope="col">分段累计</th>}
            <th scope="col">配速</th>
            <th scope="col">心率</th>
          </tr>
        </thead>
        <tbody>
          {splits.map((split) => {
            cumulativeSeconds += split.durationSeconds;
            const fastest = split.averagePaceSecPerKm === fastestPace;
            return (
              <tr key={split.index} className={fastest ? styles.fastest : undefined}>
                <th scope="row">
                  {split.index}
                  {fastest && <small> 最快</small>}
                </th>
                <td>{(split.endDistanceMeters / 1000).toFixed(2)}</td>
                <td>{duration(split.durationSeconds)}</td>
                {expanded && <td>{duration(cumulativeSeconds)}</td>}
                <td className={styles.pace}>{formatMetric(split.averagePaceSecPerKm, 'pace')}</td>
                <td>{optional(split.averageHeartRate)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function SplitDialog({
  splits,
  fastestPace,
  onClose,
  simulated,
}: {
  splits: HistoryDetailResult['splits'];
  fastestPace: number | null;
  onClose: () => void;
  simulated: boolean;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    dialog.current?.showModal();
    if (dialog.current) dialog.current.scrollTop = 0;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus({ preventScroll: true });
    };
  }, []);
  return (
    <dialog
      ref={dialog}
      className={styles.dialog}
      aria-label="分段详情"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <button className={styles.close} onClick={onClose} aria-label="关闭分段详情">
        ×
      </button>
      <h2>分段详情</h2>
      {simulated && <p className={styles.muted}>模拟数据 · 仅供界面演示</p>}
      <p className={styles.muted}>左右滑动查看完整数据</p>
      <SplitTable splits={splits} fastestPace={fastestPace} expanded />
      <p className={styles.muted}>
        公里点为该段结束位置；分段累计仅累加已保存分段的用时，不包含未保存的尾段。心率单位为次/分，缺失数据保留为“未采集”。
      </p>
    </dialog>
  );
}

function duration(seconds: number) {
  const value = Math.round(seconds);
  const hours = Math.floor(value / 3600);
  const minutes = Math.floor((value % 3600) / 60);
  const body = `${hours ? String(minutes).padStart(2, '0') : minutes}:${String(value % 60).padStart(2, '0')}`;
  return hours ? `${hours}:${body}` : body;
}
function optional(value: number | null) {
  return value === null ? '未采集' : String(Math.round(value));
}
