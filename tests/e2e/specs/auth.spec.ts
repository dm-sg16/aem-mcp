import { test, expect } from '@playwright/test';

// The BearerTokenFilter guards everything except a small allow-list of actuator paths.
test.describe('bearer-token authentication', () => {
  test('GET /sse without a token is rejected', async ({ request }) => {
    const res = await request.get('/sse');
    expect(res.status()).toBe(401);
    const body = await res.json();
    expect(body.error).toBe('unauthorized');
    expect(body.hint).toContain('Missing bearer token');
    expect(res.headers()['www-authenticate']).toBe('Bearer');
  });

  test('GET /sse with a non-bearer Authorization header is rejected', async ({ request }) => {
    const res = await request.get('/sse', { headers: { Authorization: 'Basic Zm9vOmJhcg==' } });
    expect(res.status()).toBe(401);
    expect((await res.json()).hint).toContain('Missing bearer token');
  });

  test('GET /sse with the wrong token is rejected', async ({ request }) => {
    const res = await request.get('/sse', { headers: { Authorization: 'Bearer not-the-real-token' } });
    expect(res.status()).toBe(401);
    expect((await res.json()).hint).toContain('Invalid bearer token');
  });

  test('the MCP message endpoint requires a token', async ({ request }) => {
    const res = await request.post('/mcp/message?sessionId=does-not-exist', { data: {} });
    expect(res.status()).toBe(401);
  });

  test('actuator health/info paths are reachable without a token', async ({ request }) => {
    for (const path of [
      '/actuator/health',
      '/actuator/health/liveness',
      '/actuator/health/readiness',
      '/actuator/info',
    ]) {
      const res = await request.get(path);
      expect(res.ok(), `${path} should be reachable unauthenticated`).toBeTruthy();
    }
  });
});
