import { test, expect } from '@playwright/test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';
import { SSE_URL, TOKEN } from './env.js';

// Inject the bearer token on both the SSE stream (via the eventsource fetch) and the JSON-RPC POSTs.
const authFetch = ((input: any, init: any) => {
  const headers = new Headers(init?.headers);
  headers.set('Authorization', `Bearer ${TOKEN}`);
  return fetch(input, { ...init, headers });
}) as typeof fetch;

function newClient(): { client: Client; transport: SSEClientTransport } {
  const transport = new SSEClientTransport(new URL(SSE_URL), {
    eventSourceInit: { fetch: authFetch } as any,
    requestInit: { headers: { Authorization: `Bearer ${TOKEN}` } },
  });
  const client = new Client({ name: 'aem-mcp-e2e', version: '1.0.0' }, { capabilities: {} });
  return { client, transport };
}

// The tools return a JSON string; Spring AI then JSON-encodes that String into the MCP text
// content, so the text is a quoted JSON string wrapping the tool's JSON. Unwrap both layers.
async function callToolObject(client: Client, name: string, args: Record<string, unknown>): Promise<any> {
  const result: any = await client.callTool({ name, arguments: args });
  const text = (result.content as Array<{ type: string; text?: string }>)
    .map((c) => c.text ?? '')
    .join('');
  const unwrapped = JSON.parse(text);
  return typeof unwrapped === 'string' ? JSON.parse(unwrapped) : unwrapped;
}

// For tools that must return AEM data (not an error envelope). Surfaces the actual payload so a
// misconfigured backend — e.g. the app not pointed at the stub, which yields aem_unreachable —
// fails with a clear message instead of an opaque "expected true received undefined".
async function expectData(client: Client, name: string, args: Record<string, unknown>): Promise<any> {
  const json = await callToolObject(client, name, args);
  expect(
    json.error,
    `expected AEM data from ${name} but got an error envelope: ${JSON.stringify(json)}`,
  ).toBeUndefined();
  return json;
}

test.describe('MCP protocol over SSE', () => {
  let client: Client;
  let transport: SSEClientTransport;

  test.beforeEach(async () => {
    ({ client, transport } = newClient());
    await client.connect(transport); // performs the initialize handshake
  });

  test.afterEach(async () => {
    await client.close();
  });

  test('initialize succeeds and reports the server identity', async () => {
    const info = client.getServerVersion();
    expect(info?.name).toBe('aem-readonly-mcp');
  });

  test('tools/list exposes the three read-only tools', async () => {
    const { tools } = await client.listTools();
    const names = tools.map((t) => t.name);
    expect(names).toEqual(expect.arrayContaining(['searchContent', 'inspectNode', 'bundleHealth']));
  });

  test('searchContent returns AEM hits', async () => {
    const json = await expectData(client, 'searchContent', { path: '/content/yoursite', type: 'cq:Page' });
    expect(json.success).toBe(true);
    expect(json.hits.length).toBeGreaterThan(0);
  });

  test('searchContent without a predicate returns missing_predicate', async () => {
    const json = await callToolObject(client, 'searchContent', { path: '/content/yoursite' });
    expect(json.error).toBe('missing_predicate');
  });

  test('searchContent outside the allow-list returns invalid_argument', async () => {
    const json = await callToolObject(client, 'searchContent', { path: '/content/private', type: 'cq:Page' });
    expect(json.error).toBe('invalid_argument');
  });

  test('inspectNode returns the node JSON', async () => {
    const json = await expectData(client, 'inspectNode', { path: '/content/yoursite/en', depth: 1 });
    expect(json['jcr:primaryType']).toBe('cq:Page');
  });

  test('inspectNode maps an AEM 403 to aem_http_error', async () => {
    const json = await callToolObject(client, 'inspectNode', { path: '/content/yoursite/forbidden' });
    expect(json.error).toBe('aem_http_error');
    expect(json.status).toBe(403);
  });

  test('inspectNode maps a connection failure to aem_unreachable', async () => {
    const json = await callToolObject(client, 'inspectNode', { path: '/content/yoursite/unreachable' });
    expect(json.error).toBe('aem_unreachable');
  });

  test('bundleHealth returns the OSGi bundle status', async () => {
    const json = await expectData(client, 'bundleHealth', {});
    expect(json.status).toContain('bundles');
  });
});
