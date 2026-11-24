import {
  ArrowClockwise,
  ArrowUp,
  Check,
  CaretDown,
  BookOpen,
  PencilSimple,
  Wrench,
  Warning,
} from '@phosphor-icons/react';
import { useState } from 'react';
import shared from './ConsentPageReferenceSection.module.css';
import styles from './AgentFeedbackReferenceSection.module.css';

const groups = {
  toolFeedback: {
    title: '工具执行状态',
    lesson: '渐进披露：先显示摘要，需要时再展开过程。',
    variants: [
      ['A', '单行摘要'],
      ['B', '步骤清单'],
      ['C', '结果优先'],
    ],
  },
  sourceFeedback: {
    title: '来源引用',
    lesson: '引用锚点：让结论和证据对应；比较原位展开、集中清单和逐条审阅。',
    variants: [
      ['A', '行内引用'],
      ['B', '底部清单'],
      ['C', '逐条审阅'],
    ],
  },
  recoveryFeedback: {
    title: '发送失败与恢复',
    lesson: '原位恢复：在问题发生处处理并保留原文；编辑重发允许先修改再尝试。',
    variants: [
      ['A', '原位重试'],
      ['B', '底部提示'],
      ['C', '编辑重发'],
    ],
  },
} as const;
export type AgentFeedbackKind = keyof typeof groups;
const sources = [
  ['跑步记录 · 10月28日', '5.02 公里 · 30:18 · 平均配速 6:02'],
  ['每公里分段', '6:12 → 6:06 → 6:00 → 5:58 → 5:54'],
];
function ToolPreview({ variant }: { variant: string }) {
  const [complete, setComplete] = useState(variant === 'C');
  const [open, setOpen] = useState(variant === 'B');
  return (
    <>
      <div className={styles.message}>帮我整理今天的跑步记录。</div>
      {variant === 'C' && <h4>{complete ? '找到 1 条跑步记录' : '正在查找今天的记录'}</h4>}
      {variant === 'B' && (
        <div className={styles.timelineHeading}>
          <Wrench size={24} />
          <div>
            <strong>本次执行</strong>
            <p>读取记录 → 整理分段 → 生成摘要</p>
          </div>
        </div>
      )}
      {variant === 'C' && (
        <dl className={styles.report}>
          <div>
            <dt>读取记录</dt>
            <dd>
              {complete ? '1' : '—'} <small>条</small>
            </dd>
          </div>
          <div>
            <dt>完整分段</dt>
            <dd>
              {complete ? '5' : '—'} <small>段</small>
            </dd>
          </div>
          <div>
            <dt>读取范围</dt>
            <dd>今天</dd>
          </div>
        </dl>
      )}
      {variant !== 'B' && (
        <button className={styles.disclosure} aria-expanded={open} onClick={() => setOpen(!open)}>
          {complete ? <Check size={18} /> : <Wrench size={18} />}
          <span>
            {variant === 'C' ? '查看执行过程' : complete ? '记录已读取' : '正在读取跑步记录'}
          </span>
          <CaretDown size={16} />
        </button>
      )}
      {open && (
        <ol className={variant === 'B' ? styles.steps : styles.process}>
          {['读取本地记录', '整理距离与分段', '生成本次摘要'].map((label, index) => (
            <li key={label}>
              <span>{label}</span>
              <small>{complete || index === 0 ? '已完成' : '等待'}</small>
            </li>
          ))}
        </ol>
      )}
      {complete && (
        <p>
          今天跑了 <strong>5.02 公里</strong>，用时 30:18。五个完整分段的配速由 6:12 逐渐提升到
          5:54。
        </p>
      )}
      <p className={styles.meta}>本地记录 · 只读</p>
      <button className={styles.demo} onClick={() => setComplete(!complete)}>
        <ArrowClockwise size={16} />
        {complete ? '重新演示' : '演示完成'}
      </button>
    </>
  );
}
function SourcePreview({ variant }: { variant: string }) {
  const [active, setActive] = useState<number | null>(variant === 'C' ? 0 : null);
  return (
    <>
      <div className={styles.message}>这次跑步的数据依据是什么？</div>
      {variant !== 'C' &&
        ['本次跑步 5.02 公里，用时 30:18。', '五个完整分段的配速逐渐加快。'].map((text, index) => (
          <p key={text}>
            {text}
            {variant === 'A' && (
              <button
                className={styles.citation}
                aria-expanded={active === index}
                onClick={() => setActive(active === index ? null : index)}
                aria-label={'查看来源 ' + (index + 1)}
              >
                {index + 1}
              </button>
            )}
          </p>
        ))}
      {variant === 'B' && (
        <>
          <h4>
            来源目录 <small>2 条记录</small>
          </h4>
          <div className={styles.list}>
            {sources.map(([title], index) => (
              <button
                key={title}
                aria-expanded={active === index}
                onClick={() => setActive(active === index ? null : index)}
              >
                <BookOpen size={17} />
                <span>{title}</span>
                <CaretDown size={16} />
              </button>
            ))}
          </div>
        </>
      )}
      {variant === 'C' && (
        <>
          <div className={styles.readerHeading}>
            <BookOpen size={24} />
            <h4>证据审阅</h4>
            <span>{(active ?? 0) + 1} / 2</span>
          </div>
          <p className={styles.meta}>对应结论</p>
          <blockquote>
            {active === 1 ? '五个完整分段的配速逐渐加快。' : '本次跑步 5.02 公里，用时 30:18。'}
          </blockquote>
          <div className={styles.actions} role="group" aria-label="来源切换">
            {sources.map((_, index) => (
              <button key={index} aria-pressed={active === index} onClick={() => setActive(index)}>
                来源 {index + 1}
              </button>
            ))}
          </div>
        </>
      )}
      {active !== null && (
        <section className={styles.evidence} aria-label="来源内容">
          <h4>{sources[active][0]}</h4>
          <p>{sources[active][1]}</p>
          <p className={styles.meta}>合成记录 · 不读取真实数据</p>
        </section>
      )}
    </>
  );
}
function RecoveryPreview({ variant }: { variant: string }) {
  const [state, setState] = useState<'failed' | 'editing' | 'sent'>('failed');
  const [draft, setDraft] = useState('帮我整理今天的跑步记录。');
  return (
    <div className={styles.recovery}>
      <div className={styles.message}>{draft}</div>
      {state === 'sent' ? (
        <p role="status" className={styles.feedback}>
          <Check size={18} />
          演示发送成功，未调用模型。
        </p>
      ) : (
        <>
          {variant !== 'B' && (
            <p role="status" className={styles.feedback}>
              <Warning size={17} />
              发送失败，原文已保留。
            </p>
          )}
          {variant === 'A' && (
            <div className={styles.actions}>
              <button onClick={() => setState('sent')}>
                <ArrowClockwise size={17} />
                重试
              </button>
              <button onClick={() => setState('editing')}>
                <PencilSimple size={17} />
                编辑
              </button>
            </div>
          )}
          {variant === 'C' && state !== 'editing' && (
            <div className={styles.draftLanding}>
              <PencilSimple size={28} />
              <h4>草稿已保留</h4>
              <p>修改消息后重新发送。</p>
              <button onClick={() => setState('editing')}>
                <PencilSimple size={17} />
                编辑后重发
              </button>
            </div>
          )}
          {state === 'editing' && (
            <div className={variant === 'C' ? styles.fullEditor : styles.editor}>
              {variant === 'C' && <h4>编辑消息</h4>}
              <label htmlFor={'draft-' + variant}>消息草稿</label>
              <textarea
                id={'draft-' + variant}
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
              />
              <div className={styles.actions}>
                <button onClick={() => setState('failed')}>取消</button>
                <button disabled={!draft.trim()} onClick={() => setState('sent')}>
                  <ArrowUp size={17} />
                  重新发送
                </button>
              </div>
            </div>
          )}
          {variant === 'B' && (
            <div className={styles.bottom}>
              <Warning size={18} />
              <div>
                <strong>消息未发送</strong>
                <p>内容已保留，可重新尝试。</p>
              </div>
              <button title="重试发送" aria-label="重试发送" onClick={() => setState('sent')}>
                <ArrowClockwise size={19} />
              </button>
            </div>
          )}
        </>
      )}
      {!(variant === 'C' && state === 'editing') && (
        <div className={styles.composer}>
          <span>发消息</span>
          <button aria-label="发送" disabled>
            <ArrowUp size={18} />
          </button>
        </div>
      )}
      {state === 'sent' && (
        <button className={styles.demo} onClick={() => setState('failed')}>
          <ArrowClockwise size={16} />
          重置演示
        </button>
      )}
    </div>
  );
}
export function AgentFeedbackReferenceSection({
  kind,
  width,
}: {
  kind: AgentFeedbackKind;
  width: '320' | '393';
}) {
  const baseline = kind === 'toolFeedback' ? 'B' : 'A';
  const [selected, setSelected] = useState<string | null>(baseline);
  const group = groups[kind];
  return (
    <section className={shared.section}>
      <h2>{group.title}的三种方案</h2>
      <p>已接入正式页：{baseline}。任务仅展示真实已知状态；来源按消息关联；恢复不重新发送。</p>
      <p>{group.lesson}</p>
      <p>仅合成数据与本地状态，不调用工具、模型或真实重试，不修改正式应用。</p>
      <div className={shared.variants}>
        {group.variants.map(([id, name]) => (
          <article key={id} aria-label={id + ' ' + name}>
            <div className={shared.variantHeading}>
              <h3>
                {id} · {name}
              </h3>
            </div>
            <div
              className={shared.phone + ' ' + styles.phone}
              data-width={width}
              data-variant={id}
              data-kind={kind}
            >
              <header>
                <strong>流萤</strong>
                <span className={styles.meta}>演示数据</span>
              </header>
              {kind === 'toolFeedback' ? (
                <ToolPreview variant={id} />
              ) : kind === 'sourceFeedback' ? (
                <SourcePreview variant={id} />
              ) : (
                <RecoveryPreview variant={id} />
              )}
            </div>
            <button
              className={shared.choose}
              aria-pressed={selected === id}
              onClick={() => setSelected(id)}
            >
              {selected === id
                ? id === baseline
                  ? '已接入正式页面'
                  : '当前候选，尚未接入'
                : '选择 ' + id + ' 方案'}
            </button>
          </article>
        ))}
      </div>
    </section>
  );
}
