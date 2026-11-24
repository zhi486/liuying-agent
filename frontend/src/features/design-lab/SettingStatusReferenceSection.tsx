import { ArrowsClockwise, CheckCircle, CircleNotch } from '@phosphor-icons/react';
import { useEffect, useState } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type StatusVariant = 'badge' | 'text' | 'icon';
type DemoState = 'ready' | 'loading' | 'error';

const variants: { id: StatusVariant; letter: string; name: string; description: string }[] = [
  {
    id: 'badge',
    letter: 'A',
    name: '紧凑状态色标',
    description: '右值收进柔和底色胶囊，状态一眼可扫，数量与金额仍显示为纯文字。',
  },
  {
    id: 'text',
    letter: 'B',
    name: '无底色文字',
    description: '所有状态都沿用正文色阶，最安静；忙碌时只用文字变化表达。',
  },
  {
    id: 'icon',
    letter: 'C',
    name: '图标加状态',
    description: '只给授权、同步等语义状态加图标，数字型右值保持纯文本。',
  },
];

function StatusValue({
  variant,
  state,
  value,
}: {
  variant: StatusVariant;
  state: DemoState;
  value: string;
}) {
  if (state === 'loading') {
    if (variant === 'icon')
      return (
        <span className={styles.settingStatusValue}>
          <CircleNotch size={14} className={styles.settingStatusSpin} aria-hidden="true" />
          同步中
        </span>
      );
    if (variant === 'badge')
      return (
        <span className={styles.settingStatusBadge} data-tone="loading">
          同步中
        </span>
      );
    return (
      <span className={styles.settingStatusValue} data-tone="loading">
        同步中
      </span>
    );
  }
  if (state === 'error') {
    if (variant === 'icon')
      return (
        <span className={styles.settingStatusValue} data-tone="error">
          <ArrowsClockwise size={14} aria-hidden="true" />
          重试
        </span>
      );
    if (variant === 'badge')
      return (
        <span className={styles.settingStatusBadge} data-tone="error">
          连接失败
        </span>
      );
    return (
      <span className={styles.settingStatusValue} data-tone="error">
        连接失败
      </span>
    );
  }
  if (value === '已授权' && variant === 'icon')
    return (
      <span className={styles.settingStatusValue} data-tone="ready">
        <CheckCircle size={15} weight="fill" aria-hidden="true" />
        已授权
      </span>
    );
  if (value === '已授权' && variant === 'badge')
    return (
      <span className={styles.settingStatusBadge} data-tone="ready">
        已授权
      </span>
    );
  return <span className={styles.settingStatusValue}>{value}</span>;
}

function StatusPreview({
  variant,
  state,
  width,
  onActivate,
}: {
  variant: StatusVariant;
  state: DemoState;
  width: '393' | '320';
  onActivate: (name: string) => void;
}) {
  const rows = [
    { title: '聊天授权', description: '会话发送范围', value: '已授权' },
    { title: '长期记忆', description: '已保存事实', value: '12 条' },
    { title: 'MCP 插件', description: '同步工具状态', value: '同步中' },
    { title: '账单与用量', description: '本月模型调用', value: '¥12.40' },
  ];
  return (
    <div
      className={styles.settingStatusCanvas}
      data-width={width}
      data-variant={variant}
      data-state={state}
    >
      <h3>Agent 与数据</h3>
      <ul aria-label="Agent 与数据状态">
        {rows.map((row) => (
          <li key={row.title}>
            <button
              type="button"
              onClick={() => onActivate(row.title)}
              aria-label={
                row.title +
                '，' +
                (row.title === 'MCP 插件'
                  ? state === 'ready'
                    ? '同步中'
                    : state === 'loading'
                      ? '同步中'
                      : '连接失败'
                  : row.value)
              }
            >
              <span className={styles.settingStatusCopy}>
                <strong>{row.title}</strong>
                <small>{row.description}</small>
              </span>
              <StatusValue
                variant={variant}
                state={row.title === 'MCP 插件' ? state : 'ready'}
                value={row.value}
              />
            </button>
          </li>
        ))}
      </ul>
      <p>合成状态 · 点击行查看交互反馈</p>
    </div>
  );
}

