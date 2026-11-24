import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFunAsrProtocol } from '../src/fun-asr-protocol.ts';

const event = (name: string, sentence?: object, id = 'task') => JSON.stringify({
  header: { event: name, task_id: id }, payload: { output: { sentence } },
});
const sentence = (text: string, final = false, id = 1) => event('result-generated', {
  sentence_id: id, text, sentence_end: final,
});
const started = () => {
  const value = createFunAsrProtocol('task', 'fixture-vocabulary');
  assert.deepEqual(value.receive(event('task-started')), { type: 'ready' });
  return value;
};

test('固定16k单声道协议与已创建热词表，不发送即时热词或用户配置', () => {
  const value = createFunAsrProtocol('task', 'fixture-vocabulary');
  const request = JSON.parse(value.start());
  assert.equal(request.payload.model, 'fun-asr-realtime');
  assert.deepEqual(request.payload.parameters, { format: 'pcm', sample_rate: 16000,
    vocabulary_id: 'fixture-vocabulary', language_hints: ['zh'], semantic_punctuation_enabled: false });
  assert.throws(() => value.audio(3200));
  assert.throws(() => value.finish());
});

test('中间句修订覆盖，最终句去重，按句号排序后只完成一次', () => {
  const value = started(); value.audio(3200);
  assert.deepEqual(value.receive(sentence('青松')), { type: 'preview', text: '青松' });
  assert.deepEqual(value.receive(sentence('轻松跑。', true)), { type: 'preview', text: '轻松跑。' });
  assert.equal(value.receive(sentence('轻松跑。', true)), undefined);
  assert.equal(value.receive(event('result-generated', { heartbeat: true })), undefined);
  assert.deepEqual(value.receive(sentence('流萤。', true, 2)), { type: 'preview', text: '轻松跑。流萤。' });
  assert.equal(JSON.parse(value.finish()).header.action, 'finish-task');
  assert.deepEqual(value.receive(event('task-finished')), { type: 'completed', text: '轻松跑。流萤。' });
  assert.throws(() => value.receive(event('task-finished')));
  assert.throws(() => value.audio(3200));
});

test('拒绝任务串线、空结果、未定稿、最终句修改和超长结果', () => {
  const value = started(); value.audio(3200);
  assert.throws(() => value.receive(event('task-started')));
  assert.throws(() => value.receive(event('task-finished')));
  assert.throws(() => value.receive(event('result-generated', {}, 'other')));
  assert.throws(() => value.receive(sentence('字'.repeat(2001))));
  const unfinished = started(); unfinished.audio(3200); unfinished.receive(sentence('中间')); unfinished.finish();
  assert.throws(() => unfinished.receive(event('task-finished')));
  const empty = started(); empty.audio(3200); empty.finish();
  assert.throws(() => empty.receive(event('task-finished')));
  const final = started(); final.receive(sentence('确定', true));
  assert.throws(() => final.receive(sentence('修改', true)));
  assert.throws(() => final.receive(event('task-failed')));
});

test('音频帧、总时长及响应个数均有界', () => {
  const value = started();
  for (const size of [0, 1, 4097, 4098, -2, 2.5]) assert.throws(() => value.audio(size));
  assert.throws(() => value.finish());
  for (let i = 0; i < 300; i++) value.audio(3200);
  assert.throws(() => value.audio(2));
  for (let i = 0; i < 999; i++) value.receive(event('result-generated', { heartbeat: true }));
  assert.throws(() => value.receive(event('result-generated', { heartbeat: true })));
});
