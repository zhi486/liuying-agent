import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Link } from 'react-router-dom';

import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import type { AppSnapshot, ResultPayload } from '../../bridge/contract';
import { formatPace } from './formatters';
import styles from './RunningPage.module.css';

type RunPhase = 'idle' | 'running' | 'paused' | 'finished';

const permissionSettingsGuide =
  '请到系统设置 > 应用 > 流萤 > 权限，开启位置信息；若使用 Keep B5，再允许附近的设备。';

export function RunningPage() {
  const [previewPhase, setPreviewPhase] = useState<RunPhase>('idle');
  const [confirmingStop, setConfirmingStop] = useState(false);
  const [commandError, setCommandError] = useState<string | null>(null);
  const [connectionPending, setConnectionPending] = useState(false);
  const stopTriggerRef = useRef<HTMLButtonElement>(null);
  const continueRunRef = useRef<HTMLButtonElement>(null);
  const bridgeStatus = useBridgeStore((state) => state.status);
  const bridgeSnapshot = useBridgeStore((state) => state.snapshot);
  const nativeSnapshot = bridgeStatus === 'ready' ? bridgeSnapshot : null;
  const isPreview = bridgeStatus === 'unavailable';
  const displayPhase = nativeSnapshot ? toRunPhase(nativeSnapshot.session.state) : previewPhase;

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

    return (
      <section className={styles.page}>
        <h1 className="sr-only">跑步</h1>
        {isPreview && <PreviewBadge />}
        <div className={styles.finishedIntro}>
          <span className={styles.firefly} aria-hidden="true" />
          <p className="eyebrow">本次跑步已结束</p>
          <h2>{completedRun ? '完成摘要' : '摘要暂不可用'}</h2>
          <p>
            {completedRun
              ? `结束时间 ${formatCompletedAt(completedRun.endedAt)}`
              : isPreview
                ? '交互预览不会生成或保存跑步数据。'
                : '正在等待原生跑步服务返回完成数据。'}
          </p>
        </div>

        <div className={styles.summary} aria-label="本次跑步摘要">
          <div className={styles.summaryPrimary}>
            <strong>{formatDistance(completedRun?.distanceMeters)}</strong>
            <span>{completedRun ? '公里' : ''}</span>
          </div>
          <dl>
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
        </div>

        <div
          className={styles.syncStatus}
          role={nativeSnapshot?.persistenceState === 'failed' ? 'alert' : 'status'}
        >
          <div>
            <span>本地记录</span>
            <strong>{persistenceText(nativeSnapshot?.persistenceState, isPreview)}</strong>
          </div>
          <div>
            <span>Health Connect</span>
            <strong>{healthSyncText(nativeSnapshot?.healthSync, isPreview)}</strong>
          </div>
        </div>

        <SafetyNotice safetyState={nativeSnapshot?.safetyState} preview={isPreview} />

        <aside className={styles.coachNote}>
          <span className={styles.coachLabel}>流萤提醒</span>
          <p>{nativeSnapshot?.latestCoachMessage?.text ?? '暂无提醒'}</p>
        </aside>

        {commandError && (
          <p className={styles.commandError} role="alert">
            {commandError}
          </p>
        )}

        <div className={styles.finishedActions}>
          {historySaved && nativeSnapshot?.session.id && (
            <Link
              className="secondary-button"
              to={`/history/${encodeURIComponent(nativeSnapshot.session.id)}`}
            >
              查看本次总结
            </Link>
          )}
          {historySaved ? (
            <Link className="primary-button" to="/history">
              查看记录
            </Link>
          ) : (
            <button className="primary-button" type="button" disabled>
              {historyActionText(nativeSnapshot?.persistenceState, isPreview)}
            </button>
          )}
          <button
            className="quiet-button"
            type="button"
            onClick={() => (isPreview ? setPreviewPhase('idle') : startRun())}
          >
            再跑一次
          </button>
        </div>
      </section>
    );
  }

  if (displayPhase === 'idle') {
    const permissionState = nativeSnapshot?.permissions.runningSensors;
    const permissionGranted = permissionState === 'granted';
    const serviceUnavailable = !isPreview && !nativeSnapshot;
    const heading = isPreview
      ? '跑步页交互预览'
      : serviceUnavailable
        ? bridgeStatus === 'error'
          ? '跑步服务连接失败'
          : '正在连接跑步服务'
        : permissionGranted
          ? '准备开始本次跑步'
          : '开跑前需要系统授权';

    return (
      <section className={styles.page}>
        <h1 className="sr-only">跑步</h1>
        {isPreview && <PreviewBadge />}
        <div className={styles.readinessHeader}>
          <div>
            <p className="eyebrow">
              {isPreview ? '未连接 Android 设备' : permissionGranted ? '跑前检查' : '权限检查'}
            </p>
            <h2>{heading}</h2>
          </div>
          <div
            className={`${styles.devicePulse} ${
              permissionGranted && !isPreview ? '' : styles.devicePulseMuted
            }`}
            aria-label={readinessBadgeText(nativeSnapshot, isPreview)}
          >
            <span aria-hidden="true" />
            {readinessBadgeText(nativeSnapshot, isPreview)}
          </div>
        </div>

        <div className={styles.goalPanel}>
          <div className={styles.goalHeading}>
            <span>默认目标</span>
            {nativeSnapshot && <Link to="/settings">调整目标</Link>}
          </div>
          <dl className={styles.targetGrid}>
            <div>
              <dt>目标配速</dt>
              <dd>{formatPace(nativeSnapshot?.settings.targetPaceSecPerKm)}</dd>
            </div>
            <div>
              <dt>目标距离</dt>
              <dd>{formatTargetDistanceKm(nativeSnapshot?.settings.targetDistanceKm)}</dd>
            </div>
          </dl>
          {!nativeSnapshot?.settings.targetPaceSecPerKm &&
            !nativeSnapshot?.settings.targetDistanceKm && (
              <p className={styles.goalEmpty}>
                {isPreview ? '交互预览不提供设备目标。' : '未设置距离和配速目标。'}
              </p>
            )}
        </div>

        <ul className={styles.readinessList} aria-label="跑前检查">
          <li>
            <span>定位主源</span>
            <strong>
              {permissionGranted
                ? connectionText(nativeSnapshot?.connection, false)
                : isPreview
                  ? '未连接设备'
                  : '等待位置信息授权'}
            </strong>
          </li>
          <li>
            <span>Keep B5</span>
            <strong>{wearableText(nativeSnapshot?.wearable, isPreview)}</strong>
          </li>
        </ul>

        {!isPreview && nativeSnapshot?.wearable.supported && (
          <div className={styles.wearableControls}>
            <button
              className="secondary-button"
              type="button"
              disabled={
                connectionPending ||
                nativeSnapshot.wearable.state === 'connecting' ||
                nativeSnapshot.wearable.state === 'connected'
              }
              onClick={() => void connectWearable()}
            >
              {nativeSnapshot.wearable.state === 'connected'
                ? '手环已连接'
                : connectionPending || nativeSnapshot.wearable.state === 'connecting'
                  ? '正在连接手环…'
                  : nativeSnapshot.wearable.state === 'error'
                    ? '重试连接手环'
                    : '连接手环'}
            </button>
            <p>只连接蓝牙，不开始运动。心率将在开跑后获取；请先断开 Keep 的连接。</p>
          </div>
        )}

        <SafetyNotice safetyState={nativeSnapshot?.safetyState} preview={isPreview} />

        <button
          className="primary-button"
          type="button"
          disabled={serviceUnavailable}
          onClick={startRun}
        >
          {isPreview
            ? '进入交互预览'
            : permissionGranted
              ? '开始跑步'
              : serviceUnavailable
                ? '等待跑步服务'
                : '请求系统授权'}
        </button>
        <p className={styles.startHint} role={commandError ? 'alert' : undefined}>
          {commandError ??
            (isPreview
              ? '交互预览不显示、不保存设备数据。'
              : permissionGranted
                ? '开跑后由手机 GPS 记录距离和路线。'
                : '手机 GPS 需要位置信息；Keep B5 需要附近的设备权限。')}
        </p>
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
  const liveMetrics = buildLiveMetrics(nativeSnapshot);

  return (
    <section className={`${styles.page} ${styles.activePage}`}>
      <div inert={confirmingStop ? true : undefined} aria-hidden={confirmingStop || undefined}>
        <h1 className="sr-only">跑步</h1>
        {isPreview && <PreviewBadge />}
        <div className={styles.liveHeadline} role="status">
          <span
            className={paused || isPreview ? styles.pauseIndicator : styles.liveIndicator}
            aria-hidden="true"
          />
          <strong>{paused ? '已暂停' : isPreview ? '跑步交互预览' : '跑步中'}</strong>
        </div>

        <div className={styles.sourceStatusList} aria-label="数据源状态">
          <div>
            <span>定位主源</span>
            <strong>
              {!isPreview && nativeSnapshot?.dataQuality.gps === 'waiting'
                ? '等待定位'
                : !isPreview && nativeSnapshot?.dataQuality.gps === 'stale'
                  ? '定位暂未更新'
                  : connectionText(nativeSnapshot?.connection, isPreview)}
            </strong>
          </div>
          <div>
            <span>Keep B5</span>
            <strong>{wearableText(nativeSnapshot?.wearable, isPreview)}</strong>
          </div>
          {!isPreview && nativeSnapshot?.wearable.supported && (
            <div>
              <span>心率数据</span>
              <strong>
                {nativeSnapshot.dataQuality.heartRate === 'waiting'
                  ? '等待心率'
                  : nativeSnapshot.dataQuality.heartRate === 'stale'
                    ? '心率暂未更新'
                    : nativeSnapshot.dataQuality.heartRate === 'fresh'
                      ? '心率正常更新'
                      : '心率不可用'}
              </strong>
            </div>
          )}
        </div>

        <div className={styles.activePlan} aria-label="本次冻结目标">
          <span>本次目标</span>
          <strong>配速 {formatPace(nativeSnapshot?.session.targetPaceSecPerKm)}</strong>
          <small>
            距离 {formatTargetDistanceMeters(nativeSnapshot?.session.targetDistanceMeters)}
          </small>
        </div>

        <div className={styles.paceHero}>
          <span>当前配速</span>
          <strong>{formatPace(nativeSnapshot?.metrics.paceSecPerKm)}</strong>
          <small>分钟 / 公里</small>
        </div>

        <div className={styles.metricsGrid}>
          {liveMetrics.map((metric) => (
            <div key={metric.label}>
              <span>{metric.label}</span>
              <strong>{metric.value}</strong>
              {metric.unit && <small>{metric.unit}</small>}
            </div>
          ))}
        </div>

        {intensityPercent != null && (
          <div className={styles.zoneStrip}>
            <div>
              <span>当前强度</span>
              <strong>{zoneLabel(nativeSnapshot?.metrics.hrZone)}</strong>
              <small>{intensityPercent}% 最大心率</small>
            </div>
            <div
              className={styles.zoneTrack}
              role="meter"
              aria-label={`当前强度 ${intensityPercent}%`}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={intensityPercent}
            >
              <span style={{ width: `${intensityPercent}%` }} />
            </div>
          </div>
        )}

        <SafetyNotice safetyState={nativeSnapshot?.safetyState} preview={isPreview} />

        <aside className={styles.liveCoach}>
          <span className={styles.coachLabel}>流萤提醒</span>
          <p>{nativeSnapshot?.latestCoachMessage?.text ?? '暂无提醒'}</p>
        </aside>

        {commandError && (
          <p className={styles.commandError} role="alert">
            {commandError}
          </p>
        )}

        <div className={styles.runControls}>
          <button
            ref={stopTriggerRef}
            className="secondary-button"
            type="button"
            onClick={() => setConfirmingStop(true)}
          >
            结束
          </button>
          <button
            className="primary-button"
            type="button"
            onClick={() =>
              isPreview
                ? setPreviewPhase(paused ? 'running' : 'paused')
                : runNativeCommand(paused ? 'run.resume' : 'run.pause')
            }
          >
            {paused ? '继续' : '暂停'}
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

function PreviewBadge() {
  return <p className={styles.previewBadge}>交互预览，不代表设备数据</p>;
}

function SafetyNotice({
  safetyState,
  preview,
}: {
  safetyState: AppSnapshot['safetyState'] | undefined;
  preview: boolean;
}) {
  const level = safetyState?.level ?? 'normal';
  const message = preview
    ? '交互预览不接收设备安全状态。'
    : (safetyState?.message ?? '当前没有安全提醒。');
  return (
    <div
      className={`${styles.safetyNotice} ${styles[`safety_${level}`]}`}
      role={level === 'normal' ? 'status' : 'alert'}
    >
      <span>安全状态</span>
      <strong>{message}</strong>
    </div>
  );
}

function buildLiveMetrics(snapshot: AppSnapshot | null) {
  const heartRate = snapshot?.dataQuality.heartRate === 'fresh' ? snapshot.metrics.heartRate : null;
  return [
    {
      label: '心率',
      value: heartRate?.toString() ?? '--',
      unit: heartRate == null ? '' : 'bpm',
    },
    {
      label: '步频',
      value: snapshot?.metrics.cadence?.toString() ?? '--',
      unit: snapshot?.metrics.cadence == null ? '' : 'spm',
    },
    {
      label: '距离',
      value: snapshot ? (snapshot.metrics.distanceMeters / 1000).toFixed(2) : '--',
      unit: snapshot ? 'km' : '',
    },
    {
      label: '用时',
      value: formatElapsed(snapshot?.metrics.elapsedSeconds),
      unit: '',
    },
  ];
}

function readinessBadgeText(snapshot: AppSnapshot | null, preview: boolean): string {
  if (preview) return '示例';
  if (!snapshot) return '连接中';
  if (snapshot.permissions.runningSensors !== 'granted') return '待授权';
  return '可开跑';
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
