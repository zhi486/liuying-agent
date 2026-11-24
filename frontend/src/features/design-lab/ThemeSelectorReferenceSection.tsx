import {
  CaretRight,
  Check,
  CheckCircle,
  CircleNotch,
  Moon,
  Sun,
  type Icon,
} from '@phosphor-icons/react';
import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';

import styles from './ComponentReferenceLabPage.module.css';

type ThemeChoice = 'morning' | 'night';
type ThemeVariantId = 'cards' | 'segmented' | 'canvas';
type PreviewWidth = '393' | '320';
type PreviewState = 'default' | 'focus' | 'long' | 'saving';
type DetailTab = 'logic' | 'css' | 'a11y';

interface ThemeVariantDefinition {
  id: ThemeVariantId;
  letter: 'A' | 'B' | 'C';
  name: string;
  summary: string;
  discovery: string;
  density: string;
  narrowScreen: string;
}

interface ThemeOptionDefinition {
  id: ThemeChoice;
  label: string;
  shortDescription: string;
  longDescription: string;
  icon: Icon;
}

interface ThemeSelectorReferenceSectionProps {
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  ruler: ReactNode;
}

const themeVariants: ThemeVariantDefinition[] = [
  {
    id: 'cards',
    letter: 'A',
    name: '双主题卡片',
    summary: '两张完整主题卡同时展示界面气质，发现性最强，但占用高度最多。',
    discovery: '最强',
    density: '中等',
    narrowScreen: '稳定',
  },
  {
    id: 'segmented',
    letter: 'B',
    name: '玻璃分段条',
    summary: '单行双段控件最紧凑，切换路径最短，最接近 Codex 的工具面板。',
    discovery: '中等',
    density: '最高',
    narrowScreen: '稳定',
  },
  {
    id: 'canvas',
    letter: 'C',
    name: '双画布主题窗',
    summary: '一个玻璃画框内并排两张主题画布，兼顾预览感与纵向密度。',
    discovery: '强',
    density: '中高',
    narrowScreen: '稳定',
  },
];

const themeOptions: ThemeOptionDefinition[] = [
  {
    id: 'morning',
    label: '晨光',
    shortDescription: '清爽浅色',
    longDescription: '明亮背景，适合白天、户外和强光环境阅读',
    icon: Sun,
  },
  {
    id: 'night',
    label: '夜萤',
    shortDescription: '沉静深色',
    longDescription: '低亮度背景，适合夜间和昏暗环境阅读',
    icon: Moon,
  },
];

const detailTabs: { id: DetailTab; label: string }[] = [
  { id: 'logic', label: 'React 逻辑' },
  { id: 'css', label: 'CSS' },
  { id: 'a11y', label: '无障碍' },
];

const detailCopy: Record<
  ThemeVariantId,
  Record<DetailTab, { heading: string; code: string; notes: string[] }>
