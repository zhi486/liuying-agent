import { expect, it } from 'vitest';
import {
  memoryCommandSchemas,
  memoryContentSchema,
  memoryDocumentPageSchema,
  memoryExportCommandSchemas,
  memoryExportStatusSchema,
  memoryHistorySchema,
  memoryPageSchema,
  memoryPreviewSchema,
} from './memories';

const id = '11111111-1111-4111-8111-111111111111';
it('文档保存拒绝路径正文和无确认写入，成功必须带完整快照计数', () => {
  const command = {
    helloId: id,
    name: 'memory.exportBegin',
    body: { exportId: id, confirmed: true },
  };
  expect(memoryExportCommandSchemas[0].safeParse(command).success).toBe(true);
  for (const body of [
    { exportId: id },
    { ...command.body, confirmed: 'true' },
    { ...command.body, uri: 'content://file' },
    { ...command.body, content: '伪造正文' },
    { ...command.body, ownerId: 'other' },
  ])
    expect(memoryExportCommandSchemas[0].safeParse({ ...command, body }).success).toBe(false);
  const status = {
    commandId: id,
    exportId: id,
    phase: 'saved',
    totalItems: 121,
    generatedAtMillis: 3000,
  };
  expect(memoryExportStatusSchema.safeParse(status).success).toBe(true);
  for (const patch of [
    { totalItems: null },
    { generatedAtMillis: null },
    { phase: 'choosing' },
    { phase: 'completed' },
    { uri: 'content://file' },
  ])
    expect(memoryExportStatusSchema.safeParse({ ...status, ...patch }).success).toBe(false);
});
it('文档只读分页拒绝越权、正文回填及无效来源关系', () => {
  const command = { helloId: id, name: 'memory.document', body: { offset: 0, limit: 20 } };
  const schema = memoryCommandSchemas.at(-1)!;
  expect(schema.safeParse(command).success).toBe(true);
  for (const body of [
    { offset: 0, limit: 21 },
    { offset: -1, limit: 20 },
    { ...command.body, ownerId: 'other' },
    { ...command.body, path: '/file' },
  ])
    expect(schema.safeParse({ ...command, body }).success).toBe(false);
  const entry = {
    memory: {
      id,
      content: '合成偏好',
      source: 'user_explicit',
      version: 1,
      createdAtMillis: 1000,
      updatedAtMillis: 2000,
    },
    changeSource: 'manual',
    sourceTurnId: null,
    category: null,
  };
  const page = {
    commandId: id,
    offset: 0,
    limit: 20,
    totalItems: 1,
    generatedAtMillis: 3000,
    items: [entry],
  };
  expect(memoryDocumentPageSchema.safeParse(page).success).toBe(true);
  for (const patch of [{ category: 'preference' }, { sourceTurnId: id }, { changeSource: 'agent' }])
    expect(
      memoryDocumentPageSchema.safeParse({ ...page, items: [{ ...entry, ...patch }] }).success,
    ).toBe(false);
  expect(
    memoryDocumentPageSchema.safeParse({ ...page, items: [entry, entry], totalItems: 2 }).success,
  ).toBe(false);
});
it('预览只接受唯一ID版本且不把结果升级为上传授权', () => {
  const ref = { id, version: 1 };
  const command = { helloId: id, name: 'memory.preview', body: { memoryRefs: [ref] } };
  expect(memoryCommandSchemas[4].safeParse(command).success).toBe(true);
  for (const body of [
    { memoryRefs: [] },
    { memoryRefs: [ref, ref] },
    { memoryRefs: [{ ...ref, content: '网页伪造' }] },
    { memoryRefs: [ref], approved: true },
  ])
    expect(memoryCommandSchemas[4].safeParse({ ...command, body }).success).toBe(false);
  expect(
    memoryPreviewSchema.safeParse({ commandId: id, scope: 'cloud_approved', items: [] }).success,
  ).toBe(false);
});

