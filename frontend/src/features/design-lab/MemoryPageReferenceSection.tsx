import {
  ArrowLeft,
  Brain,
  CaretDown,
  MagnifyingGlass,
  PencilSimple,
  Plus,
  Trash,
  X,
} from '@phosphor-icons/react';
import { useState } from 'react';
import styles from './MemoryPageReferenceSection.module.css';

const variants = [
  { id: 'list', letter: 'A', name: '记忆清单', summary: '完整列表优先，适合快速查找与批量浏览。' },
  {
    id: 'groups',
    letter: 'B',
    name: '分组审阅 · B+C',
    summary: '紧凑分组清单，点击一条原位展开审阅；保留上下文，不再翻页。',
  },
  {
    id: 'review',
    letter: 'C',
    name: '逐条审阅',
    summary: '一次聚焦一条事实，适合仔细修改与确认。',
  },
] as const;
const initialRecords = [
  { id: 1, group: '跑步偏好', content: '轻松跑时更在意呼吸节奏，不追求配速。' },
  { id: 2, group: '跑步偏好', content: '平日通常在晚上跑步，周末可以安排长距离。' },
  { id: 3, group: '表达偏好', content: '建议简洁一些，先告诉我下一步该做什么。' },
  { id: 4, group: '跑步偏好', content: '长距离之后，第二天优先恢复。' },
  { id: 5, group: '跑步偏好', content: '训练建议中保留热身和放松环节。' },
  { id: 6, group: '表达偏好', content: '解释数据时带上单位，不用过多术语。' },
  { id: 7, group: '表达偏好', content: '不确定的结论请明确说明。' },
];

