import { expect, it } from 'vitest';
import {
  memoryCommandSchemas,
  memoryContentSchema,
  memoryPageSchema,
  memoryPreviewSchema,
} from './memories';

const id = '11111111-1111-4111-8111-111111111111';
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
