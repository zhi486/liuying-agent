import { ArrowLeft, CheckCircle, ShieldCheck, ArrowRight } from '@phosphor-icons/react';
import { useState } from 'react';
import styles from './ConsentPageReferenceSection.module.css';

const variants = [
  { id: 'A', name: '状态优先', description: '先确认当前状态与服务，再阅读范围，操作集中在页尾。' },
  {
    id: 'B',
    name: '范围清单',
    description: '按发送内容、单次核对和独立权限分组，一屏审阅授权边界。',
  },
  { id: 'C', name: '分步确认', description: '先阅读范围再进入确认页；可以返回复查，不预选同意。' },
] as const;

function Scope({ compact = false }: { compact?: boolean }) {
  return (
    <div className={compact ? styles.compactScope : styles.scope}>
      <section>
        <h4>本次授权包含</h4>
        <p>
          你主动发送的文字与当前会话最近上下文，会发送到已配置的云端和模型供应商，可能产生费用。不自动补发旧问题。
        </p>
      </section>
      <section>
        <h4>仍需单次核对</h4>
        <p>选中记忆、知识库、跑步摘要。</p>
      </section>
      <section>
        <h4>不包含的权限</h4>
        <p>录音、自主写入记忆与插件权限。</p>
      </section>
      <p className={styles.caveat}>
        仅对当前账号、设备、服务和凭据有效，更换后需重新授权。关闭不会撤回已经发出的请求。
      </p>
    </div>
  );
}

function ConsentPreview({
  variant,
  width,
}: {
  variant: (typeof variants)[number];
  width: '320' | '393';
}) {
  const [enabled, setEnabled] = useState(false);
  const [agreed, setAgreed] = useState(false);
  const [step, setStep] = useState(0);
  const [error, setError] = useState(false);
  const [notice, setNotice] = useState('');
  const confirmStep = variant.id !== 'C' || step === 1 || enabled;
  const agreement = (
    <label className={styles.agreement}>
      <input
        type="checkbox"
        checked={agreed}
        onChange={(event) => setAgreed(event.target.checked)}
      />
      <span>我同意上述范围，普通文字消息无需每次确认</span>
    </label>
  );
  const action = (
    <button
      type="button"
      className={enabled ? styles.secondary : styles.primary}
      disabled={!enabled && !agreed}
      onClick={() => {
        setEnabled(!enabled);
        setAgreed(false);
        setNotice(
          enabled ? '演示授权已关闭，已发送请求不撤回。' : '演示授权已开启，未修改真实权限。',
        );
      }}
    >
      {enabled ? '关闭持续授权' : '开启持续授权'}
    </button>
  );
  return (
    <div className={styles.phone} data-width={width} data-variant={variant.id}>
      <header>
        <button
          type="button"
          className={styles.back}
          onClick={() => {
            if (variant.id === 'C' && step) {
              setStep(0);
              setAgreed(false);
            } else setNotice('正式页面返回设置；此处为合成预览。');
          }}
        >
          <ArrowLeft size={16} aria-hidden="true" />
          {variant.id === 'C' && step ? '授权范围' : '设置'}
        </button>
        <ShieldCheck size={22} aria-hidden="true" />
      </header>
      <h3>聊天授权</h3>
      {variant.id === 'C' && (
        <ol className={styles.steps} aria-label="授权步骤">
          <li aria-current={step === 0 ? 'step' : undefined}>1 阅读范围</li>
          <li aria-current={step === 1 ? 'step' : undefined}>2 确认授权</li>
        </ol>
      )}
      <div className={styles.status}>
        <CheckCircle size={22} weight={enabled ? 'fill' : 'regular'} aria-hidden="true" />
        <div>
          <strong>{enabled ? '持续授权已开启' : '持续授权未开启'}</strong>
          <small>{enabled ? '普通文字消息点击即发送' : '普通文字消息仍需每次确认'}</small>
        </div>
      </div>
      <dl className={styles.service}>
        <dt>当前服务</dt>
        <dd>https://agent.example.com</dd>
      </dl>
      {error ? (
        <div role="alert" className={styles.error}>
          <h4>无法读取授权状态</h4>
          <p>不会自动重试，也不会提交授权更改。</p>
          <button
            type="button"
            className={styles.secondary}
            onClick={() => {
              setError(false);
              setNotice('合成状态已恢复，未查询真实服务。');
            }}
          >
            重新读取
          </button>
        </div>
      ) : (
        <>
          {variant.id === 'C' && step === 1 ? (
            <section className={styles.review}>
              <h4>普通文字消息点击即发送</h4>
              <p>文字与最近上下文会发送到云端和模型供应商，可能产生费用。不自动补发旧问题。</p>
              <p>选中记忆、知识库与跑步摘要仍需单次核对；录音、记忆写入与插件权限不在本授权内。</p>
              <p>仅对当前账号、设备、服务和凭据有效。关闭不会撤回已发送请求。</p>
            </section>
          ) : (
            <Scope compact={variant.id === 'B'} />
          )}
          <footer className={styles.actions}>
            {confirmStep ? (
              <>
                {!enabled && agreement}
                {action}
              </>
            ) : (
              <button type="button" className={styles.primary} onClick={() => setStep(1)}>
                继续确认
                <ArrowRight size={18} aria-hidden="true" />
              </button>
            )}
          </footer>
        </>
      )}
      <p role="status" className={styles.notice}>
        {notice}
      </p>
      <div className={styles.demo}>
        <button
          type="button"
          aria-pressed={error}
          onClick={() => {
            setError(!error);
            setAgreed(false);
          }}
        >
          模拟读取失败
        </button>
        <button
          type="button"
          onClick={() => {
            setEnabled(false);
            setAgreed(false);
            setError(false);
            setStep(0);
            setNotice('演示已重置。');
          }}
        >
          重置演示
        </button>
      </div>
    </div>
  );
}

export function ConsentPageReferenceSection({ width }: { width: '320' | '393' }) {
  const [selected, setSelected] = useState<string | null>('B');
  return (
    <section className={styles.section} aria-labelledby="consent-reference-heading">
      <h2 id="consent-reference-heading">聊天授权页的三种组织方式</h2>
      <p>三案授权范围一致；当前服务与状态均为合成数据，所有操作仅在预览内生效，不修改真实权限。</p>
      <div className={styles.variants}>
        {variants.map((variant) => (
          <article key={variant.id} aria-label={variant.id + ' ' + variant.name}>
            <div className={styles.variantHeading}>
              <h3>
                {variant.id} · {variant.name}
              </h3>
              <p>{variant.description}</p>
            </div>
            <ConsentPreview variant={variant} width={width} />
            <button
              type="button"
              className={styles.choose}
              aria-pressed={selected === variant.id}
              onClick={() => setSelected(variant.id)}
            >
              {variant.id === 'B'
                ? 'B 已接入正式页面'
                : selected === variant.id
                  ? '对比候选，正式页仍为 B'
                  : '比较 ' + variant.id + ' 方案'}
            </button>
          </article>
        ))}
      </div>
    </section>
  );
}
