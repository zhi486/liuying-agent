import type { useCloudAgent } from './useCloudAgent';
import { useEffect, useRef, useState } from 'react';
import styles from './AgentPage.module.css';
import type { KnowledgeSelection } from '../../bridge/cloudAgent';

function knowledgeLabel(value?: KnowledgeSelection) {
  return !value
    ? '不使用知识库'
    : value.library === 'health'
      ? '运动健康 · WHO 精选（无剧透）'
      : `流萤官方资料 · ${value.allowSpoilers ? '允许身份与身世剧透' : '不允许剧透'}`;
}

type Props = { cloud: ReturnType<typeof useCloudAgent>; ready: boolean; onSent: () => void };
const labels = {
  unconfirmed: '云端状态待确认',
  queued: '云端排队中',
  running: '流萤正在思考',
  completed: '已完成',
  blocked: '未执行',
  failed: '执行失败',
  cancelled: '已取消',
  timed_out: '云端超时',
};

export function AgentCloudPanel({ cloud, ready, onSent }: Props) {
  const [withdrawId, setWithdrawId] = useState<string | null>(null);
  const confirmation = useRef<HTMLElement>(null);
  const token = cloud.prepared?.token;
  useEffect(() => {
    if (token) {
      confirmation.current?.focus();
      confirmation.current?.scrollIntoView({ block: 'start' });
    }
  }, [token]);
  useEffect(() => {
    if (!ready) setWithdrawId(null);
  }, [ready]);
  return (
    <section className={styles.onlineSection} aria-label="在线 Agent">
      {cloud.error && <p role="alert">{cloud.error}</p>}
      {cloud.prepared && (
        <section
          className={styles.detail}
          aria-label="本次云端授权"
          tabIndex={-1}
          ref={confirmation}
        >
          <h2>只授权这一次</h2>
          <p>
            发送至 {cloud.prepared.origin}
            ，由云端模型处理，可能消耗额度。仅发送以下问题、记忆和知识库选择，不含历史任务、跑步轨迹或连续心率。
          </p>
          <h3>本次问题</h3>
          <p>本次知识库：{knowledgeLabel(cloud.prepared.knowledge)}</p>
          {cloud.prepared.knowledge && (
            <p>
              云端会检索所选公共资料并将命中片段提供给模型；无可用资料时不调用模型回答。选择仅绑定本次任务。
            </p>
          )}
          <p className={styles.message}>{cloud.prepared.prompt}</p>
          <h3>本次记忆（{cloud.prepared.memories.length} 条）</h3>
          <ul>
            {cloud.prepared.memories.map((item) => (
              <li className={styles.message} key={item.id}>
                {item.content}（版本 {item.version}）
              </li>
            ))}
          </ul>
          <p>
            确认后再次核对版本。记忆删除只影响后续使用，无法撤回模型已收到的内容；回答可能引用它们并保存在本机。
          </p>
          <div className={styles.taskControls}>
            <button type="button" disabled={cloud.working} onClick={cloud.discard}>
              暂不发送
            </button>
            <button
              type="button"
              disabled={cloud.working}
              onClick={() => {
                void cloud.act('cloud.confirm').then((sent) => {
                  if (sent) onSent();
                });
              }}
            >
              {cloud.working ? '正在提交…' : '同意本次上传并发送'}
            </button>
          </div>
        </section>
      )}
      {cloud.listing?.items.length ? <h2 className="sr-only">最近对话</h2> : null}
      {cloud.listing?.items.map((item) => (
        <article
          id={`chat-${item.requestId}`}
          key={item.requestId}
          className={styles.chatTurn}
          aria-label="在线任务"
        >
          <p className={styles.userMessage}>{item.prompt}</p>
          <p role="status">
            {item.busy
              ? '正在与云端通信…'
              : !item.attempted
                ? '尚未发送，不会自动补发'
                : labels[item.status]}
          </p>
          {item.reason && <p>原因：{item.reason}</p>}
          {item.error && <p>{item.error}</p>}
          {item.pendingControl && (
            <p>
              {item.pendingControl === 'withdraw' ? '本机回答已隐藏；' : ''}
              {item.confirmedControl === item.pendingControl
                ? '云端已确认控制请求。'
                : '云端控制请求待确认，可恢复查询。'}
            </p>
          )}
          {item.answer && item.pendingControl !== 'withdraw' && (
            <section aria-label="流萤的回答">
              <h3>流萤</h3>
              <p className={styles.message}>{item.answer}</p>
              {item.sources && (
                <section aria-label="知识来源">
                  <h4>本次检索来源</h4>
                  <p>不代表模型逐条采用，也不保证回答每句话都有依据。</p>
                  {item.sources.length === 0 ? (
                    <p>本次没有可用的匹配来源。</p>
                  ) : (
                    <ul>
                      {item.sources.map((source) => (
                        <li key={source.citationId} className={styles.message}>
                          <strong>{source.title}</strong>
                          <p style={{ userSelect: 'text', overflowWrap: 'anywhere' }}>
                            来源地址（可选中复制）：{source.url}
                          </p>
                          <small>
                            发布：{source.publishedAt ?? '未提供'} · 核验：
                            {new Date(source.checkedAtMillis).toISOString().slice(0, 10)} ·{' '}
                            {source.citationId}
                          </small>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}
            </section>
          )}
          {!item.answer && item.status === 'completed' && item.pendingControl !== 'withdraw' && (
            <p>云端任务已完成，但本机没有可显示的回答；结果可能已过期或已被确认清除。</p>
          )}
          <details className={styles.messageMeta}>
            <summary>本次详情与用量</summary>
            <p>本次知识库：{knowledgeLabel(item.knowledge)}</p>
            {item.pendingControl !== 'withdraw' && item.resultState !== 'withdrawn' && (
              <section aria-label="本次费用估算">
                <p>
                  本次成本估算：
                  {!item.cost || item.cost.estimatedMicros === null
                    ? '待确认'
                    : `$${(item.cost.estimatedMicros / 1_000_000).toFixed(6)} USD`}
                </p>
                {item.cost && (
                  <p>
                    已确认用量：输入 {item.cost.inputTokens} / 输出 {item.cost.outputTokens} token
                  </p>
                )}
                {item.cost?.rateStatus === 'stale' && <p>沿用上次核验费率，可能已过时。</p>}
                {item.cost?.rateStatus === 'reference' && <p>使用内置参考费率，不是实时价格。</p>}
                {item.cost && !item.cost.usageComplete && <p>部分用量待确认，不代表零费用。</p>}
                <small>这是结果保存时的估算，不是供应商账单；预算超出不关闭功能。</small>
              </section>
            )}
          </details>
          <details className={styles.messageMeta}>
            <summary>更多操作</summary>
            {item.memoryRefs.length > 0 && (
              <details>
                <summary>本次授权提供的记忆（不代表模型全部采用）</summary>
                <ul>
                  {item.memoryRefs.map((ref) => (
                    <li className={styles.message} key={ref.id}>
                      {ref.id} · 版本 {ref.version}
                    </li>
                  ))}
                </ul>
              </details>
            )}
            <div className={styles.taskControls}>
              <button
                type="button"
                disabled={!ready || cloud.working || item.busy || !item.attempted}
                onClick={() => void cloud.act('cloud.recover', item.requestId)}
              >
                恢复查询
              </button>
              {['unconfirmed', 'queued', 'running'].includes(item.status) &&
                !item.pendingControl && (
                  <button
                    type="button"
                    disabled={!ready || cloud.working}
                    onClick={() => void cloud.act('cloud.cancel', item.requestId)}
                  >
                    取消执行
                  </button>
                )}
              {item.pendingControl !== 'withdraw' && (
                <button
                  type="button"
                  disabled={!ready || cloud.working}
                  onClick={() => setWithdrawId(item.requestId)}
                >
                  撤回回答
                </button>
              )}
            </div>
            {withdrawId === item.requestId && (
              <div role="group" aria-label="确认撤回回答">
                <p>隐藏本机回答并请求云端撤回？这不能撤回模型供应商已接收的内容。</p>
                <div className={styles.taskControls}>
                  <button type="button" onClick={() => setWithdrawId(null)}>
                    保留回答
                  </button>
                  <button
                    type="button"
                    disabled={!ready || cloud.working}
                    onClick={() => {
                      setWithdrawId(null);
                      void cloud.act('cloud.withdraw', item.requestId);
                    }}
                  >
                    确认撤回
                  </button>
                </div>
              </div>
            )}
          </details>
        </article>
      ))}
    </section>
  );
}