> = {
  cards: {
    logic: {
      heading: '主题是互斥选择，不用两个互不相关的开关',
      code: `const [theme, setTheme] = useState<ThemeChoice>('morning');

<div role="radiogroup" aria-label="界面主题" aria-busy={saving}>
  {themes.map((option) => (
    <button
      key={option.id}
      type="button"
      role="radio"
      aria-checked={theme === option.id}
      tabIndex={theme === option.id ? 0 : -1}
      disabled={saving}
      onClick={() => setTheme(option.id)}
    >
      <ThemePreview theme={option.id} />
      {option.label}
    </button>
  ))}
</div>`,
      notes: [
        '主题只能同时选中一个，因此使用 radiogroup，而不是两个 aria-pressed 按钮。',
        '正式页仍调用原生设置保存；参考台只更新本地演示值，不发送 Bridge 命令。',
        '保存中锁定整组，失败时由正式页恢复上一个已保存主题。',
      ],
    },
    css: {
      heading: '卡片负责说明完整结果，玻璃只铺一层外表面',
      code: `.themeCard {
  display: grid;
  min-width: 0;
  gap: 0.55rem;
  padding: 0.45rem;
  border: 1px solid var(--line);
  border-radius: 0.95rem;
  background: var(--glass-strong);
  backdrop-filter: blur(var(--blur-glass)) saturate(1.16);
}

.themeCard[aria-checked='true'] {
  border-color: var(--accent);
  box-shadow: inset 0 0 0 1px var(--accent);
}`,
      notes: [
        '两张卡等宽，内部预览使用固定比例，切换选中态不会改变卡片高度。',
        '小画布使用局部主题变量模拟晨光与夜萤，避免受当前页面主题影响。',
        '320px 下仍保留双列；文字最多两行，完整描述进入可访问名称。',
      ],
    },
    a11y: {
      heading: '选中、焦点和保存中是三个独立状态',
      code: `<button
  role="radio"
  aria-checked={selected}
  aria-label="晨光，清爽浅色"
  tabIndex={selected ? 0 : -1}
  disabled={saving}
  onKeyDown={moveWithArrowKeys}
>
  <Sun aria-hidden="true" />
  <strong>晨光</strong>
  <small>清爽浅色</small>
</button>`,
      notes: [
        '只有当前选项进入 Tab 顺序；方向键在组内移动并同步选择。',
        '可见勾选标记和 aria-checked 同时表达选择，不能只靠颜色。',
        '禁用态仍保留文字对比度，并通过 aria-busy 说明正在保存。',
      ],
    },
  },
  segmented: {
    logic: {
      heading: '分段条使用 roving tabindex，整组只占一个 Tab 停靠点',
      code: `function moveTheme(
  event: KeyboardEvent<HTMLButtonElement>,
  current: ThemeChoice,
) {
  const keys = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'];
  if (!keys.includes(event.key)) return;

  const next = current === 'morning' ? 'night' : 'morning';
  setTheme(next);
  focusThemeRadio(event.currentTarget, next);
  event.preventDefault();
}`,
      notes: [
        'Tab 进入当前选项，左右键或上下键在晨光与夜萤之间切换。',
        'Home、End 分别定位第一项和最后一项，行为与原生单选组一致。',
        '切换后立即把焦点移到新选项，屏幕阅读器能朗读新的选中结果。',
      ],
    },
    css: {
      heading: '玻璃容器只包一次，内部靠表面、阴影和间距分段',
      code: `.segmented {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.2rem;
  padding: 0.25rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--glass-strong);
  backdrop-filter: blur(var(--blur-glass)) saturate(1.18);
}

.segment[aria-checked='true'] {
  background: var(--surface);
  box-shadow: 0 0.5rem 1.4rem -1rem var(--shadow-strong);
}`,
      notes: [
        '分段条最小高度 56px，内部每个命中区仍高于 44px。',
        '选中段不改变字体粗细和宽度，避免切换时文字跳动。',
        '嵌套半径遵循外圆内圆，内部胶囊不会突破外框。',
      ],
    },
    a11y: {
      heading: '不要用可滚动的假 Tab 栏代替主题单选组',
      code: `<div
  role="radiogroup"
  aria-label="界面主题"
  aria-busy={saving}
>
  <button role="radio" aria-checked="true">晨光</button>
  <button role="radio" aria-checked="false">夜萤</button>
</div>`,
      notes: [
        'radiogroup 名称描述整组目的，每个 radio 名称描述自己的主题。',
        '方向键切换是单选组惯例，不额外宣称成 Tab 面板。',
        '触控目标互不重叠；焦点环绘制在分段外缘，不被 overflow 裁切。',
      ],
    },
  },
  canvas: {
    logic: {
      heading: '视觉容器可以共享，选择逻辑仍由两个 radio 承担',
      code: `const [theme, setTheme] = useState<ThemeChoice>('morning');
const saving = state === 'saving';

<div className={styles.themeWindow}>
  <div role="radiogroup" aria-label="界面主题" aria-busy={saving}>
    {themeOptions.map((option) => (
      <ThemeCanvasRadio
        key={option.id}
        option={option}
        checked={theme === option.id}
        onChange={setTheme}
      />
    ))}
  </div>
</div>`,
      notes: [
        '外层画框只是视觉容器，不获得焦点，也不伪装成按钮。',
        '两个画布各自是 radio，命中区覆盖完整画布与文字区。',
        '状态更新与 B 方案共用同一份 ThemeChoice，不在视觉组件里复制业务状态。',
      ],
    },
    css: {
      heading: '画框用内高光和低对比边界，避免第二层重阴影',
      code: `.themeWindow {
  padding: 0.4rem;
  border: 1px solid var(--line);
  border-radius: 1.05rem;
  background: var(--glass-strong);
  box-shadow: inset 0 1px 0
    color-mix(in srgb, white 26%, transparent);
  backdrop-filter: blur(var(--blur-glass)) saturate(1.15);
}

.themeCanvas {
  min-width: 0;
  border-radius: 0.78rem;
}

.themeCanvas[aria-checked='true'] {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}`,
      notes: [
        '画框和画布使用两级圆角，外层大于内层，边界连续。',
        '选中环画在画布内部，不依赖额外占位，不推动相邻元素。',
        '20px 根字号下描述限制两行，画布保持固定宽高比。',
      ],
    },
    a11y: {
      heading: '视觉预览是装饰，可访问名称必须包含主题和用途',
      code: `<button
  role="radio"
  aria-checked={checked}
  aria-label="夜萤，沉静深色"
  aria-busy={saving}
  disabled={saving}
>
  <span aria-hidden="true">
    <MiniThemeCanvas theme="night" />
  </span>
  <strong>夜萤</strong>
  <small>沉静深色</small>
</button>`,
      notes: [
        '小画布里的演示文字全部 aria-hidden，避免朗读一串假数据。',
        '选中勾标不单独命名，radio 的 aria-checked 已经表达状态。',
        '颜色不是唯一线索：边框、勾选图标和文字同时变化。',
      ],
    },
  },
};

