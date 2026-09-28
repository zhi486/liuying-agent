import {
  useEffect,
  useMemo,
  useState,
  type Dispatch,
  type FormEvent,
  type SetStateAction,
} from 'react';

import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import type { AppSnapshot, SettingsUpdateBody } from '../../bridge/contract';
import styles from './SettingsPage.module.css';
import { useNativeSettings } from './useNativeSettings';

type SettingsSnapshot = AppSnapshot['settings'];
type ThemeChoice = 'morning' | 'night';

interface RunPreferencesDraft {
  targetPace: string;
  targetDistance: string;
  maxHeartRate: string;
  hrAlertThresholdBpm: string;
  hrAlertRepeatIntervalSec: string;
  ttsEnabled: boolean;
  ttsMinIntervalSec: string;
  coachTone: SettingsSnapshot['coachTone'];
  motionPreference: SettingsSnapshot['motionPreference'];
}

export function SettingsPage() {
  const bridgeStatus = useBridgeStore((state) => state.status);
  const bridgeSnapshot = useBridgeStore((state) => state.snapshot);
  const { settings, isLoading, isSaving, error, query, update } = useNativeSettings(
    bridgeStatus,
    bridgeSnapshot?.settings ?? null,
  );
  const [draft, setDraft] = useState<RunPreferencesDraft | null>(null);
  const [isDirty, setIsDirty] = useState(false);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);
  const [configurationError, setConfigurationError] = useState<string | null>(null);
  const [isConfiguring, setIsConfiguring] = useState(false);

  const configureDeepseek = async () => {
    setConfigurationError(null);
    setIsConfiguring(true);
    try {
      const result = await sendNativeCommand('settings.configureDeepseek', {});
      if (result.status === 'rejected') setConfigurationError(result.error.message);
    } catch {
      setConfigurationError('无法打开私密配置，请稍后重试');
    } finally {
      setIsConfiguring(false);
    }
  };

  const updateCompanion = async (enabled: boolean) => {
    try {
      await update({ llmEnabled: enabled });
    } catch {
      // 设置错误由 useNativeSettings 统一显示。
    }
  };

  useEffect(() => {
    if (settings && !isDirty) setDraft(toDraft(settings));
  }, [isDirty, settings]);

  const patchDraft = (patch: Partial<RunPreferencesDraft>) => {
    setIsDirty(true);
    updateDraft(setDraft, patch);
  };

  const activeTheme = useMemo<ThemeChoice>(() => {
    if (settings?.themePreference === 'morning') return 'morning';
    if (settings?.themePreference === 'night') return 'night';
    return bridgeSnapshot?.theme ?? 'morning';
  }, [bridgeSnapshot?.theme, settings?.themePreference]);

  const updateTheme = async (nextTheme: ThemeChoice) => {
    const previousTheme = document.documentElement.dataset.theme;
    document.documentElement.dataset.theme = nextTheme;
    setSavedMessage(null);
    try {
      await update({ themePreference: nextTheme });
      setSavedMessage('外观已保存');
    } catch {
      document.documentElement.dataset.theme = previousTheme ?? activeTheme;
    }
  };

  const saveRunPreferences = async (event: FormEvent) => {
    event.preventDefault();
    if (!draft) return;

    const parsed = parseDraft(draft);
    if ('error' in parsed) {
      setValidationError(parsed.error);
      return;
    }

    setValidationError(null);
    setSavedMessage(null);
    try {
      const savedSettings = await update(parsed.patch);
      setDraft(toDraft(savedSettings));
      setIsDirty(false);
      setSavedMessage('已保存，将在下一次跑步开始时生效');
    } catch {
      // Bridge 错误由页面状态统一呈现。
    }
  };

  if (bridgeStatus === 'unavailable') return <SettingsUnavailable />;
  if ((bridgeStatus === 'connecting' || isLoading) && !settings) return <SettingsLoading />;
  if (!settings || !draft) {
    return <SettingsError message={error ?? '暂时无法读取本地设置'} onRetry={query} />;
  }

  const isRunActive =
    bridgeSnapshot?.session.state === 'running' || bridgeSnapshot?.session.state === 'paused';

  return (
    <section className="standard-page settings-page">
      <header className="page-title-row">
        <div>
          <p className="eyebrow">按你的习惯陪跑</p>
          <h1>设置</h1>
        </div>
        <span className={styles.syncState}>
          {isSaving ? '保存中' : isLoading ? '同步中' : '本机已同步'}
        </span>
      </header>

      {isRunActive ? (
        <p className={styles.notice} role="status">
          本次跑步继续使用开跑时的配置；现在的修改会在下一次开跑时生效。
        </p>
      ) : null}

      <section className="setting-section" aria-labelledby="appearance-title">
        <div className="section-heading">
          <div>
            <h2 id="appearance-title">界面外观</h2>
            <p>偏好保存在手机本地，并作用于整个 App</p>
          </div>
        </div>
        <div className="theme-selector" aria-label="界面主题">
          <button
            type="button"
            className={activeTheme === 'morning' ? 'selected' : ''}
            aria-pressed={activeTheme === 'morning'}
            disabled={isSaving}
            onClick={() => void updateTheme('morning')}
          >
            <span className="theme-swatch morning-swatch" aria-hidden="true" />
            <strong>晨光</strong>
            <small>清爽浅色</small>
          </button>
          <button
            type="button"
            className={activeTheme === 'night' ? 'selected' : ''}
            aria-pressed={activeTheme === 'night'}
            disabled={isSaving}
            onClick={() => void updateTheme('night')}
          >
            <span className="theme-swatch night-swatch" aria-hidden="true" />
            <strong>夜萤</strong>
            <small>沉静深色</small>
          </button>
        </div>
      </section>

      <section className="setting-section" aria-labelledby="deepseek-title">
        <div className="section-heading">
          <div>
            <h2 id="deepseek-title">DeepSeek 实时陪伴</h2>
            <p>
              {!settings.deepseekApiKeyConfigured
                ? '未配置'
                : settings.llmEnabled
                  ? 'AI 陪伴已启用，尚未验证连接'
                  : '已配置，AI 陪伴关闭'}
            </p>
          </div>
        </div>
        <label className={styles.switchRow}>
          <span>
            <strong>AI 陪伴</strong>
            <small>在线时尝试追加短鼓励；失败只保留核心提示</small>
          </span>
          <input
            type="checkbox"
            checked={settings.llmEnabled}
            disabled={isSaving || !settings.deepseekApiKeyConfigured}
            onChange={(event) => void updateCompanion(event.target.checked)}
          />
        </label>
        <button
          type="button"
          className="secondary-button"
          disabled={isRunActive || isConfiguring || bridgeSnapshot?.session.state === 'preparing'}
          onClick={() => void configureDeepseek()}
        >
          {settings.deepseekApiKeyConfigured ? '更换 DeepSeek 密钥' : '配置 DeepSeek 密钥'}
        </button>
        <p>密钥仅在原生私密窗口填写；保存不会自动开启、检测或试听。跑步及暂停期间不可更换。</p>
        {configurationError ? (
          <p className={styles.error} role="alert">
            {configurationError}
          </p>
        ) : null}
      </section>

      <form className={styles.form} onSubmit={(event) => void saveRunPreferences(event)}>
        <section className="setting-section" aria-labelledby="running-title">
          <div className="section-heading">
            <div>
              <h2 id="running-title">跑步偏好</h2>
              <p>目标留空即自由跑；最大心率用于训练分区，警戒心率单独设置</p>
            </div>
          </div>
          <div className={styles.fieldGrid}>
            <label>
              <span>目标配速</span>
              <input
                inputMode="numeric"
                placeholder="例如 6:15"
                value={draft.targetPace}
                onChange={(event) => patchDraft({ targetPace: event.target.value })}
              />
              <small>分:秒 /km，可留空</small>
            </label>
            <label>
              <span>目标距离</span>
              <input
                inputMode="decimal"
                placeholder="例如 5"
                value={draft.targetDistance}
                onChange={(event) => patchDraft({ targetDistance: event.target.value })}
              />
              <small>公里，可留空</small>
            </label>
            <label>
              <span>最大心率</span>
              <input
                inputMode="numeric"
                value={draft.maxHeartRate}
                onChange={(event) => patchDraft({ maxHeartRate: event.target.value })}
              />
              <small>80-240 bpm</small>
            </label>
            <label>
              <span>警戒心率</span>
              <input
                inputMode="numeric"
                placeholder={automaticThresholdLabel(draft.maxHeartRate)}
                value={draft.hrAlertThresholdBpm}
                onChange={(event) => patchDraft({ hrAlertThresholdBpm: event.target.value })}
              />
              <small>
                80-240 bpm；留空沿用分区门槛，连续达到 10 秒后提醒。自动值并非个体医学评估。
              </small>
            </label>
            <label>
              <span>高心率重复提醒间隔</span>
              <input
                inputMode="numeric"
                value={draft.hrAlertRepeatIntervalSec}
                onChange={(event) => patchDraft({ hrAlertRepeatIntervalSec: event.target.value })}
              />
              <small>30-300 秒，默认 120 秒；仅影响高心率重复提醒，下次跑步生效。</small>
            </label>
          </div>
        </section>

        <section className="setting-section" aria-labelledby="coach-title">
          <div className="section-heading">
            <div>
              <h2 id="coach-title">流萤教练</h2>
              <p>保持克制，只在需要时出现</p>
            </div>
          </div>
          <div className={styles.fieldGrid}>
            <label className={styles.switchRow}>
              <span>
                <strong>语音提醒</strong>
                <small>关闭后仍保留屏幕提示</small>
              </span>
              <input
                type="checkbox"
                checked={draft.ttsEnabled}
                onChange={(event) => patchDraft({ ttsEnabled: event.target.checked })}
              />
            </label>
            <label>
              <span>最短提醒间隔</span>
              <input
                inputMode="numeric"
                disabled={!draft.ttsEnabled}
                value={draft.ttsMinIntervalSec}
                onChange={(event) => patchDraft({ ttsMinIntervalSec: event.target.value })}
              />
              <small>0-300 秒</small>
            </label>
            <label>
              <span>陪跑语气</span>
              <select
                value={draft.coachTone}
                onChange={(event) =>
                  patchDraft({
                    coachTone: event.target.value as SettingsSnapshot['coachTone'],
                  })
                }
              >
                <option value="gentle">温和</option>
                <option value="balanced">均衡</option>
                <option value="direct">直接</option>
              </select>
            </label>
            <label>
              <span>动态效果</span>
              <select
                value={draft.motionPreference}
                onChange={(event) =>
                  patchDraft({
                    motionPreference: event.target.value as SettingsSnapshot['motionPreference'],
                  })
                }
              >
                <option value="system">跟随系统</option>
                <option value="expressive">灵动</option>
                <option value="balanced">均衡</option>
                <option value="reduced">克制</option>
                <option value="off">关闭</option>
              </select>
            </label>
          </div>
        </section>

        {validationError ? <p className={styles.error}>{validationError}</p> : null}
        {error ? <p className={styles.error}>{error}</p> : null}
        {savedMessage ? <p className={styles.saved}>{savedMessage}</p> : null}

        <button className={`primary-button ${styles.saveButton}`} type="submit" disabled={isSaving}>
          {isSaving ? '正在保存' : '保存跑步偏好'}
        </button>
      </form>
    </section>
  );
}

