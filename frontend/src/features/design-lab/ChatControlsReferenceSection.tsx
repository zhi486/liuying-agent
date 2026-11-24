import {
  ArrowDown,
  ArrowUp,
  Copy,
  Check,
  DotsThree,
  BookOpen,
  X,
  CaretRight,
} from '@phosphor-icons/react';
import { useRef, useState } from 'react';
import shared from './ConsentPageReferenceSection.module.css';
import styles from './ChatControlsReferenceSection.module.css';

const groups = {
  answerActions: {
    title: '回答操作',
    note: '比较常驻操作、溢出菜单和底部操作面板。复制仅演示反馈，不写入剪贴板。',
    variants: ['常驻工具栏', '折叠菜单', '底部操作面板'],
  },
  latestMessage: {
    title: '回到最新消息',
    note: '比较圆形悬浮按钮、未读摘要和输入区上方的连续提示。点击只滚动合成消息。',
    variants: ['悬浮箭头', '未读摘要', '输入区提示'],
  },
  knowledgePicker: {
    title: '知识库选择',
    note: '比较直接切换、目录选择和分步核对。选择只保存在当前演示，不授权上传。',
    variants: ['横向切换', '目录面板', '分步核对'],
  },
} as const;
export type ChatControlsKind = keyof typeof groups;
function AnswerActions({ variant }: { variant: string }) {
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const actions = (
    <>
      <button aria-label="复制回答" title="复制回答" onClick={() => setCopied(true)}>
        {copied ? <Check size={19} /> : <Copy size={19} />}
        {variant !== 'A' && '复制回答'}
      </button>
      <button
        onClick={() => {
          setOpen(false);
          setCopied(false);
        }}
      >
        关闭操作
      </button>
    </>
  );
  return (
    <div className={styles.scene}>
      <div className={styles.message}>帮我总结这次跑步。</div>
      <p>这次完成 5.02 公里，用时 30:18。后半程的配速比前半程更快。</p>
      <p>五个完整分段均已保存，心率采集缺失时不推测数值。</p>
      {variant === 'A' ? (
        <div className={styles.toolbar}>{actions}</div>
      ) : (
        <button
          className={styles.more}
          aria-label="回答操作"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          <DotsThree size={24} />
          {variant === 'C' && '操作'}
        </button>
      )}
      {open && (
        <section className={variant === 'B' ? styles.menu : styles.sheet} aria-label="回答操作面板">
          <header>
            <strong>本条回答</strong>
            <button aria-label="关闭操作面板" onClick={() => setOpen(false)}>
              <X size={18} />
            </button>
          </header>
          {actions}
        </section>
      )}
      <p className={styles.meta} role="status">
        {copied ? '演示：已复制，未写入剪贴板。' : ''}
      </p>
    </div>
  );
}
function LatestMessage({ variant }: { variant: string }) {
  const area = useRef<HTMLDivElement>(null);
  const [latest, setLatest] = useState(false);
  const jump = () => {
    const element = area.current;
    if (element) element.scrollTop = element.scrollHeight;
    setLatest(true);
  };
  return (
    <div className={styles.scene}>
      <div
        className={styles.messages}
        ref={area}
        tabIndex={0}
        role="region"
        aria-label="合成消息列表"
        onScroll={() => {
          const element = area.current;
          if (element && element.scrollHeight - element.scrollTop - element.clientHeight > 30)
            setLatest(false);
        }}
      >
        {[
          '这周想跑三次。',
          '先看看最近一次记录。',
          '最近一次跑了 5.02 公里。',
          '每公里分段已整理。',
          '已找到两条可核对的来源。',
          '最新消息：本次摘要已完成。',
        ].map((text, index) => (
          <p key={text} className={index % 2 === 0 ? styles.message : undefined}>
            {text}
          </p>
        ))}
      </div>
      {!latest && (
        <button
          aria-label="回到最新消息"
          className={
            variant === 'A' ? styles.jumpOrb : variant === 'B' ? styles.unread : styles.jumpBar
          }
          onClick={jump}
        >
          <ArrowDown size={19} />
          {variant === 'B' ? (
            <span>
              <strong>2 条新消息</strong>
              <small>摘要与来源已整理</small>
            </span>
          ) : variant === 'C' ? (
            '查看最新回答 · 2 条'
          ) : null}
        </button>
      )}
      <p className={styles.meta} role="status">
        {latest ? '已到最新消息' : ''}
      </p>
      <div className={styles.composer}>
        发消息
        <ArrowUp size={19} />
      </div>
    </div>
  );
}
const libraries = ['不使用知识库', '运动健康', '流萤官方资料'];
function KnowledgePicker({ variant }: { variant: string }) {
  const [choice, setChoice] = useState(0);
  const [open, setOpen] = useState(variant === 'B');
  const [step, setStep] = useState(0);
  const [confirmed, setConfirmed] = useState(false);
  const choices = (
    <div
      className={variant === 'A' ? styles.segments : styles.catalogue}
      role="group"
      aria-label="选择知识库"
    >
      {libraries.map((label, index) => (
        <button
          key={label}
          aria-pressed={choice === index}
          onClick={() => {
            setChoice(index);
            setConfirmed(false);
          }}
        >
          <BookOpen size={18} />
          <span>
            {label}
            {variant !== 'A' && (
              <small>
                {index === 0
                  ? '仅使用本次消息'
                  : index === 1
                    ? '公共运动健康资料'
                    : '官方角色与世界观资料'}
              </small>
            )}
          </span>
          {variant !== 'A' && choice === index && <Check size={18} />}
        </button>
      ))}
    </div>
  );
  return (
    <div className={styles.scene}>
      <div className={styles.message}>这次回答使用哪些资料？</div>
      {variant === 'A' ? (
        <>
          <h4>本次资料</h4>
          {choices}
          <p className={styles.meta}>仅影响本次消息</p>
        </>
      ) : variant === 'B' ? (
        <>
          <button
            className={styles.directoryTrigger}
            onClick={() => setOpen(!open)}
            aria-expanded={open}
          >
            <BookOpen size={18} />
            {libraries[choice]}
            <CaretRight size={18} />
          </button>
          {open && (
            <section className={styles.directory}>
              <h4>资料目录</h4>
              {choices}
              <button
                onClick={() => {
                  setConfirmed(true);
                  setOpen(false);
                }}
              >
                选用此资料
              </button>
            </section>
          )}
        </>
      ) : (
        <section className={styles.wizard}>
          <p className={styles.meta}>步骤 {step + 1} / 2</p>
          <h4>{step === 0 ? '选择资料范围' : '核对本次选择'}</h4>
          {step === 0 ? (
            choices
          ) : (
            <>
              <BookOpen size={32} />
              <h4>{libraries[choice]}</h4>
              <p>仅供本次演示。不上传消息，不新增工具权限。</p>
            </>
          )}
          <div className={styles.toolbar}>
            {step === 1 && <button onClick={() => setStep(0)}>上一步</button>}
            <button onClick={() => (step === 0 ? setStep(1) : setConfirmed(true))}>
              {step === 0 ? '下一步' : '确认选择'}
            </button>
          </div>
        </section>
      )}
      <p role="status" className={styles.meta}>
        {confirmed ? '演示已选择：' + libraries[choice] : ''}
      </p>
    </div>
  );
}
export function ChatControlsReferenceSection({
  kind,
  width,
}: {
  kind: ChatControlsKind;
  width: '320' | '393';
}) {
  const [selected, setSelected] = useState<string | null>('A');
  const group = groups[kind];
  return (
    <section className={shared.section}>
      <h2>{group.title}的三种方案</h2>
      <p>A 已接入正式聊天页。</p>
      <p>{group.note}</p>
      <div className={shared.variants}>
        {group.variants.map((name, index) => {
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
                  <span className={styles.meta}>演示数据</span>
                </header>
                {kind === 'answerActions' ? (
                  <AnswerActions variant={id} />
                ) : kind === 'latestMessage' ? (
                  <LatestMessage variant={id} />
                ) : (
                  <KnowledgePicker variant={id} />
                )}
              </div>
              <button
                className={shared.choose}
                aria-pressed={selected === id}
                onClick={() => setSelected(id)}
              >
                {id === 'A'
                  ? 'A 已接入正式页面'
                  : selected === id
                    ? '当前候选，尚未接入'
                    : '选择 ' + id + ' 方案'}
              </button>
            </article>
          );
        })}
      </div>
    </section>
  );
}
