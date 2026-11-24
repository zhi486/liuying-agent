import { ArrowClockwise, Check, Trash, X, Plus } from '@phosphor-icons/react';
import { useState } from 'react';
import shared from './ConsentPageReferenceSection.module.css';
import styles from './FinalStatesReferenceSection.module.css';

const groups = {
  loadingEmpty: {
    title: '加载与空状态',
    names: ['原位文字', '骨架占位', '状态面板'],
    description: 'A 保留列表上下文；B 预留内容结构；C 独立呈现状态与下一步。',
  },
  dangerConfirm: {
    title: '危险操作确认',
    names: ['原位二次确认', '底部确认', '输入名称确认'],
    description: 'A 在条目位置确认；B 在底部集中核对；C 输入名称后才开放操作。',
  },
} as const;
export type FinalStatesKind = keyof typeof groups;
function LoadingPreview({ variant }: { variant: string }) {
  const [phase, setPhase] = useState<'loading' | 'empty' | 'error' | 'ready'>('loading');
  return (
    <div className={styles.scene}>
      <header>
        <h4>跑步记录</h4>
        <span className={styles.muted}>{phase === 'ready' ? '1 条' : '—'}</span>
      </header>
      {phase === 'ready' ? (
        <div className={styles.record}>
          <strong>晨间跑步</strong>
          <p>5.02 公里 · 30:18</p>
          <small>10月28日 · 合成数据</small>
        </div>
      ) : phase === 'loading' ? (
        variant === 'B' ? (
          <div className={styles.skeletons} role="status" aria-label="正在加载记录">
            {[0, 1, 2].map((i) => (
              <div key={i}>
                <span />
                <span />
                <span />
              </div>
            ))}
            <p>正在读取本地记录…</p>
          </div>
        ) : (
          <section
            className={variant === 'C' ? styles.statePanel : styles.inlineState}
            role="status"
          >
            <ArrowClockwise size={variant === 'C' ? 28 : 17} />
            <h4>正在读取记录</h4>
            <p>尚未获得结果，不代表没有记录。</p>
          </section>
        )
      ) : (
        <section className={variant === 'C' ? styles.statePanel : styles.inlineState} role="status">
          <h4>{phase === 'error' ? '暂时无法读取' : '还没有跑步记录'}</h4>
          <p>
            {phase === 'error'
              ? '未清除已有记录，请重新尝试。'
              : '完成一次跑步后，会在这里保留记录。'}
          </p>
          <button onClick={() => setPhase('ready')}>
            {phase === 'error' ? <ArrowClockwise size={18} /> : <Plus size={18} />}
            {phase === 'error' ? '重新读取' : '体验一条示例'}
          </button>
        </section>
      )}
      <div className={styles.demoControls} role="group" aria-label="演示状态">
        {(['loading', 'empty', 'error'] as const).map((value, index) => (
          <button key={value} aria-pressed={phase === value} onClick={() => setPhase(value)}>
            {['加载中', '无记录', '读取失败'][index]}
          </button>
        ))}
      </div>
    </div>
  );
}
function DangerPreview({ variant }: { variant: string }) {
  const [confirm, setConfirm] = useState(false);
  const [removed, setRemoved] = useState(false);
  const [name, setName] = useState('');
  const content = (
    <section
      className={variant === 'B' ? styles.sheet : styles.confirmation}
      aria-label="删除演示项确认"
    >
      <header>
        <h4>删除「晨间跑步」？</h4>
        <button aria-label="关闭确认" onClick={() => setConfirm(false)}>
          <X size={18} />
        </button>
      </header>
      <p>仅移除当前预览中的合成条目，不影响手机或已保存记录。</p>
      {variant === 'C' && (
        <>
          <label htmlFor="confirm-demo-name">输入 晨间跑步</label>
          <input
            id="confirm-demo-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoComplete="off"
          />
        </>
      )}
      <div className={styles.actions}>
        <button
          onClick={() => {
            setConfirm(false);
            setName('');
          }}
        >
          保留记录
        </button>
        <button
          className={styles.danger}
          disabled={variant === 'C' && name !== '晨间跑步'}
          onClick={() => {
            setRemoved(true);
            setConfirm(false);
          }}
        >
          <Trash size={17} />
          删除演示项
        </button>
      </div>
    </section>
  );
  return (
    <div className={styles.scene}>
      <h4>记录管理</h4>
      {removed ? (
        <>
          <p className={styles.feedback} role="status">
            <Check size={18} />
            演示项已移除，真实记录未改变。
          </p>
          <button
            onClick={() => {
              setRemoved(false);
              setName('');
            }}
          >
            重置演示
          </button>
        </>
      ) : (
        <>
          <div className={styles.record}>
            <div>
              <strong>晨间跑步</strong>
              <p>5.02 公里 · 30:18</p>
              <small>合成记录</small>
            </div>
            <button
              aria-label="删除晨间跑步演示项"
              title="删除演示项"
              onClick={() => setConfirm(true)}
            >
              <Trash size={19} />
            </button>
          </div>
          {confirm && content}
          {!confirm && <p className={styles.muted}>记录删除后不可通过普通返回恢复。</p>}
        </>
      )}
    </div>
  );
}
export function FinalStatesReferenceSection({
  kind,
  width,
}: {
  kind: FinalStatesKind;
  width: '320' | '393';
}) {
  const [selected, setSelected] = useState<string | null>('B');
  const group = groups[kind];
  return (
    <section className={shared.section}>
      <h2>{group.title}的三种方案</h2>
      <p>{group.description}</p>
      <p>仅本地合成演示，不读取、修改或删除真实记录。</p>
      <div className={shared.variants}>
        {group.names.map((name, index) => {
          const id = ['A', 'B', 'C'][index];
          return (
            <article key={id} aria-label={id + ' ' + name}>
              <div className={shared.variantHeading}>
                <h3>
                  {id} · {name}
                </h3>
              </div>
              <div className={shared.phone + ' ' + styles.phone} data-width={width}>
                <header>
                  <strong>流萤</strong>
                  <span className={styles.muted}>演示数据</span>
                </header>
                {kind === 'loadingEmpty' ? (
                  <LoadingPreview variant={id} />
                ) : (
                  <DangerPreview variant={id} />
                )}
              </div>
              <button
                className={shared.choose}
                aria-pressed={selected === id}
                onClick={() => setSelected(id)}
              >
                {selected === id
                  ? id === 'B'
                    ? 'B 已接入正式页面'
                    : '当前预览候选'
                  : '预览 ' + id + ' 方案'}
              </button>
            </article>
          );
        })}
      </div>
    </section>
  );
}
