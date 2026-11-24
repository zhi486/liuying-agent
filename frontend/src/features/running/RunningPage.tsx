import {
  ArrowRight,
  Bluetooth,
  Check,
  Heartbeat,
  MapPin,
  Pause,
  Play,
  Shield,
  Stop,
  Warning,
} from '@phosphor-icons/react';
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import type { AppSnapshot, ResultPayload } from '../../bridge/contract';
import { formatPace } from './formatters';
import styles from './RunningPage.module.css';
import { BackLink } from '../../design-system/BackControl';

type RunPhase = 'idle' | 'running' | 'paused' | 'finished';
type ReadinessTone = 'ready' | 'optional' | 'warning' | 'muted';

const permissionSettingsGuide =
  '请到系统设置 > 应用 > 流萤 > 权限，开启位置信息；若使用 Keep B5，再允许附近的设备。';

export function RunningPage() {
  const navigate = useNavigate();
  const [dismissedRun, setDismissedRun] = useState(() => {
    try {
      return localStorage.getItem('liuying.completedRun.dismissed');
    } catch {
      return null;
    }
  });
  const [previewPhase, setPreviewPhase] = useState<RunPhase>('idle');
  const [confirmingStop, setConfirmingStop] = useState(false);
  const [commandError, setCommandError] = useState<string | null>(null);
  const [connectionPending, setConnectionPending] = useState(false);
  const [holdingStop, setHoldingStop] = useState(false);
  const stopHoldTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelStopHold = () => {
    if (stopHoldTimer.current !== null) clearTimeout(stopHoldTimer.current);
    stopHoldTimer.current = null;
    setHoldingStop(false);
  };
  const stopTriggerRef = useRef<HTMLButtonElement>(null);
  const continueRunRef = useRef<HTMLButtonElement>(null);
  const bridgeStatus = useBridgeStore((state) => state.status);
  const bridgeSnapshot = useBridgeStore((state) => state.snapshot);
  const nativeSnapshot = bridgeStatus === 'ready' ? bridgeSnapshot : null;
  const isPreview = bridgeStatus === 'unavailable';
  const completedRunKey = nativeSnapshot
    ? JSON.stringify([
        nativeSnapshot.session.id,
        nativeSnapshot.session.startedAt,
        nativeSnapshot.completedRun?.endedAt,
      ])
    : null;
  const nativePhase = nativeSnapshot ? toRunPhase(nativeSnapshot.session.state) : previewPhase;
  const displayPhase =
    nativePhase === 'finished' && completedRunKey !== null && dismissedRun === completedRunKey
      ? 'idle'
      : nativePhase;
  const finishReview = () => {
    if (completedRunKey !== null) {
      try {
        localStorage.setItem('liuying.completedRun.dismissed', completedRunKey);
      } catch {
        setCommandError('无法保存完成状态，请重试');
        return;
      }
      setDismissedRun(completedRunKey);
    }
    setPreviewPhase('idle');
    navigate('/agent', { replace: true });
  };
  useEffect(() => {
    setHoldingStop(false);
    return () => {
      if (stopHoldTimer.current !== null) clearTimeout(stopHoldTimer.current);
      stopHoldTimer.current = null;
    };
  }, [displayPhase]);

  useEffect(() => {
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
  }, [displayPhase]);

  useEffect(() => {
    if (!confirmingStop) return;
    const trigger = stopTriggerRef.current;
    continueRunRef.current?.focus();

    return () => {
      if (trigger?.isConnected) trigger.focus();
    };
  }, [confirmingStop]);

  useEffect(() => {
    if (displayPhase !== 'running' && displayPhase !== 'paused' && confirmingStop) {
      setConfirmingStop(false);
    }
  }, [confirmingStop, displayPhase]);

  const closeStopDialog = () => setConfirmingStop(false);

  const handleStopDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeStopDialog();
      return;
    }
    if (event.key !== 'Tab') return;

    const focusable = Array.from(
      event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not([disabled])'),
    );
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) return;

    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    } else if (!focusable.includes(document.activeElement as HTMLButtonElement)) {
      event.preventDefault();
      first.focus();
    }
  };

  const acceptCommandResult = (result: ResultPayload) => {
    if (result.status !== 'rejected') return;
    setCommandError(
      result.error.code === 'PERMISSION_REQUIRED' ? permissionSettingsGuide : result.error.message,
    );
  };

  const runNativeCommand = (command: 'run.start' | 'run.pause' | 'run.resume' | 'run.stop') => {
    setCommandError(null);
    void sendNativeCommand(command, {})
      .then(acceptCommandResult)
      .catch(() => setCommandError('原生跑步服务暂时不可用'));
  };

  const requestRunningPermission = () => {
    setCommandError('正在请求系统授权。若未出现系统弹窗，请按下方路径手动开启。');
    void sendNativeCommand('permission.request', { kind: 'runningSensors' })
      .then((result) => {
        setCommandError(
          result.status === 'accepted'
            ? '授权请求已发送，请完成系统弹窗；若未出现弹窗，请到系统设置中手动开启。'
            : permissionSettingsGuide,
        );
      })
      .catch(() => setCommandError(permissionSettingsGuide));
  };

  const startRun = () => {
    if (isPreview) {
      setPreviewPhase('running');
      return;
    }
    if (!nativeSnapshot) {
      setCommandError(
        bridgeStatus === 'error' ? '原生跑步服务连接失败，请重新打开应用' : '正在连接原生跑步服务',
      );
      return;
    }
    if (nativeSnapshot.permissions.runningSensors !== 'granted') {
      requestRunningPermission();
      return;
    }
    runNativeCommand('run.start');
  };

  const connectWearable = async () => {
    if (!nativeSnapshot || !nativeSnapshot.wearable.supported || connectionPending) return;
    if (nativeSnapshot.permissions.bluetooth !== 'granted') {
      requestRunningPermission();
      return;
    }
    setCommandError(null);
    setConnectionPending(true);
    try {
      acceptCommandResult(await sendNativeCommand('device.connect', {}));
    } catch {
      setCommandError('连接手环请求失败，请重试');
    } finally {
      setConnectionPending(false);
    }
  };

  if (displayPhase === 'finished') {
    const completedRun = nativeSnapshot?.completedRun ?? null;
    const historySaved = nativeSnapshot?.persistenceState === 'saved';
    const localRecordText = persistenceText(nativeSnapshot?.persistenceState, isPreview);
    const healthStatusText = healthSyncText(nativeSnapshot?.healthSync, isPreview);
    const persistenceTone = isPreview
      ? 'muted'
      : nativeSnapshot?.persistenceState === 'saved'
        ? 'ready'
        : nativeSnapshot?.persistenceState === 'failed'
          ? 'warning'
          : 'loading';
    const healthTone = isPreview
      ? 'muted'
      : nativeSnapshot?.healthSync.state === 'synced'
        ? 'ready'
        : nativeSnapshot?.healthSync.state === 'syncing'
          ? 'loading'
          : nativeSnapshot?.healthSync.state === 'idle'
            ? 'muted'
            : 'warning';
    const safetyLevel = nativeSnapshot?.safetyState.level;
    const showSafetyAlert = !isPreview && safetyLevel != null && safetyLevel !== 'normal';

    return (
      <section className={`${styles.page} ${styles.finishedScorecardPage}`}>
        <h1 className="sr-only">跑步</h1>
        <div className={styles.finishedScorecard} data-testid="running-finished-scorecard">
          <RunBackLink />
          <header className={styles.finishedScorecardHeader}>
            <span>
              <Check size={15} weight="bold" aria-hidden="true" />
              跑步完成
            </span>
            <time>{completedRun ? formatCompletedAt(completedRun.endedAt) : '--'}</time>
          </header>

          {isPreview && <PreviewBadge />}

          <section className={styles.finishedDistanceHero} aria-label="本次跑步摘要">
            <span>{completedRun ? '本次距离' : '摘要暂不可用'}</span>
            <div>
              <strong>{formatDistance(completedRun?.distanceMeters)}</strong>
              {completedRun && <small>公里</small>}
            </div>
            <p>
              {completedRun
                ? `结束于 ${formatCompletedAt(completedRun.endedAt)}`
                : isPreview
                  ? '交互预览不会生成或保存跑步数据。'
                  : '正在等待原生跑步服务返回完成数据。'}
            </p>
          </section>

          <dl className={styles.finishedScoreGrid}>
            <div>
              <dt>用时</dt>
              <dd>{formatElapsed(completedRun?.durationSeconds)}</dd>
            </div>
            <div>
              <dt>平均配速</dt>
              <dd>{formatPace(completedRun?.averagePaceSecPerKm)}</dd>
            </div>
            <div>
              <dt>平均心率</dt>
              <dd>{formatMetric(completedRun?.averageHeartRate, 'bpm')}</dd>
            </div>
            <div>
              <dt>平均步频</dt>
              <dd>{formatMetric(completedRun?.averageCadence, 'spm')}</dd>
            </div>
          </dl>

          <div
            className={styles.finishedSyncRail}
            role={nativeSnapshot?.persistenceState === 'failed' ? 'alert' : 'status'}
          >
            <span data-tone={persistenceTone}>
              <i aria-hidden="true" />
              <span>本地记录</span>
              <strong>{localRecordText}</strong>
            </span>
            <span data-tone={healthTone}>
              <i aria-hidden="true" />
              <span>Health Connect</span>
              <strong>{healthStatusText}</strong>
            </span>
          </div>

          {showSafetyAlert && (
            <div className={styles.finishedSafetyAlert} role="alert">
              <Warning size={17} weight="fill" aria-hidden="true" />
              <div>
                <strong>安全提醒</strong>
                <p>{nativeSnapshot?.safetyState.message ?? '当前安全状态需要关注。'}</p>
              </div>
            </div>
          )}

          <aside className={styles.finishedCoachStrip}>
            <Heartbeat size={17} weight="fill" aria-hidden="true" />
            <div>
              <strong>本次提醒</strong>
              <p>{nativeSnapshot?.latestCoachMessage?.text ?? '暂无提醒'}</p>
            </div>
          </aside>

          <p
            className={styles.finishedNotice}
            data-tone={commandError ? 'error' : 'default'}
            role={commandError ? 'alert' : undefined}
          >
            {commandError ??
              (isPreview
                ? '交互预览不显示、不保存设备数据。'
                : historySaved
                  ? '本地记录已保存；Health Connect 同步结果单独显示。'
                  : '记录保存完成后才会开放历史与复盘入口。')}
          </p>

          <div className={styles.finishedScorecardActions}>
            <button type="button" className={styles.completeButton} onClick={finishReview}>
              <Check size={18} weight="bold" aria-hidden="true" />
              完成
            </button>
            {historySaved && nativeSnapshot?.session.id ? (
              <>
                <Link
                  className={styles.finishedReviewLink}
                  to="/agent"
                  state={{ runId: nativeSnapshot.session.id }}
                >
                  让流萤复盘本次跑步
                  <ArrowRight size={16} weight="bold" aria-hidden="true" />
                </Link>
                <div>
                  <Link to={`/history/${encodeURIComponent(nativeSnapshot.session.id)}`}>
                    查看本次总结
                  </Link>
                  <Link to="/history">查看记录</Link>
                  <button
                    type="button"
                    onClick={() => (isPreview ? setPreviewPhase('idle') : startRun())}
                  >
                    再跑一次
                  </button>
                </div>
              </>
            ) : (
              <>
                <button type="button" className={styles.finishedReviewLink} disabled>
                  {historyActionText(nativeSnapshot?.persistenceState, isPreview)}
                </button>
                <div className={styles.finishedSingleAction}>
                  <button
                    type="button"
                    onClick={() => (isPreview ? setPreviewPhase('idle') : startRun())}
                  >
                    再跑一次
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      </section>
    );
  }

  if (displayPhase === 'idle') {
    const permissionState = nativeSnapshot?.permissions.runningSensors;
    const permissionGranted = permissionState === 'granted';
    const serviceUnavailable = !isPreview && !nativeSnapshot;
    const wearableState = nativeSnapshot?.wearable.state;
    const wearableConnected = wearableState === 'connected';
    const wearableConnecting = connectionPending || wearableState === 'connecting';
    const showWearableControl = Boolean(nativeSnapshot?.wearable.supported);
    const heading = isPreview
      ? '跑步页交互预览'
      : serviceUnavailable
        ? bridgeStatus === 'error'
          ? '跑步服务连接失败'
          : '正在连接跑步服务'
        : permissionGranted
          ? '准备开始本次跑步'
          : '开跑前需要系统授权';
    const targetDistance = formatTargetDistanceKm(nativeSnapshot?.settings.targetDistanceKm);
    const targetPace = formatPace(nativeSnapshot?.settings.targetPaceSecPerKm);
    const hasTarget =
      Boolean(nativeSnapshot?.settings.targetDistanceKm) ||
      Boolean(nativeSnapshot?.settings.targetPaceSecPerKm);
    const locationTone: ReadinessTone = isPreview
      ? 'muted'
      : permissionGranted
        ? 'ready'
        : 'warning';
    const wearableTone: ReadinessTone = wearableConnected
      ? 'ready'
      : wearableState === 'error'
        ? 'warning'
        : 'optional';
    const safetyLevel = nativeSnapshot?.safetyState.level;
    const hasNativeSafety = safetyLevel !== undefined;
    const safetyTone: ReadinessTone = hasNativeSafety
      ? safetyLevel === 'normal'
        ? 'ready'
        : 'warning'
      : 'muted';
    const safetyMessage = isPreview
      ? '交互预览不接收设备安全状态。'
      : nativeSnapshot
        ? (nativeSnapshot.safetyState.message ?? '当前没有安全提醒。')
        : bridgeStatus === 'error'
          ? '安全状态暂时无法读取。'
          : '跑步服务连接后读取安全状态。';
    const safetyBadgeText = isPreview
      ? '示例'
      : hasNativeSafety
        ? safetyLevel === 'normal'
          ? '正常'
          : '需关注'
        : '待读取';
    const connectionActionLabel = wearableConnected
      ? '手环已连接'
      : wearableConnecting
        ? '正在连接手环…'
        : wearableState === 'error'
          ? '重试连接手环'
          : '连接手环';
    const connectionActionText = wearableConnected
      ? '已连接'
      : wearableConnecting
        ? '连接中'
        : wearableState === 'error'
          ? '重试连接'
          : '连接手环';
    const startActionLabel = isPreview
      ? '进入交互预览'
      : permissionGranted
        ? '开始跑步'
        : serviceUnavailable
          ? '等待跑步服务'
          : '请求系统授权';
    const startActionText = isPreview
      ? '进入预览'
      : permissionGranted
        ? '开始跑步'
        : serviceUnavailable
          ? '等待服务'
          : '请求授权';
    const startActionUnavailable = serviceUnavailable;
    const badgeText = isPreview
      ? '示例'
      : bridgeStatus === 'error'
        ? '服务异常'
        : serviceUnavailable
          ? '连接中'
          : permissionGranted
            ? '可开跑'
            : '待授权';
    const badgeTone: ReadinessTone =
      isPreview || (serviceUnavailable && bridgeStatus !== 'error')
        ? 'muted'
        : bridgeStatus === 'error' || !permissionGranted
          ? 'warning'
          : 'ready';

    return (
      <section className={`${styles.page} ${styles.readinessPage}`}>
        <h1 className="sr-only">跑步</h1>
        <div
          className={styles.readinessConsole}
          data-testid="running-readiness-console"
          aria-label="跑前状态仪表"
        >
          {isPreview && <PreviewBadge />}
          <RunBackLink />
          <header className={styles.readinessHeader}>
            <h2>{heading}</h2>
            <span
              className={styles.readinessStatusBadge}
              data-tone={badgeTone}
              aria-label={badgeText}
            >
              <span aria-hidden="true" />
              {badgeText}
            </span>
          </header>

          <div className={styles.readinessGoalContext}>
            <span>{hasTarget ? '今日目标' : '今日目标未设置'}</span>
            {nativeSnapshot && (
              <Link to="/settings" aria-label="调整跑步目标">
                调整目标
              </Link>
            )}
          </div>

          <div className={styles.readinessGauge} aria-label="跑前里程 0.00 公里">
            <strong>0.00</strong>
            <span>km</span>
          </div>

          <dl className={styles.readinessFactGrid}>
            <div>
              <dt>目标配速</dt>
              <dd>{targetPace}</dd>
            </div>
            <div>
              <dt>目标距离</dt>
              <dd>{targetDistance}</dd>
            </div>
          </dl>
          {!hasTarget && (
            <p className={styles.readinessEmptyTarget}>
              {isPreview ? '交互预览不提供设备目标。' : '未设置距离和配速目标。'}
            </p>
          )}

          <ul className={styles.readinessTrack} aria-label="跑前检查">
            <li className={styles.readinessTrackItem}>
              <span className={styles.readinessTrackIcon} data-tone={locationTone}>
                {permissionGranted ? (
                  <Check size={16} weight="bold" aria-hidden="true" />
                ) : isPreview ? (
                  <MapPin size={16} aria-hidden="true" />
                ) : (
                  <Warning size={16} weight="fill" aria-hidden="true" />
                )}
              </span>
              <span className={styles.readinessTrackCopy}>
                <strong>手机定位</strong>
                <small>
                  {permissionGranted
                    ? connectionText(nativeSnapshot?.connection, false)
                    : isPreview
                      ? '交互预览不请求位置权限'
                      : serviceUnavailable
                        ? '跑步服务连接后读取位置状态'
                        : '等待位置信息授权'}
                </small>
              </span>
              <em>{permissionGranted ? '就绪' : isPreview ? '示例' : '待授权'}</em>
            </li>
            <li className={styles.readinessTrackItem}>
              <span className={styles.readinessTrackIcon} data-tone={wearableTone}>
                {wearableConnected ? (
                  <Check size={16} weight="bold" aria-hidden="true" />
                ) : (
                  <Bluetooth size={16} aria-hidden="true" />
                )}
              </span>
              <span className={styles.readinessTrackCopy}>
                <strong>{nativeSnapshot?.wearable.deviceName || 'Keep B5'}</strong>
                <small>{wearableText(nativeSnapshot?.wearable, isPreview)}</small>
              </span>
              <em>
                {wearableConnected ? '已连接' : wearableState === 'error' ? '可重试' : '可选'}
              </em>
            </li>
            <li className={styles.readinessTrackItem}>
              <span className={styles.readinessTrackIcon} data-tone={safetyTone}>
                {safetyTone === 'ready' ? (
                  <Check size={16} weight="bold" aria-hidden="true" />
                ) : safetyTone === 'warning' ? (
                  <Warning size={16} weight="fill" aria-hidden="true" />
                ) : (
                  <Shield size={16} aria-hidden="true" />
                )}
              </span>
              <span className={styles.readinessTrackCopy}>
                <strong>安全状态</strong>
                <small
                  role={
                    safetyTone === 'warning'
                      ? 'alert'
                      : safetyTone === 'ready'
                        ? 'status'
                        : undefined
                  }
                >
                  {safetyMessage}
                </small>
              </span>
              <em>{safetyBadgeText}</em>
            </li>
          </ul>

          <div className={styles.readinessActions} data-count={showWearableControl ? 2 : 1}>
            {showWearableControl && (
              <div className={styles.readinessAction}>
                <button
                  className={styles.readinessConnectionOrb}
                  type="button"
                  data-testid="running-connect-orb"
                  data-state={
                    wearableConnected ? 'connected' : wearableConnecting ? 'connecting' : 'idle'
                  }
                  aria-label={connectionActionLabel}
                  aria-busy={wearableConnecting}
                  disabled={wearableConnected || wearableConnecting}
                  onClick={() => void connectWearable()}
                >
                  <span className={styles.readinessOrbGlyph} aria-hidden="true">
                    {wearableConnected ? (
                      <Check size={28} weight="bold" />
                    ) : (
                      <Bluetooth size={28} />
                    )}
                  </span>
                </button>
                <span aria-hidden="true">{connectionActionText}</span>
              </div>
            )}
            <div className={styles.readinessAction}>
              <button
                className={styles.readinessStartOrb}
                type="button"
                data-testid="running-start-orb"
                aria-label={startActionLabel}
                disabled={startActionUnavailable}
                onClick={startRun}
              >
                <span className={styles.readinessOrbGlyph} aria-hidden="true">
                  {isPreview || permissionGranted ? (
                    <Play size={28} weight="fill" />
                  ) : (
                    <Warning size={28} weight="fill" />
                  )}
                </span>
              </button>
              <span aria-hidden="true">{startActionText}</span>
            </div>
          </div>

          <p
            className={styles.readinessHint}
            data-tone={commandError ? 'error' : 'default'}
            role={commandError ? 'alert' : undefined}
          >
            {commandError ??
              (isPreview
                ? '交互预览不显示、不保存设备数据。'
                : permissionGranted
                  ? '开跑后由手机 GPS 记录距离和路线。'
                  : '手机 GPS 需要位置信息；Keep B5 需要附近的设备权限。')}
          </p>
        </div>
      </section>
    );
  }

  const paused = displayPhase === 'paused';
  const heartRate =
    nativeSnapshot?.dataQuality.heartRate === 'fresh' ? nativeSnapshot.metrics.heartRate : null;
  const maxHeartRate = nativeSnapshot?.settings.maxHeartRate ?? null;
  const intensityPercent =
    heartRate != null && maxHeartRate != null && maxHeartRate > 0
      ? Math.min(100, Math.max(0, Math.round((heartRate / maxHeartRate) * 100)))
      : null;
  const currentPace = nativeSnapshot?.metrics.paceSecPerKm ?? null;
  const targetPaceSeconds = nativeSnapshot?.session.targetPaceSecPerKm ?? null;
  const targetPace = formatPace(targetPaceSeconds);
  const targetDistance = formatTargetDistanceMeters(nativeSnapshot?.session.targetDistanceMeters);
  const paceDeltaSeconds =
    currentPace != null && targetPaceSeconds != null ? targetPaceSeconds - currentPace : null;
  const paceRelation =
    paceDeltaSeconds == null
      ? null
      : Math.abs(paceDeltaSeconds) < 2
        ? '与目标配速一致'
        : paceDeltaSeconds > 0
          ? `比目标快 ${Math.round(paceDeltaSeconds)} 秒`
          : `比目标慢 ${Math.round(Math.abs(paceDeltaSeconds))} 秒`;
  const gpsSourceLabel = isPreview
    ? '定位示例'
    : nativeSnapshot?.dataQuality.gps === 'waiting'
      ? '定位等待'
      : nativeSnapshot?.dataQuality.gps === 'stale'
        ? '定位延迟'
        : 'GPS 正常';
  const gpsTone = !isPreview && nativeSnapshot?.dataQuality.gps !== 'fresh' ? 'warning' : 'ready';
  const wearableTone = nativeSnapshot?.wearable.state === 'connected' ? 'ready' : 'muted';
  const wearableSourceLabel = isPreview
    ? 'B5 示例'
    : !nativeSnapshot?.wearable.supported
      ? 'B5 未启用'
      : nativeSnapshot.wearable.state === 'connected'
        ? `B5 ${nativeSnapshot.wearable.batteryPercent == null ? '已连接' : `${nativeSnapshot.wearable.batteryPercent}%`}`
        : nativeSnapshot.wearable.state === 'connecting'
          ? 'B5 连接中'
          : nativeSnapshot.wearable.state === 'error'
            ? 'B5 异常'
            : 'B5 未连接';
  const heartSourceLabel = isPreview
    ? '心率示例'
    : !nativeSnapshot?.wearable.supported
      ? '心率未接入'
      : nativeSnapshot.dataQuality.heartRate === 'fresh' && heartRate != null
        ? `心率 ${heartRate}`
        : nativeSnapshot.dataQuality.heartRate === 'stale'
          ? '心率延迟'
          : nativeSnapshot.dataQuality.heartRate === 'waiting'
            ? '心率等待'
            : '心率不可用';
  const heartTone =
    !isPreview &&
    (nativeSnapshot?.dataQuality.heartRate === 'stale' ||
      nativeSnapshot?.dataQuality.heartRate === 'not_available')
      ? 'warning'
      : !isPreview && nativeSnapshot?.dataQuality.heartRate === 'fresh'
        ? 'ready'
        : 'muted';
  const safetyLevel = nativeSnapshot?.safetyState.level;
  const showSafetyAlert = !isPreview && safetyLevel != null && safetyLevel !== 'normal';
  const distance = nativeSnapshot
    ? (nativeSnapshot.metrics.distanceMeters / 1000).toFixed(2)
    : '--';
  const cadence = nativeSnapshot?.metrics.cadence?.toString() ?? '--';
  const elapsed = formatElapsed(nativeSnapshot?.metrics.elapsedSeconds);

  return (
    <section
      className={`${styles.page} ${styles.activeConsolePage} ${paused ? styles.pausedPage : ''}`}
    >
      <div
        className={styles.activeRunLayout}
        inert={confirmingStop ? true : undefined}
        aria-hidden={confirmingStop || undefined}
      >
        <h1 className="sr-only">跑步</h1>
        <div
          className={styles.activeRunConsole}
          data-testid="running-active-console"
          data-paused={paused || undefined}
        >
          <RunBackLink />
          <header className={styles.activeRunHeader}>
            <div className={styles.activeRunStatus} role="status">
              <span data-paused={paused || undefined} aria-hidden="true" />
              <strong>{paused ? '已暂停' : isPreview ? '跑步交互预览' : '跑步中'}</strong>
            </div>
            <time>{elapsed}</time>
          </header>

          <div className={styles.activeSourceRail} aria-label="数据源状态">
            <span
              data-tone={gpsTone}
              aria-label={
                isPreview ? '交互预览不读取定位' : connectionText(nativeSnapshot?.connection, false)
              }
            >
              <MapPin size={13} weight="fill" aria-hidden="true" />
              {gpsSourceLabel}
            </span>
            <span
              data-tone={wearableTone}
              aria-label={wearableText(nativeSnapshot?.wearable, isPreview)}
            >
              <Bluetooth size={13} weight="bold" aria-hidden="true" />
              {wearableSourceLabel}
            </span>
            <span data-tone={heartTone}>
              <Heartbeat size={13} weight="bold" aria-hidden="true" />
              {heartSourceLabel}
            </span>
          </div>

          {showSafetyAlert && (
            <div className={styles.activeSafetyAlert} role="alert">
              <Warning size={17} weight="fill" aria-hidden="true" />
              <div>
                <strong>安全提醒</strong>
                <p>{nativeSnapshot?.safetyState.message ?? '当前安全状态需要关注。'}</p>
              </div>
            </div>
          )}

          {paused && (
            <>
              <div className={styles.pausedDistance}>
                <strong>{distance}</strong>
                <span>公里</span>
              </div>
              <dl className={styles.pausedMetrics}>
                <div>
                  <dt>运动用时</dt>
                  <dd>{elapsed}</dd>
                </div>
                <div>
                  <dt>当前配速</dt>
                  <dd>{formatPace(currentPace)}</dd>
                </div>
                <div>
                  <dt>实时心率</dt>
                  <dd>
                    {heartRate ?? '--'} <small>次/分</small>
                  </dd>
                </div>
                <div>
                  <dt>步频</dt>
                  <dd>
                    {cadence} <small>步/分</small>
                  </dd>
                </div>
              </dl>
            </>
          )}
          {!paused && (
            <>
              <section
                hidden={paused}
                className={styles.activePaceHero}
                data-paused={paused || undefined}
              >
                <span>当前配速</span>
                <div>
                  <strong>{formatPace(currentPace)}</strong>
                  <small>/公里</small>
                </div>
                <p>
                  {isPreview ? (
                    '交互预览不读取实时配速。'
                  ) : targetPace !== '--' || targetDistance !== '--' ? (
                    <>
                      <span>配速 {targetPace}</span>
                      <span>距离 {targetDistance}</span>
                      {paceRelation && <em>{paceRelation}</em>}
                    </>
                  ) : (
                    '本次未设置目标。'
                  )}
                </p>
              </section>

              <dl hidden={paused} className={styles.activeRunMetrics}>
                <div>
                  <dt>距离</dt>
                  <dd>
                    {distance}
                    {nativeSnapshot && <small> km</small>}
                  </dd>
                </div>
                <div>
                  <dt>心率</dt>
                  <dd>
                    {heartRate ?? '--'}
                    {heartRate != null && <small> bpm</small>}
                  </dd>
                </div>
                <div>
                  <dt>步频</dt>
                  <dd>
                    {cadence}
                    {cadence !== '--' && <small> spm</small>}
                  </dd>
                </div>
              </dl>
            </>
          )}

          <div
            className={styles.activeZoneRail}
            role={intensityPercent != null ? 'meter' : undefined}
            aria-label={
              intensityPercent != null ? `当前强度 ${intensityPercent}%` : '当前强度等待心率'
            }
            aria-valuemin={intensityPercent != null ? 0 : undefined}
            aria-valuemax={intensityPercent != null ? 100 : undefined}
            aria-valuenow={intensityPercent ?? undefined}
          >
            <span style={{ width: `${intensityPercent ?? 0}%` }} aria-hidden="true" />
            <div>
              <span>当前强度</span>
              <strong>
                {intensityPercent == null ? '等待心率' : zoneLabel(nativeSnapshot?.metrics.hrZone)}
              </strong>
              <em>{intensityPercent == null ? '--' : `${intensityPercent}%`}</em>
            </div>
          </div>

          <aside className={styles.activeCoachLine}>
            <strong>流萤提醒</strong>
            <p>{nativeSnapshot?.latestCoachMessage?.text ?? '暂无提醒'}</p>
          </aside>

          <p
            className={styles.activeRunNotice}
            data-tone={commandError ? 'error' : 'default'}
            role={commandError ? 'alert' : undefined}
          >
            {commandError ??
              (isPreview
                ? '交互预览不显示、不保存设备数据。'
                : '实时数据仅用于本次跑步，结束并确认后才会保存。')}
          </p>
        </div>

        <div className={styles.activeRunControls}>
          <button
            type="button"
            onClick={() =>
              isPreview
                ? setPreviewPhase(paused ? 'running' : 'paused')
                : runNativeCommand(paused ? 'run.resume' : 'run.pause')
            }
          >
            {paused ? (
              <Play size={18} weight="fill" aria-hidden="true" />
            ) : (
              <Pause size={18} weight="fill" aria-hidden="true" />
            )}
            <span>{paused ? '继续' : '暂停'}</span>
          </button>
          <button
            ref={stopTriggerRef}
            type="button"
            data-holding={holdingStop}
            onPointerDown={(event) => {
              if (!paused || event.button !== 0) return;
              cancelStopHold();
              setHoldingStop(true);
              stopHoldTimer.current = setTimeout(() => {
                cancelStopHold();
                setConfirmingStop(true);
              }, 1200);
            }}
            onPointerUp={cancelStopHold}
            onPointerCancel={cancelStopHold}
            onPointerLeave={cancelStopHold}
            onBlur={cancelStopHold}
            onClick={(event) => {
              if (!paused || event.detail === 0) setConfirmingStop(true);
            }}
          >
            <Stop size={18} weight="fill" aria-hidden="true" />
            <span>结束</span>
          </button>
        </div>
      </div>

      {confirmingStop && (
        <div className={styles.dialogBackdrop}>
          <div
            className={styles.stopDialog}
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="stop-title"
            aria-describedby="stop-description"
            onKeyDown={handleStopDialogKeyDown}
          >
            <p className="eyebrow">确认操作</p>
            <h2 id="stop-title">结束本次跑步？</h2>
            <p id="stop-description">结束后会保存当前数据，Health Connect 同步结果会单独显示。</p>
            <div>
              <button
                ref={continueRunRef}
                className="quiet-button"
                type="button"
                onClick={closeStopDialog}
              >
                继续跑
              </button>
              <button
                className="danger-button"
                type="button"
                onClick={() => {
                  setConfirmingStop(false);
                  if (isPreview) setPreviewPhase('finished');
                  else runNativeCommand('run.stop');
                }}
              >
                确认结束
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function RunBackLink() {
  return <BackLink to="/agent" label="返回聊天" inverse />;
}

function PreviewBadge() {
  return <p className={styles.previewBadge}>交互预览，不代表设备数据</p>;
}

function connectionText(
  connection: AppSnapshot['connection'] | undefined,
  preview: boolean,
): string {
  if (preview || !connection) return '未连接设备';
  switch (connection.state) {
    case 'connecting':
      return '正在连接手机 GPS';
    case 'connected':
      return `${connection.deviceName ?? '手机 GPS'} 已连接`;
    case 'error':
      return '手机定位暂不可用';
    default:
      return '开跑时启用手机 GPS';
  }
}

function wearableText(wearable: AppSnapshot['wearable'] | undefined, preview: boolean): string {
  if (preview || !wearable) return '未连接设备';
  if (!wearable.supported) return '此设备未启用手环接入';
  switch (wearable.state) {
    case 'connecting':
      return '正在连接 Keep B5';
    case 'connected':
      return `${wearable.deviceName ?? 'Keep B5'} 已连接${
        wearable.batteryPercent == null ? '' : `，电量 ${wearable.batteryPercent}%`
      }`;
    case 'error':
      return 'Keep B5 暂不可用，GPS 仍可继续';
    default:
      return 'Keep B5 未连接，可仅用手机 GPS';
  }
}

function toRunPhase(state: string): RunPhase {
  if (state === 'running' || state === 'paused' || state === 'finished') return state;
  return 'idle';
}

function formatCompletedAt(timestamp: string): string {
  const parsed = new Date(timestamp);
  if (Number.isNaN(parsed.getTime())) return '--';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(parsed);
}

function formatDistance(distanceMeters: number | null | undefined): string {
  return distanceMeters == null ? '--' : (distanceMeters / 1000).toFixed(2);
}

function formatElapsed(totalSeconds: number | null | undefined): string {
  if (totalSeconds == null) return '--';
  const secondsTotal = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(secondsTotal / 3600);
  const minutes = Math.floor((secondsTotal % 3600) / 60);
  const seconds = secondsTotal % 60;
  return hours > 0
    ? [hours, minutes, seconds].map((value) => value.toString().padStart(2, '0')).join(':')
    : [minutes, seconds].map((value) => value.toString().padStart(2, '0')).join(':');
}

function formatTargetDistanceKm(distanceKm: number | null | undefined): string {
  if (distanceKm == null || distanceKm <= 0) return '--';
  return `${distanceKm.toFixed(distanceKm % 1 === 0 ? 0 : 1)} km`;
}

function formatTargetDistanceMeters(distanceMeters: number | null | undefined): string {
  if (distanceMeters == null || distanceMeters <= 0) return '--';
  return `${(distanceMeters / 1000).toFixed(distanceMeters % 1000 === 0 ? 0 : 1)} km`;
}

function formatMetric(value: number | null | undefined, unit: string): string {
  return value == null ? '--' : `${Math.round(value)} ${unit}`;
}

function zoneLabel(zone: AppSnapshot['metrics']['hrZone'] | undefined): string {
  switch (zone) {
    case 'z1':
      return '热身恢复区';
    case 'z2':
      return '有氧舒适区';
    case 'z3':
      return '有氧进阶区';
    case 'z4':
      return '乳酸阈值区';
    case 'z5':
      return '高强度警戒区';
    default:
      return '未分区';
  }
}

function persistenceText(
  state: AppSnapshot['persistenceState'] | undefined,
  preview: boolean,
): string {
  if (preview) return '交互预览不保存';
  return (
    {
      idle: '等待保存',
      saving: '正在保存',
      saved: '已保存',
      failed: '保存失败',
    }[state ?? 'idle'] ?? '等待保存'
  );
}

function historyActionText(
  state: AppSnapshot['persistenceState'] | undefined,
  preview: boolean,
): string {
  if (preview) return '交互预览不保存';
  if (state === 'saving') return '正在保存记录';
  if (state === 'failed') return '记录保存失败';
  return '等待记录保存';
}

function healthSyncText(
  healthSync: AppSnapshot['healthSync'] | undefined,
  preview: boolean,
): string {
  if (preview) return '交互预览不同步';
  if (!healthSync) return '等待同步状态';
  switch (healthSync.state) {
    case 'syncing':
      return '正在同步';
    case 'synced':
      if (healthSync.routeWritten === true) return '已同步，包含路线';
      if (healthSync.routeWritten === false) return '已同步，本次未写入路线';
      return '已同步';
    case 'permissionRequired':
      return '需要 Health Connect 授权';
    case 'providerUpdateRequired':
      return '需要更新 Health Connect';
    case 'unavailable':
      return '当前设备不可用';
    case 'failed':
      return '同步失败';
    default:
      return '尚未同步';
  }
}
