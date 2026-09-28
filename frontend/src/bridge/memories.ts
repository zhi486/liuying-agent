import { z } from 'zod';

const id = z.uuidv4().regex(/^[0-9a-f-]{36}$/);
const version = z.number().int().min(1).max(2_147_483_646);
const offset = z.number().int().min(0).max(2_147_483_647);
const limit = z.number().int().min(1).max(50);
const millis = z.number().int().min(0).max(8_640_000_000_000_000);
const memoryRefs = z
  .array(z.object({ id, version: z.number().int().min(1).max(2_147_483_647) }).strict())
  .min(1)
  .max(5)
  .refine((refs) => new Set(refs.map((ref) => ref.id)).size === refs.length);
export const memoryContentSchema = z
  .string()
  .min(1)
  .max(500)
  .refine(
    (text) =>
      text.trim().length > 0 &&
      !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(text),
  );

export const memoryCommandSchemas = [
  z
    .object({
      helloId: id,
      name: z.literal('memory.create'),
      body: z.object({ memoryId: id, content: memoryContentSchema }).strict(),
    })
    .strict(),
  z
    .object({
      helloId: id,
      name: z.literal('memory.list'),
      body: z.object({ offset, limit }).strict(),
    })
    .strict(),
  z
    .object({
      helloId: id,
      name: z.literal('memory.update'),
      body: z.object({ memoryId: id, version, content: memoryContentSchema }).strict(),
    })
    .strict(),
  z
    .object({
      helloId: id,
      name: z.literal('memory.delete'),
      body: z.object({ memoryId: id, version, confirmed: z.literal(true) }).strict(),
    })
    .strict(),
  z
    .object({
      helloId: id,
      name: z.literal('memory.preview'),
      body: z.object({ memoryRefs }).strict(),
    })
    .strict(),
] as const;

const memorySchema = z
  .object({
    id,
    content: memoryContentSchema.refine((text) => text === text.trim()),
    source: z.literal('user_explicit'),
    createdAtMillis: millis,
    updatedAtMillis: millis,
    version: z.number().int().min(1).max(2_147_483_647),
  })
  .strict()
  .refine((memory) => memory.updatedAtMillis >= memory.createdAtMillis);

export const memoryPageSchema = z
  .object({
    commandId: id,
    offset,
    limit,
    totalItems: offset,
    items: z.array(memorySchema).max(50),
  })
  .strict()
  .refine(
    (page) =>
      page.items.length === Math.min(page.limit, Math.max(0, page.totalItems - page.offset)) &&
      new Set(page.items.map((memory) => memory.id)).size === page.items.length,
  );
export const memoryDetailSchema = z.object({ commandId: id, memory: memorySchema }).strict();
export const memoryDeletedSchema = z
  .object({ commandId: id, memoryId: id, deleted: z.literal(true) })
  .strict();
export type MemoryRecord = z.infer<typeof memorySchema>;
export const memoryPreviewSchema = z
  .object({
    commandId: id,
    scope: z.literal('local_preview'),
    items: z.array(memorySchema).min(1).max(5),
  })
  .strict()
  .refine((preview) => new Set(preview.items.map((item) => item.id)).size === preview.items.length);
