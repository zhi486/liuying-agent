import {
  ArrowUp,
  CalendarBlank,
  CaretRight,
  ChartLine,
  ChatCircle,
  GearSix,
  Moon,
  Plus,
  Play,
  Sun,
  X,
} from '@phosphor-icons/react';
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from 'react';
import { BackButton, BackLink } from '../../design-system/BackControl';
import { ChoiceInput } from '../../design-system/ChoiceInput';
import { MenuGlyph } from '../../design-system/MenuGlyph';
import styles from './MotionReferenceSection.module.css';

type Preset = 'fade' | 'stack' | 'focus';
type Screen = 'chat' | 'run' | 'settings' | 'memory';
type Motion = 'full' | 'reduced' | 'off';
const presets: { id: Preset; title: string; detail: string }[] = [
  { id: 'fade', title: 'A · 轻淡', detail: '只改变透明度，切换安静利落。' },
  { id: 'stack', title: 'B · 层级', detail: '同级轻淡，进入与返回有相反方向。' },
  { id: 'focus', title: 'C · 聚焦', detail: '子页轻微缩放展开，弱化横向移动。' },
];
const titles: Record<Screen, string> = {
  chat: '流萤',
  run: '流萤',
  settings: '设置与管理',
  memory: '长期记忆',
};

function trapFocus(event: KeyboardEvent<HTMLElement>, close: () => void) {
  if (event.key === 'Escape') {
    event.preventDefault();
    event.stopPropagation();
    close();
  }
  if (event.key !== 'Tab') return;
  const items = [
    ...event.currentTarget.querySelectorAll<HTMLElement>(
      'button:not(:disabled), input:not(:disabled)',
    ),
  ].filter(
    (item) => item.tabIndex >= 0 && item.getClientRects().length > 0 && !item.closest('[inert]'),
  );
  const target = event.shiftKey ? items.at(-1) : items[0];
  if (
    (event.shiftKey && document.activeElement === items[0]) ||
    (!event.shiftKey && document.activeElement === items.at(-1))
  ) {
    event.preventDefault();
    target?.focus();
  }
}

