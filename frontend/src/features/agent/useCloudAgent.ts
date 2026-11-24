import { useCallback, useEffect, useRef, useState } from 'react';
import { sendNativeCommand, useBridgeStore } from '../../bridge/bridgeStore';
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
  runId?: string,
  mcpServices: string[] = [],
) {
  const [listing, setListing] = useState<CloudListing | null>(null);
  const [prepared, setPrepared] = useState<CloudPreparation | null>(null);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  const [lifecycleError, setLifecycleError] = useState('');
  const readOnly =
    listing?.conversationArchived ??
    (listing?.conversationId === null
      ? listing.legacyArchived === true
      : listing?.conversations?.some(
          (item) => item.id === listing.conversationId && item.archived,
        ) === true);
  const locked = useRef(false);
  const sequence = useRef(0);
  const reads = useRef(0);
  const refreshing = useRef(false);
  const refreshPending = useRef(false);
  const refreshEnabled = useRef(false);
  const token = useRef<string | null>(null);
  const selectionKey = JSON.stringify(memoryRefs);
  const knowledgeKey = JSON.stringify(knowledge);
  const mcpKey = JSON.stringify(mcpServices);
  const cloudRevision = useBridgeStore((state) => state.cloudRevision);
  const seenRevision = useRef(cloudRevision);

  const refresh = useCallback(async () => {
    if (!refreshEnabled.current || document.hidden) return;
    refreshPending.current = true;
    if (refreshing.current || locked.current) return;
    refreshing.current = true;
    try {
      while (
        refreshPending.current &&
        refreshEnabled.current &&
        !document.hidden &&
        !locked.current
      ) {
        // 通知只合并跟进读取；代际仅由会话、发送和页面生命周期失效。
        refreshPending.current = false;
        const read = reads.current;
        try {
          const result = await sendNativeCommand('cloud.list', {});
          if (read !== reads.current || document.hidden || !refreshEnabled.current) continue;
          if (result.status !== 'accepted') throw new Error();
          const parsed = cloudListingSchema.parse(result.data);
          setListing(parsed);
        } catch {
          if (read === reads.current && !document.hidden && refreshEnabled.current) {
            setListing(
              (current) =>
                current && {
                  ...current,
                  items: current.items.map((item) => ({ ...item, draftAnswer: undefined })),
                },
            );
            setError('暂时无法读取在线状态，请刷新。不会自动重发。');
          }
        }
      }
    } finally {
      refreshing.current = false;
    }
  }, []);

  useEffect(() => {
    if (seenRevision.current === cloudRevision) return;
    seenRevision.current = cloudRevision;
    if (!document.hidden) void refresh();
  }, [cloudRevision, refresh]);

  useEffect(() => {
    setListing(null);
    if (status !== 'ready') return;
    refreshEnabled.current = true;
    void refresh();
    // 这里只读本机状态，不向云端轮询；用于展示原生显式执行和配置窗口的结果。
    const timer = window.setInterval(() => {
      if (!document.hidden) void refresh();
    }, 2000);
    const visibilityChanged = () => {
      if (document.hidden) {
        reads.current += 1;
        refreshPending.current = false;
        setListing(
          (current) =>
            current && {
              ...current,
              items: current.items.map((item) => ({ ...item, draftAnswer: undefined })),
            },
        );
      } else void refresh();
    };
    document.addEventListener('visibilitychange', visibilityChanged);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', visibilityChanged);
      reads.current += 1;
      refreshEnabled.current = false;
      refreshPending.current = false;
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
  }, [status, prompt, selectionKey, knowledgeKey, runId, mcpKey]);

  useEffect(
    () => () => {
      if (token.current)
        void sendNativeCommand('cloud.discard', { token: token.current }).catch(() => undefined);
      token.current = null;
    },
    [],
  );

  async function prepare() {
    if (status !== 'ready' || !listing?.configured || readOnly || locked.current || !prompt.trim())
      return;
    locked.current = true;
    setWorking(true);
    setError('');
    const request = ++sequence.current;
    let sending = false;
    try {
      // 旧版历史不拼成会话；首次发送时创建独立会话，当前输入保留。
      let conversationId = listing.conversationId;
      if (conversationId === null) {
        const created = await sendNativeCommand('cloud.newConversation', {});
        if (created.status !== 'accepted') throw new Error();
        const next = cloudListingSchema.parse(created.data);
        if (request !== sequence.current) return;
        setListing(next);
        conversationId = next.conversationId;
        if (!conversationId) throw new Error();
      }
      const result = await sendNativeCommand('cloud.prepare', {
        prompt,
        ...(conversationId ? { conversationId } : {}),
        memoryRefs,
        ...(knowledge ? { knowledge } : {}),
        ...(runId ? { runId } : {}),
        ...(mcpServices.length ? { mcpServices } : {}),
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
        value.conversation?.id !== (conversationId ?? undefined) ||
        value.runSummary?.runId !== runId ||
        JSON.stringify(value.mcpServices?.map(({ id }) => id) ?? []) !== mcpKey ||
        JSON.stringify(value.knowledge) !== knowledgeKey ||
        JSON.stringify(value.memories.map(({ id, version }) => ({ id, version }))) !== selectionKey
      ) {
        void sendNativeCommand('cloud.discard', { token: value.token }).catch(() => undefined);
        throw new Error();
      }
      token.current = value.token;
      if (
        listing.chatAuthorized === true &&
        conversationId &&
        !memoryRefs.length &&
        !runId &&
        !mcpServices.length
      ) {
        // 原生仍须校验持续授权和预览快照；不能由 Web 绕过额外数据授权。
        reads.current++;
        sending = true;
        const sent = await sendNativeCommand('cloud.send', { token: value.token });
        if (request !== sequence.current) return false;
        if (sent.status !== 'accepted') throw new Error();
        reads.current++;
        setListing(cloudListingSchema.parse(sent.data));
        token.current = null;
        return true;
      }
      setPrepared(value);
      return false;
    } catch {
      if (request === sequence.current)
        setError(
          sending
            ? '发送未确认，请刷新状态。持续授权可能已关闭或配置已变化；已发出的任务仅恢复查询，不自动重发。'
            : '问题、记忆、跑步记录、知识库或插件选择核对失败，请重新选择后重试；没有发送。',
        );
      if (request === sequence.current && token.current) {
        void sendNativeCommand('cloud.discard', { token: token.current }).catch(() => undefined);
        token.current = null;
      }
      return false;
    } finally {
      if (request === sequence.current) {
        locked.current = false;
        setWorking(false);
        if (refreshPending.current) void refresh();
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

  async function changeConversation(conversationId?: string | null, title?: string) {
    if (status !== 'ready' || locked.current || prepared) return false;
    locked.current = true;
    setWorking(true);
    reads.current++;
    try {
      const result =
        title !== undefined && conversationId
          ? await sendNativeCommand('cloud.renameConversation', { conversationId, title })
          : conversationId === undefined
            ? await sendNativeCommand('cloud.newConversation', {})
            : await sendNativeCommand('cloud.selectConversation', { conversationId });
      if (result.status !== 'accepted') throw new Error();
      setListing(cloudListingSchema.parse(result.data));
      setError('');
      return true;
    } catch {
      setError('会话操作未完成，请刷新后重试；没有发送问题。');
      return false;
    } finally {
      locked.current = false;
      setWorking(false);
      if (refreshPending.current) void refresh();
    }
  }

  async function manageConversation(
    name:
      | 'cloud.archiveConversation'
      | 'cloud.restoreConversation'
      | 'cloud.deleteConversation'
      | 'cloud.retryConversationCleanup',
    conversationId: string | null = null,
  ) {
    if (status !== 'ready' || locked.current || prepared) return false;
    locked.current = true;
    setWorking(true);
    setLifecycleError('');
    const request = ++sequence.current;
    reads.current++;
    try {
      const result =
        name === 'cloud.retryConversationCleanup'
          ? await sendNativeCommand(name, {})
          : name === 'cloud.deleteConversation'
            ? await sendNativeCommand(name, { conversationId, confirmed: true })
            : await sendNativeCommand(name, { conversationId });
      if (request !== sequence.current) return false;
      if (result.status !== 'accepted') throw new Error();
      reads.current++;
      setListing(cloudListingSchema.parse(result.data));
      return true;
    } catch {
      if (request === sequence.current)
        setLifecycleError('会话操作未确认完成，请刷新在线记录后重试。没有发送问题。');
      return false;
    } finally {
      if (request === sequence.current) {
        locked.current = false;
        setWorking(false);
        if (refreshPending.current) void refresh();
      }
    }
  }

  async function act(
    name: 'cloud.confirm' | 'cloud.recover' | 'cloud.cancel' | 'cloud.withdraw' | 'cloud.configure',
    requestId?: string,
  ) {
    if (
      status !== 'ready' ||
      locked.current ||
      (readOnly && name !== 'cloud.configure') ||
      (name === 'cloud.confirm' && !token.current)
    )
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
          name === 'cloud.cancel'
            ? '停止请求尚未确认，请恢复查询原任务以核对状态，不要重新发送问题。'
            : '操作未确认完成，请刷新在线记录。已授权的请求只恢复查询，不自动重发；记忆或跑步记录若有变更需重新授权。',
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
        if (refreshPending.current) void refresh();
      }
    }
  }
  async function speak(requestId?: string) {
    if (status !== 'ready') return;
    try {
      const result = requestId
        ? await sendNativeCommand('cloud.speak', { requestId })
        : await sendNativeCommand('cloud.stopSpeech', {});
      if (result.status !== 'accepted') throw new Error();
      setListing(cloudListingSchema.parse(result.data));
    } catch {
      setError('语音操作未完成，请检查语音设置和运动状态后重试。');
    }
  }

  return {
    speak,
    listing,
    prepared,
    working,
    error,
    lifecycleError,
    readOnly,
    prepare,
    discard,
    act,
    refresh,
    changeConversation,
    manageConversation,
  };
}
