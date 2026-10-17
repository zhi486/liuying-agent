import styles from './AgentSettings.module.css';

export function AgentNoticesPage() {
  return (
    <section className={`standard-page ${styles.page}`}>
      <header>
        <p className="eyebrow">在线 Agent</p>
        <h1>用户须知</h1>
      </header>
      <section className={styles.noticeSection}>
        <h2>聊天与授权</h2>
        <p>
          在线聊天会把本次输入及当前会话最近上下文发给云端和模型供应商，可能产生费用。默认每次确认；在“聊天授权”中主动开启持续授权后，普通文字消息点击即发送，可随时关闭。更换账号、服务或凭据后需重新授权，不会自动补发旧问题。
        </p>
        <p>
          选中记忆、知识库和单条跑步摘要仍需单次核对，不会默认发送其他会话、全部记忆、跑步轨迹或连续心率。输入中的个人信息也会发送，请勿填写密钥等秘密。语音识别需另行同意，识别结果只填入草稿。关闭授权不会撤回已经发出的请求。
        </p>
      </section>
      <section className={styles.noticeSection}>
        <h2>记忆与第三方工具</h2>
        <p>
          当前记忆由你管理、按本次选择提供。自主记忆与 MCP
          插件尚未开放，后续需单独开启和授权，不因升级自动扩大权限。
        </p>
        <p>
          删除记忆影响后续使用，撤回回答会隐藏本机内容并请求云端处理；这些操作无法撤回供应商已收到的内容。
        </p>
      </section>
      <section className={styles.noticeSection}>
        <h2>资料与回答</h2>
        <p>
          所选知识库会检索公共资料供模型参考；目前没有可用匹配时不调用模型回答。检索来源可在回答下方展开查看，不代表逐条采用或每句话都有依据。
        </p>
        <p>
          回答可能有误，跑步复盘只说明已提供的数据及其局限，不代替医学诊断。不要将模型建议当成已完成的操作，保存和工具调用以应用实际反馈为准。
        </p>
      </section>
      <section className={styles.noticeSection}>
        <h2>费用与网络异常</h2>
        <p>
          费用估算和 token
          用量在“账单与用量”中查看，不是实时余额或供应商账单。缺失回执显示待确认，不当作零费用；预算超出不关闭功能。
        </p>
        <p>
          超时不代表云端没有执行。“恢复查询”只读取原任务状态，不重新生成；状态未确认前不要重复发送同一问题。
        </p>
      </section>
    </section>
  );
}
