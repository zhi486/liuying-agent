import type { useCloudAgent } from './useCloudAgent';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import styles from './AgentPage.module.css';
import type { CloudListing, KnowledgeSelection } from '../../bridge/cloudAgent';
import { AgentRunSummary } from './AgentRunSummary';
import { ArrowClockwise, Copy, Check, Stop, SpeakerHigh, CircleNotch } from '@phosphor-icons/react';

function knowledgeLabel(value?: KnowledgeSelection) {
  return !value
    ? '不使用知识库'
    : value.library === 'both'
      ? `运动健康与流萤资料 · ${value.allowSpoilers ? '允许剧透' : '不允许剧透'}`
      : value.library === 'health'
        ? '运动健康 · WHO 精选（无剧透）'
        : `流萤官方资料 · ${value.allowSpoilers ? '允许身份与身世剧透' : '不允许剧透'}`;
}

type Props = { cloud: ReturnType<typeof useCloudAgent>; ready: boolean; onSent: () => void };
const needsRecovery = (item: CloudListing['items'][number]) =>
  item.attempted &&
  !item.busy &&
  (Boolean(item.error) ||
    Boolean(item.pendingControl && item.confirmedControl !== item.pendingControl));
const canStop = (item: CloudListing['items'][number]) =>
  ['unconfirmed', 'queued', 'running'].includes(item.status) &&
  !item.pendingControl &&
  item.resultState !== 'withdrawn' &&
  item.confirmedControl !== 'withdraw';
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
  const [copyNotice, setCopyNotice] = useState<{ id: string; ok: boolean } | null>(null);
  const confirmation = useRef<HTMLElement>(null);
  const token = cloud.prepared?.token;
  const speech = cloud.listing?.speech;
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
      {cloud.error && (
        <p className={styles.turnError} role="alert">
          {cloud.error}
        </p>
      )}
      {cloud.prepared && !cloud.readOnly && (
        <section
          className={styles.detail}
          aria-label="本次云端授权"
          tabIndex={-1}
          ref={confirmation}
        >
          <h2>只授权这一次</h2>
          <p>
            发送至 {cloud.prepared.origin}
            ，由云端模型处理，可能消耗额度。仅发送以下问题、当前会话上下文、记忆、知识库选择和明确展示的单条跑步摘要，不含其他会话、跑步轨迹或连续心率。
          </p>
          <h3>本次问题</h3>
          <p>本次知识库：{knowledgeLabel(cloud.prepared.knowledge)}</p>
          {cloud.prepared.knowledge && (
            <p>
              云端会检索所选公共资料并将命中片段提供给模型；无可用资料时不调用模型回答。选择仅绑定本次任务。
            </p>
          )}
          <p className={styles.message}>{cloud.prepared.prompt}</p>
          {cloud.prepared.mcpServices && (
            <section aria-label="本次插件权限">
              <h3>本次可用插件</h3>
              <p>
                工具定义会提供给模型；调用参数和返回结果经云端转发。插件密钥保留在手机。这里只授权下列工具，手机离开前台或配置变化后停止调用。
              </p>
              {cloud.prepared.mcpServices.map((service) => (
                <div key={service.id} className={styles.mcpPreview}>
                  <p>{service.endpoint}</p>
                  <ul>
                    {service.tools.map((tool) => (
                      <li key={tool.name}>
                        {tool.name}：
                        {tool.mode === 'read' ? '只读，可自主调用' : '每次展示完整参数，确认后调用'}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </section>
          )}
          {cloud.prepared.conversation && (
            <details>
              <summary>
                本次会话上下文（{cloud.prepared.conversation.messages.length / 2} 轮）
              </summary>
              <p>
                最多最近四轮完整问答、合计一万二千字符；新对话不继承旧对话。这里只提供上下文，不新增工具权限。
              </p>
              {cloud.prepared.conversation.messages.map((message, index) => (
                <p key={index} className={styles.message}>
                  {message.role === 'user' ? '你' : '流萤'}：{message.text}
                </p>
              ))}
            </details>
          )}
          {cloud.prepared.runSummary && (
            <>
              <AgentRunSummary summary={cloud.prepared.runSummary} />
              <p>
                确认后保存本次摘要快照；更改原记录不会改写已授权任务，也无法撤回供应商已收到的内容。
              </p>
            </>
          )}
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
              disabled={!ready || cloud.working}
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
      {cloud.listing?.items
        .slice()
        .reverse()
        .map((item) => (
          <article
            id={`chat-${item.requestId}`}
            key={item.requestId}
            className={`${styles.chatTurn} ${styles.messageBubbleTurn}`}
            aria-label="在线任务"
          >
            <div
              className={`${styles.userTurn} ${styles.agentTurnContext} ${styles.messageUserTurn}`}
            >
              <span
                className={`${styles.userContextLabel} ${styles.messageSenderLabel}`}
                aria-hidden="true"
              >
                你
              </span>
              <p
                className={`${styles.userMessage} ${styles.messageUserBubble}`}
                data-testid="message-user-bubble"
              >
                {item.prompt}
              </p>
            </div>
            {item.reason === 'scope_denied' ? (
              <p className={styles.turnNotice}>
                本次未获准读取这条跑步记录。请前往“跑步记录”，选择一条记录后点击“让流萤复盘本次跑步”。
              </p>
            ) : (
              item.reason && (
                <p className={styles.turnNotice}>
                  这次没能完成回答，可在“更多操作”中查看原因或恢复查询。
                </p>
              )
            )}
            {item.error && <p className={styles.turnError}>{item.error}</p>}
            {needsRecovery(item) && (
              <div className={`${styles.taskControls} ${styles.inlineActions}`}>
                <button
                  type="button"
                  disabled={!ready || cloud.working}
                  onClick={() => void cloud.act('cloud.recover', item.requestId)}
                >
                  <ArrowClockwise size={16} aria-hidden="true" />
                  恢复查询
                </button>
              </div>
            )}
            {canStop(item) && (
              <div className={`${styles.taskControls} ${styles.inlineActions}`}>
                <button
                  type="button"
                  disabled={!ready || cloud.working}
                  onClick={() => void cloud.act('cloud.cancel', item.requestId)}
                >
                  <Stop size={16} aria-hidden="true" />
                  <span>停止生成</span>
                </button>
              </div>
            )}
            {item.pendingControl && (
              <p className={styles.turnStatus}>
                {item.pendingControl === 'withdraw'
                  ? '本机回答已隐藏；'
                  : item.confirmedControl === 'cancel'
                    ? '停止请求已确认；'
                    : '正在停止生成；'}
                {item.confirmedControl === item.pendingControl
                  ? '云端已确认控制请求。'
                  : '云端控制请求待确认，可恢复查询。'}
              </p>
            )}
            {(item.answer || item.draftAnswer) && item.pendingControl !== 'withdraw' && (
              <section
                className={`${styles.assistantAnswer} ${styles.agentReadingArea} ${styles.messageAssistantGroup}`}
                aria-label={item.answer ? '流萤的回答' : '流萤正在回答'}
                aria-busy={!item.answer}
              >
                <span className={styles.messageSenderLabel} aria-hidden="true">
                  流萤
                </span>
                <div
                  className={styles.messageAssistantBubble}
                  data-testid="message-assistant-bubble"
                >
                  <p className={styles.message}>{item.answer ?? item.draftAnswer}</p>
                </div>
                {item.answer && (
                  <div className={styles.answerToolbar} role="group" aria-label="回答操作">
                    <button
                      type="button"
                      title="复制回答"
                      aria-label="复制回答"
                      onClick={() => {
                        const text = item.answer;
                        if (!text) return;
                        void (async () => {
                          try {
                            await navigator.clipboard.writeText(text);
                            setCopyNotice({ id: item.requestId, ok: true });
                          } catch {
                            setCopyNotice({ id: item.requestId, ok: false });
                          }
                        })();
                      }}
                    >
                      {copyNotice?.id === item.requestId && copyNotice.ok ? (
                        <Check size={18} />
                      ) : (
                        <Copy size={18} />
                      )}
                    </button>
                    <button
                      type="button"
                      title={
                        speech?.requestId === item.requestId && speech.state !== 'idle'
                          ? '停止播放'
                          : '播放回答'
                      }
                      aria-label={
                        speech?.requestId === item.requestId && speech.state !== 'idle'
                          ? '停止播放'
                          : '播放回答'
                      }
                      disabled={
                        !ready ||
                        (!speech?.available &&
                          !(speech?.requestId === item.requestId && speech.state !== 'idle'))
                      }
                      onClick={() =>
                        void cloud.speak(
                          speech?.requestId === item.requestId && speech.state !== 'idle'
                            ? undefined
                            : item.requestId,
                        )
                      }
                    >
                      {speech?.requestId === item.requestId && speech.state === 'preparing' ? (
                        <CircleNotch size={18} aria-hidden="true" />
                      ) : speech?.requestId === item.requestId && speech.state === 'playing' ? (
                        <Stop size={18} aria-hidden="true" />
                      ) : (
                        <SpeakerHigh size={18} aria-hidden="true" />
                      )}
                    </button>
                    {speech?.requestId === item.requestId && speech.error && (
                      <span role="status">{speech.error}</span>
                    )}
                    {copyNotice?.id === item.requestId && (
                      <span role="status">
                        {copyNotice.ok ? '已复制' : '复制失败，请选中文字复制'}
                      </span>
                    )}
                  </div>
                )}
                {item.sources && (
                  <div className={`${styles.messageMeta} ${styles.sourceDisclosure}`}>
                    <section className={styles.sourceContent} aria-label="知识来源">
                      {item.sources.length === 0 ? (
                        <p>本次没有可用的匹配来源。</p>
                      ) : (
                        <ul>
                          {item.sources.map((source, index) => (
                            <li key={source.citationId} className={styles.sourceItem}>
                              <details className={styles.inlineSource}>
                                <summary aria-label={'查看来源 ' + (index + 1)}>
                                  {index + 1}
                                </summary>
                                <strong>{source.title}</strong>
                                <p className={styles.sourceUrl}>
                                  来源地址（可选中复制）：{source.url}
                                </p>
                                <small>
                                  发布：{source.publishedAt ?? '未提供'} · 核验：
                                  {new Date(source.checkedAtMillis)
                                    .toISOString()
                                    .slice(0, 10)} · {source.citationId}
                                </small>
                              </details>
                            </li>
                          ))}
                        </ul>
                      )}
                    </section>
                  </div>
                )}
              </section>
            )}
            {(item.status !== 'completed' || !item.attempted || item.busy || item.error) && (
              <ol className={styles.executionSteps} aria-label="任务执行状态">
                <li>本次请求 · {item.attempted ? '已尝试提交' : '尚未发送'}</li>
                <li>
                  <p className={styles.turnStatus} role="status">
                    {item.busy
                      ? item.draftAnswer
                        ? '流萤正在回答…'
                        : '正在与云端通信…'
                      : !item.attempted
                        ? '尚未发送，不会自动补发'
                        : item.error && ['unconfirmed', 'queued', 'running'].includes(item.status)
                          ? '查询已中断，结果待同步'
                          : labels[item.status]}
                  </p>
                </li>
              </ol>
            )}
            {!item.answer && item.status === 'completed' && item.pendingControl !== 'withdraw' && (
              <p className={styles.turnNotice}>
                云端任务已完成，但本机没有可显示的回答；结果可能已过期或已被确认清除。
              </p>
            )}
            {item.memorySaved &&
              item.pendingControl !== 'withdraw' &&
              item.confirmedControl !== 'withdraw' &&
              item.resultState !== 'withdrawn' && (
                <Link
                  className={styles.memorySaved}
                  to="/memories"
                  aria-label="记忆已保存，管理记忆"
                >
                  记忆已保存
                </Link>
              )}
            <details className={`${styles.messageMeta} ${styles.messageActions}`}>
              <summary>更多操作</summary>
              {item.reason && <p className={styles.actionDetail}>原因：{item.reason}</p>}
              {item.knowledge && (
                <p className={styles.actionDetail}>本次知识库：{knowledgeLabel(item.knowledge)}</p>
              )}
              {item.runSummary &&
                item.pendingControl !== 'withdraw' &&
                item.resultState !== 'withdrawn' && <AgentRunSummary summary={item.runSummary} />}
              {item.memoryRefs.length > 0 && (
                <details>
                  <summary>本次授权提供的记忆（不代表模型全部采用）</summary>
                  <ul className={styles.memoryRefs}>
                    {item.memoryRefs.map((ref) => (
                      <li key={ref.id}>
                        {ref.id} · 版本 {ref.version}
                      </li>
                    ))}
                  </ul>
                </details>
              )}
              <div className={styles.taskControls}>
                {!needsRecovery(item) && (
                  <button
                    type="button"
                    disabled={!ready || cloud.working || item.busy || !item.attempted}
                    onClick={() => void cloud.act('cloud.recover', item.requestId)}
                  >
                    恢复查询
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
