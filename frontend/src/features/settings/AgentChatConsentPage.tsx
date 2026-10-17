import { useEffect, useRef, useState } from 'react';
import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import { cloudListingSchema, type CloudListing } from '../../bridge/cloudAgent';
import styles from './AgentSettings.module.css';

export function AgentChatConsentPage() {
  const status = useBridgeStore((state) => state.status);
  const [listing, setListing] = useState<CloudListing | null>(null);
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const epoch = useRef(0);
  const locked = useRef(false);
  useEffect(() => {
    const request = ++epoch.current;
    setListing(null);
    setAgreed(false);
    setError('');
    locked.current = false;
    setBusy(false);
    if (status === 'ready') {
      void sendNativeCommand('cloud.list', {})
        .then((result) => {
          if (request !== epoch.current) return;
          if (result.status !== 'accepted') throw new Error();
          setListing(cloudListingSchema.parse(result.data));
        })
        .catch(() => {
          if (request === epoch.current) setError('无法读取授权状态，请重新读取。');
        });
    }
    return () => {
      epoch.current = request + 1;
    };
  }, [status, revision]);

  async function save(enabled: boolean) {
    if (
      status !== 'ready' ||
      locked.current ||
      !listing?.configured ||
      !listing.chatConsentContext ||
      (enabled && !agreed)
    )
      return;
    locked.current = true;
    setBusy(true);
    setError('');
    const request = epoch.current;
    try {
      const result = await sendNativeCommand('cloud.chatConsent', {
        identityToken: listing.chatConsentContext,
        enabled,
      });
      if (request !== epoch.current) return;
      if (result.status !== 'accepted') throw new Error();
      const next = cloudListingSchema.parse(result.data);
      if (next.chatConsentContext !== listing.chatConsentContext || next.chatAuthorized !== enabled)
        throw new Error();
      setListing(next);
      setAgreed(false);
    } catch {
      if (request === epoch.current) {
        setListing(null);
        setAgreed(false);
        setError('操作结果尚未确认，请重新读取授权状态；不会自动重试。');
      }
    } finally {
      if (request === epoch.current) {
        locked.current = false;
        setBusy(false);
      }
    }
  }

  return (
    <section className={`standard-page ${styles.page}`}>
      <header>
        <p className="eyebrow">在线 Agent</p>
        <h1>聊天授权</h1>
      </header>
      <section className={styles.noticeSection} aria-label="持续授权范围">
        <h2>普通文字消息点击即发送</h2>
        <p>
          开启后，你主动发送的文字和当前会话最近上下文会发送到已配置的云端和模型供应商，可能产生费用。不自动补发旧问题。
        </p>
        <p>
          不包含选中记忆、知识库或跑步摘要，它们仍需单次核对。录音、自主写入记忆与插件权限不在本授权内。
        </p>
        <p>仅对当前账号、设备、服务和凭据有效，更换后需重新授权。关闭不会撤回已经发出的请求。</p>
      </section>
      {status !== 'ready' ? (
        <p role="status">请在手机连接就绪后管理授权。</p>
      ) : error ? (
        <p role="alert">{error}</p>
      ) : !listing ? (
        <p role="status">正在读取授权状态…</p>
      ) : !listing.configured ? (
        <p>请先配置在线 Agent。</p>
      ) : !listing.chatConsentContext ? (
        <p>当前原生版本尚不支持持续授权，仍按每次确认发送。</p>
      ) : (
        <section className={styles.noticeSection} aria-label="当前聊天授权">
          <h2>{listing.chatAuthorized ? '持续授权已开启' : '持续授权未开启'}</h2>
          <p>当前服务：{listing.origin}</p>
          {listing.chatAuthorized ? (
            <button
              className="secondary-button"
              type="button"
              disabled={busy}
              onClick={() => void save(false)}
            >
              关闭持续授权
            </button>
          ) : (
            <>
              <label className={styles.consentCheck}>
                <input
                  type="checkbox"
                  checked={agreed}
                  disabled={busy}
                  onChange={(event) => setAgreed(event.target.checked)}
                />
                <span>我同意上述范围，普通文字消息无需每次确认</span>
              </label>
              <button
                className="primary-button"
                type="button"
                disabled={busy || !agreed}
                onClick={() => void save(true)}
              >
                开启持续授权
              </button>
            </>
          )}
          {busy && <p role="status">正在保存授权…</p>}
        </section>
      )}
      {status === 'ready' && (
        <button
          className="secondary-button"
          type="button"
          disabled={busy}
          onClick={() => setRevision((value) => value + 1)}
        >
          重新读取授权状态
        </button>
      )}
    </section>
  );
}
