import { useEffect, useState } from 'react';
import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import { cloudListingSchema, type CloudListing } from '../../bridge/cloudAgent';
import styles from './AgentSettings.module.css';

export function AgentUsagePage() {
  const status = useBridgeStore((state) => state.status);
  const [listing, setListing] = useState<CloudListing | null>(null);
  const [error, setError] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    setListing(null);
    setError(false);
    if (status === 'ready') {
      // 只读取本机已保存的回执，不恢复任务、不调用模型、不查询供应商。
      void sendNativeCommand('cloud.list', {})
        .then((result) => {
          if (!active) return;
          const parsed = result.status === 'accepted' && cloudListingSchema.safeParse(result.data);
          if (parsed && parsed.success) setListing(parsed.data);
          else setError(true);
        })
        .catch(() => {
          if (active) setError(true);
        });
    }
    return () => {
      active = false;
    };
  }, [status, revision]);
  const title = listing?.conversations?.find((item) => item.id === listing.conversationId)?.title;
  const items = listing?.items.filter(
    (item) =>
      item.pendingControl !== 'withdraw' &&
      item.resultState !== 'withdrawn' &&
      item.confirmedControl !== 'withdraw',
  );
  return (
    <section className={`standard-page ${styles.page}`}>
      <header>
        <p className="eyebrow">在线 Agent</p>
        <h1>账单与用量</h1>
      </header>
      <p className={styles.notice}>
        当前会话最近 10
        条本机记录，不是完整账单或账户余额。估算沿用结果保存时的费率，实际扣费以供应商账单为准；预算超出不关闭功能。
      </p>
      {status !== 'ready' ? (
        <p role="status">请在手机连接就绪后查看本机用量。</p>
      ) : error ? (
        <div role="alert">
          <p>暂时无法读取本机用量，没有重新发送问题。</p>
          <button
            className="secondary-button"
            type="button"
            onClick={() => setRevision((value) => value + 1)}
          >
            重新读取
          </button>
        </div>
      ) : !listing ? (
        <p role="status">正在读取本机用量…</p>
      ) : (
        <>
          <h2>{title ?? (listing.conversationId === null ? '旧版历史记录' : '当前会话')}</h2>
          {!listing.configured ? (
            <p>请先在侧栏配置在线 Agent。</p>
          ) : !items?.length ? (
            <p>暂无可展示的用量记录。</p>
          ) : (
            <ul className={styles.usageList}>
              {items.map((item) => (
                <li key={item.requestId}>
                  <h3>{item.prompt}</h3>
                  <p className={styles.amount}>
                    {!item.cost || item.cost.estimatedMicros === null
                      ? '待确认'
                      : `$${(item.cost.estimatedMicros / 1_000_000).toFixed(6)} USD`}
                  </p>
                  {item.cost ? (
                    <>
                      <p>
                        已确认用量：输入 {item.cost.inputTokens} / 输出 {item.cost.outputTokens}{' '}
                        token
                      </p>
                      {!item.cost.usageComplete && <p>部分用量待确认，不代表零费用。</p>}
                      {item.cost.rateStatus === 'stale' && <p>沿用上次核验费率，可能已过时。</p>}
                      {item.cost.rateStatus === 'reference' && (
                        <p>使用内置参考费率，不是实时价格。</p>
                      )}
                    </>
                  ) : (
                    <p>尚无可用费用回执，不代表零费用。</p>
                  )}
                </li>
              ))}
            </ul>
          )}
          <button
            className="secondary-button"
            type="button"
            onClick={() => setRevision((value) => value + 1)}
          >
            刷新本机用量
          </button>
        </>
      )}
    </section>
  );
}