function SettingsUnavailable() {
  return (
    <section className="standard-page settings-page">
      <header className="page-title-row">
        <div>
          <p className="eyebrow">浏览器预览</p>
          <h1>设置</h1>
        </div>
      </header>
      <div className={styles.stateCard}>
        <strong>未连接手机本地设置</strong>
        <p>请在 Android App 中打开此页。这里不会展示或保存虚构偏好。</p>
      </div>
    </section>
  );
}

function SettingsLoading() {
  return (
    <section className="standard-page settings-page">
      <header className="page-title-row">
        <div>
          <p className="eyebrow">正在同步</p>
          <h1>设置</h1>
        </div>
      </header>
      <div className={styles.stateCard} role="status">
        <strong>正在读取本地设置</strong>
        <p>所有偏好只保存在这台手机上。</p>
      </div>
    </section>
  );
}

function SettingsError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <section className="standard-page settings-page">
      <header className="page-title-row">
        <div>
          <p className="eyebrow">读取失败</p>
          <h1>设置</h1>
        </div>
      </header>
      <div className={styles.stateCard} role="alert">
        <strong>{message}</strong>
        <button className="secondary-button" type="button" onClick={onRetry}>
          重新读取
        </button>
      </div>
    </section>
  );
}

function toDraft(settings: SettingsSnapshot): RunPreferencesDraft {
  return {
    targetPace: formatPace(settings.targetPaceSecPerKm),
    targetDistance: settings.targetDistanceKm?.toString() ?? '',
    maxHeartRate: settings.maxHeartRate.toString(),
    hrAlertThresholdBpm: settings.hrAlertThresholdBpm?.toString() ?? '',
    hrAlertRepeatIntervalSec: settings.hrAlertRepeatIntervalSec.toString(),
    ttsEnabled: settings.ttsEnabled,
    ttsMinIntervalSec: settings.ttsMinIntervalSec.toString(),
    coachTone: settings.coachTone,
    motionPreference: settings.motionPreference,
  };
}

