import type { SelectedRunSummary } from '../../bridge/cloudAgent';
import styles from './AgentPage.module.css';

export function AgentRunSummary({ summary }: { summary: SelectedRunSummary }) {
  return (
    <section className={styles.message} aria-label="本次授权跑步摘要">
      <h3>本次跑步摘要</h3>
      <p>记录：{summary.runId}</p>
      <p>
        距离 {summary.distanceMeters} 米 · 用时 {summary.durationSeconds} 秒
      </p>
      <p>
        平均配速：
        {summary.averagePaceSecPerKm === null ? '未采集' : `${summary.averagePaceSecPerKm} 秒/公里`}
        {' · '}平均心率：
        {summary.averageHeartRate === null ? '未采集' : `${summary.averageHeartRate} 次/分`}
      </p>
      <small>只含以上概要，不含日期、地点、轨迹或连续心率；不代表模型已采用。</small>
    </section>
  );
}
