import { useCallback, useEffect, useRef, useState } from 'react';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import {
  cloudListingSchema,
  cloudPreparationSchema,
  type CloudListing,
  type CloudPreparation,
  type SelectedMemoryRef,
  type KnowledgeSelection,
} from '../../bridge/cloudAgent';

export function useCloudAgent(
  status: string,
  prompt: string,
  memoryRefs: SelectedMemoryRef[],
  knowledge?: KnowledgeSelection,
) {
  const [listing, setListing] = useState<CloudListing | null>(null);
  const [prepared, setPrepared] = useState<CloudPreparation | null>(null);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  const locked = useRef(false);
  const sequence = useRef(0);
  const reads = useRef(0);
  const token = useRef<string | null>(null);
  const selectionKey = JSON.stringify(memoryRefs);
  const knowledgeKey = JSON.stringify(knowledge);

  const refresh = useCallback(async () => {
    if (status !== 'ready' || locked.current) return;
    const read = ++reads.current;
    try {
      const result = await sendNativeCommand('cloud.list', {});
      if (read !== reads.current) return;
      if (result.status !== 'accepted') throw new Error();
      const parsed = cloudListingSchema.parse(result.data);
      setListing(parsed);
    } catch {
      if (read === reads.current) setError('暂时无法读取在线状态，请刷新。不会自动重发。');
    }
  }, [status]);

  useEffect(() => {
    setListing(null);
    if (status !== 'ready') return;
    void refresh();
    // 这里只读本机状态，不向云端轮询；用于展示原生显式执行和配置窗口的结果。
    const timer = window.setInterval(() => {
      if (!document.hidden) void refresh();
    }, 2000);
    return () => {
      window.clearInterval(timer);
      reads.current += 1;
    };
  }, [status, refresh]);

  useEffect(() => {
    sequence.current++;
    locked.current = false;
    setWorking(false);
    setPrepared(null);
    setError('');
    if (token.current)
      void sendNativeCommand('cloud.discard', { token: token.current }).catch(() => undefined);
    token.current = null;
    return () => {
      sequence.current += 1;
    };
  }, [status, prompt, selectionKey, knowledgeKey]);

  useEffect(
    () => () => {
      if (token.current)
        void sendNativeCommand('cloud.discard', { token: token.current }).catch(() => undefined);
      token.current = null;
    },
    [],
  );

  async function prepare() {
    if (status !== 'ready' || !listing?.configured || locked.current || !prompt.trim()) return;
    locked.current = true;
    setWorking(true);
    setError('');
    const request = ++sequence.current;
    try {
      const result = await sendNativeCommand('cloud.prepare', {
        prompt,
        memoryRefs,
        ...(knowledge ? { knowledge } : {}),
      });
      if (result.status !== 'accepted') throw new Error();
      const value = cloudPreparationSchema.parse(result.data);
      if (request !== sequence.current) {
        void sendNativeCommand('cloud.discard', { token: value.token }).catch(() => undefined);
        return;
      }
      if (
        value.prompt !== prompt ||
        value.origin !== listing.origin ||
        JSON.stringify(value.knowledge) !== knowledgeKey ||
        JSON.stringify(value.memories.map(({ id, version }) => ({ id, version }))) !== selectionKey
      ) {
        void sendNativeCommand('cloud.discard', { token: value.token }).catch(() => undefined);
        throw new Error();
      }
      token.current = value.token;
      setPrepared(value);
    } catch {
      if (request === sequence.current)
        setError('问题、记忆或知识库选择核对失败，请重新选择后重试；没有发送。');
    } finally {
      if (request === sequence.current) {
        locked.current = false;
        setWorking(false);
      }
    }
  }

  function discard() {
    if (locked.current) return;
    if (token.current)
      void sendNativeCommand('cloud.discard', { token: token.current }).catch(() => undefined);
    token.current = null;
    setPrepared(null);
  }

  async function act(
    name: 'cloud.confirm' | 'cloud.recover' | 'cloud.cancel' | 'cloud.withdraw' | 'cloud.configure',
    requestId?: string,
  ) {
    if (status !== 'ready' || locked.current || (name === 'cloud.confirm' && !token.current))
      return false;
    locked.current = true;
    setWorking(true);
    setError('');
    const request = ++sequence.current;
    reads.current++;
    try {
      const result =
        name === 'cloud.confirm'
          ? await sendNativeCommand(name, { token: token.current!, approved: true })
          : name === 'cloud.configure'
            ? await sendNativeCommand(name, {})
            : await sendNativeCommand(name, { requestId: requestId! });
      if (request !== sequence.current) return false;
      if (result.status !== 'accepted') throw new Error();
      reads.current++;
      if (name !== 'cloud.configure') setListing(cloudListingSchema.parse(result.data));
      else await refresh();
      return true;
    } catch {
      if (request === sequence.current) {
        setError(
          '操作未确认完成，请刷新在线记录。已授权的请求只恢复查询，不自动重发；记忆若有变更需重新授权。',
        );
        void refresh();
      }
      return false;
    } finally {
      if (request === sequence.current) {
        if (name === 'cloud.confirm') {
          token.current = null;
          setPrepared(null);
        }
        locked.current = false;
        setWorking(false);
      }
    }
  }
  return { listing, prepared, working, error, prepare, discard, act, refresh };
}
