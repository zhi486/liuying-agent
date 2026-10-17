import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Microphone, X } from '@phosphor-icons/react';
import { sendNativeCommand } from '../../bridge/bridgeStore';
import { voiceStatusSchema } from '../../bridge/voiceInput';
import styles from './AgentPage.module.css';

/** 语音仅写入草稿；发送与云端单次授权沿用原流程。 */
export function AgentVoiceInput({
  disabled,
  onText,
  onBusy,
  children,
}: {
  disabled: boolean;
  onText(text: string): boolean;
  onBusy(busy: boolean): void;
  children?: (trigger: ReactNode) => ReactNode;
}) {
  const [state, setState] = useState('idle');
  const [message, setMessage] = useState('');
  const [preview, setPreview] = useState('');
  const active = useRef<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(
    () => () => {
      const id = active.current;
      active.current = null;
      clearTimeout(timer.current);
      if (id) void sendNativeCommand('voice.cancel', { sessionId: id }).catch(() => {});
      onBusy(false);
    },
    [onBusy],
  );

  function cancel(note = '已取消语音输入，草稿未改变') {
    const id = active.current;
    active.current = null;
    clearTimeout(timer.current);
    if (id) void sendNativeCommand('voice.cancel', { sessionId: id }).catch(() => {});
    setState('idle');
    setPreview('');
    setMessage(note);
    onBusy(false);
  }

  async function begin() {
    if (active.current || disabled) return;
    const id = crypto.randomUUID();
    active.current = id;
    setState('permission');
    setPreview('');
    setMessage('请允许本次麦克风访问');
    const deadline = Date.now() + 70000;
    async function accept(name: 'voice.begin' | 'voice.status') {
      try {
        const response = await sendNativeCommand(
          name,
          name === 'voice.begin' ? { sessionId: id, approved: true } : { sessionId: id },
        );
        if (active.current !== id) return;
        if (response.status !== 'accepted' || !('data' in response)) throw new Error();
        const result = voiceStatusSchema.parse(response.data);
        if (result.sessionId !== id) throw new Error();
        if (result.state === 'completed') {
          cancel(
            onText(result.text!)
              ? '已填入草稿，请核对后自行发送'
              : '识别文字与草稿合计超过2000字，原草稿保留，请缩短后重试',
          );
          return;
        }
        if (result.state === 'failed') {
          cancel(result.error ?? '语音识别失败，可继续文字输入');
          return;
        }
        if (result.state === 'idle' || Date.now() >= deadline) throw new Error();
        setState(result.state);
        setPreview(result.preview ?? '');
        setMessage(
          result.state === 'permission'
            ? '等待麦克风权限'
            : result.state === 'connecting'
              ? '正在连接流式识别，请稍候再说'
              : result.state === 'recording'
                ? '正在聆听，边说边识别，最长30秒'
                : '正在确认最终文字，不会自动发送',
        );
        timer.current = setTimeout(() => void accept('voice.status'), 500);
      } catch {
        if (active.current === id) cancel('语音输入未完成，草稿未改变；不会自动重发');
      }
    }
    await accept('voice.begin');
  }

  async function finish() {
    const id = active.current;
    if (!id || state !== 'recording') return;
    setState('transcribing');
    setMessage('正在识别，不会自动发送');
    try {
      const response = await sendNativeCommand('voice.finish', { sessionId: id });
      if (response.status !== 'accepted') throw new Error();
    } catch {
      if (active.current === id) cancel('结束录音未确认，已取消；不会自动重发');
    }
  }

  const panel =
    state === 'idle' ? (
      message ? (
        <div className={styles.voiceToast}>
          <p role="status">{message}</p>
          <button
            type="button"
            className={styles.voiceDismiss}
            aria-label="关闭语音提示"
            onClick={() => setMessage('')}
          >
            关闭
          </button>
        </div>
      ) : null
    ) : state === 'consent' ? (
      <div className={styles.voicePanel}>
        <p>
          本次录音期间，音频将经已配置的云端实时发送至阿里云 Fun-ASR，可能产生费用。最长30秒；本 App
          不保存录音。中间文字可能修订，完成后只填入草稿，不自动发送给流萤。
        </p>
        <button type="button" disabled={disabled} onClick={() => void begin()}>
          同意本次录音并识别
        </button>
        <button type="button" onClick={() => cancel()}>
          暂不录音
        </button>
        {message && <p role="status">{message}</p>}
      </div>
    ) : (
      <div className={styles.voicePanel}>
        {state === 'recording' && (
          <button type="button" onClick={() => void finish()}>
            结束并识别
          </button>
        )}
        <button type="button" onClick={() => cancel()}>
          取消语音输入
        </button>
        {message && <p role="status">{message}</p>}
        {preview && <p aria-label="实时识别预览">{preview}</p>}
      </div>
    );
  const trigger = (
    <section className={styles.voiceInput} aria-label="语音输入">
      <button
        type="button"
        className={styles.voiceTrigger}
        aria-label={state === 'idle' ? '语音输入' : '取消当前语音输入'}
        title={state === 'idle' ? '语音输入' : '取消当前语音输入'}
        disabled={state === 'idle' && disabled}
        onClick={() => {
          if (state !== 'idle') {
            cancel();
            return;
          }
          setState('consent');
          setMessage('');
          onBusy(true);
        }}
      >
        {state === 'idle' ? (
          <Microphone size={22} aria-hidden="true" />
        ) : (
          <X size={22} aria-hidden="true" />
        )}
      </button>
    </section>
  );
  return (
    <>
      {panel && (
        <div className={styles.voiceStatusSlot} data-testid="voice-status-slot">
          {panel}
        </div>
      )}
      {children ? children(trigger) : trigger}
    </>
  );
}
