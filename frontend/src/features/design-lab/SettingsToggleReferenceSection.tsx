import { Check, Moon, Sun } from '@phosphor-icons/react';
import { useState } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type ToggleVariant = 'switch' | 'icon' | 'label';

const variants: {
  id: ToggleVariant;
  letter: string;
  name: string;
  description: string;
}[] = [
  {
    id: 'switch',
    letter: 'A',
    name: '纯滑块',
    description: '保留系统熟悉的双态开关，信息密度最低，适合列表中的常用偏好。',
  },
  {
    id: 'icon',
    letter: 'B',
    name: '图标滑块',
    description: '滑块内部用月亮和太阳提示关闭/开启，状态仍由位置与读屏语义共同表达。',
  },
  {
    id: 'label',
    letter: 'C',
    name: '状态文字',
    description: '开关旁同步显示“已开启/已关闭”，确认度最高，也最占横向空间。',
  },
];

function TogglePreview({
  variant,
  enabled,
  onToggle,
}: {
  variant: ToggleVariant;
  enabled: boolean;
  onToggle: () => void;
}) {
  return (
    <div className={styles.settingsToggleCanvas} data-variant={variant} data-enabled={enabled}>
      <div className={styles.settingsToggleRow}>
        <span className={styles.settingsToggleIcon} aria-hidden="true">
          {enabled ? <Sun size={19} weight="regular" /> : <Moon size={19} weight="regular" />}
        </span>
        <span className={styles.settingsToggleCopy}>
          <strong>跑步语音播报</strong>
          <small>关键配速与阶段提醒</small>
        </span>
        {variant === 'label' && (
          <span className={styles.settingsToggleState}>{enabled ? '已开启' : '已关闭'}</span>
        )}
        <button
          type="button"
          role="switch"
          aria-checked={enabled}
          aria-label="跑步语音播报"
          onClick={onToggle}
        >
          <span className={styles.settingsToggleThumb}>
            {variant === 'icon' &&
              (enabled ? (
                <Sun size={12} weight="fill" aria-hidden="true" />
              ) : (
                <Moon size={12} weight="fill" aria-hidden="true" />
              ))}
            {variant === 'switch' && enabled && (
              <Check size={11} weight="bold" aria-hidden="true" />
            )}
          </span>
        </button>
      </div>
      <p>{enabled ? '播报已开启 · 本次预览不会触发语音' : '播报已关闭 · 仅演示设置状态'}</p>
    </div>
  );
}

export function SettingsToggleReferenceSection({ width }: { width: '393' | '320' }) {
  const [enabled, setEnabled] = useState(true);
  const [selected, setSelected] = useState<ToggleVariant | null>(() => {
    try {
      const value = window.localStorage.getItem('healthy-agent:ui-reference:settings-toggle');
      return value === 'switch' || value === 'icon' || value === 'label' ? value : 'label';
    } catch {
      return 'label';
    }
  });
  const [feedback, setFeedback] = useState('三案共享本地预览状态；不会保存到真实设置或播放语音。');

  const select = (variant: ToggleVariant, name: string) => {
    setSelected(variant);
    try {
      window.localStorage.setItem('healthy-agent:ui-reference:settings-toggle', variant);
      setFeedback(
        variant === 'label'
          ? 'C「状态文字」已接入正式语音提醒；这里仅切换合成预览。'
          : '已将“' + name + '”记为对比候选；正式语音提醒保持 C「状态文字」。',
      );
    } catch {
      setFeedback('已暂时选定“' + name + '”；本地偏好未能保存。');
    }
  };

  return (
    <section className={styles.variantsSection} aria-labelledby="settings-toggle-heading">
      <header className={styles.sectionHeading}>
        <h2 id="settings-toggle-heading">设置项开关的三种反馈</h2>
        <p>同一条跑步语音设置、同一开关状态；点击任一开关会同步更新三案。</p>
      </header>
      <div className={styles.settingsToggleVariants} data-testid="settings-toggle-previews">
        {variants.map((variant) => (
          <article className={styles.variantCard} key={variant.id}>
            <header className={styles.variantHeader}>
              <span className={styles.variantMark}>{variant.letter}</span>
              <div className={styles.variantHeading}>
                <h3>{variant.name}</h3>
                <p>{variant.description}</p>
              </div>
            </header>
            <TogglePreview
              variant={variant.id}
              enabled={enabled}
              onToggle={() => {
                setEnabled((value) => !value);
                setFeedback('预览状态已切换；没有写入真实设置。');
              }}
            />
            <dl className={styles.settingStatusFacts}>
              <div>
                <dt>点击</dt>
                <dd>三案同步切换</dd>
              </div>
              <div>
                <dt>语义</dt>
                <dd>switch + 已开/关</dd>
              </div>
              <div>
                <dt>宽度</dt>
                <dd>{width}px 预览</dd>
              </div>
            </dl>
            <button
              type="button"
              className={styles.settingStatusSelect}
              aria-pressed={selected === variant.id}
              onClick={() => select(variant.id, variant.name)}
            >
              {variant.id === 'label'
                ? '已确认并接入'
                : selected === variant.id
                  ? '已选为候选基线'
                  : '选择此方案'}
            </button>
          </article>
        ))}
      </div>
      <p className={styles.feedback} role="status" aria-live="polite">
        {feedback}
      </p>
    </section>
  );
}
