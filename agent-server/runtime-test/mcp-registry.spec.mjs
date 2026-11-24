import { expect, it } from 'vitest';
import {
  approveMcpTools, authorizeMcpTool, collectMcpCatalog, createMcpService,
  disableMcpService, invalidateMcpCatalog, setMcpCatalog,
} from '../src/mcp-registry.ts';

it('workerd 中分页发现及 Web Crypto 指纹不因目录顺序变化而改变', async () => {
  const make = name => ({ name, inputSchema: { type: 'object', additionalProperties: false } });
  const first = await collectMcpCatalog(async cursor => cursor
    ? { tools: [make('b')] } : { tools: [make('a')], nextCursor: 'next' }, new AbortController().signal);
  const second = await collectMcpCatalog(async () => ({ tools: [make('b'), make('a')] }), new AbortController().signal);
  expect(first).toEqual(second);
  expect(first.fingerprint).toMatch(/^[a-f0-9]{64}$/);
  expect(Object.isFrozen(first.tools[0].definition.inputSchema)).toBe(true);
});

it('workerd 中停用、通知失效、重新授权后，原代际均不能继续调用', async () => {
  const catalog = await collectMcpCatalog(async () => ({ tools: [{ name: 'read', inputSchema: { type: 'object' } }] }), new AbortController().signal);
  let service = setMcpCatalog(createMcpService('synthetic-owner', 'https://tools.example.com/mcp'), 'synthetic-owner', catalog);
  service = approveMcpTools(service, 'synthetic-owner', service.revision, [{ name: 'read', mode: 'read' }]);
  const revision = service.revision;
  const name = service.grants[0].modelName;
  expect(authorizeMcpTool(service, 'synthetic-owner', revision, name).status).toBe('allowed_read');
  service = disableMcpService(service, 'synthetic-owner');
  service = approveMcpTools(service, 'synthetic-owner', service.revision, [{ name: 'read', mode: 'read' }]);
  expect(authorizeMcpTool(service, 'synthetic-owner', revision, name)).toEqual({ status: 'rejected', reason: 'stale_review' });
  service = invalidateMcpCatalog(service, 'synthetic-owner');
  expect(() => approveMcpTools(service, 'synthetic-owner', service.revision, [{ name: 'read', mode: 'read' }])).toThrow();
});

it('workerd 中副作用工具保留逐次确认要求，不从远端注解提升权限', async () => {
  const catalog = await collectMcpCatalog(async () => ({ tools: [{ name: 'send',
    inputSchema: { type: 'object' }, annotations: { readOnlyHint: true } }] }), new AbortController().signal);
  let service = setMcpCatalog(createMcpService('synthetic-owner', 'https://tools.example.com/mcp'), 'synthetic-owner', catalog);
  service = approveMcpTools(service, 'synthetic-owner', service.revision, [{ name: 'send', mode: 'confirm' }]);
  expect(authorizeMcpTool(service, 'synthetic-owner', service.revision, service.grants[0].modelName))
    .toEqual({ status: 'confirmation_required', name: 'send' });
});