function parseDraft(draft: RunPreferencesDraft): { patch: SettingsUpdateBody } | { error: string } {
  const pace = parsePace(draft.targetPace);
  if (pace === undefined) return { error: '目标配速请使用“分:秒”格式，并保持在 2:00-20:00 /km' };

  const distance = draft.targetDistance.trim() === '' ? null : Number(draft.targetDistance);
  if (distance !== null && (!Number.isFinite(distance) || distance < 0.1 || distance > 200)) {
    return { error: '目标距离应在 0.1-200 公里之间，或留空自由跑' };
  }

  const maxHeartRate = Number(draft.maxHeartRate);
  if (!Number.isInteger(maxHeartRate) || maxHeartRate < 80 || maxHeartRate > 240) {
    return { error: '最大心率应为 80-240 之间的整数' };
  }

  const ttsMinIntervalSec = Number(draft.ttsMinIntervalSec);
  const hrAlertThresholdBpm =
    draft.hrAlertThresholdBpm.trim() === '' ? null : Number(draft.hrAlertThresholdBpm);
  if (
    hrAlertThresholdBpm !== null &&
    (!Number.isInteger(hrAlertThresholdBpm) ||
      hrAlertThresholdBpm < 80 ||
      hrAlertThresholdBpm > 240)
  ) {
    return { error: '警戒心率应为 80-240 之间的整数，或留空使用自动门槛' };
  }
  const hrAlertRepeatIntervalSec = Number(draft.hrAlertRepeatIntervalSec);
  if (
    !Number.isInteger(hrAlertRepeatIntervalSec) ||
    hrAlertRepeatIntervalSec < 30 ||
    hrAlertRepeatIntervalSec > 300
  ) {
    return { error: '高心率重复提醒间隔应为 30-300 之间的整数' };
  }
  if (!Number.isInteger(ttsMinIntervalSec) || ttsMinIntervalSec < 0 || ttsMinIntervalSec > 300) {
    return { error: '最短提醒间隔应为 0-300 之间的整数' };
  }

  return {
    patch: {
      targetPaceSecPerKm: pace,
      targetDistanceKm: distance,
      maxHeartRate,
      hrAlertThresholdBpm,
      hrAlertRepeatIntervalSec,
      ttsEnabled: draft.ttsEnabled,
      ttsMinIntervalSec,
      coachTone: draft.coachTone,
      motionPreference: draft.motionPreference,
    },
  };
}

function parsePace(value: string): number | null | undefined {
  if (value.trim() === '') return null;
  const match = /^(\d{1,2}):(\d{2})$/.exec(value.trim());
  if (!match) return undefined;
  const minutes = Number(match[1]);
  const seconds = Number(match[2]);
  const total = minutes * 60 + seconds;
  return seconds < 60 && total >= 120 && total <= 1200 ? total : undefined;
}

function automaticThresholdLabel(maxHeartRate: string): string {
  const maximum = Number(maxHeartRate);
  if (!Number.isInteger(maximum) || maximum < 80 || maximum > 240)
    return '自动（请先填写有效最大心率）';
  return `自动（当前 ${Math.floor((maximum * 9 + 9) / 10)} bpm）`;
}

function formatPace(value: number | null): string {
  if (value === null) return '';
  const rounded = Math.round(value);
  return `${Math.floor(rounded / 60)}:${String(rounded % 60).padStart(2, '0')}`;
}

function updateDraft(
  setDraft: Dispatch<SetStateAction<RunPreferencesDraft | null>>,
  patch: Partial<RunPreferencesDraft>,
) {
  setDraft((current) => (current ? { ...current, ...patch } : current));
}
