import { RunSummary } from './RunDetailPage';
import { simulatedRun, simulatedSamples } from './simulatedRun';
import styles from './RunDetailPage.module.css';

export function SimulatedRunPage() {
  return (
    <section className="standard-page">
      <p className={styles.notice}>
        以下均为模拟数据，仅供界面演示，不是训练建议。不保存、不计入跑量、不上传。
        每五秒一个均值，包含热身、加速、恢复及十秒数据缺失；点击两种区间的“查看详情”可查看分布和具体数值。
      </p>
      <RunSummary data={simulatedRun} previewSamples={simulatedSamples} />
    </section>
  );
}
