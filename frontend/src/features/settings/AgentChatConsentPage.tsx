import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import { cloudListingSchema, type CloudListing } from '../../bridge/cloudAgent';
import styles from './AgentSettings.module.css';
import consentStyles from './AgentChatConsentPage.module.css';
import { AuthorizationSwitch } from '../../design-system/AuthorizationSwitch';

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
    <section className={`standard-page ${styles.page} ${consentStyles.page}`}>
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
        <section className={consentStyles.actions} aria-label="当前聊天授权">
          <AuthorizationSwitch
            label="持续聊天授权"
            status={listing.chatAuthorized ? '持续授权已开启' : '持续授权未开启'}
            checked={listing.chatAuthorized === true}
            pending={busy}
            summary={
              listing.chatAuthorized
                ? '普通文字点击即发送；关闭不会撤回已发请求。记忆、跑步摘要和 MCP 仍需单次核对。'
                : '开启后，主动发送的文字和近期会话会直接发给云端与模型供应商，可能产生费用。记忆、跑步摘要和 MCP 仍需单次核对。'
            }
            agreement="我同意上述范围，普通文字消息无需每次确认"
            agreed={agreed}
            onAgree={setAgreed}
            onToggle={(enabled) => void save(enabled)}
          />
          <p className={consentStyles.service}>当前服务：{listing.origin ?? '尚未配置'}</p>
          {busy && <p role="status">正在保存授权…</p>}
        </section>
      )}
      <Link className="notices-link" to="/settings/notices" state={{ from: '/settings/chat' }}>
        用户须知
      </Link>
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
