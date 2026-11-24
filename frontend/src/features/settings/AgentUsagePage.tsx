import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowClockwise, CaretDown } from '@phosphor-icons/react';
import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import { cloudListingSchema, type CloudListing } from '../../bridge/cloudAgent';
import styles from './AgentUsagePage.module.css';

type Cost = CloudListing['items'][number]['cost'];
const money = (cost: Cost | null | undefined) =>
  cost?.estimatedMicros == null ? '待确认' : `$${(cost.estimatedMicros / 1_000_000).toFixed(6)}`;

function UsageRecord({
  title,
  cost,
  tokens,
  date,
}: {
  title: string;
  cost?: Cost | null;
  tokens: boolean;
  date?: string;
}) {
  const [expanded, setExpanded] = useState(false);
  return (
    <li>
      <button
        type="button"
        className={styles.row}
        aria-expanded={expanded}
        onClick={() => setExpanded(!expanded)}
      >
        <span className={styles.copy}>
          <strong>{title}</strong>
          <small>{date ?? (cost?.usageComplete ? '用量已确认' : '用量不完整，不代表零费用')}</small>
        </span>
        <span className={styles.value}>
          {tokens
            ? cost
              ? (cost.inputTokens + cost.outputTokens).toLocaleString('en-US')
              : '待确认'
            : money(cost)}
          <small>{tokens ? '已知 token' : 'USD'}</small>
        </span>
        <CaretDown size={14} aria-hidden="true" />
      </button>
      {expanded && (
        <div className={styles.detail}>
          <p className={styles.fullTitle}>{title}</p>
          <UsageCost cost={cost ?? null} />
        </div>
      )}
    </li>
  );
}

function UsageCost({ cost }: { cost: CloudListing['items'][number]['cost'] | null }) {
  return (
    <>
      <p className={styles.amount}>
        {!cost || cost.estimatedMicros === null
          ? '待确认'
          : `$${(cost.estimatedMicros / 1_000_000).toFixed(6)} USD`}
      </p>
      {cost ? (
        <>
          <p>
            已确认用量：输入 {cost.inputTokens} / 输出 {cost.outputTokens} token
          </p>
          {!cost.usageComplete && <p>部分用量待确认，不代表零费用。</p>}
          {cost.rateStatus === 'stale' && <p>沿用上次核验费率，可能已过时。</p>}
          {cost.rateStatus === 'reference' && <p>使用内置参考费率，不是实时价格。</p>}
        </>
      ) : (
        <p>尚无可用费用回执，不代表零费用。</p>
      )}
    </>
  );
}

export function AgentUsagePage() {
  const status = useBridgeStore((state) => state.status);
  const [listing, setListing] = useState<CloudListing | null>(null);
  const [error, setError] = useState(false);
  const [revision, setRevision] = useState(0);
  const [tab, setTab] = useState<'cost' | 'tokens'>('cost');
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
  const known = items?.filter((item) => item.cost?.estimatedMicros != null) ?? [];
  const subtotal = known.reduce((sum, item) => sum + item.cost!.estimatedMicros!, 0);
  return (
    <section className={`standard-page ${styles.page}`}>
      <p className={styles.notice}>
        当前会话最近 10 条本机记录。仅供估算，不是账单或余额。{' '}
        <Link className="notices-link" to="/settings/notices" state={{ from: '/settings/usage' }}>
          用户须知
        </Link>
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
          <section className={styles.overview} aria-label="当前会话已知费用小计">
            <span>当前会话 · 已知估算小计 · USD</span>
            <strong>
              {known.length ? `$${(subtotal / 1_000_000).toFixed(6)}` : '暂无已知估算'}
            </strong>
            <small>
              {known.length} 笔有估算 · {(items?.length ?? 0) - known.length} 笔待确认
            </small>
          </section>
          <div className={styles.listHeading}>
            <h2>
              {title ??
                (listing.conversationId === null && listing.items.length
                  ? '旧版历史记录'
                  : '当前会话')}
            </h2>
            <button
              className={styles.refresh}
              type="button"
              aria-label="刷新本机用量"
              title="刷新本机用量"
              onClick={() => setRevision((value) => value + 1)}
            >
              <ArrowClockwise size={20} aria-hidden="true" />
            </button>
          </div>
          <div className={styles.tabs} role="group" aria-label="回执视图">
            <button type="button" aria-pressed={tab === 'cost'} onClick={() => setTab('cost')}>
              估算费用
            </button>
            <button type="button" aria-pressed={tab === 'tokens'} onClick={() => setTab('tokens')}>
              Token 用量
            </button>
          </div>
          {!listing.configured ? (
            <p>请先在侧栏配置在线 Agent。</p>
          ) : !items?.length ? (
            <p>暂无可展示的用量记录。</p>
          ) : (
            <ul className={styles.usageList}>
              {items.map((item) => (
                <UsageRecord
                  key={item.requestId}
                  title={item.prompt}
                  cost={item.cost}
                  tokens={tab === 'tokens'}
                />
              ))}
            </ul>
          )}
          {!!listing.retainedUsage?.length && (
            <section aria-label="删除后保留的必要用量">
              <h2>删除后保留的必要用量</h2>
              <ul className={styles.usageList}>
                {listing.retainedUsage.map((item) => (
                  <UsageRecord
                    key={item.requestId}
                    title="已删除对话的用量"
                    date={new Date(item.atMillis).toLocaleString('zh-CN')}
                    cost={item.cost}
                    tokens={tab === 'tokens'}
                  />
                ))}
              </ul>
            </section>
          )}
        </>
      )}
    </section>
  );
}