it('历史及恢复严格校验确认、版本边界、来源和分页顺序', () => {
  const history = {
    helloId: id,
    name: 'memory.history',
    body: { memoryId: id, offset: 0, limit: 10 },
  };
  expect(memoryCommandSchemas[5].safeParse(history).success).toBe(true);
  for (const body of [
    { ...history.body, offset: -1 },
    { ...history.body, limit: 51 },
    { ...history.body, ownerId: 'other' },
  ])
    expect(memoryCommandSchemas[5].safeParse({ ...history, body }).success).toBe(false);
  const restore = {
    helloId: id,
    name: 'memory.restore',
    body: { memoryId: id, version: 3, targetVersion: 1, confirmed: true },
  };
  expect(memoryCommandSchemas[6].safeParse(restore).success).toBe(true);
  for (const body of [
    { ...restore.body, confirmed: false },
    { ...restore.body, targetVersion: 3 },
    { ...restore.body, targetVersion: 0 },
    { ...restore.body, version: '3' },
    { ...restore.body, content: '伪造正文' },
  ])
    expect(memoryCommandSchemas[6].safeParse({ ...restore, body }).success).toBe(false);
  const revision = {
    version: 2,
    content: '喜欢晚跑',
    changedAtMillis: 1000,
    changeSource: 'agent',
    turnId: id,
  };
  const page = {
    commandId: id,
    memoryId: id,
    currentVersion: 2,
    offset: 0,
    limit: 10,
    totalItems: 2,
    items: [revision, { ...revision, version: 1, changeSource: 'manual', turnId: null }],
  };
  expect(memoryHistorySchema.safeParse(page).success).toBe(true);
  for (const items of [
    [revision, revision],
    [...page.items].reverse(),
    [{ ...revision, turnId: null }, page.items[1]],
    [{ ...revision, version: 3 }, page.items[1]],
    [{ ...revision, content: ' 未规范化 ' }, page.items[1]],
  ])
    expect(memoryHistorySchema.safeParse({ ...page, items }).success).toBe(false);
  expect(memoryHistorySchema.safeParse({ ...page, totalItems: 3 }).success).toBe(false);
});
it('记忆正文允许中文和成对emoji，拒绝空白超长及孤立代理字符', () => {
  for (const text of ['夜跑😊', '跑'.repeat(500)])
    expect(memoryContentSchema.safeParse(text).success).toBe(true);
  for (const text of ['', ' \n', '跑'.repeat(501), '\ud800', '\udc00'])
    expect(memoryContentSchema.safeParse(text).success).toBe(false);
});
it('严格拒绝网页指定归属和未确认删除', () => {
  const create = { helloId: id, name: 'memory.create', body: { memoryId: id, content: '夜跑' } };
  expect(memoryCommandSchemas[0].safeParse(create).success).toBe(true);
  expect(
    memoryCommandSchemas[0].safeParse({ ...create, body: { ...create.body, ownerId: 'other' } })
      .success,
  ).toBe(false);
  const remove = {
    helloId: id,
    name: 'memory.delete',
    body: { memoryId: id, version: 1, confirmed: true },
  };
  expect(memoryCommandSchemas[3].safeParse(remove).success).toBe(true);
  expect(
    memoryCommandSchemas[3].safeParse({ ...remove, body: { ...remove.body, confirmed: false } })
      .success,
  ).toBe(false);
  expect(
    memoryCommandSchemas[3].safeParse({ ...remove, body: { ...remove.body, version: '1' } })
      .success,
  ).toBe(false);
});

it('空白处理沿用JS语义，与原生回执正文一致', () => {
  expect('\u001c夜跑\u001c'.trim()).toBe('\u001c夜跑\u001c');
  expect(memoryContentSchema.safeParse('\u001c夜跑\u001c').success).toBe(true);
  expect('\ufeff\u00a0夜跑\u3000'.trim()).toBe('夜跑');
  expect(memoryContentSchema.safeParse('\ufeff\u2007\u2028').success).toBe(false);
});
it('列表拒绝重复ID、虚假数量及未知来源', () => {
  const memory = {
    id,
    content: '夜跑',
    source: 'user_explicit',
    createdAtMillis: 1000,
    updatedAtMillis: 1000,
    version: 1,
  };
  const page = { commandId: id, offset: 0, limit: 20, totalItems: 1, items: [memory] };
  expect(memoryPageSchema.safeParse(page).success).toBe(true);
  expect(memoryPageSchema.safeParse({ ...page, totalItems: 2 }).success).toBe(false);
  expect(
    memoryPageSchema.safeParse({ ...page, totalItems: 2, items: [memory, memory] }).success,
  ).toBe(false);
  expect(
    memoryPageSchema.safeParse({ ...page, items: [{ ...memory, source: 'automatic' }] }).success,
  ).toBe(false);
});
