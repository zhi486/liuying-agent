import { useBridgeStore } from '../../bridge/bridgeStore';
import type { HistoryRunRecord } from '../../bridge/contract';
import styles from './HistoryPage.module.css';
import { useHistoryRecords } from './useHistoryRecords';

export function HistoryPage() {
  const bridgeStatus = useBridgeStore((state) => state.status);
  const history = useHistoryRecords(bridgeStatus);
  const latestRun = history.data?.items[0];

  return (
    <section className="standard-page history-page">
      <header className="page-title-row">
        <div>
          <p className="eyebrow">你的节奏</p>
          <h1>跑步记录</h1>
        </div>
        <span className="month-label">
          {latestRun ? formatMonth(latestRun.startedAt) : '本地记录'}
        </span>
      </header>

      {bridgeStatus === 'unavailable' ? (
        <StatePanel
          eyebrow="浏览器预览"
          title="等待连接 Android"
          detail="这里不会展示虚构成绩。请在流萤 App 内打开此页，查看保存在手机上的真实跑步记录。"
        />
      ) : bridgeStatus === 'connecting' ? (
        <StatePanel title="正在连接跑步记录" detail="正在建立安全的本地 Bridge 连接。" />
      ) : bridgeStatus === 'error' ? (
        <StatePanel
          eyebrow="连接失败"
          title="暂时无法读取记录"
          detail="请返回跑步页后重试；你的本地记录不会丢失。"
          alert
        />
      ) : history.phase === 'loading' ? (
        <StatePanel title="正在读取本地记录" detail="流萤正在整理刚刚保存的跑步数据。" />
      ) : history.phase === 'error' ? (
        <StatePanel
          eyebrow="读取失败"
          title="没有拿到跑步记录"
          detail={history.error ?? '请稍后重试。'}
          actionLabel="重新读取"
          onAction={history.retry}
          alert
        />
      ) : history.phase === 'empty' || !latestRun ? (
        <StatePanel
          eyebrow="本地记录"
          title="还没有跑步记录"
          detail="完成并保存第一次跑步后，它会自动出现在这里。"
        />
      ) : (
        <HistoryContent
          latestRun={latestRun}
          olderRuns={history.data?.items.slice(1) ?? []}
          totalItems={history.data?.totalItems ?? 0}
          hasNextPage={history.data?.hasNextPage ?? false}
          isLoadingMore={history.isLoadingMore}
          loadMoreError={history.loadMoreError}
          onLoadMore={history.loadMore}
        />
      )}
    </section>
  );
}

interface HistoryContentProps {
  latestRun: HistoryRunRecord;
  olderRuns: HistoryRunRecord[];
  totalItems: number;
  hasNextPage: boolean;
  isLoadingMore: boolean;
  loadMoreError: string | null;
  onLoadMore(): void;
}

function HistoryContent({
  latestRun,
  olderRuns,
  totalItems,
  hasNextPage,
  isLoadingMore,
  loadMoreError,
  onLoadMore,
}: HistoryContentProps) {
  return (
    <>
      <article className="latest-run">
        <div className="latest-run-heading">
          <div>
            <span>{formatRunTime(latestRun.startedAt)}</span>
            <h2>最近一次跑步</h2>
          </div>
          <span className="run-status">已保存</span>
        </div>
        <div className="distance-lockup">
          <strong>{formatDistanceKm(latestRun.distanceMeters)}</strong>
          <span>公里</span>
        </div>
        <dl className="compact-stats">
          <div>
            <dt>用时</dt>
            <dd>{formatDuration(latestRun.durationSeconds)}</dd>
          </div>
          <div>
            <dt>平均配速</dt>
            <dd>{formatPace(latestRun.averagePaceSecPerKm)}</dd>
          </div>
          <div>
            <dt>平均心率</dt>
            <dd>{formatOptionalInteger(latestRun.averageHeartRate)}</dd>
          </div>
        </dl>
      </article>

      <section className={`past-runs ${styles.recordCount}`} aria-labelledby="past-runs-title">
        <div className="section-heading">
          <h2 id="past-runs-title">更早记录</h2>
          <span>共 {totalItems} 次</span>
        </div>
        {olderRuns.length > 0 && (
          <ul>
            {olderRuns.map((run) => (
              <li key={run.id}>
                <div className="date-chip">
                  <strong>{formatDay(run.startedAt)}</strong>
                  <span>{formatShortMonth(run.startedAt)}</span>
                </div>
                <div>
                  <strong>{formatDuration(run.durationSeconds)}</strong>
                  <span>{formatPace(run.averagePaceSecPerKm)} /km</span>
                </div>
                <strong>{formatListDistance(run.distanceMeters)}</strong>
              </li>
            ))}
          </ul>
        )}
        {hasNextPage && (
          <button
            className={`secondary-button ${styles.loadMore}`}
            type="button"
            disabled={isLoadingMore}
            onClick={onLoadMore}
          >
            {isLoadingMore ? '正在读取…' : '加载更多'}
          </button>
        )}
        {loadMoreError && (
          <p className={styles.loadMoreError} role="alert">
            {loadMoreError}
          </p>
        )}
      </section>
    </>
  );
}

interface StatePanelProps {
  eyebrow?: string;
  title: string;
  detail: string;
  actionLabel?: string;
  onAction?: () => void;
  alert?: boolean;
}

function StatePanel({ eyebrow, title, detail, actionLabel, onAction, alert }: StatePanelProps) {
  return (
    <article
      className={`latest-run ${styles.statePanel}`}
      role={alert ? 'alert' : 'status'}
      aria-live="polite"
    >
      {eyebrow && <p className="eyebrow">{eyebrow}</p>}
      <h2>{title}</h2>
      <p>{detail}</p>
      {actionLabel && onAction && (
        <button className="secondary-button" type="button" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </article>
  );
}

function formatMonth(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'long' }).format(new Date(value));
}

function formatRunTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

function formatDay(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { day: '2-digit' }).format(new Date(value));
}

function formatShortMonth(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short' }).format(new Date(value));
}

function formatDistanceKm(meters: number) {
  return (meters / 1000).toFixed(2);
}

function formatListDistance(meters: number) {
  return meters < 1000 ? `${Math.round(meters)} m` : `${formatDistanceKm(meters)} km`;
}

function formatDuration(seconds: number) {
  const rounded = Math.max(0, Math.round(seconds));
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const remainingSeconds = rounded % 60;
  const minuteValue = hours > 0 ? String(minutes).padStart(2, '0') : String(minutes);
  const body = `${minuteValue}:${String(remainingSeconds).padStart(2, '0')}`;
  return hours > 0 ? `${hours}:${body}` : body;
}

function formatPace(seconds: number | null) {
  return seconds === null ? '--' : formatDuration(seconds);
}

function formatOptionalInteger(value: number | null) {
  return value === null ? '--' : String(Math.round(value));
}
