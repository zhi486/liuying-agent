import { useState } from 'react';

type ThemeChoice = 'morning' | 'night';

function initialTheme(): ThemeChoice {
  const storedTheme = document.documentElement.dataset.theme;
  if (storedTheme === 'morning' || storedTheme === 'night') {
    return storedTheme;
  }
  return 'night';
}

export function SettingsPage() {
  const [theme, setTheme] = useState<ThemeChoice>(initialTheme);

  const updateTheme = (nextTheme: ThemeChoice) => {
    setTheme(nextTheme);
    document.documentElement.dataset.theme = nextTheme;
  };

  return (
    <section className="standard-page settings-page">
      <header className="page-title-row">
        <div>
          <p className="eyebrow">按你的习惯陪跑</p>
          <h1>设置</h1>
        </div>
      </header>

      <section className="setting-section" aria-labelledby="appearance-title">
        <div className="section-heading">
          <div>
            <h2 id="appearance-title">界面外观</h2>
            <p>两种模式保持相同的信息结构</p>
          </div>
        </div>
        <div className="theme-selector" aria-label="界面主题">
          <button
            type="button"
            className={theme === 'morning' ? 'selected' : ''}
            aria-pressed={theme === 'morning'}
            onClick={() => updateTheme('morning')}
          >
            <span className="theme-swatch morning-swatch" aria-hidden="true" />
            <strong>晨光</strong>
            <small>清爽浅色</small>
          </button>
          <button
            type="button"
            className={theme === 'night' ? 'selected' : ''}
            aria-pressed={theme === 'night'}
            onClick={() => updateTheme('night')}
          >
            <span className="theme-swatch night-swatch" aria-hidden="true" />
            <strong>夜萤</strong>
            <small>沉静深色</small>
          </button>
        </div>
      </section>

      <section className="setting-section" aria-labelledby="running-title">
        <div className="section-heading">
          <div>
            <h2 id="running-title">跑步偏好</h2>
            <p>用于生成开跑建议和途中提醒</p>
          </div>
        </div>
        <div className="setting-list">
          <button type="button">
            <span>默认目标</span>
            <strong>30 分钟轻松跑</strong>
          </button>
          <button type="button">
            <span>目标配速</span>
            <strong>6:00–6:30 /km</strong>
          </button>
          <button type="button">
            <span>心率提醒</span>
            <strong>超过 165 bpm</strong>
          </button>
        </div>
      </section>

      <section className="setting-section" aria-labelledby="coach-title">
        <div className="section-heading">
          <div>
            <h2 id="coach-title">流萤教练</h2>
            <p>提醒克制一点，只在需要时出现</p>
          </div>
        </div>
        <div className="setting-list">
          <button type="button">
            <span>陪跑语气</span>
            <strong>温和</strong>
          </button>
          <button type="button">
            <span>语音频率</span>
            <strong>适中</strong>
          </button>
          <button type="button">
            <span>动态效果</span>
            <strong>跟随系统</strong>
          </button>
        </div>
      </section>
    </section>
  );
}
