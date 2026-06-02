import { defineConfig } from '@playwright/test';

// Ports and secrets are overridable so the same config works locally and in CI.
const APP_PORT = process.env.APP_PORT ?? '18080';
const AEM_STUB_PORT = process.env.AEM_STUB_PORT ?? '14502';
const TOKEN = process.env.AEM_MCP_TOKEN ?? 'e2e-secret-token';
const BASE_URL = `http://127.0.0.1:${APP_PORT}`;
const JAR = process.env.APP_JAR ?? '../../target/aem-readonly-mcp-1.0.0.jar';

export default defineConfig({
  testDir: './specs',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: BASE_URL,
  },
  // Playwright boots both servers and waits for them before any test runs. The stub starts first
  // (fast) so the app's startup probe and the health endpoints see a live AEM. Both are torn down
  // (SIGTERM) when the run finishes.
  webServer: [
    {
      command: 'node aem-stub.mjs',
      port: Number(AEM_STUB_PORT),
      reuseExistingServer: !process.env.CI,
      env: { AEM_STUB_PORT },
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: `java -jar ${JAR}`,
      // readiness does not depend on AEM, so it flips UP as soon as the context is ready.
      url: `${BASE_URL}/actuator/health/readiness`,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'ignore',
      stderr: 'pipe',
      env: {
        SERVER_PORT: APP_PORT,
        AEM_BASE_URL: `http://127.0.0.1:${AEM_STUB_PORT}`,
        AEM_USERNAME: 'svc-e2e',
        AEM_PASSWORD: 'e2e-password',
        AEM_MCP_TOKEN: TOKEN,
        // Enable the bundle tool/probe so the full surface is exercised end to end.
        AEM_BUNDLEHEALTHENABLED: 'true',
      },
    },
  ],
});
