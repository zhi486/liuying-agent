import { ArrowUpRight, CalendarBlank, ChartLine, ChatCircle } from '@phosphor-icons/react';
import styles from './AgentWelcome.module.css';

const taskPrompts = [
  {
    icon: ChartLine,
    label: '复盘一次跑步',
    detail: '看看表现与恢复',
    prompt: '请带我复盘最近一次跑步，说明数据能支持的结论和不足，不做医学诊断。',
  },
  {
    icon: CalendarBlank,
    label: '安排本周训练',
    detail: '找到适合的节奏',
    prompt: '请根据我最近的状态，帮我安排本周训练，并说明每次训练的目的和强度。',
  },
  {
    icon: ChatCircle,
    label: '聊聊今天的状态',
    detail: '从此刻的感受开始',
    prompt: '我想聊聊今天的状态，请先问我几个关键问题，再给出调整建议。',
  },
] as const;

type Props = {
  bridgeTone: string;
  bridgeStatus: string;
  configured: boolean;
  authorization: string;
  continuous: boolean;
  voiceBusy: boolean;
  showPrompts: boolean;
  onPrompt: (prompt: string) => void;
};

export function AgentWelcome(props: Props) {
  const statuses = [
    { label: '本机', value: props.bridgeStatus, tone: props.bridgeTone },
    {
      label: 'Agent',
      value: props.configured ? '已配置' : '未配置',
      tone: props.configured ? 'good' : 'muted',
    },
    { label: '执行', value: props.authorization, tone: props.continuous ? 'good' : 'muted' },
    ...(props.voiceBusy ? [{ label: '语音', value: '处理中', tone: 'active' }] : []),
  ];
  return (
    <div className={styles.welcome}>
      <header className={styles.intro}>
        <h1 id="agent-title">今天想把哪件事交给流萤？</h1>
        <p>训练、跑步和今天的状态，都可以从一次具体的问题开始。</p>
      </header>
      {props.showPrompts && (
        <div className={styles.prompts} role="group" aria-label="任务起点">
          {taskPrompts.map(({ icon: Icon, ...item }) => (
            <button key={item.label} type="button" onClick={() => props.onPrompt(item.prompt)}>
              <Icon className={styles.promptIcon} size={21} aria-hidden="true" />
              <span className={styles.promptCopy}>
                <strong>{item.label}</strong>
                <small>{item.detail}</small>
              </span>
              <ArrowUpRight className={styles.promptArrow} size={17} aria-hidden="true" />
            </button>
          ))}
        </div>
      )}
      <section className={styles.statuses} aria-label="当前工作状态" aria-live="polite">
        {statuses.map((status) => (
          <div className={styles.status} key={status.label}>
            <span className={styles.dot} data-tone={status.tone} aria-hidden="true" />
            <span className={styles.statusLabel}>{status.label}</span>
            <strong>{status.value}</strong>
          </div>
        ))}
      </section>
    </div>
  );
}
