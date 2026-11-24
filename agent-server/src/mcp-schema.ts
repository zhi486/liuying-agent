import { CfWorkerJsonSchemaValidator } from '@modelcontextprotocol/client/validators/cf-worker';
import { encodeMcpJson } from './mcp-registry.ts';

const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const types = ['object', 'array', 'string', 'number', 'integer', 'boolean', 'null'];

/** 首版仅接受可有界解释的 schema 子集；不让未知约束被引擎静默忽略，也不执行远端正则或引用。 */
export function mcpSchemaValidator(raw: unknown): (value: unknown) => boolean {
  // 引擎会在 schema 上添加内部标记，不能把授权目录中的冻结对象直接交给它。
  const schema: unknown = JSON.parse(encodeMcpJson(raw));
  let nodes = 0;
  const fail = (): never => { throw new Error('MCP schema 暂不支持'); };
  const visit = (item: unknown, depth: number): void => {
    if (++nodes > 128 || depth > 8) fail();
    if (typeof item === 'boolean') return;
    if (!object(item)) return fail();
    for (const [key, value] of Object.entries(item)) {
      switch (key) {
        case '$schema':
          if (depth !== 0 || typeof value !== 'string' || !['https://json-schema.org/draft/2020-12/schema', 'https://json-schema.org/draft/2019-09/schema',
            'http://json-schema.org/draft-07/schema#', 'http://json-schema.org/draft-06/schema#'].includes(value)) fail();
          break;
        case 'title': case 'description':
          if (typeof value !== 'string') fail();
          break;
        case 'default': case 'examples': case 'const':
          break;
        case 'enum':
          if (!Array.isArray(value) || value.length === 0 || value.length > 64) fail();
          break;
        case 'type': {
          const names = Array.isArray(value) ? value : [value];
          if (!names.length || names.some(name => typeof name !== 'string' || !types.includes(name)) || new Set(names).size !== names.length) fail();
          break;
        }
        case 'properties':
          if (!object(value)) return fail();
          for (const child of Object.values(value)) visit(child, depth + 1);
          break;
        case 'required':
          if (!Array.isArray(value) || value.some(name => typeof name !== 'string') || new Set(value).size !== value.length) fail();
          break;
        case 'additionalProperties': case 'items':
          visit(value, depth + 1);
          break;
        case 'minProperties': case 'maxProperties': case 'minItems': case 'maxItems': case 'minLength': case 'maxLength':
          if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) fail();
          break;
        case 'minimum': case 'maximum': case 'exclusiveMinimum': case 'exclusiveMaximum': case 'multipleOf':
          if (typeof value !== 'number' || !Number.isFinite(value) || (key === 'multipleOf' && value <= 0)) fail();
          break;
        default: fail();
      }
    }
  };
  visit(schema, 0);
  if (!object(schema) || schema.type !== 'object') return fail();
  const validate = new CfWorkerJsonSchemaValidator().getValidator(schema);
  return value => validate(value).valid;
}
