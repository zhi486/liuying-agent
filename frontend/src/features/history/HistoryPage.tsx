const pastRuns = [
  { month: '8月', day: '29', type: '轻松跑', distance: '4.08 km', pace: '6:18 /km' },
  { month: '8月', day: '26', type: '节奏跑', distance: '6.21 km', pace: '5:48 /km' },
  { month: '8月', day: '23', type: '恢复跑', distance: '3.52 km', pace: '6:34 /km' },
];

export function HistoryPage() {
  return (
    <section className="standard-page history-page">
      <header className="page-title-row">
        <div>
          <p className="eyebrow">你的节奏</p>
          <h1>跑步记录</h1>
        </div>
        <span className="month-label">9 月</span>
      </header>

      <article className="latest-run">
        <div className="latest-run-heading">
          <div>
            <span>今天 · 21:14</span>
            <h2>夜间轻松跑</h2>
          </div>
          <span className="run-status">已完成</span>
        </div>
        <div className="distance-lockup">
          <strong>5.02</strong>
          <span>公里</span>
        </div>
        <dl className="compact-stats">
          <div>
            <dt>用时</dt>
            <dd>30:18</dd>
          </div>
          <div>
            <dt>平均配速</dt>
            <dd>6:02</dd>
          </div>
          <div>
            <dt>平均心率</dt>
            <dd>144</dd>
          </div>
        </dl>
      </article>

      <div className="week-rhythm" aria-label="本周跑量 9.1 公里">
        <div>
          <span>本周跑量</span>
          <strong>9.1 km</strong>
        </div>
        <div className="rhythm-bars" aria-hidden="true">
          {[20, 42, 18, 76, 28, 54, 24].map((height, index) => (
            <span key={index} style={{ height: `${height}%` }} />
          ))}
        </div>
      </div>

      <section className="past-runs" aria-labelledby="past-runs-title">
        <div className="section-heading">
          <h2 id="past-runs-title">最近记录</h2>
          <span>共 18 次</span>
        </div>
        <ul>
          {pastRuns.map((run) => (
            <li key={`${run.month}${run.day}`}>
              <div className="date-chip">
                <strong>{run.day}</strong>
                <span>{run.month}</span>
              </div>
              <div>
                <strong>{run.type}</strong>
                <span>{run.pace}</span>
              </div>
              <strong>{run.distance}</strong>
            </li>
          ))}
        </ul>
      </section>
    </section>
  );
}