/** 设计台专用：所有页面、授权和保存结果均为本地合成状态，不接 Bridge。 */
export function MotionReferenceSection() {
  const [preset, setPreset] = useState<Preset>('stack');
  const [motion, setMotion] = useState<Motion>('full');
  const [slow, setSlow] = useState(false);
  const [width, setWidth] = useState('393');
  const [theme, setTheme] = useState(() =>
    document.documentElement.dataset.theme === 'night' ? 'night' : 'morning',
  );
  const [screen, setScreen] = useState<Screen>('chat');
  const [direction, setDirection] = useState(1);
  const [menu, setMenu] = useState(false);
  const [sheet, setSheet] = useState<'input' | 'consent' | null>(null);
  const [agree, setAgree] = useState(false);
  const [memory, setMemory] = useState(false);
  const [reminder, setReminder] = useState(true);
  const [saving, setSaving] = useState(false);
  const [failSave, setFailSave] = useState(false);
  const [status, setStatus] = useState('仅合成预览，未接入正式页面。');
  const [systemReduced, setSystemReduced] = useState(
    () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false,
  );
  const [appMotion, setAppMotion] = useState(document.documentElement.dataset.motion);
  const scene = useRef<HTMLDivElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLDivElement>(null);
  const sheetTrigger = useRef<HTMLElement | null>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const previous = useRef(screen);
  const reduced =
    motion !== 'full' || systemReduced || appMotion === 'reduced' || appMotion === 'off';
  const factor = slow ? 2 : 1;
  const rootStyle = {
    '--preview-width': `${width}px`,
    '--motion-menu': `${240 * factor}ms`,
    '--motion-switch': `${380 * factor}ms`,
    '--motion-check': `${200 * factor}ms`,
    '--preview-time': reduced ? '0ms' : `${240 * factor}ms`,
  } as CSSProperties;

  useEffect(() => {
    const root = document.documentElement;
    const original = root.dataset.theme;
    root.dataset.theme = theme;
    return () => {
      if (original) root.dataset.theme = original;
      else delete root.dataset.theme;
    };
  }, [theme]);
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (!media) return;
    const changed = () => setSystemReduced(media.matches);
    media.addEventListener('change', changed);
    return () => media.removeEventListener('change', changed);
  }, []);
  useEffect(() => {
    const observer = new MutationObserver(() =>
      setAppMotion(document.documentElement.dataset.motion),
    );
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-motion'],
    });
    return () => observer.disconnect();
  }, []);
  useEffect(() => () => clearTimeout(saveTimer.current), []);
  useLayoutEffect(() => {
    if (previous.current === screen) return;
    previous.current = screen;
    scene.current?.scrollTo?.(0, 0);
    scene.current?.focus({ preventScroll: true });
    if (motion === 'off' || !scene.current?.animate) return;
    const lateral =
      !reduced &&
      preset === 'stack' &&
      (screen === 'settings' || screen === 'memory' || direction < 0);
    const focus =
      !reduced &&
      preset === 'focus' &&
      (screen === 'settings' || screen === 'memory' || direction < 0);
    const animation = scene.current.animate(
      [
        {
          opacity: 0,
          transform: lateral
            ? `translateX(${24 * direction}px)`
            : focus
              ? 'translateY(8px) scale(0.985)'
              : 'none',
        },
        { opacity: 1, transform: 'none' },
      ],
      {
        duration: (reduced ? 100 : preset === 'fade' ? 180 : direction < 0 ? 200 : 240) * factor,
        easing: 'cubic-bezier(0.2, 0.75, 0.2, 1)',
      },
    );
    return () => animation.cancel();
  }, [screen, direction, preset, reduced, motion, factor]);
  useEffect(() => {
    if (menu) menuButton.current?.focus();
  }, [menu]);
  useLayoutEffect(() => {
    if (sheet) dialog.current?.querySelector<HTMLButtonElement>('button')?.focus();
    else if (sheetTrigger.current) {
      sheetTrigger.current.focus();
      sheetTrigger.current = null;
    }
  }, [sheet]);

  const navigate = (next: Screen, back = false) => {
    setDirection(back ? -1 : 1);
    setScreen(next);
    setMenu(false);
    setStatus(
      `预览：${next === 'chat' ? '聊天' : next === 'run' ? '跑步准备' : titles[next]}，没有业务请求。`,
    );
  };
  const closeMenu = () => {
    setMenu(false);
    menuButton.current?.focus();
  };
  const openSheet = (kind: 'input' | 'consent') => {
    sheetTrigger.current = document.activeElement as HTMLElement;
    setAgree(false);
    setSheet(kind);
  };
  const closeSheet = () => {
    setSheet(null);
  };
  const saveReminder = () => {
    setSaving(true);
    setStatus('合成保存中，开关等待结果确认。');
    saveTimer.current = setTimeout(() => {
      setSaving(false);
      if (!failSave) setReminder((value) => !value);
      setStatus(
        failSave ? '合成保存失败，保留原状态；未自动重试。' : '合成保存完成，未修改真实设置。',
      );
    }, 700 * factor);
  };
  const child = screen === 'settings' || screen === 'memory';

  return (
    <main
      className={styles.page}
      style={rootStyle}
      data-preview-motion={reduced ? 'reduced' : 'full'}
    >
      <header className={styles.heading}>
        <div className={styles.backLink}>
          <BackLink to="/design/reference" label="返回组件参考台" />
          <span>组件参考台</span>
        </div>
        <h1>流萤 · 交互动效</h1>
        <span>合成预览 · 尚未接入</span>
      </header>
      <div className={styles.workspace}>
        <aside className={styles.controls} aria-label="动效预览条件">
          <MenuMorphDemo reduced={reduced} />
          <fieldset>
            <legend>页面过渡</legend>
            <div className={styles.presets} role="group" aria-label="过渡方案">
              {presets.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  aria-pressed={preset === item.id}
                  onClick={() => setPreset(item.id)}
                >
                  <strong>
                    {item.title}
                    {item.id === 'stack' && <small>推荐</small>}
                  </strong>
                  <span>{item.detail}</span>
                </button>
              ))}
            </div>
          </fieldset>
          <fieldset>
            <legend>播放</legend>
            <div className={styles.segment} role="group" aria-label="播放速度">
              <button type="button" aria-pressed={!slow} onClick={() => setSlow(false)}>
                正常
              </button>
              <button type="button" aria-pressed={slow} onClick={() => setSlow(true)}>
                半速
              </button>
            </div>
          </fieldset>
          <fieldset>
            <legend>动态效果</legend>
            <div className={styles.segment}>
              {(['full', 'reduced', 'off'] as const).map((value, i) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={motion === value}
                  onClick={() => setMotion(value)}
                >
                  {['完整', '减弱', '关闭'][i]}
                </button>
              ))}
            </div>
          </fieldset>
          <fieldset>
            <legend>外观与尺寸</legend>
            <div className={styles.segment} role="group" aria-label="预览主题">
              <button
                type="button"
                aria-pressed={theme === 'morning'}
                onClick={() => setTheme('morning')}
              >
                <Sun size={17} />
                浅色
              </button>
              <button
                type="button"
                aria-pressed={theme === 'night'}
                onClick={() => setTheme('night')}
              >
                <Moon size={17} />
                深色
              </button>
            </div>
            <div className={styles.segment} role="group" aria-label="预览宽度">
              {['393', '320'].map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={width === value}
                  onClick={() => setWidth(value)}
                >
                  {value}px
                </button>
              ))}
            </div>
          </fieldset>
          <label className={styles.failure}>
            <ChoiceInput
              checked={failSave}
              onChange={(event) => setFailSave(event.currentTarget.checked)}
            />
            模拟保存失败
          </label>
          <p className={styles.status} role="status">
            {status}
          </p>
        </aside>

        <section className={styles.stage} aria-label="合成手机预览">
          <div
            className={styles.phone}
            data-testid="motion-phone"
            data-screen={screen}
            data-preset={preset}
          >
            <header className={styles.phoneHeader} inert={menu || sheet !== null}>
              {child ? (
                <BackButton
                  label="返回上一级预览"
                  onClick={() => navigate(screen === 'memory' ? 'settings' : 'chat', true)}
                />
              ) : (
                <span className={styles.menuSpace} />
              )}
              <strong>{titles[screen]}</strong>
              <button
                type="button"
                className={styles.icon}
                aria-label="打开设置预览"
                onClick={() => navigate('settings')}
              >
                <GearSix size={21} />
              </button>
            </header>
            <div
              ref={scene}
              className={styles.scene}
              tabIndex={-1}
              inert={menu || sheet !== null}
              aria-label={`${screen}预览内容`}
            >
              {(screen === 'chat' || screen === 'run') && (
                <div className={styles.tabs} role="group" aria-label="预览模块">
                  <button
                    type="button"
                    aria-pressed={screen === 'chat'}
                    onClick={() => navigate('chat')}
                  >
                    聊天
                  </button>
                  <button
                    type="button"
                    aria-pressed={screen === 'run'}
                    onClick={() => navigate('run')}
                  >
                    跑步
                  </button>
                </div>
              )}
              {screen === 'chat' && (
                <div className={styles.chat}>
                  <div>
                    <h2>
                      今天想把哪件事
                      <br />
                      交给流萤？
                    </h2>
                    <p>
                      训练、跑步和今天的状态，
                      <br />
                      从一个具体的问题开始。
                    </p>
                  </div>
                  <div className={styles.starters}>
                    {[
                      { icon: ChartLine, label: '复盘', detail: '看看最近的表现' },
                      { icon: CalendarBlank, label: '安排', detail: '找到训练的节奏' },
                      { icon: ChatCircle, label: '聊聊', detail: '说说今天的状态' },
                    ].map(({ icon: Icon, label, detail }) => (
                      <button
                        type="button"
                        key={label}
                        onClick={() => setStatus(`已点选「${label}」合成入口，未发送消息。`)}
                      >
                        <Icon size={21} />
                        <strong>{label}</strong>
                        <small>{detail}</small>
                      </button>
                    ))}
                  </div>
                </div>
              )}
              {screen === 'run' && (
                <div className={styles.run}>
                  <span>轻松跑</span>
                  <h2>
                    准备好，
                    <br />
                    就出发。
                  </h2>
                  <div className={styles.runValue}>
                    <strong>5.00</strong>
                    <span>公里</span>
                  </div>
                  <p>舒适配速，关注当下的呼吸。</p>
                  <button
                    type="button"
                    onClick={() => setStatus('这里只预览准备页，不会开始跑步。')}
                  >
                    开始跑步
                  </button>
                </div>
              )}
              {screen === 'settings' && (
                <div className={styles.settings}>
                  <h2>日常偏好</h2>
                  <label className={styles.row}>
                    <span>
                      <strong>训练提醒</strong>
                      <small>{saving ? '合成保存中' : reminder ? '已开启' : '已关闭'}</small>
                    </span>
                    <ChoiceInput
                      variant="switch"
                      role="switch"
                      aria-label="训练提醒预览"
                      checked={reminder}
                      disabled={saving}
                      aria-busy={saving}
                      onChange={saveReminder}
                    />
                  </label>
                  <label className={styles.row}>
                    <span>
                      <strong>触感反馈</strong>
                      <small>仅改变预览状态</small>
                    </span>
                    <LocalSwitch />
                  </label>
                  <button type="button" className={styles.row} onClick={() => navigate('memory')}>
                    <span>
                      <strong>长期记忆</strong>
                      <small>{memory ? '合成状态：已开启' : '合成状态：未开启'}</small>
                    </span>
                    <CaretRight size={19} />
                  </button>
                  <h2>关于流萤</h2>
                  <p className={styles.note}>记录每一次出发，也认真对待休息。</p>
                </div>
              )}
              {screen === 'memory' && (
                <div className={styles.settings}>
                  <h2>让对话更连贯</h2>
                  <p className={styles.note}>记忆开启后，可以读取已保存的偏好。你始终可以关闭。</p>
                  <label className={styles.row}>
                    <span>
                      <strong>自动记忆</strong>
                      <small>{memory ? '合成状态：已开启' : '合成状态：未开启'}</small>
                    </span>
                    <ChoiceInput
                      variant="switch"
                      role="switch"
                      aria-label="自动记忆预览"
                      checked={memory}
                      onChange={() => {
                        if (memory) {
                          setMemory(false);
                          setStatus('合成记忆已关闭，未修改真实授权。');
                        } else openSheet('consent');
                      }}
                    />
                  </label>
                  <label className={styles.row}>
                    <span>
                      <strong>事实写入</strong>
                      <small>本次预览不开放</small>
                    </span>
                    <ChoiceInput
                      variant="switch"
                      role="switch"
                      aria-label="事实写入预览"
                      checked={false}
                      disabled
                    />
                  </label>
                </div>
              )}
            </div>
            <footer className={styles.composer} inert={menu || sheet !== null}>
              <button
                type="button"
                className={styles.icon}
                aria-label="打开输入选项预览"
                onClick={() => openSheet('input')}
              >
                <Plus size={22} />
              </button>
              <span>给流萤的消息</span>
              <button
                type="button"
                className={styles.send}
                aria-label="发送（预览已禁用）"
                disabled
              >
                <ArrowUp size={19} />
              </button>
            </footer>
            <div
              className={styles.menuHost}
              data-open={menu}
              role={menu ? 'dialog' : undefined}
              aria-modal={menu || undefined}
              aria-label={menu ? '流萤菜单预览' : undefined}
              onKeyDown={(event) => {
                if (menu) trapFocus(event, closeMenu);
              }}
            >
              <button
                type="button"
                tabIndex={-1}
                aria-label="关闭菜单遮罩"
                className={styles.scrim}
                onClick={closeMenu}
              />
              <nav
                id="motion-preview-menu"
                className={styles.drawer}
                inert={!menu}
                aria-label="预览菜单"
              >
                <strong>流萤</strong>
                <small>合成会话</small>
                <button type="button" onClick={() => navigate('chat')}>
                  <Plus size={18} />
                  新建对话
                </button>
                <button type="button" onClick={() => navigate('run')}>
                  <ChartLine size={18} />
                  跑步
                </button>
                <button type="button" onClick={() => navigate('settings')}>
                  <GearSix size={18} />
                  设置与管理
                </button>
              </nav>
              <button
                ref={menuButton}
                type="button"
                className={`${styles.icon} ${styles.menuButton}`}
                hidden={child || sheet !== null}
                aria-label={menu ? '关闭菜单预览' : '打开菜单预览'}
                aria-expanded={menu}
                aria-controls="motion-preview-menu"
                onClick={() => setMenu((value) => !value)}
              >
                <MenuGlyph open={menu} />
              </button>
            </div>
            {sheet && (
              <div className={styles.sheetHost}>
                <button
                  type="button"
                  tabIndex={-1}
                  aria-label="关闭面板遮罩"
                  className={styles.sheetScrim}
                  onClick={closeSheet}
                />
                <div
                  ref={dialog}
                  className={styles.sheet}
                  role="dialog"
                  aria-modal="true"
                  aria-label={sheet === 'consent' ? '合成记忆确认' : '输入选项预览'}
                  onKeyDown={(event) => trapFocus(event, closeSheet)}
                >
                  <header>
                    <h2>{sheet === 'consent' ? '开启自动记忆' : '输入选项'}</h2>
                    <button
                      type="button"
                      className={styles.icon}
                      aria-label="关闭预览面板"
                      onClick={closeSheet}
                    >
                      <X size={20} />
                    </button>
                  </header>
                  {sheet === 'consent' ? (
                    <>
                      <p>仅演示开启前的范围确认。不会读取、发送或保存任何真实记忆。</p>
                      <label className={styles.agreement}>
                        <ChoiceInput
                          checked={agree}
                          onChange={(event) => setAgree(event.currentTarget.checked)}
                        />
                        我已了解本次合成预览范围
                      </label>
                      <button
                        type="button"
                        className={styles.primary}
                        disabled={!agree}
                        onClick={() => {
                          setMemory(true);
                          setStatus('合成记忆已开启，真实授权未变。');
                          closeSheet();
                        }}
                      >
                        确认开启（仅预览）
                      </button>
                    </>
                  ) : (
                    <>
                      <button
                        type="button"
                        className={styles.row}
                        onClick={() => {
                          closeSheet();
                          navigate('memory');
                        }}
                      >
                        <span>长期记忆</span>
                        <CaretRight size={19} />
                      </button>
                      <p>合成面板，不读取可用插件，不上传内容。</p>
                    </>
                  )}
                </div>
              </div>
            )}
          </div>
        </section>
      </div>
    </main>
  );
}

