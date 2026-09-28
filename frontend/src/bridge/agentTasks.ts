import { z } from 'zod';

const id = z.uuidv4().regex(/^[0-9a-f-]{36}$/);
const millis = z.number().int().min(0).max(8_640_000_000_000_000);
const prompt = z
  .string()
  .min(1)
  .max(2000)
  .refine((text) => text.trim().length > 0);
const taskSchema = z
  .object({
    id,
    title: z.string().min(1).max(80),
    status: z.enum(['blocked', 'cancelled']),
    reason: z.literal('cloud_not_connected').nullable(),
    createdAtMillis: millis,
    updatedAtMillis: millis,
  })
  .strict()
  .refine(
    (task) =>
      task.updatedAtMillis >= task.createdAtMillis &&
      (task.status === 'blocked' ? task.reason === 'cloud_not_connected' : task.reason === null),
  );

export const agentCommandSchemas = [
  z
    .object({
      helloId: id,
      name: z.literal('agent.submit'),
      body: z.object({ clientRequestId: id, prompt }).strict(),
    })
    .strict(),
  z
    .object({
      helloId: id,
      name: z.literal('agent.list'),
      body: z
        .object({
          offset: z.number().int().min(0).max(2_147_483_647),
          limit: z.number().int().min(1).max(50),
        })
        .strict(),
    })
    .strict(),
  z
    .object({
      helloId: id,
      name: z.literal('agent.detail'),
      body: z.object({ taskId: id }).strict(),
    })
    .strict(),
  z
    .object({
      helloId: id,
      name: z.literal('agent.cancel'),
      body: z.object({ taskId: id }).strict(),
    })
    .strict(),
] as const;

export const agentTaskPageSchema = z
  .object({
    commandId: id,
    offset: z.number().int().min(0).max(2_147_483_647),
    limit: z.number().int().min(1).max(50),
    totalItems: z.number().int().min(0).max(2_147_483_647),
    items: z.array(taskSchema).max(50),
  })
  .strict()
  .refine(
    (page) =>
      page.items.length === Math.min(page.limit, Math.max(0, page.totalItems - page.offset)) &&
      new Set(page.items.map((task) => task.id)).size === page.items.length,
  );

export const agentTaskDetailSchema = z
  .object({
    commandId: id,
    task: taskSchema,
    messages: z
      .array(
        z
          .object({
            sequence: z.literal(0),
            role: z.literal('user'),
            text: prompt,
            createdAtMillis: millis,
          })
          .strict(),
      )
      .length(1),
    actions: z
      .array(
        z
          .object({
            sequence: z.number().int().min(0).max(1),
            kind: z.enum(['saved_locally', 'cancelled']),
            createdAtMillis: millis,
          })
          .strict(),
      )
      .min(1)
      .max(2),
  })
  .strict()
  .refine((detail) => {
    if (detail.messages.length !== 1 || detail.actions.length === 0) return false;
    const first = detail.actions[0];
    const cancelled = detail.task.status === 'cancelled';
    return (
      detail.messages[0].createdAtMillis === detail.task.createdAtMillis &&
      detail.task.title === detail.messages[0].text.slice(0, 80) &&
      first.sequence === 0 &&
      first.kind === 'saved_locally' &&
      first.createdAtMillis === detail.task.createdAtMillis &&
      (cancelled
        ? detail.actions.length === 2 &&
          detail.actions[1].sequence === 1 &&
          detail.actions[1].kind === 'cancelled' &&
          detail.actions[1].createdAtMillis === detail.task.updatedAtMillis
        : detail.actions.length === 1 &&
          detail.task.updatedAtMillis === detail.task.createdAtMillis)
    );
  });

export type AgentTaskSummary = z.infer<typeof taskSchema>;
export type AgentTaskDetail = z.infer<typeof agentTaskDetailSchema>;
