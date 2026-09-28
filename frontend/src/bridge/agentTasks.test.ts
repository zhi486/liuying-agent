import { expect, it } from 'vitest';
import { agentTaskDetailSchema, agentTaskPageSchema } from './agentTasks';

const id = '22222222-2222-4222-8222-222222222222';
const task = {
  id,
  title: '计划',
  status: 'blocked',
  reason: 'cloud_not_connected',
  createdAtMillis: 1000,
  updatedAtMillis: 1000,
};
const detail = {
  commandId: id,
  task,
  messages: [{ sequence: 0, role: 'user', text: '计划', createdAtMillis: 1000 }],
  actions: [{ sequence: 0, kind: 'saved_locally', createdAtMillis: 1000 }],
};

it('合法本地详情可读取，拒绝状态原因矛盾和伪助手消息', () => {
  expect(agentTaskDetailSchema.safeParse(detail).success).toBe(true);
  for (const invalid of [
    { ...detail, task: { ...task, reason: null } },
    { ...detail, task: { ...task, status: 'completed' } },
    { ...detail, task: { ...task, ownerId: 'other' } },
    { ...detail, messages: [{ ...detail.messages[0], role: 'assistant' }] },
    { ...detail, messages: [] },
    { ...detail, actions: [] },
    { ...detail, actions: [{ ...detail.actions[0], sequence: 1 }] },
    { ...detail, task: { ...task, updatedAtMillis: 999 } },
  ])
    expect(agentTaskDetailSchema.safeParse(invalid).success).toBe(false);
});

it('分页限制条数和精确字段，拒绝重复 ID 或错误总数', () => {
  const page = { commandId: id, offset: 0, limit: 20, totalItems: 1, items: [task] };
  expect(agentTaskPageSchema.safeParse(page).success).toBe(true);
  for (const invalid of [
    { ...page, items: [task, task], totalItems: 2 },
    { ...page, totalItems: 0 },
    { ...page, limit: 51 },
    { ...page, rawMessages: [] },
    { ...page, offset: '0' },
  ])
    expect(agentTaskPageSchema.safeParse(invalid).success).toBe(false);
});
