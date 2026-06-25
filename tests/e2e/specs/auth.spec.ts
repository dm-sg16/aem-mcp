import { test, expect } from '@playwright/test';

// Spring Security (oauth2-resource-server) guards everything except a small allow-list of
// actuator paths plus `/`, `/error`, and `/actuator/info`. The legacy shared bearer is still
// accepted alongside JWTs while aem-mcp.auth.legacy-bearer-enabled=true (the migration window).
test.describe('bearer-token authentication', () => {
  test('GET /sse without a token is rejected', async ({ request }) => {
    const res = await request.get('/sse');
    expect(res.status()).toBe(401);
    // Spring's BearerTokenAuthenticationEntryPoint emits `WWW-Authenticate: Bearer` with no
    // `error="..."` qualifier when no token was presented at all.
    expect(res.headers()['www-authenticate']).toMatch(/^Bearer\b/);
  });

  test('GET /sse with a non-bearer Authorization header is rejected', async ({ request }) => {
    const res = await request.get('/sse', { headers: { Authorization: 'Basic Zm9vOmJhcg==' } });
    expect(res.status()).toBe(401);
    expect(res.headers()['www-authenticate']).toMatch(/^Bearer\b/);
  });

  test('GET /sse with a malformed token is rejected with invalid_token', async ({ request }) => {
    // A bearer string that doesn't match the legacy secret AND isn't a valid JWT.
    // The legacy provider returns null (no match → falls through to JWT provider); the JWT
    // provider's NimbusJwtDecoder throws BadJwtException → InvalidBearerTokenException → 401.
    const res = await request.get('/sse', {
      headers: { Authorization: 'Bearer not-a-real-token-or-a-valid-jwt' },
    });
    expect(res.status()).toBe(401);
    expect(res.headers()['www-authenticate']).toContain('error="invalid_token"');
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
