import { z } from 'zod';

const uuid = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
const token = z.string().regex(/^[0-9a-f]{64}$/);
const endpoint = z
  .string()
  .max(2048)
  .refine((value) => {
    try {
      const url = new URL(value);
      return (
        url.href === value &&
        url.protocol === 'https:' &&
        !url.port &&
        !url.username &&
        !url.password &&
        !/[?#\\\s]/.test(value) &&
        /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/.test(url.hostname) &&
        !/\.(localhost|local|internal|lan|home|onion)$/.test(url.hostname)
      );
    } catch {
      return false;
    }
  });
export const mcpEndpointSchema = endpoint;
const toolName = z.string().regex(/^[A-Za-z0-9_.-]{1,128}$/);
const selection = z
  .array(z.object({ name: toolName, mode: z.enum(['read', 'confirm']) }).strict())
  .min(1)
  .max(64)
  .refine((items) => new Set(items.map((item) => item.name)).size === items.length);
type Bound = { identityToken: string };
type Reviewed = Bound & { id: string; revision: string };
export interface McpCommandBodies {
  'mcp.status': Record<string, never>;
  'mcp.list': Bound & { after: string | null };
  'mcp.detail': Bound & { id: string };
  'mcp.register': Bound & { endpoint: string };
  'mcp.configure': Reviewed;
  'mcp.revokePrivate': Bound & { id: string; context: string };
  'mcp.disable': Reviewed;
  'mcp.endpoint': Reviewed & { context: string; confirmed: true; endpoint: string };
  'mcp.remove': Reviewed & { context: string; confirmed: true };
  'mcp.approve': Reviewed & {
    confirmed: true;
    selection: { name: string; mode: 'read' | 'confirm' }[];
  };
  'mcp.discover': Reviewed & { confirmed: true };
  'mcp.recover': Bound & { requestId: string };
}
const bound = { identityToken: token };
const reviewed = { ...bound, id: uuid, revision: uuid };
const command = <T extends string, S extends z.ZodRawShape>(name: T, body: S) =>
  z.object({ helloId: uuid, name: z.literal(name), body: z.object(body).strict() }).strict();
export const mcpCommandSchemas = [
  command('mcp.status', {}),
  command('mcp.list', { ...bound, after: uuid.nullable() }),
  command('mcp.detail', { ...bound, id: uuid }),
  command('mcp.register', { ...bound, endpoint }),
  command('mcp.configure', reviewed),
  command('mcp.disable', reviewed),
  command('mcp.endpoint', { ...reviewed, context: token, confirmed: z.literal(true), endpoint }),
  command('mcp.remove', { ...reviewed, context: token, confirmed: z.literal(true) }),
  command('mcp.revokePrivate', { ...bound, id: uuid, context: token }),
  command('mcp.approve', { ...reviewed, confirmed: z.literal(true), selection }),
  command('mcp.discover', { ...reviewed, confirmed: z.literal(true) }),
  command('mcp.recover', { ...bound, requestId: uuid }),
] as const;
const privateStatus = z
  .object({
    configured: z.boolean(),
    hasCredential: z.boolean(),
    context: token,
    endpointMatches: z.boolean(),
  })
  .strict();
const summary = { id: uuid, revision: uuid, endpoint, enabled: z.boolean() };
export const mcpStatusSchema = z
  .object({
    commandId: uuid,
    configured: z.boolean(),
    identityToken: token.nullable(),
    operations: z
      .array(
        z
          .object({
            requestId: uuid,
            serviceId: uuid,
            endpoint,
            status: z.enum(['unknown', 'running', 'completed', 'unconfirmed']),
            reason: z
              .enum(['unavailable', 'service_changed', 'interrupted', 'timed_out'])
              .nullable(),
          })
          .strict(),
      )
      .max(64),
  })
  .strict()
  .refine((row) => row.configured === (row.identityToken !== null));
export const mcpListSchema = z
  .object({
    commandId: uuid,
    identityToken: token,
    services: z.array(z.object({ ...summary, private: privateStatus }).strict()).max(20),
    nextCursor: uuid.nullable(),
    phoneDiscoveryAvailable: z.boolean(),
  })
  .strict();
export const mcpDetailSchema = z
  .object({
    commandId: uuid,
    identityToken: token,
    private: privateStatus,
    service: z
      .object({
        ...summary,
        catalog: z
          .object({
            fingerprint: token,
            tools: z
              .array(
                z
                  .object({ name: toolName, definition: z.record(z.string(), z.unknown()) })
                  .strict(),
              )
              .max(64),
          })
          .strict()
          .nullable(),
        grants: z
          .array(
            z
              .object({
                name: toolName,
                mode: z.enum(['read', 'confirm']),
                modelName: z.string().max(100),
              })
              .strict(),
          )
          .max(64),
      })
      .strict(),
  })
  .strict();
export type McpStatus = z.infer<typeof mcpStatusSchema>;
export type McpListing = z.infer<typeof mcpListSchema>;
export type McpDetail = z.infer<typeof mcpDetailSchema>;
export const mcpRemovedSchema = z
  .object({ commandId: uuid, identityToken: token, removed: z.literal(true) })
  .strict();
