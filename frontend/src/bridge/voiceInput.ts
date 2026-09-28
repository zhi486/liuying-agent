import { z } from 'zod';

const id = z.uuidv4().regex(/^[0-9a-f-]{36}$/);
const command = <T extends string, S extends z.ZodType>(name: T, body: S) =>
  z.object({ helloId: id, name: z.literal(name), body }).strict();
export const voiceCommandSchemas = [
  command('voice.begin', z.object({ sessionId: id, approved: z.literal(true) }).strict()),
  command('voice.status', z.object({ sessionId: id }).strict()),
  command('voice.finish', z.object({ sessionId: id }).strict()),
  command('voice.cancel', z.object({ sessionId: id }).strict()),
] as const;
export const voiceStatusSchema = z
  .object({
    commandId: id,
    sessionId: id,
    state: z.enum([
      'idle',
      'permission',
      'connecting',
      'recording',
      'transcribing',
      'completed',
      'failed',
    ]),
    text: z.string().min(1).max(2000).nullable(),
    preview: z.string().max(2000).nullable(),
    error: z.string().max(200).nullable(),
  })
  .strict()
  .refine((value) => (value.state === 'completed') === (value.text !== null));