function MemoryPreview({
  variant,
  width,
}: {
  variant: (typeof variants)[number];
  width: '320' | '393';
}) {
  const [records, setRecords] = useState(initialRecords);
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  const [draft, setDraft] = useState('');
  const [deleting, setDeleting] = useState<number | null>(null);
  const [index, setIndex] = useState(0);
  const [notice, setNotice] = useState('');
  const [expanded, setExpanded] = useState<number | null>(1);
  const filtered = records.filter(
    (record) => record.content.includes(query) || record.group.includes(query),
  );
  const visible =
    variant.id === 'review'
      ? filtered.slice(
          Math.min(index, Math.max(0, filtered.length - 1)),
          Math.min(index, Math.max(0, filtered.length - 1)) + 1,
        )
      : filtered;
  function edit(id: number | 'new', content = '') {
    setEditing(id);
    setDraft(content);
    setDeleting(null);
  }
  function save() {
    if (!draft.trim()) return;
    setRecords((items) =>
      editing === 'new'
        ? [
            ...items,
            {
              id: Math.max(0, ...items.map((item) => item.id)) + 1,
              group: '表达偏好',
              content: draft.trim(),
            },
          ]
        : items.map((item) => (item.id === editing ? { ...item, content: draft.trim() } : item)),
    );
    setEditing(null);
    setNotice('演示内容已更新，未写入手机。');
  }
  function renderRecord(record: (typeof initialRecords)[number]) {
    return (
      <li key={record.id} className={styles.record}>
        {variant.id === 'groups' && (
          <button
            type="button"
            className={styles.recordSummary}
            aria-expanded={expanded === record.id}
            aria-controls={'memory-preview-detail-' + record.id}
            onClick={() => {
              setExpanded(expanded === record.id ? null : record.id);
              setDeleting(null);
            }}
          >
            <span>{record.content}</span>
            <CaretDown size={16} aria-hidden="true" />
          </button>
        )}
        <div
          id={variant.id === 'groups' ? 'memory-preview-detail-' + record.id : undefined}
          hidden={variant.id === 'groups' && expanded !== record.id}
          className={styles.recordDetail}
        >
          <small>{record.group} · 手动添加</small>
          <p>{record.content}</p>
          <div className={styles.actions}>
            <button
              type="button"
              aria-label={'编辑：' + record.content}
              title="编辑"
              onClick={() => edit(record.id, record.content)}
            >
              <PencilSimple size={18} />
            </button>
            <button
              type="button"
              aria-label={'删除：' + record.content}
              title="删除"
              onClick={() => {
                setDeleting(record.id);
                setEditing(null);
              }}
            >
              <Trash size={18} />
            </button>
          </div>
          {deleting === record.id && (
            <div className={styles.confirm}>
              <p>删除这条演示记忆？</p>
              <button
                type="button"
                onClick={() => {
                  setRecords((items) => items.filter((item) => item.id !== record.id));
                  setDeleting(null);
                  setNotice('演示记忆已删除，未修改手机数据。');
                }}
              >
                确认删除
              </button>
              <button type="button" onClick={() => setDeleting(null)}>
                取消
              </button>
            </div>
          )}
        </div>
      </li>
    );
  }
  return (
    <div className={styles.phone} data-width={width} data-variant={variant.id}>
      <header className={styles.header}>
        <button
          type="button"
          className={styles.back}
          onClick={() => setNotice('正式页面将返回设置；当前停留在设计预览。')}
        >
          <ArrowLeft size={16} />
          设置
        </button>
        <span>仅本机</span>
      </header>
      <div className={styles.title}>
        <Brain size={26} />
        <h3>长期记忆</h3>
        <span>{records.length} 条</span>
      </div>
      <p className={styles.scope}>由你保管，按授权用于回答。</p>
      <label className={styles.search}>
        <MagnifyingGlass size={18} aria-hidden="true" />
        <input
          aria-label="搜索记忆"
          placeholder="搜索记忆"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setIndex(0);
          }}
        />
      </label>
      <div className={styles.toolbar}>
        <strong>
          {variant.id === 'groups'
            ? '按主题整理'
            : variant.id === 'review'
              ? '逐条核对'
              : '已保存的事实'}
        </strong>
        <button type="button" onClick={() => edit('new')}>
          <Plus size={17} />
          新增
        </button>
      </div>
      {editing !== null && (
        <form
          className={styles.editor}
          onSubmit={(event) => {
            event.preventDefault();
            save();
          }}
        >
          <label>
            {editing === 'new' ? '新增记忆' : '编辑记忆'}
            <textarea
              autoFocus
              aria-label="记忆内容"
              value={draft}
              maxLength={500}
              onChange={(event) => setDraft(event.target.value)}
            />
          </label>
          <div className={styles.actions}>
            <button type="submit" disabled={!draft.trim()}>
              保存
            </button>
            <button type="button" onClick={() => setEditing(null)}>
              <X size={15} />
              取消
            </button>
          </div>
        </form>
      )}
      {filtered.length === 0 ? (
        <div className={styles.empty}>
          <Brain size={32} />
          <h4>{records.length ? '没有匹配的记忆' : '还没有记忆'}</h4>
          <p>{records.length ? '换个关键词再试试。' : '从一条希望被记住的偏好开始。'}</p>
        </div>
      ) : variant.id === 'groups' ? (
        <div className={styles.groups}>
          {['跑步偏好', '表达偏好'].map((group) => (
            <details key={group} open>
              <summary>
                {group}
                <span>{filtered.filter((record) => record.group === group).length} 条</span>
              </summary>
              <ul>{filtered.filter((record) => record.group === group).map(renderRecord)}</ul>
            </details>
          ))}
        </div>
      ) : (
        <ul className={styles.records}>{visible.map(renderRecord)}</ul>
      )}
      {variant.id === 'review' && filtered.length > 0 && (
        <div className={styles.pager}>
          <button
            type="button"
            disabled={index <= 0}
            onClick={() => setIndex((value) => value - 1)}
          >
            上一条
          </button>
          <span>
            {Math.min(index + 1, filtered.length)} / {filtered.length}
          </span>
          <button
            type="button"
            disabled={index >= filtered.length - 1}
            onClick={() => setIndex((value) => value + 1)}
          >
            下一条
          </button>
        </div>
      )}
      <p className={styles.notice} role="status">
        {notice}
      </p>
    </div>
  );
}

export function MemoryPageReferenceSection({ width }: { width: '320' | '393' }) {
  const [selected, setSelected] = useState<string | null>(null);
  return (
    <section className={styles.section} aria-labelledby="memory-reference-heading">
      <h2 id="memory-reference-heading">长期记忆页的三种布局</h2>
      <p>
        合成数据预览，不读取手机记忆，也不改变授权。主题分组与搜索在正式接入前需单独确认实现范围。
      </p>
      <div className={styles.variants}>
        {variants.map((variant) => (
          <article key={variant.id} aria-label={variant.letter + ' ' + variant.name}>
            <header className={styles.variantHeader}>
              <strong>
                {variant.letter} · {variant.name}
              </strong>
              <p>{variant.summary}</p>
            </header>
            <MemoryPreview variant={variant} width={width} />
            <button
              type="button"
              className={styles.choose}
              aria-pressed={selected === variant.id}
              onClick={() => setSelected(variant.id)}
            >
              {variant.id === 'groups'
                ? 'B 已确认并接入'
                : selected === variant.id
                  ? '当前候选'
                  : '选择 ' + variant.letter + ' 方案'}
            </button>
          </article>
        ))}
      </div>
    </section>
  );
}
