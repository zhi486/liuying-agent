import { useEffect, useRef, useState } from 'react';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import {
  memoryAuthorizationSchema,
  type MemoryAuthorization as Authorization,
} from '../../bridge/cloudAgent';
import styles from './MemoriesPage.module.css';
import { AuthorizationSwitch } from '../../design-system/AuthorizationSwitch';

export function MemoryAuthorization({ ready }: { ready: boolean }) {
  const [value, setValue] = useState<Authorization | null>(null);
  const [agreed, setAgreed] = useState(false);
  const [factsAgreed, setFactsAgreed] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const epoch = useRef(0);
  const locked = useRef(false);
  useEffect(() => {
    const request = ++epoch.current;
    setValue(null);
    setAgreed(false);
    setFactsAgreed(false);
    setPending(false);
    setError('');
    locked.current = false;
    if (ready)
      void sendNativeCommand('cloud.memoryAuthorization', {})
        .then((result) => {
          if (request !== epoch.current) return;
          if (result.status !== 'accepted') throw new Error();
          setValue(memoryAuthorizationSchema.parse(result.data));
        })
        .catch(() => {
          if (request === epoch.current)
            setError('无法读取自动记忆状态。请重新读取；旧版 App 可能不支持此功能。');
        });
    return () => {
      epoch.current = request + 1;
    };
  }, [ready, revision]);

  async function save(enabled: boolean, factsAllowed = false) {
    if (
      !ready ||
      locked.current ||
      !value?.configured ||
      !value.context ||
      (enabled && !value.enabled && !agreed) ||
      (factsAllowed && !factsAgreed)
    )
      return;
    const request = epoch.current;
    locked.current = true;
    setPending(true);
    setError('');
    try {
      const result = await sendNativeCommand('cloud.memoryConsent', {
        identityToken: value.context,
        enabled,
        ...(value.factsAllowed !== undefined ? { factsAllowed } : {}),
      });
      if (request !== epoch.current) return;
      if (result.status !== 'accepted') throw new Error();
      const next = memoryAuthorizationSchema.parse(result.data);
      if (
        !next.configured ||
        next.enabled !== enabled ||
        next.context === value.context ||
        (next.factsAllowed ?? false) !== factsAllowed
      )
        throw new Error();
      setValue(next);
      setAgreed(false);
      setFactsAgreed(false);
    } catch {
      if (request === epoch.current) {
        setValue(null);
        setAgreed(false);
        setFactsAgreed(false);
        setError('授权结果未确认，请重新读取状态；不会自动重试。');
      }
    } finally {
      if (request === epoch.current) {
        locked.current = false;
        setPending(false);
      }
    }
  }

  return (
    <section className={styles.authorization} aria-label="记忆授权">
      {!ready ? (
        <p role="status">请在手机 App 连接就绪后管理自动记忆。</p>
      ) : error ? (
        <p role="alert">{error}</p>
      ) : !value ? (
        <p role="status">正在读取自动记忆状态…</p>
      ) : !value.configured ? (
        <p>请先在设置中配置在线 Agent。</p>
      ) : (
        <>
          <AuthorizationSwitch
            label="自动记忆"
            status={value.enabled ? '自动记忆已开启' : '自动记忆未开启'}
            checked={value.enabled}
            pending={pending}
            summary={
              value.enabled
                ? '关闭后停止自动读写，不删除已保存的记忆。'
                : '允许读取记忆及保存明确的偏好与目标；用于回答的条目会发给云端与模型供应商。'
            }
            agreement="我允许上述记忆读写与发送范围，可随时关闭"
            agreed={agreed}
            onAgree={setAgreed}
            onToggle={(enabled) => void save(enabled)}
          />
          {value.enabled && value.factsAllowed !== undefined && (
            <AuthorizationSwitch
              label="事实写入"
              status={value.factsAllowed ? '事实写入已允许' : '事实写入未允许'}
              checked={value.factsAllowed}
              pending={pending}
              summary={
                value.factsAllowed
                  ? '关闭后仍可读取已保存记忆，不会删除已有事实。'
                  : '仅保存你明确告知的职业、专业或主要编程语言；用于回答时会发给云端与模型供应商。'
              }
              agreement="我另行允许上述事实写入与发送范围"
              agreed={factsAgreed}
              onAgree={setFactsAgreed}
              onToggle={(enabled) => void save(true, enabled)}
            />
          )}
        </>
      )}
      {pending && <p role="status">正在保存自动记忆授权…</p>}
      {ready && (
        <button
          type="button"
          className={styles.reload}
          disabled={pending}
          onClick={() => setRevision((item) => item + 1)}
        >
          重新读取自动记忆状态
        </button>
      )}
    </section>
  );
}