function MenuMorphDemo({ reduced }: { reduced: boolean }) {
  const [progress, setProgress] = useState(0);
  const current = useRef(0);
  const frame = useRef<number | undefined>(undefined);
  useEffect(() => {
    const cancel = () => {
      if (frame.current !== undefined) cancelAnimationFrame(frame.current);
    };
    if (reduced) cancel();
    return cancel;
  }, [reduced]);
  const play = () => {
    if (reduced) return;
    if (frame.current !== undefined) cancelAnimationFrame(frame.current);
    const from = current.current;
    const to = from >= 0.5 ? 0 : 1;
    const start = performance.now();
    const tick = (now: number) => {
      const elapsed = Math.min(1, (now - start) / 1600);
      current.current = from + (to - from) * elapsed;
      setProgress(current.current);
      if (elapsed < 1) frame.current = requestAnimationFrame(tick);
    };
    frame.current = requestAnimationFrame(tick);
  };
  return (
    <fieldset className={styles.morphDemo}>
      <legend>汉堡变叉 · 慢动作</legend>
      <div className={styles.morphSample}>
        <span
          className={styles.morphGlyph}
          data-testid="menu-morph-demo"
          style={{ '--glyph-progress': progress } as CSSProperties}
          aria-hidden="true"
        >
          <MenuGlyph open={false} />
        </span>
        <button
          type="button"
          className={styles.icon}
          aria-label={progress >= 0.5 ? '慢放还原三横线' : '慢放三横线变叉'}
          title={progress >= 0.5 ? '慢放还原三横线' : '慢放三横线变叉'}
          disabled={reduced}
          onClick={play}
        >
          <Play size={20} />
        </button>
        <span className={styles.morphReadout} aria-hidden="true">
          {Math.round(progress * 100)}%
        </span>
      </div>
      <input
        type="range"
        min="0"
        max="100"
        value={Math.round(progress * 100)}
        aria-label="三横线变叉进度"
        onChange={(event) => {
          if (frame.current !== undefined) cancelAnimationFrame(frame.current);
          current.current = Number(event.currentTarget.value) / 100;
          setProgress(current.current);
        }}
      />
      {reduced && <p>当前减少或关闭动效，可拖动进度查看形状。</p>}
    </fieldset>
  );
}

function LocalSwitch() {
  const [checked, setChecked] = useState(false);
  return (
    <ChoiceInput
      variant="switch"
      role="switch"
      aria-label="触感反馈预览"
      checked={checked}
      onChange={(event) => setChecked(event.currentTarget.checked)}
    />
  );
}