const terminology = [
  {
    term: '单选组',
    description: '一组选项里只能有一个被选中。主题、字号档位和声音模式都适合这个语义。',
  },
  {
    term: 'roving tabindex',
    description: '整组只让当前项进入 Tab 顺序，方向键负责在组内移动，减少键盘停靠次数。',
  },
  {
    term: '状态同步',
    description: '视觉选中、aria-checked、焦点位置和保存状态必须同时一致，不能只顾其中一个。',
  },
  {
    term: '固定几何',
    description: '按钮在默认、聚焦、选中和加载时保持相同宽高，状态变化不会让页面抖动。',
  },
  {
    term: '局部主题',
    description: '小画布使用一套仅服务预览的局部色变量，不复制整个应用的第二套设计系统。',
  },
  {
    term: '渐进反馈',
    description: '保存中先锁定操作并说明进度，成功或失败后再恢复，不假装瞬时完成。',
  },
];

function getThemeCopy(option: ThemeOptionDefinition, longCopy: boolean) {
  return longCopy ? option.longDescription : option.shortDescription;
}

function moveThemeSelection(
  event: KeyboardEvent<HTMLButtonElement>,
  currentTheme: ThemeChoice,
  onChange: (theme: ThemeChoice) => void,
) {
  const supportedKeys = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'];
  if (!supportedKeys.includes(event.key)) return;

  const currentIndex = themeOptions.findIndex((option) => option.id === currentTheme);
  let nextIndex = currentIndex;

  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
    nextIndex = (currentIndex + 1) % themeOptions.length;
  }
  if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
    nextIndex = (currentIndex - 1 + themeOptions.length) % themeOptions.length;
  }
  if (event.key === 'Home') nextIndex = 0;
  if (event.key === 'End') nextIndex = themeOptions.length - 1;

  const nextTheme = themeOptions[nextIndex].id;
  const group = event.currentTarget.closest('[role="radiogroup"]');
  const target = group?.querySelector<HTMLButtonElement>(`[data-theme-choice="${nextTheme}"]`);

  onChange(nextTheme);
  target?.focus();
  event.preventDefault();
}

