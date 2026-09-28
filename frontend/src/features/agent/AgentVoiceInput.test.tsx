import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, afterEach, vi, it, expect } from 'vitest';
import { AgentVoiceInput } from './AgentVoiceInput';
import { voiceCommandSchemas, voiceStatusSchema } from '../../bridge/voiceInput';

const send = vi.hoisted(() => vi.fn());
vi.mock('../../bridge/bridgeStore', () => ({ sendNativeCommand: send }));
const commandId = '11111111-1111-4111-8111-111111111111';
let state: 'recording' | 'completed' | 'failed';
let preview: string | null;
const onBusy = vi.fn();
const onText = vi.fn(() => true);
beforeEach(() => {
  vi.useFakeTimers();
  state = 'recording';
  preview = null;
  send.mockReset();
  onBusy.mockClear();
  onText.mockClear();
  onText.mockReturnValue(true);
  send.mockImplementation(async (_name: string, body: { sessionId: string }) => ({
    status: 'accepted',
    data: {
      commandId,
      sessionId: body.sessionId,
      state,
      preview,
      text: state === 'completed' ? '今晚轻松跑' : null,
      error: state === 'failed' ? '麦克风权限未授权' : null,
    },
  }));
});
afterEach(() => vi.useRealTimers());
const mount = () => render(<AgentVoiceInput disabled={false} onBusy={onBusy} onText={onText} />);
async function begin() {
  fireEvent.click(screen.getByRole('button', { name: '语音输入' }));
  await act(async () =>
    fireEvent.click(screen.getByRole('button', { name: '同意本次录音并识别' })),
  );
}

it('点击麦克风先展示单次录音上传说明，未同意不调用原生', () => {
  mount();
  fireEvent.click(screen.getByRole('button', { name: '语音输入' }));
  expect(screen.getByText(/实时发送至阿里云 Fun-ASR/)).toBeInTheDocument();
  expect(send).not.toHaveBeenCalled();
  expect(onBusy).toHaveBeenCalledWith(true);
  fireEvent.click(screen.getByRole('button', { name: '暂不录音' }));
  expect(send).not.toHaveBeenCalled();
  expect(onText).not.toHaveBeenCalled();
});

it('完成只回填一次，释放原生会话且没有在线任务发送', async () => {
  mount();
  await begin();
  fireEvent.click(screen.getByRole('button', { name: '结束并识别' }));
  state = 'completed';
  await act(async () => vi.advanceTimersByTimeAsync(500));
  expect(onText).toHaveBeenCalledExactlyOnceWith('今晚轻松跑');
  expect(screen.getByText('已填入草稿，请核对后自行发送')).toBeInTheDocument();
  expect(send.mock.calls.filter(([name]) => name === 'voice.begin')).toHaveLength(1);
  expect(send.mock.calls.some(([name]) => name === 'voice.cancel')).toBe(true);
  expect(send.mock.calls.every(([name]) => name.startsWith('voice.'))).toBe(true);
});

it('取消后迟到的识别结果不能写入草稿', async () => {
  mount();
  await begin();
  let resolve!: (value: unknown) => void;
  send.mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  await act(async () => vi.advanceTimersByTimeAsync(500));
  const sessionId = send.mock.calls[0][1].sessionId;
  fireEvent.click(screen.getByRole('button', { name: '取消语音输入' }));
  await act(async () =>
    resolve({
      status: 'accepted',
      data: { commandId, sessionId, state: 'completed', text: '迟到', preview: null, error: null },
    }),
  );
  expect(onText).not.toHaveBeenCalled();
});

it('组件卸载取消录音，不继续轮询或发送', async () => {
  const view = mount();
  await begin();
  view.unmount();
  const count = send.mock.calls.length;
  await act(async () => vi.advanceTimersByTimeAsync(5000));
  expect(send.mock.calls.length).toBe(count);
  expect(onText).not.toHaveBeenCalled();
  expect(send.mock.calls.at(-1)?.[0]).toBe('voice.cancel');
});

it('权限或识别失败不改变草稿且不重试', async () => {
  mount();
  state = 'failed';
  await begin();
  expect(screen.getByText('麦克风权限未授权')).toBeInTheDocument();
  expect(onText).not.toHaveBeenCalled();
  await act(async () => vi.advanceTimersByTimeAsync(5000));
  expect(send.mock.calls.filter(([name]) => name === 'voice.begin')).toHaveLength(1);
});

it('超过草稿容量时保留原文并明确提示', async () => {
  mount();
  onText.mockReturnValue(false);
  state = 'completed';
  await begin();
  expect(screen.getByText(/原草稿保留/)).toBeInTheDocument();
});

it('命令拒绝伪授权和密钥偷渡，未完成响应不能带转写文本', () => {
  for (const body of [
    { sessionId: commandId, approved: false },
    { sessionId: commandId, approved: 'true' },
    { sessionId: commandId, approved: true, apiKey: 'fake' },
  ]) {
    expect(
      voiceCommandSchemas[0].safeParse({ helloId: commandId, name: 'voice.begin', body }).success,
    ).toBe(false);
  }
  expect(
    voiceStatusSchema.safeParse({
      commandId,
      sessionId: commandId,
      state: 'recording',
      preview: null,
      text: '文本',
      error: null,
    }).success,
  ).toBe(false);
});

it('流式中间文字替换预览，取消时清空且不修改草稿', async () => {
  mount();
  preview = '今晚青松';
  await begin();
  expect(screen.getByLabelText('实时识别预览')).toHaveTextContent('今晚青松');
  preview = '今晚轻松跑';
  await act(async () => vi.advanceTimersByTimeAsync(500));
  expect(screen.getByLabelText('实时识别预览')).toHaveTextContent('今晚轻松跑');
  expect(screen.queryByText('今晚青松')).not.toBeInTheDocument();
  expect(onText).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '取消语音输入' }));
  expect(screen.queryByLabelText('实时识别预览')).not.toBeInTheDocument();
  expect(onText).not.toHaveBeenCalled();
});
