import { z } from 'zod';

export const knowledgeSelectionSchema = z
  .object({
    version: z.literal('curated_knowledge_v1'),
    library: z.enum(['health', 'firefly']),
    allowSpoilers: z.boolean(),
  })
  .strict()
  .refine((value) => value.library === 'firefly' || !value.allowSpoilers);
export type KnowledgeSelection = z.infer<typeof knowledgeSelectionSchema>;
const sourceSchema = z
  .object({
    citationId: z.string().regex(/^[a-zA-Z0-9_-]{1,100}$/),
    title: z
      .string()
      .min(1)
      .max(80)
      .refine(
        (value) =>
          !!value.trim() &&
          [...value].every((char) => char.charCodeAt(0) >= 32 && char.charCodeAt(0) !== 127),
      ),
    url: z
      .url()
      .max(300)
      .refine((value) => {
        const url = new URL(value);
        return (
          url.protocol === 'https:' &&
          !url.username &&
          !url.password &&
          !url.port &&
          !url.search &&
          ['www.who.int', 'hsr.hoyoverse.com'].includes(url.hostname)
        );
      }),
    publishedAt: z
      .string()
      .regex(/^\d{4}-\d{2}-\d{2}$/)
      .refine(
        (value) =>
          Number.isFinite(Date.parse(value)) &&
          new Date(value).toISOString().slice(0, 10) === value,
      )
      .nullable(),
    checkedAtMillis: z.number().int().min(0).max(8640000000000000),
  })
  .strict()
  .refine((row) => row.publishedAt === null || Date.parse(row.publishedAt) <= row.checkedAtMillis);
const sourcesSchema = z
  .array(sourceSchema)
  .max(3)
  .refine((rows) => new Set(rows.map((row) => row.citationId)).size === rows.length);

const id = z.uuidv4().regex(/^[0-9a-f-]{36}$/);
const prompt = z
  .string()
  .min(1)
  .max(2000)
  .refine((text) => text.trim().length > 0);
const ref = z.object({ id, version: z.number().int().min(1).max(2_147_483_647) }).strict();
const refs = z
  .array(ref)
  .max(5)
  .refine((items) => new Set(items.map((item) => item.id)).size === items.length);
export type SelectedMemoryRef = z.infer<typeof ref>;
const command = <T extends string, S extends z.ZodType>(name: T, body: S) =>
  z.object({ helloId: id, name: z.literal(name), body }).strict();
export const cloudCommandSchemas = [
  command('cloud.list', z.object({}).strict()),
  command('cloud.configure', z.object({}).strict()),
  command(
    'cloud.prepare',
    z.object({ prompt, memoryRefs: refs, knowledge: knowledgeSelectionSchema.optional() }).strict(),
  ),
  command('cloud.confirm', z.object({ token: id, approved: z.literal(true) }).strict()),
  command('cloud.discard', z.object({ token: id }).strict()),
  command('cloud.recover', z.object({ requestId: id }).strict()),
  command('cloud.cancel', z.object({ requestId: id }).strict()),
  command('cloud.withdraw', z.object({ requestId: id }).strict()),
] as const;
const origin = z.url().refine((value) => {
  const url = new URL(value);
  return url.protocol === 'https:' && url.origin === value;
});
export const cloudPreparationSchema = z
  .object({
    commandId: id,
    token: id,
    origin,
    prompt,
    knowledge: knowledgeSelectionSchema.optional(),
    memories: z.array(ref.extend({ content: z.string().min(1).max(500) }).strict()).max(5),
  })
  .strict();
const control = z.enum(['cancel', 'withdraw']).nullable();
export const costEstimateSchema = z
  .object({
    currency: z.literal('USD'),
    estimatedMicros: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).nullable(),
    inputTokens: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    outputTokens: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    usageComplete: z.boolean(),
    rateStatus: z.enum(['current', 'stale', 'reference']),
  })
  .strict()
  .refine((value) => value.usageComplete === (value.estimatedMicros !== null));
export const cloudListingSchema = z
  .object({
    commandId: id,
    configured: z.boolean(),
    configuring: z.boolean(),
    origin: origin.nullable(),
    items: z
      .array(
        z
          .object({
            requestId: id,
            prompt,
            busy: z.boolean(),
            attempted: z.boolean(),
            status: z.enum([
              'unconfirmed',
              'queued',
              'running',
              'completed',
              'blocked',
              'failed',
              'cancelled',
              'timed_out',
            ]),
            resultState: z.enum([
              'unavailable',
              'pending',
              'available',
              'acknowledged',
              'expired',
              'withdrawn',
              'none',
            ]),
            reason: z.string().nullable(),
            answer: z.string().min(1).max(4000).nullable(),
            pendingControl: control,
            confirmedControl: control,
            error: z.string().nullable(),
            memoryRefs: refs,
            knowledge: knowledgeSelectionSchema.optional(),
            sources: sourcesSchema.optional(),
            cost: costEstimateSchema.optional(),
          })
          .strict(),
      )
      .max(10),
  })
  .strict()
  .refine(
    (data) =>
      data.configured === (data.origin !== null) &&
      new Set(data.items.map((item) => item.requestId)).size === data.items.length,
  );
export type CloudPreparation = z.infer<typeof cloudPreparationSchema>;
export type CloudListing = z.infer<typeof cloudListingSchema>;
