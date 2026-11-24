import { ArrowLeft, CaretDown, Receipt, ArrowClockwise } from '@phosphor-icons/react';
import { useState } from 'react';
import styles from './UsagePageReferenceSection.module.css';

const variants = [
  {
    id: 'ledger',
    letter: 'A',
    name: '紧凑流水',
    description: '问题摘要与金额对齐，展开查看用量，适合快速查账。',
  },
  {
    id: 'split',
    letter: 'B',
    name: '费用与用量',
    description: '费用、Token 分开浏览，减少同屏干扰，突出数字比较。',
  },
  {
    id: 'receipt',
    letter: 'C',
    name: '逐笔回执',
    description: '每笔记录完整展开，费用依据与待确认项放在一起。',
  },
] as const;
const records = [
  {
    id: 1,
    title: '今晚轻松跑，怎样安排节奏？',
    input: 1240,
    output: 386,
    micros: 1420,
    complete: true,
    rate: '参考费率',
  },
  {
    id: 2,
    title: '复盘周末的长距离训练',
    input: 2860,
    output: 612,
    micros: 2740,
    complete: true,
    rate: '保存时费率',
  },
  {
    id: 3,
    title: '跑后恢复需要注意什么？',
    input: 980,
    output: 0,
    micros: null,
    complete: false,
    rate: '费率待确认',
  },
];
const money = (micros: number | null) =>
  micros === null ? '待确认' : '$' + (micros / 1_000_000).toFixed(6);

function UsagePreview({
  variant,
  width,
}: {
  variant: (typeof variants)[number];
  width: '320' | '393';
}) {
  const [tab, setTab] = useState<'cost' | 'tokens'>('cost');
  const [expanded, setExpanded] = useState<number | null>(1);
  const [state, setState] = useState<'ready' | 'empty' | 'error'>('ready');
  const [notice, setNotice] = useState('');
  return (
    <div className={styles.phone} data-width={width} data-variant={variant.id}>
      <header className={styles.header}>
        <button
          type="button"
          className={styles.back}
          onClick={() => setNotice('正式页面将返回设置，当前为合成预览。')}
        >
          <ArrowLeft size={16} />
          设置
        </button>
        <span>本机回执</span>
      </header>
      <div className={styles.title}>
        <Receipt size={25} aria-hidden="true" />
        <h3>账单与用量</h3>
      </div>
      <p className={styles.scope}>
        当前会话 · 最近 10 条以内
        <br />
        不是完整账单或账户余额
      </p>
      <section className={styles.overview} aria-label="已知费用小计">
        <span>已知估算小计 · USD</span>
        <strong>{state === 'ready' ? '$0.004160' : '—'}</strong>
        <small>
          {state === 'ready' ? '2 笔有估算 · 1 笔待确认' : '尚无可汇总回执'}
          <br />
          实际扣费以供应商账单为准
        </small>
      </section>
      <div className={styles.listHeading}>
        <h4>轻松跑与恢复</h4>
        <button
          type="button"
          title="刷新本机回执"
          aria-label="刷新本机回执"
          onClick={() => {
            setState('ready');
            setNotice('演示回执已刷新，没有读取真实账单。');
          }}
        >
          <ArrowClockwise size={18} />
        </button>
      </div>
      {variant.id === 'split' && (
        <div className={styles.tabs} role="group" aria-label="回执视图">
          <button type="button" aria-pressed={tab === 'cost'} onClick={() => setTab('cost')}>
            估算费用
          </button>
          <button type="button" aria-pressed={tab === 'tokens'} onClick={() => setTab('tokens')}>
            Token 用量
          </button>
        </div>
      )}
      {state === 'empty' ? (
        <p className={styles.empty}>暂无可展示的用量记录。</p>
      ) : state === 'error' ? (
        <div className={styles.empty} role="alert">
          <p>暂时无法读取本机回执。</p>
          <button
            type="button"
            onClick={() => {
              setState('ready');
              setNotice('演示读取已恢复，没有重新发送问题。');
            }}
          >
            重新读取
          </button>
        </div>
      ) : (
        <ul className={styles.records}>
          {records.map((record) => (
            <li key={record.id}>
              <button
                type="button"
                className={styles.row}
                aria-expanded={variant.id === 'receipt' || expanded === record.id}
                aria-controls={'usage-' + variant.id + '-' + record.id}
                onClick={() => setExpanded(expanded === record.id ? null : record.id)}
              >
                <span className={styles.copy}>
                  <strong>{record.title}</strong>
                  <small>{record.complete ? '用量已确认' : '用量不完整，不代表零费用'}</small>
                </span>
                <span className={styles.value}>
                  {variant.id === 'split' && tab === 'tokens'
                    ? (record.input + record.output).toLocaleString('en-US')
                    : money(record.micros)}
                  {variant.id === 'split' && tab === 'tokens' && <small>已知 token</small>}
                </span>
                {variant.id !== 'receipt' && <CaretDown size={14} aria-hidden="true" />}
              </button>
              <div
                id={'usage-' + variant.id + '-' + record.id}
                hidden={variant.id !== 'receipt' && expanded !== record.id}
                className={styles.detail}
              >
                <dl>
                  <div>
                    <dt>输入 token</dt>
                    <dd>{record.input.toLocaleString('en-US')}</dd>
                  </div>
                  <div>
                    <dt>输出 token</dt>
                    <dd>{record.complete ? record.output.toLocaleString('en-US') : '待确认'}</dd>
                  </div>
                  <div>
                    <dt>费率依据</dt>
                    <dd>{record.rate}</dd>
                  </div>
                </dl>
                {!record.complete && <p>不纳入已知估算小计，仍可能产生费用。</p>}
              </div>
            </li>
          ))}
        </ul>
      )}
      <p className={styles.footnote}>仅展示已保存回执，不重新执行任务。参考费率并非实时价格。</p>
      <div className={styles.demoControls} role="group" aria-label="合成状态">
        <button type="button" aria-pressed={state === 'ready'} onClick={() => setState('ready')}>
          有记录
        </button>
        <button type="button" aria-pressed={state === 'empty'} onClick={() => setState('empty')}>
          空记录
        </button>
        <button type="button" aria-pressed={state === 'error'} onClick={() => setState('error')}>
          读取失败
        </button>
      </div>
      <p className={styles.notice} role="status">
        {notice}
      </p>
    </div>
  );
}

export function UsagePageReferenceSection({ width }: { width: '320' | '393' }) {
  const [selected, setSelected] = useState<string | null>('split');
  return (
    <section className={styles.section} aria-labelledby="usage-reference-heading">
      <h2 id="usage-reference-heading">账单与用量页的三种布局</h2>
      <p>三案使用同一份合成回执；不查询供应商、不调用模型。小计只包含已知估算，不含待确认金额。</p>
      <div className={styles.variants}>
        {variants.map((variant) => (
          <article key={variant.id} aria-label={variant.letter + ' ' + variant.name}>
            <header className={styles.variantHeader}>
              <h3>
                {variant.letter} · {variant.name}
              </h3>
              <p>{variant.description}</p>
            </header>
            <UsagePreview variant={variant} width={width} />
            <button
              type="button"
              className={styles.choose}
              aria-pressed={selected === variant.id}
              onClick={() => setSelected(variant.id)}
            >
              {variant.id === 'split'
                ? 'B 已接入正式页面'
                : selected === variant.id
                  ? '对比候选，正式页仍为 B'
                  : '比较 ' + variant.letter + ' 方案'}
            </button>
          </article>
        ))}
      </div>
    </section>
  );
}
