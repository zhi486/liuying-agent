import { ArrowLeft, CaretDown, Plugs, BookOpen, MagnifyingGlass } from '@phosphor-icons/react';
import { useState } from 'react';
import styles from './ConsentPageReferenceSection.module.css';
import layout from './MorePagesReferenceSection.module.css';

const notices = [
  {
    title: '聊天与授权',
    summary: '发送什么，由你确认',
    text: '在线聊天会把本次输入和当前会话最近上下文发送到云端及模型供应商，可能产生费用。默认每次确认；主动开启持续授权后，普通文字消息点击即发送。更换账号、服务或凭据后需重新授权，不自动补发旧问题。',
    detail:
      '选中记忆、知识库和单条跑步摘要仍需单次核对，不默认发送其他会话、全部记忆、跑步轨迹或连续心率。请勿在输入中填写密钥等秘密。语音识别另行同意，结果只填入草稿。关闭授权不会撤回已发请求。',
  },
  {
    title: '记忆与第三方工具',
    summary: '自动记忆单独授权',
    text: '记忆保存在本机，可手动选择用于单次回答。自动记忆默认关闭，单独授权后才可按需读取并保存明确偏好与目标；用于回答的条目会发送到云端与模型供应商。关闭阻止后续调用，不清除已有记忆。更换账号、设备或服务配置需重新授权。',
    detail:
      '版本历史可另存为新版本；删除会清空正文及历史，无法在 App 内撤销。修改或删除不影响已展示的旧消息。撤回无法收回供应商已收到的内容。插件需独立配置与授权，不能视为聊天授权的一部分；后端升级与真机验收尚待完成。',
  },
  {
    title: '资料与回答',
    summary: '可追溯来源，不代替医学诊断',
    text: '所选知识库检索公共资料供参考；目前无可用匹配时不调用模型回答。来源可在回答下方查看，不代表逐条采用或每句话都有依据。',
    detail:
      '回答可能有误。跑步复盘只说明已提供数据及局限，不代替医学诊断。不要把建议当成已完成操作，保存与工具调用以应用实际反馈为准。',
  },
  {
    title: '费用与网络异常',
    summary: '缺失回执不代表零费用',
    text: '估算费用和 Token 在账单与用量中查看，不是实时余额或供应商账单。缺失回执待确认，不当作零费用；预算超出不关闭功能。',
    detail:
      '超时不代表云端未执行。恢复查询只读取原任务状态，不重新生成；状态未确认前不要重复发送同一问题。',
  },
];
const services = [
  {
    name: '训练资料',
    host: 'https://training.example.com/mcp',
    status: '目录已保存',
    tool: '搜索训练资料',
    mode: '只读',
  },
  {
    name: '个人日程',
    host: 'https://calendar.example.com/mcp',
    status: '尚未授权',
    tool: '创建训练日程',
    mode: '每次确认',
  },
];
const definitions = {
  notices: [
    { id: 'A', name: '连续阅读', description: '按主题通栏排版，全文直接可读，减少点击。' },
    { id: 'B', name: '问题索引', description: '先扫摘要，再原位展开；适合带着疑问查阅。' },
    { id: 'C', name: '分类阅读', description: '分类切换，单次聚焦一个主题，保留清晰目录。' },
  ],
  plugins: [
    { id: 'A', name: '服务列表', description: '以服务为入口，紧凑列表展开地址与工具权限。' },
    { id: 'B', name: '工具审阅', description: '工具与权限直接并排，优先看能做什么及如何确认。' },
    { id: 'C', name: '接入流程', description: '依次查看登记、私密配置、发现目录和授权的状态。' },
  ],
};
type Kind = keyof typeof definitions;

function NoticePreview({ variant }: { variant: string }) {
  const [active, setActive] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  return (
    <>
      {variant === 'B' && (
        <label className={layout.search}>
          <MagnifyingGlass size={18} aria-hidden="true" />
          <input
            aria-label="搜索须知"
            placeholder="搜索须知"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
      )}
      {variant === 'C' && (
        <div role="group" aria-label="须知分类" className={layout.categories}>
          {notices.map((item, index) => (
            <button
              type="button"
              key={item.title}
              aria-pressed={index === active}
              onClick={() => setActive(index)}
            >
              {item.title}
            </button>
          ))}
        </div>
      )}
      <div className={layout.reading}>
        {notices.map((item, index) => {
          if (variant === 'C' && index !== active) return null;
          if (variant === 'B' && !Object.values(item).join('').includes(search.trim())) return null;
          return (
            <section key={item.title}>
              {variant === 'B' ? (
                <button
                  type="button"
                  className={layout.question}
                  aria-expanded={open === index}
                  onClick={() => setOpen(open === index ? null : index)}
                >
                  <span>
                    <strong>{item.title}</strong>
                    <small>{item.summary}</small>
                  </span>
                  <CaretDown size={16} aria-hidden="true" />
                </button>
              ) : (
                <>
                  <h4>{item.title}</h4>
                  <p className={layout.summary}>{item.summary}</p>
                </>
              )}
              {(variant !== 'B' || open === index) && (
                <>
                  <p>{item.text}</p>
                  <p>{item.detail}</p>
                </>
              )}
            </section>
          );
        })}
        {variant === 'B' &&
          !notices.some((item) => Object.values(item).join('').includes(search.trim())) && (
            <p role="status">未找到相关须知。</p>
          )}
      </div>
    </>
  );
}