function ThemeStateLine({ theme, saving }: { theme: ThemeChoice; saving: boolean }) {
  const selected = themeOptions.find((option) => option.id === theme) ?? themeOptions[0];

  return (
    <div className={styles.themeStateLine}>
      <span data-tone={saving ? 'saving' : 'ready'}>
        {saving ? (
          <CircleNotch
            className={styles.themeSavingIcon}
            size={14}
            weight="bold"
            aria-hidden="true"
          />
        ) : (
          <CheckCircle size={14} weight="fill" aria-hidden="true" />
        )}
        {saving ? '正在保存主题' : `当前演示：${selected.label}`}
      </span>
      <small>{saving ? '选项暂不可切换' : '仅参考台演示'}</small>
    </div>
  );
}

function MiniThemeCanvas({ theme, compact = false }: { theme: ThemeChoice; compact?: boolean }) {
  return (
    <span
      className={`${styles.themeMiniCanvas} ${
        theme === 'morning' ? styles.themeMiniMorning : styles.themeMiniNight
      } ${compact ? styles.themeMiniCanvasCompact : ''}`}
      aria-hidden="true"
    >
      <span className={styles.themeMiniTopbar}>
        <span className={styles.themeMiniBrand}>流萤</span>
        <span className={styles.themeMiniStatus}>本机</span>
      </span>
      <span className={styles.themeMiniContent}>
        <span className={styles.themeMiniMetric}>
          <small>本周训练</small>
          <strong>
            18.4 <i>km</i>
          </strong>
          <span className={styles.themeMiniProgress}>
            <i />
          </span>
        </span>
        <span className={styles.themeMiniMessage}>
          <span className={styles.themeMiniAvatar} />
          <span>
            <strong>今天轻松跑</strong>
            <small>配速 6'00" / km</small>
          </span>
        </span>
      </span>
      <span className={styles.themeMiniComposer}>和流萤聊聊今天的训练</span>
    </span>
  );
}

interface ThemePreviewProps {
  value: ThemeChoice;
  saving: boolean;
  forceFocus: boolean;
  longCopy: boolean;
  onChange: (theme: ThemeChoice) => void;
}

function ThemeChoiceCards({ value, saving, forceFocus, longCopy, onChange }: ThemePreviewProps) {
  return (
    <div
      className={styles.themeChoiceCards}
      role="radiogroup"
      aria-label="双主题卡片的界面主题"
      aria-busy={saving}
    >
      {themeOptions.map((option) => {
        const OptionIcon = option.icon;
        const selected = value === option.id;

        return (
          <button
            key={option.id}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-label={`${option.label}，${getThemeCopy(option, longCopy)}`}
            className={`${styles.themeChoiceCard} ${
              forceFocus && selected ? styles.forceFocus : ''
            }`}
            data-theme-choice={option.id}
            tabIndex={selected ? 0 : -1}
            disabled={saving}
            onClick={() => onChange(option.id)}
            onKeyDown={(event) => moveThemeSelection(event, option.id, onChange)}
          >
            <span className={styles.themeChoicePreview}>
              <MiniThemeCanvas theme={option.id} />
              <span className={styles.themeChoiceCheck} aria-hidden="true">
                {selected ? <Check size={13} weight="bold" /> : null}
              </span>
            </span>
            <span className={styles.themeChoiceCopy}>
              <span>
                <OptionIcon size={15} weight="regular" aria-hidden="true" />
                <strong>{option.label}</strong>
              </span>
              <small>{getThemeCopy(option, longCopy)}</small>
            </span>
          </button>
        );
      })}
    </div>
  );
}

function ThemeSegmentedBar({ value, saving, forceFocus, longCopy, onChange }: ThemePreviewProps) {
  return (
    <div
      className={styles.themeSegmentedBar}
      role="radiogroup"
      aria-label="玻璃分段条的界面主题"
      aria-busy={saving}
    >
      {themeOptions.map((option) => {
        const OptionIcon = option.icon;
        const selected = value === option.id;

        return (
          <button
            key={option.id}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-label={`${option.label}，${getThemeCopy(option, longCopy)}`}
            className={`${styles.themeSegment} ${forceFocus && selected ? styles.forceFocus : ''}`}
            data-theme-choice={option.id}
            tabIndex={selected ? 0 : -1}
            disabled={saving}
            onClick={() => onChange(option.id)}
            onKeyDown={(event) => moveThemeSelection(event, option.id, onChange)}
          >
            <span className={styles.themeSegmentIcon} aria-hidden="true">
              <OptionIcon size={17} weight="regular" />
            </span>
            <span className={styles.themeSegmentCopy}>
              <strong>{option.label}</strong>
              <small>{getThemeCopy(option, longCopy)}</small>
            </span>
            <span className={styles.themeSegmentCheck} aria-hidden="true">
              {selected ? <Check size={12} weight="bold" /> : null}
            </span>
          </button>
        );
      })}
    </div>
  );
}