export function SettingStatusReferenceSection({
  width,
  state,
}: {
  width: '393' | '320';
  state: 'default' | 'focus' | 'long' | 'saving';
}) {
  const [previewState, setPreviewState] = useState<DemoState>('ready');
  const [selected, setSelected] = useState<StatusVariant | null>(() => {
    try {
      const value = window.localStorage.getItem('healthy-agent:ui-reference:setting-status');
      return value === 'badge' || value === 'text' || value === 'icon' ? value : 'icon';
    } catch {
      return 'icon';
    }
  });
  const [feedback, setFeedback] = useState('当前为合成状态；点击行只显示预览反馈，不会更改设置。');

  useEffect(() => {
    if (state === 'saving') setPreviewState('loading');
    else if (state === 'default') setPreviewState('ready');
  }, [state]);

  const activate = (name: string) =>
    setFeedback('已点按“' + name + '”；此预览不会读取或修改真实设置。');
  const select = (variant: StatusVariant, name: string) => {
    setSelected(variant);
    try {
      window.localStorage.setItem('healthy-agent:ui-reference:setting-status', variant);
    } catch {
      setFeedback('已暂时选定“' + name + '”；本地偏好未能保存。');
      return;
    }
    setFeedback('已将“' + name + '”记为候选；尚未接入正式设置页。');
  };

  return (
    <section className={styles.variantsSection} aria-labelledby="setting-status-heading">
      <header className={styles.sectionHeading}>
        <h2 id="setting-status-heading">设置行状态值的三种表达</h2>
        <p>同一行结构、同一份合成设置；只比较右侧状态的颜色、图标和忙碌/错误反馈。</p>
      </header>
      <div className={styles.settingStatusVariants} data-testid="setting-status-previews">
        {variants.map((variant) => (
          <article className={styles.variantCard} key={variant.id}>
            <header className={styles.variantHeader}>
              <span className={styles.variantMark}>{variant.letter}</span>
              <div className={styles.variantHeading}>
                <h3>{variant.name}</h3>
                <p>{variant.description}</p>
              </div>
            </header>
            <StatusPreview
              variant={variant.id}
              state={previewState}
              width={width}
              onActivate={activate}
            />
            <dl className={styles.settingStatusFacts}>
              <div>
                <dt>常态</dt>
                <dd>
                  {variant.id === 'badge'
                    ? '色标 / 数值'
                    : variant.id === 'icon'
                      ? '图标只标语义状态'
                      : '统一文字色阶'}
                </dd>
              </div>
              <div>
                <dt>忙碌</dt>
                <dd>{variant.id === 'icon' ? '旋转图标 + 文案' : '替换为“同步中”'}</dd>
              </div>
              <div>
                <dt>窄屏</dt>
                <dd>状态列固定，不挤压标题</dd>
              </div>
            </dl>
            <button
              type="button"
              className={styles.settingStatusSelect}
              aria-pressed={selected === variant.id}
              onClick={() => select(variant.id, variant.name)}
            >
              {selected === variant.id
                ? variant.id === 'icon'
                  ? 'C 已接入真实状态与入口右值'
                  : '已选为候选基线'
                : '选择此方案'}
            </button>
          </article>
        ))}
      </div>
      <div className={styles.settingStatusControls} role="group" aria-label="设置状态预览">
        <button
          type="button"
          aria-pressed={previewState === 'ready'}
          onClick={() => setPreviewState('ready')}
        >
          常态
        </button>
        <button
          type="button"
          aria-pressed={previewState === 'loading'}
          onClick={() => setPreviewState('loading')}
        >
          同步中
        </button>
        <button
          type="button"
          aria-pressed={previewState === 'error'}
          onClick={() => setPreviewState('error')}
        >
          连接失败
        </button>
        <span>
          {width}px · {state === 'focus' ? '焦点状态' : '日夜主题跟随应用设置'}
        </span>
      </div>
      <p className={styles.feedback} role="status" aria-live="polite">
        {feedback}
      </p>
    </section>
  );
}