function PluginPreview({ variant }: { variant: string }) {
  const [open, setOpen] = useState<number | null>(null);
  const [empty, setEmpty] = useState(false);
  const [error, setError] = useState(false);
  const [step, setStep] = useState(0);
  const [notice, setNotice] = useState('');
  const steps = ['登记服务', '手机私密配置', '发现目录', '审阅授权'];
  return (
    <>
      <p>密钥留在手机，服务地址、工具目录和权限保存在云端。仅支持公开 HTTPS 服务。</p>
      <p className={styles.caveat}>登记或发现不代表已授权。正式后端升级与真机验收尚待完成。</p>
      {error ? (
        <div role="alert">
          <p>无法读取插件配置，不会自动重试。</p>
          <button type="button" onClick={() => setError(false)}>
            重新读取
          </button>
        </div>
      ) : empty ? (
        <div className={layout.empty}>
          <Plugs size={28} aria-hidden="true" />
          <h4>暂无插件服务</h4>
          <p>添加公开服务后，仍需完成私密配置、发现与单独授权。</p>
        </div>
      ) : variant === 'C' ? (
        <>
          <ol className={layout.process}>
            {steps.map((title, index) => (
              <li key={title}>
                <button type="button" aria-pressed={step === index} onClick={() => setStep(index)}>
                  <span>{index + 1}</span>
                  {title}
                </button>
              </li>
            ))}
          </ol>
          <section className={layout.stepBody}>
            <h4>{steps[step]}</h4>
            <p>
              {
                [
                  '合成服务已登记：https://training.example.com/mcp。登记本身不启用工具。',
                  '密钥只在原生手机界面填写；网页不显示或收集任何凭据。',
                  '发现会访问服务目录，需要明确确认；此预览不会发起发现请求。',
                  '搜索训练资料：只读。创建训练日程：每次确认。真实授权需在正式页单独核对。',
                ][step]
              }
            </p>
          </section>
        </>
      ) : (
        <ul className={layout.services}>
          {services.map((service, index) => (
            <li key={service.name}>
              <button
                type="button"
                className={layout.question}
                aria-expanded={variant === 'B' || open === index}
                onClick={() => setOpen(open === index ? null : index)}
              >
                <span>
                  <strong>{service.name}</strong>
                  <small>{service.status}</small>
                </span>
                <CaretDown size={16} aria-hidden="true" />
              </button>
              {(variant === 'B' || open === index) && (
                <div className={layout.serviceDetail}>
                  <p>{service.host}</p>
                  <dl>
                    <div>
                      <dt>{service.tool}</dt>
                      <dd>{service.mode}</dd>
                    </div>
                  </dl>
                  <button
                    type="button"
                    onClick={() => setNotice('仅预览工具权限；未提交任何真实授权。')}
                  >
                    审阅权限
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      <button
        type="button"
        className={styles.primary}
        onClick={() => setNotice('正式添加需核对公开 HTTPS 地址；本预览不登记服务、不收集密钥。')}
      >
        添加服务
      </button>
      <p role="status" className={styles.notice}>
        {notice}
      </p>
      <div className={styles.demo}>
        <button
          type="button"
          aria-pressed={empty}
          onClick={() => {
            setEmpty(!empty);
            setError(false);
          }}
        >
          切换空列表
        </button>
        <button type="button" aria-pressed={error} onClick={() => setError(!error)}>
          模拟读取失败
        </button>
      </div>
    </>
  );
}

export function MorePagesReferenceSection({ kind, width }: { kind: Kind; width: '320' | '393' }) {
  const baseline = kind === 'notices' ? 'A' : 'C';
  const [selected, setSelected] = useState<string | null>(baseline);
  const [notice, setNotice] = useState('');
  const title = kind === 'notices' ? '用户须知' : '插件管理';
  return (
    <section className={styles.section}>
      <h2>{title}页的三种组织方式</h2>
      <p>合成内容与服务，仅比较布局和前端交互；不修改正式页面、不调用模型、不连接外部服务。</p>
      <div className={styles.variants}>
        {definitions[kind].map((variant) => (
          <article key={variant.id} aria-label={variant.id + ' ' + variant.name}>
            <div className={styles.variantHeading}>
              <h3>
                {variant.id} · {variant.name}
              </h3>
              <p>{variant.description}</p>
            </div>
            <div className={styles.phone} data-width={width}>
              <header>
                <button
                  type="button"
                  className={styles.back}
                  onClick={() => setNotice('正式页面返回设置，当前仍在设计台。')}
                >
                  <ArrowLeft size={16} aria-hidden="true" />
                  设置
                </button>
                {kind === 'notices' ? (
                  <BookOpen size={22} aria-hidden="true" />
                ) : (
                  <Plugs size={22} aria-hidden="true" />
                )}
              </header>
              <h3>{title}</h3>
              {kind === 'notices' ? (
                <NoticePreview variant={variant.id} />
              ) : (
                <PluginPreview variant={variant.id} />
              )}
            </div>
            <button
              type="button"
              className={styles.choose}
              aria-pressed={selected === variant.id}
              onClick={() => setSelected(variant.id)}
            >
              {variant.id === baseline
                ? baseline + ' 已接入正式页面'
                : selected === variant.id
                  ? '对比候选，正式页仍为 ' + baseline
                  : '比较 ' + variant.id + ' 方案'}
            </button>
          </article>
        ))}
      </div>
      <p role="status">{notice}</p>
    </section>
  );
}