function ThemeCanvasWindow({ value, saving, forceFocus, longCopy, onChange }: ThemePreviewProps) {
  return (
    <div className={styles.themeCanvasWindow}>
      <div className={styles.themeCanvasWindowHeader} aria-hidden="true">
        <span>界面画布</span>
        <small>晨光 / 夜萤</small>
      </div>
      <div
        className={styles.themeCanvasOptions}
        role="radiogroup"
        aria-label="双画布主题窗的界面主题"
        aria-busy={saving}
      >
        {themeOptions.map((option) => {
          const OptionIcon = option.icon;
          const selected = value === option.id;

          return (
            <button
              key={option.id}
              type="button"
              role="radio"
              aria-checked={selected}
              aria-label={`${option.label}，${getThemeCopy(option, longCopy)}`}
              className={`${styles.themeCanvasOption} ${
                forceFocus && selected ? styles.forceFocus : ''
              }`}
              data-theme-choice={option.id}
              tabIndex={selected ? 0 : -1}
              disabled={saving}
              onClick={() => onChange(option.id)}
              onKeyDown={(event) => moveThemeSelection(event, option.id, onChange)}
            >
              <MiniThemeCanvas theme={option.id} compact />
              <span className={styles.themeCanvasCaption}>
                <span>
                  <OptionIcon size={14} weight="regular" aria-hidden="true" />
                  <strong>{option.label}</strong>
                </span>
                {selected ? <Check size={13} weight="bold" aria-hidden="true" /> : null}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

interface ThemePreviewCanvasProps extends ThemePreviewProps {
  variant: ThemeVariantId;
  width: PreviewWidth;
  showRuler: boolean;
  ruler: ReactNode;
}

function ThemePreviewCanvas({
  variant,
  width,
  showRuler,
  ruler,
  ...previewProps
}: ThemePreviewCanvasProps) {
  return (
    <div className={styles.previewFrame}>
      <div
        className={`${styles.demoCanvas} ${styles.appearanceDemoCanvas}`}
        data-width={width}
        data-variant={variant}
      >
        <div className={styles.appearanceDemoBody}>
          <div className={styles.appearanceSectionHeader}>
            <span>
              <small>设置</small>
              <strong>界面外观</strong>
            </span>
            <small>整个 App</small>
          </div>
          {variant === 'cards' ? <ThemeChoiceCards {...previewProps} /> : null}
          {variant === 'segmented' ? <ThemeSegmentedBar {...previewProps} /> : null}
          {variant === 'canvas' ? <ThemeCanvasWindow {...previewProps} /> : null}
          <ThemeStateLine theme={previewProps.value} saving={previewProps.saving} />
        </div>
        {showRuler ? ruler : null}
      </div>
    </div>
  );
}

interface ThemeVariantCardProps {
  variant: ThemeVariantDefinition;
  width: PreviewWidth;
  state: PreviewState;
  showRuler: boolean;
  ruler: ReactNode;
  value: ThemeChoice;
  isBaseline: boolean;
  onChange: (theme: ThemeChoice) => void;
  onInspect: (variant: ThemeVariantId) => void;
}

function ThemeVariantCard({
  variant,
  width,
  state,
  showRuler,
  ruler,
  value,
  isBaseline,
  onChange,
  onInspect,
}: ThemeVariantCardProps) {
  return (
    <article className={`${styles.variantCard} ${isBaseline ? styles.variantSelected : ''}`}>
      <header className={styles.variantHeader}>
        <span className={styles.variantMark} aria-hidden="true">
          {variant.letter}
        </span>
        <div className={styles.variantHeading}>
          <div className={styles.variantNameRow}>
            <h3>{variant.name}</h3>
            <span className={isBaseline ? styles.recommendedBadge : styles.candidateBadge}>
              {isBaseline ? '已确认基线' : '候选'}
            </span>
          </div>
          <p>{variant.summary}</p>
        </div>
      </header>
      <ThemePreviewCanvas
        variant={variant.id}
        width={width}
        showRuler={showRuler}
        ruler={ruler}
        value={value}
        saving={state === 'saving'}
        forceFocus={state === 'focus'}
        longCopy={state === 'long'}
        onChange={onChange}
      />
      <dl className={styles.factGrid}>
        <div>
          <dt>发现性</dt>
          <dd>{variant.discovery}</dd>
        </div>
        <div>
          <dt>视觉密度</dt>
          <dd>{variant.density}</dd>
        </div>
        <div>
          <dt>320px</dt>
          <dd>{variant.narrowScreen}</dd>
        </div>
      </dl>
      <footer className={styles.variantActions}>
        {isBaseline ? (
          <span className={styles.baselineStatus}>
            <Check size={17} weight="bold" aria-hidden="true" />
            已确认并接入
          </span>
        ) : (
          <span className={styles.candidateStatus}>候选仅供对照</span>
        )}
        <button
          type="button"
          className={styles.inspectButton}
          aria-label={`查看${variant.name}的实现`}
          onClick={() => onInspect(variant.id)}
        >
          看实现
          <CaretRight size={15} weight="bold" aria-hidden="true" />
        </button>
      </footer>
    </article>
  );
}

interface ThemeImplementationDetailsProps {
  activeVariant: ThemeVariantId;
  activeTab: DetailTab;
  onVariantChange: (variant: ThemeVariantId) => void;
  onTabChange: (tab: DetailTab) => void;
}

function ThemeImplementationDetails({
  activeVariant,
  activeTab,
  onVariantChange,
  onTabChange,
}: ThemeImplementationDetailsProps) {
  const tabRefs = useRef<Record<DetailTab, HTMLButtonElement | null>>({
    logic: null,
    css: null,
    a11y: null,
  });
  const activeDefinition =
    themeVariants.find((variant) => variant.id === activeVariant) ?? themeVariants[0];
  const copy = detailCopy[activeVariant][activeTab];

  const moveTabFocus = (key: string) => {
    const currentIndex = detailTabs.findIndex((tab) => tab.id === activeTab);
    let nextIndex = currentIndex;

    if (key === 'ArrowRight') nextIndex = (currentIndex + 1) % detailTabs.length;
    if (key === 'ArrowLeft') {
      nextIndex = (currentIndex - 1 + detailTabs.length) % detailTabs.length;
    }
    if (key === 'Home') nextIndex = 0;
    if (key === 'End') nextIndex = detailTabs.length - 1;
    if (nextIndex === currentIndex && !['Home', 'End'].includes(key)) return false;

    const nextTab = detailTabs[nextIndex].id;
    onTabChange(nextTab);
    tabRefs.current[nextTab]?.focus();
    return true;
  };

  return (
    <section
      id="theme-implementation"
      className={styles.implementation}
      aria-labelledby="theme-implementation-heading"
      tabIndex={-1}
    >
      <header className={styles.sectionHeading}>
        <h2 id="theme-implementation-heading">同一个主题选择，三套实现细节</h2>
        <p>先用眼睛选信息密度，再用 React、CSS 和键盘行为确认它是否经得起真实使用。</p>
      </header>

      <div className={styles.implementationControls}>
        <div className={styles.variantSwitch} role="group" aria-label="查看哪个主题参考的实现">
          {themeVariants.map((variant) => (
            <button
              key={variant.id}
              type="button"
              aria-pressed={activeVariant === variant.id}
              onClick={() => onVariantChange(variant.id)}
            >
              <span aria-hidden="true">{variant.letter}</span>
              {variant.name}
            </button>
          ))}
        </div>
        <div className={styles.detailTabs} role="tablist" aria-label="主题选择实现明细">
          {detailTabs.map((tab) => (
            <button
              key={tab.id}
              ref={(element) => {
                tabRefs.current[tab.id] = element;
              }}
              id={`theme-detail-tab-${tab.id}`}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              aria-controls={`theme-detail-panel-${tab.id}`}
              tabIndex={activeTab === tab.id ? 0 : -1}
              onClick={() => onTabChange(tab.id)}
              onKeyDown={(event) => {
                if (moveTabFocus(event.key)) event.preventDefault();
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <div
        id={`theme-detail-panel-${activeTab}`}
        className={styles.detailPanel}
        role="tabpanel"
        aria-labelledby={`theme-detail-tab-${activeTab}`}
        tabIndex={0}
      >
        <div className={styles.detailIntro}>
          <span>{activeDefinition.letter}</span>
          <div>
            <p>{activeDefinition.name}</p>
            <h3>{copy.heading}</h3>
          </div>
        </div>
        <pre className={styles.codeBlock}>
          <code>{copy.code}</code>
        </pre>
        <ul className={styles.implementationNotes}>
          {copy.notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function ThemeGlossary() {
  return (
    <section className={styles.glossary} aria-labelledby="theme-glossary-heading">
      <header className={styles.sectionHeading}>
        <h2 id="theme-glossary-heading">主题选择再掌握六个词</h2>
        <p>这些词能帮你判断问题出在信息密度、状态语义，还是视觉几何不稳定。</p>
      </header>
      <dl className={styles.termGrid}>
        {terminology.map((item) => (
          <div key={item.term} className={styles.termItem}>
            <dt>{item.term}</dt>
            <dd>{item.description}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

export function ThemeSelectorReferenceSection({
  width,
  state,
  showRuler,
  ruler,
}: ThemeSelectorReferenceSectionProps) {
  const [theme, setTheme] = useState<ThemeChoice>('morning');
  const [activeVariant, setActiveVariant] = useState<ThemeVariantId>('canvas');
  const [activeTab, setActiveTab] = useState<DetailTab>('logic');
  const [feedback, setFeedback] = useState(
    'C「双画布主题窗」已接入正式设置页；A、B 保留为参考。这里不会写入手机设置或切换正式 App 主题。',
  );

  const handleThemeChange = (nextTheme: ThemeChoice) => {
    if (state === 'saving') return;
    setTheme(nextTheme);
    const selected = themeOptions.find((option) => option.id === nextTheme) ?? themeOptions[0];
    setFeedback(
      `当前演示主题已切换为“${selected.label}”；正式设置页采用 C「双画布主题窗」，此处不会写入本机设置。`,
    );
  };

  const handleInspect = (variant: ThemeVariantId) => {
    setActiveVariant(variant);
    setActiveTab('logic');
    const section = document.getElementById('theme-implementation');
    try {
      section?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch {
      // jsdom 没有布局引擎；真实浏览器仍会正常滚动。
    }
    section?.focus({ preventScroll: true });
  };

  return (
    <>
      <section className={styles.variantsSection} aria-labelledby="theme-variants-heading">
        <header className={styles.sectionHeading}>
          <h2 id="theme-variants-heading">主题选择器的三种取舍</h2>
          <p>
            A 先让人看清结果，B 追求操作效率，C 把预览收进一个画框。C
            已接入正式设置页，三者仍使用同一份主题状态和同一套单选语义。
          </p>
        </header>
        <div className={styles.variantGrid} data-testid="theme-selector-previews">
          {themeVariants.map((variant) => (
            <ThemeVariantCard
              key={variant.id}
              variant={variant}
              width={width}
              state={state}
              showRuler={showRuler}
              ruler={ruler}
              value={theme}
              isBaseline={variant.id === 'canvas'}
              onChange={handleThemeChange}
              onInspect={handleInspect}
            />
          ))}
        </div>
        <p className={styles.feedback} role="status" aria-live="polite">
          {feedback}
        </p>
      </section>

      <ThemeImplementationDetails
        activeVariant={activeVariant}
        activeTab={activeTab}
        onVariantChange={setActiveVariant}
        onTabChange={setActiveTab}
      />
      <ThemeGlossary />
    </>
  );
}
