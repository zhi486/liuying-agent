import { ArrowRight, CaretRight } from '@phosphor-icons/react';
import { Link } from 'react-router-dom';
import { useBridgeStore } from '../../bridge/bridgeStore';
import type { HistoryRunRecord } from '../../bridge/contract';
import styles from './HistoryPage.module.css';
import { useHistoryRecords } from './useHistoryRecords';
import { LoadingSkeleton } from '../../design-system/LoadingSkeleton';

export function HistoryPage() {
  const bridgeStatus = useBridgeStore((state) => state.status);
  const history = useHistoryRecords(bridgeStatus);
  const latestRun = history.data?.items[0];

  return (
    <section className={`${styles.page} standard-page history-page`}>
      <div className={styles.historyMeta}>
        <span className={styles.recordPeriod}>
          {latestRun ? formatMonth(latestRun.startedAt) : '本地记录'}
        </span>
        <Link className={styles.demoLink} to="/history/demo">
          演示记录 <ArrowRight size={15} weight="bold" aria-hidden="true" />
        </Link>
      </div>

      {bridgeStatus === 'unavailable' ? (
        <StatePanel
          eyebrow="浏览器预览"
          title="等待连接 Android"
          detail="这里不会展示虚构成绩。请在流萤 App 内打开此页，查看保存在手机上的真实跑步记录。"
        />
      ) : bridgeStatus === 'connecting' ? (
        <LoadingSkeleton label="正在连接跑步记录" />
      ) : bridgeStatus === 'error' ? (
        <StatePanel
          eyebrow="连接失败"
          title="暂时无法读取记录"
          detail="请返回跑步页后重试；你的本地记录不会丢失。"
          alert
        />
      ) : history.phase === 'loading' ? (
        <LoadingSkeleton label="正在读取本地记录" />
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
      <Link
        className={styles.latestRun}
        to={`/history/${encodeURIComponent(latestRun.id)}`}
        aria-label={`查看 ${formatRunTime(latestRun.startedAt)} 跑步总结，${formatDistanceKm(latestRun.distanceMeters)} 公里`}
      >
        <span className={styles.latestRunHeading}>
          <span className={styles.latestRunLabel}>最近一次</span>
          <time>{formatRunTime(latestRun.startedAt)}</time>
        </span>
        <span className={styles.distanceLockup}>
          <strong>{formatDistanceKm(latestRun.distanceMeters)}</strong>
          <span>公里</span>
        </span>
        <span className={styles.latestStats}>
          <span>
            <small>用时</small>
            <strong>{formatDuration(latestRun.durationSeconds)}</strong>
          </span>
          <span>
            <small>平均配速</small>
            <strong>
              {formatPace(latestRun.averagePaceSecPerKm)}
              <small> /km</small>
            </strong>
          </span>
          <span>
            <small>平均心率</small>
            <strong>
              {formatOptionalInteger(latestRun.averageHeartRate)}
              <small> bpm</small>
            </strong>
          </span>
        </span>
        <span className={styles.latestRunAction}>
          查看跑步总结 <CaretRight size={17} weight="bold" aria-hidden="true" />
        </span>
      </Link>

      <section className={`past-runs ${styles.recordCount}`} aria-labelledby="past-runs-title">
        <div className={styles.sectionHeading}>
          <h2 id="past-runs-title">更早记录</h2>
          <span>{totalItems} 次跑步</span>
        </div>
        {olderRuns.length > 0 && (
          <ul className={styles.runList}>
            {olderRuns.map((run) => (
              <li key={run.id}>
                <Link
                  className={styles.runRow}
                  to={`/history/${encodeURIComponent(run.id)}`}
                  aria-label={`查看 ${formatRunTime(run.startedAt)} 的跑步总结`}
                >
                  <span className={styles.runDate}>
                    <strong>{formatDay(run.startedAt)}</strong>
                    <small>{formatShortMonth(run.startedAt)}</small>
                  </span>
                  <span className={styles.runSummary}>
                    <strong>{formatListDistance(run.distanceMeters)}</strong>
                    <small>
                      {formatDuration(run.durationSeconds)} · {formatRunTime(run.startedAt, false)}
                    </small>
                  </span>
                  <span className={styles.runPace}>
                    {formatPace(run.averagePaceSecPerKm)}
                    <small> /km</small>
                    <CaretRight size={16} weight="bold" aria-hidden="true" />
                  </span>
                </Link>
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

function formatRunTime(value: string, includeTime = true) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'long',
    day: 'numeric',
    ...(includeTime ? { hour: '2-digit', minute: '2-digit', hour12: false as const } : {}),
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
