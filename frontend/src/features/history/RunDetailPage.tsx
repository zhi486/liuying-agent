import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import type { HistoryDetailResult } from '../../bridge/contract';
import { historyDetailResultSchema } from '../../bridge/schemas';
import styles from './RunDetailPage.module.css';
import { RunMetricCharts } from './RunMetricCharts';

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
    <section className={`standard-page ${styles.page}`}>
      <Link className={styles.back} to="/history">
        ← 跑步记录
      </Link>
      <header className="page-title-row">
        <div>
          <p className="eyebrow">每一步，都算数</p>
          <h1>跑后总结</h1>
        </div>
      </header>
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

function RunSummary({ data }: { data: HistoryDetailResult }) {
  const { record } = data;
  const splits = [...data.splits].sort((a, b) => a.index - b.index);
  const longestPace = Math.max(1, ...splits.map((split) => split.averagePaceSecPerKm));
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
        <div className="distance-lockup">
          <strong>{(record.distanceMeters / 1000).toFixed(2)}</strong>
          <span>公里</span>
        </div>
        <dl className={styles.metrics}>
          <div>
            <dt>运动用时</dt>
            <dd>{duration(record.durationSeconds)}</dd>
          </div>
          <div>
            <dt>平均配速 / 公里</dt>
            <dd>
              {record.averagePaceSecPerKm === null
                ? '未采集'
                : duration(record.averagePaceSecPerKm)}
            </dd>
          </div>
          <div>
            <dt>平均心率 / 次每分</dt>
            <dd>{optional(record.averageHeartRate)}</dd>
          </div>
          <div>
            <dt>平均步频 / 步每分</dt>
            <dd>{optional(record.averageCadence)}</dd>
          </div>
        </dl>
      </article>
      <section className={styles.section} aria-labelledby="splits-title">
        <h2 id="splits-title">每公里分段</h2>
        {splits.length === 0 ? (
          <p className={styles.muted}>未保存分段数据</p>
        ) : (
          <>
            <div
              className={styles.tableScroll}
              tabIndex={0}
              role="region"
              aria-label="分段表，可横向滚动"
            >
              <table aria-label="每公里分段">
                <thead>
                  <tr>
                    <th scope="col">段</th>
                    <th scope="col">公里点</th>
                    <th scope="col">用时</th>
                    <th scope="col">配速</th>
                    <th scope="col">心率</th>
                  </tr>
                </thead>
                <tbody>
                  {splits.map((split) => (
                    <tr key={split.index}>
                      <th scope="row">{split.index}</th>
                      <td>{(split.endDistanceMeters / 1000).toFixed(2)}</td>
                      <td>{duration(split.durationSeconds)}</td>
                      <td>{duration(split.averagePaceSecPerKm)}</td>
                      <td>{optional(split.averageHeartRate)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <h2 className={styles.chartTitle}>每公里平均配速</h2>
            <p className={styles.muted}>条形越短，用时越少。下图为分段均值，不是实时配速曲线。</p>
            <div className={styles.chart} aria-hidden="true">
              {splits.map((split) => (
                <div className={styles.barRow} key={split.index}>
                  <span>{split.index}</span>
                  <div className={styles.track}>
                    <span
                      style={{ width: `${(split.averagePaceSecPerKm / longestPace) * 100}%` }}
                    />
                  </div>
                  <span>{duration(split.averagePaceSecPerKm)}</span>
                </div>
              ))}
            </div>
          </>
        )}
      </section>
      <RunMetricCharts key={record.id} record={record} />
      <section className={styles.recap} aria-labelledby="recap-title">
        <p className="eyebrow">流萤 · 本次记录</p>
        <h2 id="recap-title">为这一程留个记号</h2>
        <p>
          你完成了 {(record.distanceMeters / 1000).toFixed(2)} 公里，用时{' '}
          {duration(record.durationSeconds)}。本次保存了 {splits.length} 个分段。
        </p>
        <p className={styles.muted}>以上为本地数据摘要，暂不提供训练评分或 AI 健康判断。</p>
      </section>
    </>
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
