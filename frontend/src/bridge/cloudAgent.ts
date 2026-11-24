import { z } from 'zod';
import { mcpEndpointSchema } from './mcp';

export const selectedRunIdSchema = z.string().regex(/^[a-zA-Z0-9_-]{1,80}$/);
export const runSummarySchema = z
  .object({
    runId: selectedRunIdSchema,
    distanceMeters: z.number().min(0).max(1_000_000),
    durationSeconds: z.number().positive().max(604_800),
    averagePaceSecPerKm: z.number().positive().max(86_400).nullable(),
    averageHeartRate: z.number().positive().max(400).nullable(),
  })
  .strict();
export type SelectedRunSummary = z.infer<typeof runSummarySchema>;

export const knowledgeSelectionSchema = z
  .object({
    version: z.literal('curated_knowledge_v1'),
    library: z.enum(['health', 'firefly', 'both']),
    allowSpoilers: z.boolean(),
  })
  .strict()
  .refine((value) => value.library !== 'health' || !value.allowSpoilers);
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
const mcpServiceIds = z
  .array(id)
  .min(1)
  .max(8)
  .refine((items) => new Set(items).size === items.length);
const mcpPreview = z
  .array(
    z
      .object({
        id,
        endpoint: mcpEndpointSchema,
        tools: z
          .array(
            z
              .object({
                name: z.string().regex(/^[A-Za-z0-9_.-]{1,128}$/),
                mode: z.enum(['read', 'confirm']),
              })
              .strict(),
          )
          .min(1)
          .max(64)
          .refine((tools) => new Set(tools.map((tool) => tool.name)).size === tools.length),
      })
      .strict(),
  )
  .min(1)
  .max(8)
  .refine(
    (rows) =>
      new Set(rows.map((row) => row.id)).size === rows.length &&
      rows.reduce((count, row) => count + row.tools.length, 0) <= 64,
  );
export type SelectedMemoryRef = z.infer<typeof ref>;
const command = <T extends string, S extends z.ZodType>(name: T, body: S) =>
  z.object({ helloId: id, name: z.literal(name), body }).strict();
export const cloudCommandSchemas = [
  command('cloud.speak', z.object({ requestId: id }).strict()),
  command('cloud.stopSpeech', z.object({}).strict()),
  command('cloud.list', z.object({}).strict()),
  command('cloud.memoryAuthorization', z.object({}).strict()),
  command(
    'cloud.memoryConsent',
    z
      .object({
        identityToken: z.string().regex(/^[0-9a-f]{64}$/),
        enabled: z.boolean(),
        factsAllowed: z.boolean().optional(),
      })
      .strict()
      .refine((value) => !value.factsAllowed || value.enabled),
  ),
  command('cloud.newConversation', z.object({}).strict()),
  command('cloud.selectConversation', z.object({ conversationId: id.nullable() }).strict()),
  command('cloud.archiveConversation', z.object({ conversationId: id.nullable() }).strict()),
  command('cloud.restoreConversation', z.object({ conversationId: id.nullable() }).strict()),
  command(
    'cloud.deleteConversation',
    z.object({ conversationId: id.nullable(), confirmed: z.literal(true) }).strict(),
  ),
  command('cloud.retryConversationCleanup', z.object({}).strict()),
  command(
    'cloud.renameConversation',
    z.object({ conversationId: id, title: z.string().trim().min(1).max(80) }).strict(),
  ),
  command('cloud.configure', z.object({}).strict()),
  command(
    'cloud.prepare',
    z
      .object({
        prompt,
        conversationId: id.optional(),
        memoryRefs: refs,
        knowledge: knowledgeSelectionSchema.optional(),
        runId: selectedRunIdSchema.optional(),
        mcpServices: mcpServiceIds.optional(),
      })
      .strict()
      .refine((value) => !value.mcpServices || !!value.conversationId),
  ),
  command('cloud.confirm', z.object({ token: id, approved: z.literal(true) }).strict()),
  command('cloud.send', z.object({ token: id }).strict()),
  command(
    'cloud.chatConsent',
    z.object({ identityToken: z.string().regex(/^[0-9a-f]{64}$/), enabled: z.boolean() }).strict(),
  ),
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
    conversation: z
      .object({
        version: z.literal('conversation_v1'),
        id,
        approved: z.literal(true),
        messages: z
          .array(
            z
              .object({ role: z.enum(['user', 'assistant']), text: z.string().min(1).max(4000) })
              .strict(),
          )
          .max(8),
      })
      .strict()
      .optional(),
    knowledge: knowledgeSelectionSchema.optional(),
    memories: z.array(ref.extend({ content: z.string().min(1).max(500) }).strict()).max(5),
    runSummary: runSummarySchema.optional(),
    mcpServices: mcpPreview.optional(),
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
    speech: z
      .object({
        requestId: id.nullable(),
        state: z.enum(['idle', 'preparing', 'playing']),
        error: z.string().nullable(),
        available: z.boolean(),
      })
      .strict()
      .optional(),
    commandId: id,
    configured: z.boolean(),
    configuring: z.boolean(),
    chatAuthorized: z.boolean().optional(),
    chatConsentContext: z
      .string()
      .regex(/^[0-9a-f]{64}$/)
      .nullable()
      .optional(),
    origin: origin.nullable(),
    conversationId: id.nullable().optional(),
    conversationArchived: z.boolean().optional(),
    legacyAvailable: z.boolean().optional(),
    legacyArchived: z.boolean().optional(),
    cleanupPending: z.number().int().nonnegative().optional(),
    cleanupBusy: z.boolean().optional(),
    retainedUsage: z
      .array(
        z
          .object({
            requestId: id,
            atMillis: z.number().int().nonnegative(),
            cost: costEstimateSchema.nullable(),
          })
          .strict(),
      )
      .max(100)
      .optional(),
    conversations: z
      .array(
        z
          .object({
            id,
            title: z.string().min(1).max(80),
            updatedAtMillis: z.number().int().nonnegative(),
            archived: z.boolean().optional(),
          })
          .strict(),
      )
      .optional(),
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
            draftAnswer: z.string().min(1).max(4000).optional(),
            pendingControl: control,
            confirmedControl: control,
            error: z.string().nullable(),
            memoryRefs: refs,
            memorySaved: z.literal(true).optional(),
            knowledge: knowledgeSelectionSchema.optional(),
            sources: sourcesSchema.optional(),
            cost: costEstimateSchema.optional(),
            runSummary: runSummarySchema.optional(),
          })
          .strict()
          .refine(
            (row) =>
              row.draftAnswer === undefined ||
              (row.busy &&
                ['queued', 'running'].includes(row.status) &&
                row.answer === null &&
                row.pendingControl === null &&
                row.confirmedControl !== 'withdraw' &&
                row.resultState !== 'withdrawn'),
          ),
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
export const memoryAuthorizationSchema = z
  .object({
    commandId: id,
    configured: z.boolean(),
    enabled: z.boolean(),
    factsAllowed: z.boolean().optional(),
    context: z
      .string()
      .regex(/^[0-9a-f]{64}$/)
      .nullable(),
  })
  .strict()
  .refine(
    (value) =>
      value.configured === (value.context !== null) &&
      (value.configured || !value.enabled) &&
      (!value.factsAllowed || value.enabled),
  );
export type MemoryAuthorization = z.infer<typeof memoryAuthorizationSchema>;
