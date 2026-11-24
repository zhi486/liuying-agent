import { useEffect, useRef, useState } from 'react';
import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
import {
  mcpDetailSchema,
  mcpEndpointSchema,
  mcpListSchema,
  mcpRemovedSchema,
  mcpStatusSchema,
} from '../../bridge/mcp';
import type { McpCommandBodies, McpDetail, McpListing, McpStatus } from '../../bridge/mcp';
import type { CommandBodyMap } from '../../bridge/contract';
import styles from './AgentSettings.module.css';
import mcpStyles from './McpSettingsPage.module.css';
import { ChoiceInput } from '../../design-system/ChoiceInput';

const operationNames = {
  unknown: '尚未确认收到',
  running: '上次确认仍在进行',
  completed: '目录已保存',
  unconfirmed: '发现未完成',
};
export function McpSettingsPage() {
  const bridge = useBridgeStore((state) => state.status);
  const [local, setLocal] = useState<McpStatus | null>(null);
  const [listing, setListing] = useState<McpListing | null>(null);
  const [detail, setDetail] = useState<McpDetail | null>(null);
  const [endpoint, setEndpoint] = useState('');
  const [editedEndpoint, setEditedEndpoint] = useState('');
  const [endpointAgreed, setEndpointAgreed] = useState(false);
  const [removeAgreed, setRemoveAgreed] = useState(false);
  const [listRemovalAgreed, setListRemovalAgreed] = useState<string | null>(null);
  const [selection, setSelection] = useState<Record<string, 'off' | 'read' | 'confirm'>>({});
  const [discoveryAgreed, setDiscoveryAgreed] = useState(false);
  const [approvalAgreed, setApprovalAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const epoch = useRef(0);
  const locked = useRef(false);
  useEffect(() => {
    const current = ++epoch.current;
    setLocal(null);
    setListing(null);
    setDetail(null);
    setSelection({});
    setDiscoveryAgreed(false);
    setApprovalAgreed(false);
    setEditedEndpoint('');
    setEndpointAgreed(false);
    setRemoveAgreed(false);
    setListRemovalAgreed(null);
    setError('');
    setNotice('');
    setBusy(false);
    locked.current = false;
    if (bridge === 'ready') {
      void (async () => {
        const response = await sendNativeCommand('mcp.status', {});
        if (current !== epoch.current) return;
        if (response.status !== 'accepted') throw new Error();
        const status = mcpStatusSchema.parse(response.data);
        setLocal(status);
        if (!status.identityToken) return;
        const result = await sendNativeCommand('mcp.list', {
          identityToken: status.identityToken,
          after: null,
        });
        if (current !== epoch.current) return;
        if (result.status !== 'accepted') throw new Error();
        const list = mcpListSchema.parse(result.data);
        if (list.identityToken !== status.identityToken) throw new Error();
        setListing(list);
      })().catch(() => {
        if (current === epoch.current)
          setError('无法读取插件配置。云端可能尚未支持此入口，请稍后重新读取；不会自动重试。');
      });
    }
    return () => {
      epoch.current = current + 1;
    };
  }, [bridge, revision]);

  async function perform<N extends keyof McpCommandBodies>(
    name: N,
    body: CommandBodyMap[N],
    apply: (value: unknown) => void,
  ) {
    if (bridge !== 'ready' || locked.current || !local?.identityToken) return;
    locked.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    const current = epoch.current;
    try {
      const result = await sendNativeCommand(name, body);
      if (current !== epoch.current) return;
      if (result.status !== 'accepted') throw new Error();
      const data = result.data as { identityToken?: unknown } | undefined;
      if (data?.identityToken !== local.identityToken) throw new Error();
      apply(data);
    } catch {
      if (current === epoch.current) {
        // 详情目录可能损坏，保留上次读取的服务元数据供明确移除；原生仍重读版本。
        if (name !== 'mcp.detail') setListing(null);
        setDetail(null);
        setListRemovalAgreed(null);
        setDiscoveryAgreed(false);
        setApprovalAgreed(false);
        setError(
          '操作结果未确认。请重新读取配置，发现请求请先查询原记录；若已开始修改地址或移除，手机私密配置可能已撤销。不会自动重试或启用工具。',
        );
        // 此处只读本机操作记录，不查询云端、不补发发现，保留超时操作的恢复入口。
        const result = await sendNativeCommand('mcp.status', {}).catch(() => null);
        if (current === epoch.current && result?.status === 'accepted') {
          const parsed = mcpStatusSchema.safeParse(result.data);
          if (parsed.success && parsed.data.identityToken === local.identityToken)
            setLocal(parsed.data);
        }
      }
    } finally {
      if (current === epoch.current) {
        locked.current = false;
        setBusy(false);
      }
    }
  }
  const identityToken = local?.identityToken ?? '';
  function showDetail(value: unknown) {
    const next = mcpDetailSchema.parse(value);
    setDetail(next);
    setSelection({});
    setDiscoveryAgreed(false);
    setApprovalAgreed(false);
    setEditedEndpoint(next.service.endpoint);
    setEndpointAgreed(false);
    setRemoveAgreed(false);
    setListRemovalAgreed(null);
    setListing(
      (previous) =>
        previous && {
          ...previous,
          services: previous.services.map((service) =>
            service.id === next.service.id
              ? { ...service, ...next.service, private: next.private }
              : service,
          ),
        },
    );
  }
  const selected =
    detail?.service.catalog?.tools.flatMap((tool) => {
      const mode = selection[tool.name];
      return mode === 'read' || mode === 'confirm' ? [{ name: tool.name, mode }] : [];
    }) ?? [];
  const reviewed = detail
    ? { identityToken, id: detail.service.id, revision: detail.service.revision }
    : null;
  return (
    <section className={`standard-page ${styles.page} ${mcpStyles.page}`}>
      <p>密钥留在手机，服务地址、工具目录和权限保存在云端。仅支持公开 HTTPS 服务。</p>
      <p className={styles.notice}>
        聊天中可从“更多输入选项”选择本次插件；正式后端升级与真机验收尚待完成。登记或发现不会自动授权，关闭私密配置不会撤回已发出的请求。
      </p>
      {bridge !== 'ready' ? (
        <p role="status">请在手机连接就绪后管理插件。</p>
      ) : (
        <>
          {error && <p role="alert">{error}</p>}
          {notice && <p role="status">{notice}</p>}
          {busy && <p role="status">正在处理，请勿重复操作…</p>}
          {!local ? (
            !error && <p>正在读取本机状态…</p>
          ) : !local.configured ? (
            <p>请先配置在线 Agent。</p>
          ) : (
            <>
              <nav className={mcpStyles.steps} aria-label="插件接入步骤">
                {[
                  ['mcp-register', '登记服务'],
                  ['mcp-private', '手机私密配置'],
                  ['mcp-discovery', '发现目录'],
                  ['mcp-permissions', '审阅授权'],
                ].map(([target, label], index) => (
                  <button
                    key={target}
                    type="button"
                    disabled={index > 0 && !detail}
                    onClick={() => {
                      const heading = document.getElementById(target);
                      heading?.scrollIntoView({ block: 'start', behavior: 'instant' });
                      heading?.focus({ preventScroll: true });
                    }}
                  >
                    <span>{index + 1}</span>
                    <span>
                      {label}
                      <small>
                        {index === 0
                          ? '先登记或选择服务'
                          : !detail
                            ? '选择服务后查看'
                            : index === 1
                              ? detail.private.configured && detail.private.endpointMatches
                                ? '本机配置已保存'
                                : '尚需配置'
                              : index === 2
                                ? detail.service.catalog
                                  ? '已有保存目录'
                                  : '尚无确认目录'
                                : detail.service.enabled
                                  ? '已有工具权限'
                                  : '尚未启用'}
                      </small>
                    </span>
                  </button>
                ))}
              </nav>
              <section className={mcpStyles.stage} aria-label="登记插件服务">
                <h2 id="mcp-register" tabIndex={-1} className={mcpStyles.stepHeading}>
                  1 · 登记服务
                </h2>
                <h2>添加服务</h2>
                <form
                  className={mcpStyles.form}
                  onSubmit={(event) => {
                    event.preventDefault();
                    if (!mcpEndpointSchema.safeParse(endpoint.trim()).success) {
                      setError(
                        '请填写规范的公开 HTTPS 地址，仅支持 443 端口，不能包含查询参数、片段或密钥。',
                      );
                      return;
                    }
                    void perform(
                      'mcp.register',
                      { identityToken, endpoint: endpoint.trim() },
                      (value) => {
                        showDetail(value);
                        setEndpoint('');
                        setNotice('已登记，尚未启用。请打开下方原生私密配置。');
                      },
                    );
                  }}
                >
                  <label htmlFor="mcp-endpoint">服务地址（不能包含密钥）</label>
                  <input
                    id="mcp-endpoint"
                    type="url"
                    value={endpoint}
                    maxLength={2048}
                    placeholder="https://tools.example.com/mcp"
                    autoComplete="off"
                    disabled={busy || !listing}
                    onChange={(event) => setEndpoint(event.target.value)}
                  />
                  <button
                    className="primary-button"
                    disabled={busy || !listing || !endpoint.trim()}
                    type="submit"
                  >
                    仅登记服务
                  </button>
                </form>
              </section>
              <section aria-label="已登记服务">
                <h2>服务列表</h2>
                {listing?.services.length === 0 && <p>还没有插件服务，登记后再选择工具权限。</p>}
                <ul className={mcpStyles.list}>
                  {listing?.services.map((service) => (
                    <li className={styles.noticeSection} key={service.id}>
                      <p className={mcpStyles.endpoint}>{service.endpoint}</p>
                      <p>
                        {service.enabled ? '工具权限已保存' : '未启用'} ·{' '}
                        {service.private.configured && service.private.endpointMatches
                          ? service.private.hasCredential
                            ? '手机已存密钥'
                            : '手机已存匿名配置'
                          : '手机未配置或端点已变化'}
                      </p>
                      <button
                        className="secondary-button"
                        disabled={busy}
                        type="button"
                        onClick={() =>
                          void perform('mcp.detail', { identityToken, id: service.id }, showDetail)
                        }
                      >
                        管理此服务
                      </button>
                      <details className={mcpStyles.serviceChanges}>
                        <summary>无法管理？移除此服务</summary>
                        <p>
                          目录无法读取时也可移除。会删除此服务的云端登记、目录和权限，并撤销这台手机的私密配置；发现记录保留，不影响其他服务。
                        </p>
                        <label className={styles.consentCheck}>
                          <ChoiceInput
                            checked={listRemovalAgreed === service.id}
                            disabled={busy}
                            onChange={(event) =>
                              setListRemovalAgreed(event.target.checked ? service.id : null)
                            }
                          />
                          <span>确认从列表移除此服务，并撤销这台手机的私密配置。</span>
                        </label>
                        <button
                          className="secondary-button"
                          type="button"
                          disabled={busy || listRemovalAgreed !== service.id}
                          onClick={() =>
                            void perform(
                              'mcp.remove',
                              {
                                identityToken,
                                id: service.id,
                                revision: service.revision,
                                context: service.private.context,
                                confirmed: true,
                              },
                              (value) => {
                                mcpRemovedSchema.parse(value);
                                setListing(
                                  (previous) =>
                                    previous && {
                                      ...previous,
                                      services: previous.services.filter(
                                        (item) => item.id !== service.id,
                                      ),
                                    },
                                );
                                setDetail(null);
                                setListRemovalAgreed(null);
                                setNotice(
                                  '服务已移除，这台手机的私密配置已撤销；发现记录仍可只读查询。',
                                );
                              },
                            )
                          }
                        >
                          确认移除列表中的服务
                        </button>
                      </details>
                    </li>
                  ))}
                </ul>
                {listing?.nextCursor && (
                  <button
                    className="secondary-button"
                    disabled={busy}
                    type="button"
                    onClick={() =>
                      void perform(
                        'mcp.list',
                        { identityToken, after: listing.nextCursor },
                        (value) => {
                          setListing(mcpListSchema.parse(value));
                          setDetail(null);
                          setListRemovalAgreed(null);
                        },
                      )
                    }
                  >
                    下一页服务
                  </button>
                )}
              </section>
              {detail && reviewed && (
                <section className={mcpStyles.stage} aria-label="当前服务管理">
                  <h2>工具与连接</h2>
                  <p className={mcpStyles.endpoint}>{detail.service.endpoint}</p>
                  <h2 id="mcp-private" tabIndex={-1} className={mcpStyles.stepHeading}>
                    2 · 手机私密配置
                  </h2>
                  <div className={mcpStyles.actions}>
                    <button
                      className="secondary-button"
                      type="button"
                      disabled={busy}
                      onClick={() =>
                        void perform('mcp.configure', reviewed, () =>
                          setNotice(
                            '请在手机原生窗口填写；关闭后点“重新读取配置”。网页不会接收密钥。',
                          ),
                        )
                      }
                    >
                      手机私密配置
                    </button>
                    {detail.private.configured && (
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={busy}
                        onClick={() =>
                          void perform(
                            'mcp.revokePrivate',
                            {
                              identityToken,
                              id: detail.service.id,
                              context: detail.private.context,
                            },
                            () => {
                              setDetail(null);
                              setNotice('手机私密配置已撤销，请重新读取；云端工具权限需另行停用。');
                            },
                          )
                        }
                      >
                        撤销手机私密配置
                      </button>
                    )}
                    {detail.service.enabled && (
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={busy}
                        onClick={() => void perform('mcp.disable', reviewed, showDetail)}
                      >
                        停用全部工具
                      </button>
                    )}
                  </div>
                  <h2 id="mcp-discovery" tabIndex={-1} className={mcpStyles.stepHeading}>
                    3 · 发现目录
                  </h2>
                  <label className={styles.consentCheck}>
                    <ChoiceInput
                      checked={discoveryAgreed}
                      disabled={busy}
                      onChange={(event) => setDiscoveryAgreed(event.target.checked)}
                    />
                    <span>
                      同意本次由手机连接此服务，发送握手及目录查询，并把工具目录交给云端；不执行工具。
                    </span>
                  </label>
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={
                      busy ||
                      !discoveryAgreed ||
                      !listing?.phoneDiscoveryAvailable ||
                      !detail.private.configured ||
                      !detail.private.endpointMatches
                    }
                    onClick={() =>
                      void perform('mcp.discover', { ...reviewed, confirmed: true }, (value) => {
                        setLocal(mcpStatusSchema.parse(value));
                        setDetail(null);
                        setNotice('发现操作已结束，请重新读取目录后审核工具。');
                      })
                    }
                  >
                    发现工具目录
                  </button>
                  {!listing?.phoneDiscoveryAvailable && <p>当前云服务未声明支持手机目录发现。</p>}
                  <h2
                    id="mcp-permissions"
                    tabIndex={-1}
                    className={`${mcpStyles.permissionHeading} ${mcpStyles.stepHeading}`}
                  >
                    4 · 逐项审核权限
                  </h2>
                  <p>
                    工具说明来自第三方，不是可信指令。只读权限由你决定，不能仅凭服务自报“只读”授权；有写入或外部副作用的工具请选择逐次确认。
                  </p>
                  {!detail.service.catalog ? (
                    <p>尚无已确认目录。</p>
                  ) : detail.service.catalog.tools.length === 0 ? (
                    <p>此目录没有工具。</p>
                  ) : (
                    <>
                      <ul className={mcpStyles.list}>
                        {detail.service.catalog.tools.map((tool, index) => (
                          <li className={mcpStyles.tool} key={tool.name}>
                            <h3>{tool.name}</h3>
                            {typeof tool.definition.description === 'string' && (
                              <p>{tool.definition.description}</p>
                            )}
                            <details>
                              <summary>查看工具定义（第三方提供）</summary>
                              <pre>{JSON.stringify(tool.definition, null, 2)}</pre>
                            </details>
                            <p>
                              当前权限：
                              {detail.service.grants.find((grant) => grant.name === tool.name)
                                ?.mode === 'read'
                                ? '允许只读调用'
                                : detail.service.grants.some((grant) => grant.name === tool.name)
                                  ? '每次执行需确认'
                                  : '未授权'}
                            </p>
                            <label htmlFor={`mcp-mode-${index}`}>本次选择</label>
                            <select
                              id={`mcp-mode-${index}`}
                              value={
                                selection[tool.name] === 'read' ||
                                selection[tool.name] === 'confirm'
                                  ? selection[tool.name]
                                  : 'off'
                              }
                              disabled={busy}
                              onChange={(event) => {
                                setSelection((previous) => ({
                                  ...previous,
                                  [tool.name]: event.target.value as 'off' | 'read' | 'confirm',
                                }));
                                setApprovalAgreed(false);
                              }}
                            >
                              <option value="off">不授权</option>
                              <option value="read">允许只读调用</option>
                              <option value="confirm">每次执行需确认</option>
                            </select>
                          </li>
                        ))}
                      </ul>
                      <label className={styles.consentCheck}>
                        <ChoiceInput
                          checked={approvalAgreed}
                          disabled={busy}
                          onChange={(event) => setApprovalAgreed(event.target.checked)}
                        />
                        <span>
                          我已审核所选工具和权限；保存将替换此服务原有权限，未选工具不获授权。
                        </span>
                      </label>
                      <button
                        className="primary-button"
                        type="button"
                        disabled={busy || !approvalAgreed || selected.length === 0}
                        onClick={() =>
                          void perform(
                            'mcp.approve',
                            { ...reviewed, selection: selected, confirmed: true },
                            (value) => {
                              showDetail(value);
                              setNotice('工具权限已保存；可到聊天选择本次使用的插件。');
                            },
                          )
                        }
                      >
                        保存所选权限
                      </button>
                    </>
                  )}
                  <details className={mcpStyles.serviceChanges} key={detail.service.revision}>
                    <summary>修改地址或移除服务</summary>
                    <p>
                      修改地址会清除云端目录和工具权限，需要重新配置、发现和审核。两项操作都会先撤销这台手机的私密配置；即使云端变更失败或结果未确认，也不会恢复旧密钥。不能撤回已经发出的外部请求。
                    </p>
                    <form
                      className={mcpStyles.form}
                      onSubmit={(event) => {
                        event.preventDefault();
                        const address = editedEndpoint.trim();
                        if (
                          !mcpEndpointSchema.safeParse(address).success ||
                          address === detail.service.endpoint
                        ) {
                          setError(
                            '请填写与当前不同的规范公开 HTTPS 地址，不能包含查询参数、片段或密钥。',
                          );
                          return;
                        }
                        if (!endpointAgreed) return;
                        void perform(
                          'mcp.endpoint',
                          {
                            ...reviewed,
                            context: detail.private.context,
                            confirmed: true,
                            endpoint: address,
                          },
                          (value) => {
                            showDetail(value);
                            setNotice(
                              '服务地址已修改，旧目录、工具权限和本机私密配置已失效。请重新配置并发现工具。',
                            );
                          },
                        );
                      }}
                    >
                      <label htmlFor="mcp-edited-endpoint">新的服务地址</label>
                      <input
                        id="mcp-edited-endpoint"
                        type="url"
                        value={editedEndpoint}
                        maxLength={2048}
                        autoComplete="off"
                        disabled={busy}
                        onChange={(event) => {
                          setEditedEndpoint(event.target.value);
                          setEndpointAgreed(false);
                        }}
                      />
                      <label className={styles.consentCheck}>
                        <ChoiceInput
                          checked={endpointAgreed}
                          disabled={busy}
                          onChange={(event) => setEndpointAgreed(event.target.checked)}
                        />
                        <span>确认修改地址，并撤销旧连接配置和工具权限。</span>
                      </label>
                      <button
                        className="secondary-button"
                        type="submit"
                        disabled={
                          busy ||
                          !endpointAgreed ||
                          !editedEndpoint.trim() ||
                          editedEndpoint.trim() === detail.service.endpoint
                        }
                      >
                        确认修改地址
                      </button>
                    </form>
                    <div className={mcpStyles.removeService}>
                      <p>
                        移除会删除此服务的云端登记、目录及工具权限；本机发现记录保留供查询，不会重新连接服务。
                      </p>
                      <label className={styles.consentCheck}>
                        <ChoiceInput
                          checked={removeAgreed}
                          disabled={busy}
                          onChange={(event) => setRemoveAgreed(event.target.checked)}
                        />
                        <span>确认移除此服务，并撤销这台手机的私密配置。</span>
                      </label>
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={busy || !removeAgreed}
                        onClick={() =>
                          void perform(
                            'mcp.remove',
                            { ...reviewed, context: detail.private.context, confirmed: true },
                            (value) => {
                              mcpRemovedSchema.parse(value);
                              setListing(
                                (previous) =>
                                  previous && {
                                    ...previous,
                                    services: previous.services.filter(
                                      (service) => service.id !== detail.service.id,
                                    ),
                                  },
                              );
                              setDetail(null);
                              setSelection({});
                              setDiscoveryAgreed(false);
                              setApprovalAgreed(false);
                              setEndpointAgreed(false);
                              setRemoveAgreed(false);
                              setNotice(
                                '服务已移除，这台手机的私密配置已撤销；发现记录仍可只读查询。',
                              );
                            },
                          )
                        }
                      >
                        确认移除服务
                      </button>
                    </div>
                  </details>
                </section>
              )}
              <section aria-label="本机发现记录">
                <h2>发现记录</h2>
                <p>
                  以下是上次确认状态，不代表此刻在线。恢复只查询同一个操作，不重新连接插件或重发目录发现。
                </p>
                {!local.operations.length && <p>没有本机发现记录。</p>}
                <ul className={mcpStyles.list}>
                  {local.operations.map((operation) => (
                    <li className={styles.noticeSection} key={operation.requestId}>
                      <p className={mcpStyles.endpoint}>{operation.endpoint}</p>
                      <p>{operationNames[operation.status]}</p>
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={busy}
                        onClick={() =>
                          void perform(
                            'mcp.recover',
                            { identityToken, requestId: operation.requestId },
                            (value) => setLocal(mcpStatusSchema.parse(value)),
                          )
                        }
                      >
                        查询原发现记录
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            </>
          )}
          <button
            className="secondary-button"
            type="button"
            disabled={busy}
            onClick={() => setRevision((value) => value + 1)}
          >
            重新读取配置
          </button>
        </>
      )}
    </section>
  );
}
