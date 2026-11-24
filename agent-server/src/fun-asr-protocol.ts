export type VoiceStreamEvent = { type: 'ready' } | { type: 'preview' | 'completed'; text: string };
export const FUN_ASR_MODEL = 'fun-asr-realtime';

/** 只保留有界转写状态；重复中间结果覆盖原句，不能反复追加。 */
export function createFunAsrProtocol(taskId: string, vocabularyId: string) {
  let state = 'starting', bytes = 0, messages = 0;
  const sentences = new Map<number, { text: string; final: boolean }>();
  const command = (action: string, payload: object) => JSON.stringify({
    header: { action, task_id: taskId, streaming: 'duplex' }, payload,
  });
  function text() {
    const result = [...sentences].sort(([left], [right]) => left - right).map(([, item]) => item.text).join('');
    if (result.length > 2000) throw new Error('转写过长');
    return result;
  }
  return {
    start: () => command('run-task', { task_group: 'audio', task: 'asr', function: 'recognition',
      model: FUN_ASR_MODEL, input: {}, parameters: { format: 'pcm', sample_rate: 16000,
        vocabulary_id: vocabularyId, language_hints: ['zh'], semantic_punctuation_enabled: false } }),
    audio(size: number) {
      if (state !== 'recording' || !Number.isInteger(size) || size < 2 || size > 4096 || size % 2 || bytes + size > 960000)
        throw new Error('音频帧无效');
      bytes += size;
    },
    finish() {
      if (state !== 'recording' || bytes < 3200) throw new Error('录音尚未就绪');
      state = 'finishing';
      return command('finish-task', { input: {} });
    },
    receive(raw: string): VoiceStreamEvent | undefined {
      if (raw.length > 16384 || ++messages > 1000 || state === 'completed') throw new Error('响应无效');
      const value = JSON.parse(raw);
      if (value?.header?.task_id !== taskId) throw new Error('任务不匹配');
      switch (value.header.event) {
        case 'task-started':
          if (state !== 'starting') throw new Error('重复启动');
          state = 'recording'; return { type: 'ready' };
        case 'result-generated': {
          if (state !== 'recording' && state !== 'finishing') throw new Error('结果顺序无效');
          const sentence = value.payload?.output?.sentence;
          if (sentence?.heartbeat === true) return;
          if (!sentence || !Number.isInteger(sentence.sentence_id) || sentence.sentence_id < 1 || sentence.sentence_id > 128 ||
              typeof sentence.text !== 'string' || typeof sentence.sentence_end !== 'boolean') throw new Error('句子无效');
          const previous = sentences.get(sentence.sentence_id);
          if (previous?.final) {
            if (previous.text !== sentence.text || !sentence.sentence_end) throw new Error('最终句被修改');
            return;
          }
          sentences.set(sentence.sentence_id, { text: sentence.text, final: sentence.sentence_end });
          return { type: 'preview', text: text() };
        }
        case 'task-finished': {
          if (state !== 'finishing' || [...sentences.values()].some(item => !item.final)) throw new Error('任务未正常结束');
          const final = text().trim();
          if (!final) throw new Error('未识别到文字');
          state = 'completed'; return { type: 'completed', text: final };
        }
        default: throw new Error('识别未完成');
      }
    },
  };
}
