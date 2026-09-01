import { useState } from 'react';
import { Link } from 'react-router-dom';

import styles from './RunningPage.module.css';

type RunPhase = 'idle' | 'running' | 'paused' | 'finished';

const metrics = [
  { label: '心率', value: '142', unit: 'bpm' },
  { label: '步频', value: '171', unit: 'spm' },
  { label: '距离', value: '1.24', unit: 'km' },
  { label: '用时', value: '07:31', unit: '' },
];

export function RunningPage() {
  const [phase, setPhase] = useState<RunPhase>('idle');
  const [confirmingStop, setConfirmingStop] = useState(false);

  if (phase === 'finished') {
    return (
      <section className={styles.page}>
        <h1 className="sr-only">跑步</h1>
        <div className={styles.finishedIntro}>
          <span className={styles.firefly} aria-hidden="true" />
          <p className="eyebrow">本次跑步已完成</p>
          <h2>今天也稳稳跑完了</h2>
          <p>节奏控制得很好，后半程没有明显掉速。</p>
        </div>

        <div className={styles.summary} aria-label="本次跑步摘要">
          <div className={styles.summaryPrimary}>
            <strong>5.02</strong>
            <span>公里</span>
          </div>
          <dl>
            <div>
              <dt>用时</dt>
              <dd>30:18</dd>
            </div>
            <div>
              <dt>平均配速</dt>
              <dd>6:02 /km</dd>
            </div>
            <div>
              <dt>平均心率</dt>
              <dd>144 bpm</dd>
            </div>
          </dl>
        </div>

        <aside className={styles.coachNote}>
          <span className={styles.coachLabel}>流萤教练</span>
          <p>前两公里很克制，最后一公里还能稳住呼吸。下次可以继续保持这个起跑节奏。</p>
        </aside>

        <div className={styles.finishedActions}>
          <Link className="primary-button" to="/history">
            查看记录
          </Link>
          <button className="quiet-button" type="button" onClick={() => setPhase('idle')}>
            再跑一次
          </button>
        </div>
      </section>
    );
  }

  if (phase === 'idle') {
    return (
      <section className={styles.page}>
        <h1 className="sr-only">跑步</h1>
        <div className={styles.readinessHeader}>
          <div>
            <p className="eyebrow">设备已就绪</p>
            <h2>今晚，跑得舒服一点</h2>
          </div>
          <div className={styles.devicePulse} aria-label="Keep B5 已连接，电量 86%">
            <span aria-hidden="true" />
            86%
          </div>
        </div>

        <div className={styles.goalPanel}>
          <p>今晚目标</p>
          <strong>轻松跑 · 30 分钟</strong>
          <span>建议配速 6:00–6:30 /km</span>
        </div>

        <ul className={styles.readinessList} aria-label="跑前检查">
          <li>
            <span>手环</span>
            <strong>Keep B5 · 连接稳定</strong>
          </li>
          <li>
            <span>心率</span>
            <strong>78 bpm · 状态平稳</strong>
          </li>
          <li>
            <span>语音陪跑</span>
            <strong>温和模式</strong>
          </li>
        </ul>

        <button className="primary-button" type="button" onClick={() => setPhase('running')}>
          开始跑步
        </button>
        <p className={styles.startHint}>开始后将持续显示配速、心率与跑步时间</p>
      </section>
    );
  }

  const paused = phase === 'paused';

  return (
    <section className={`${styles.page} ${styles.activePage}`}>
      <h1 className="sr-only">跑步</h1>
      <div className={styles.liveStatus} role="status">
        <span
          className={paused ? styles.pauseIndicator : styles.liveIndicator}
          aria-hidden="true"
        />
        <strong>{paused ? '已暂停' : '跑步中'}</strong>
        <span>{paused ? '数据已保持' : 'Keep B5 · 信号稳定'}</span>
      </div>

      <div className={styles.paceHero}>
        <span>当前配速</span>
        <strong>6:02</strong>
        <small>分钟 / 公里</small>
      </div>

      <div className={styles.metricsGrid}>
        {metrics.map((metric) => (
          <div key={metric.label}>
            <span>{metric.label}</span>
            <strong>{metric.value}</strong>
            {metric.unit && <small>{metric.unit}</small>}
          </div>
        ))}
      </div>

      <div className={styles.zoneStrip}>
        <div>
          <span>当前强度</span>
          <strong>有氧舒适区</strong>
        </div>
        <div className={styles.zoneTrack} aria-label="当前强度 62%">
          <span />
        </div>
      </div>

      <aside className={styles.liveCoach}>
        <span className={styles.coachLabel}>流萤教练 · 刚刚</span>
        <p>这个节奏很好，肩膀放松，保持现在的呼吸。</p>
      </aside>

      <div className={styles.runControls}>
        <button className="secondary-button" type="button" onClick={() => setConfirmingStop(true)}>
          结束
        </button>
        <button
          className="primary-button"
          type="button"
          onClick={() => setPhase(paused ? 'running' : 'paused')}
        >
          {paused ? '继续' : '暂停'}
        </button>
      </div>

      {confirmingStop && (
        <div className={styles.dialogBackdrop}>
          <div
            className={styles.stopDialog}
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="stop-title"
          >
            <p className="eyebrow">确认操作</p>
            <h2 id="stop-title">结束本次跑步？</h2>
            <p>结束后会保存当前数据，并生成本次跑步总结。</p>
            <div>
              <button
                className="quiet-button"
                type="button"
                onClick={() => setConfirmingStop(false)}
              >
                继续跑
              </button>
              <button
                className="danger-button"
                type="button"
                onClick={() => {
                  setConfirmingStop(false);
                  setPhase('finished');
                }}
              >
                确认结束
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
