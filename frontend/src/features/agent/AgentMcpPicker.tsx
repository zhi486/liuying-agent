import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import { mcpListSchema, mcpStatusSchema, type McpListing } from '../../bridge/mcp';
import styles from './AgentPage.module.css';
import { ChoiceInput } from '../../design-system/ChoiceInput';

type Props = {
  selected: string[];
  onChange: (ids: string[]) => void;
  disabled: boolean;
  scopeKey: string;
};

export function AgentMcpPicker({ selected, onChange, disabled, scopeKey }: Props) {
  const [listing, setListing] = useState<McpListing | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const epoch = useRef(0);
  const locked = useRef(false);
  useEffect(() => {
    const version = epoch;
    version.current++;
    locked.current = false;
    setListing(null);
    setBusy(false);
    setError('');
    return () => {
      version.current++;
    };
  }, [scopeKey]);

  async function load(more = false) {
    if (disabled || locked.current) return;
    locked.current = true;
    setBusy(true);
    setError('');
    const current = ++epoch.current;
    const previous = more ? listing : null;
    if (!more) {
      onChange([]);
      setListing(null);
    }
    try {
      // 用户显式读取目录；不自动发现工具、不连接插件、不读取凭据。
      const status = await sendNativeCommand('mcp.status', {});
      if (current !== epoch.current) return;
      if (status.status !== 'accepted') throw new Error();
      const identity = mcpStatusSchema.parse(status.data);
      if (
        !identity.configured ||
        !identity.identityToken ||
        (previous && previous.identityToken !== identity.identityToken)
      )
        throw new Error();
      const result = await sendNativeCommand('mcp.list', {
        identityToken: identity.identityToken,
        after: previous?.nextCursor ?? null,
      });
      if (current !== epoch.current) return;
      if (result.status !== 'accepted') throw new Error();
      const next = mcpListSchema.parse(result.data);
      if (next.identityToken !== identity.identityToken) throw new Error();
      const rows = [...(previous?.services ?? []), ...next.services];
      if (
        new Set(rows.map((row) => row.id)).size !== rows.length ||
        (previous && (!previous.nextCursor || next.nextCursor === previous.nextCursor))
      )
        throw new Error();
      setListing({ ...next, services: rows });
    } catch {
      if (current === epoch.current) {
        setListing(null);
        onChange([]);
        setError('插件目录未读到，请重新读取或到设置检查；没有连接插件。');
      }
    } finally {
      if (current === epoch.current) {
        locked.current = false;
        setBusy(false);
      }
    }
  }

  return (
    <fieldset className={styles.selectionFieldset} disabled={disabled || busy}>
      <legend>本次可用插件{selected.length ? `（${selected.length}/8）` : ''}</legend>
      <p>仅本次消息使用，需保持手机在前台。只读工具可自动调用，其余调用会展示参数让你确认。</p>
      <button type="button" onClick={() => void load()}>
        {busy ? '正在读取插件…' : listing ? '重新读取插件目录' : '读取可用插件'}
      </button>
      {error && <p role="alert">{error}</p>}
      {listing?.services.length === 0 && <p>尚无插件，请先到设置添加并授权工具。</p>}
      {listing?.services.map((row) => {
        const eligible = row.enabled && row.private.configured && row.private.endpointMatches;
        return (
          <label key={row.id} className={styles.mcpOption}>
            <ChoiceInput
              checked={selected.includes(row.id)}
              disabled={!eligible || (!selected.includes(row.id) && selected.length >= 8)}
              onChange={(event) =>
                onChange(
                  event.target.checked
                    ? [...selected, row.id]
                    : selected.filter((id) => id !== row.id),
                )
              }
            />
            <span>
              {row.endpoint}
              <small>
                {!row.enabled
                  ? '尚未启用工具'
                  : !row.private.configured || !row.private.endpointMatches
                    ? '需先在手机配置此服务'
                    : '发送前核对工具权限'}
              </small>
            </span>
          </label>
        );
      })}
      {listing?.nextCursor && (
        <button type="button" onClick={() => void load(true)}>
          读取更多插件
        </button>
      )}
      <Link className={styles.onlineInfo} to="/settings/plugins">
        管理 MCP 插件
      </Link>
    </fieldset>
  );
}
