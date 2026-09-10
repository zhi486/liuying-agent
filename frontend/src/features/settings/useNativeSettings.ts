import { useCallback, useEffect, useRef, useState } from 'react';

import { sendNativeCommand } from '../../bridge/bridgeStore';
import type { AppSnapshot, SettingsUpdateBody } from '../../bridge/contract';
import { settingsQueryResultSchema } from '../../bridge/schemas';

type BridgeStatus = 'unavailable' | 'connecting' | 'ready' | 'error';
type SettingsSnapshot = AppSnapshot['settings'];

export function useNativeSettings(
  bridgeStatus: BridgeStatus,
  snapshotSettings: SettingsSnapshot | null,
) {
  const requestSequence = useRef(0);
  const [settings, setSettings] = useState<SettingsSnapshot | null>(snapshotSettings);
  const [isLoading, setIsLoading] = useState(bridgeStatus === 'ready');
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (snapshotSettings) {
      setSettings((current) =>
        current && JSON.stringify(current) === JSON.stringify(snapshotSettings)
          ? current
          : snapshotSettings,
      );
    }
  }, [snapshotSettings]);

  const query = useCallback(async () => {
    if (bridgeStatus !== 'ready') return;
    const sequence = ++requestSequence.current;
    setIsLoading(true);
    setError(null);

    try {
      const result = await sendNativeCommand('settings.query', {});
      if (sequence !== requestSequence.current) return;
      if (result.status === 'rejected') throw new Error(result.error.message);
      const parsed = settingsQueryResultSchema.safeParse(result.data);
      if (!parsed.success) throw new Error('设置数据格式无效');
      setSettings(parsed.data.settings);
    } catch (reason) {
      if (sequence !== requestSequence.current) return;
      setError(reason instanceof Error ? reason.message : '读取本地设置失败');
    } finally {
      if (sequence === requestSequence.current) setIsLoading(false);
    }
  }, [bridgeStatus]);

  useEffect(() => {
    if (bridgeStatus === 'ready') void query();
    return () => {
      requestSequence.current += 1;
    };
  }, [bridgeStatus, query]);

  const update = useCallback(
    async (patch: SettingsUpdateBody) => {
      if (bridgeStatus !== 'ready') throw new Error('原生设置服务尚未就绪');
      setIsSaving(true);
      setError(null);
      try {
        const result = await sendNativeCommand('settings.update', patch);
        if (result.status === 'rejected') throw new Error(result.error.message);
        const parsed = settingsQueryResultSchema.safeParse(result.data);
        if (!parsed.success) throw new Error('设置保存结果格式无效');
        setSettings(parsed.data.settings);
        return parsed.data.settings;
      } catch (reason) {
        const message = reason instanceof Error ? reason.message : '保存本地设置失败';
        setError(message);
        throw new Error(message);
      } finally {
        setIsSaving(false);
      }
    },
    [bridgeStatus],
  );

  return { settings, isLoading, isSaving, error, query, update };
}
