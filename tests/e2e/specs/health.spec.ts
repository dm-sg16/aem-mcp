import { test, expect } from '@playwright/test';

// Actuator probes are unauthenticated; the per-tool groups expose details (show-details: always).
test.describe('actuator health probes', () => {
  test('overall health is UP', async ({ request }) => {
    const res = await request.get('/actuator/health');
    expect(res.status()).toBe(200);
    expect((await res.json()).status).toBe('UP');
  });

  test('liveness and readiness are UP', async ({ request }) => {
    for (const path of ['/actuator/health/liveness', '/actuator/health/readiness']) {
      const res = await request.get(path);
      expect(res.status()).toBe(200);
      expect((await res.json()).status).toBe('UP');
    }
  });

  test('the aggregate aem group is UP and reports all three tool components', async ({ request }) => {
    const res = await request.get('/actuator/health/aem');
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.status).toBe('UP');
    expect(Object.keys(body.components)).toEqual(
      expect.arrayContaining(['searchContent', 'inspectNode', 'bundleHealth']),
    );
  });

  test('per-tool groups are UP and expose probe details', async ({ request }) => {
    const cases: Array<[string, string]> = [
      ['/actuator/health/aem-search', 'searchContent'],
      ['/actuator/health/aem-inspect', 'inspectNode'],
      ['/actuator/health/aem-bundle', 'bundleHealth'],
    ];
    for (const [path, component] of cases) {
      const res = await request.get(path);
      expect(res.status(), `${path} status`).toBe(200);
      const body = await res.json();
      expect(body.status, `${path} UP`).toBe('UP');
      const details = body.components[component].details;
      expect(details, `${path} details`).toBeTruthy();
      expect(details.httpStatus).toBe(200);
      expect(details.probePath).toBeTruthy();
    }
  });

  test('info endpoint responds', async ({ request }) => {
    const res = await request.get('/actuator/info');
    expect(res.status()).toBe(200);
  });
});
