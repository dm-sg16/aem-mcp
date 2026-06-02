// Shared endpoints/credentials for the specs, mirroring playwright.config.ts defaults.
export const APP_PORT = process.env.APP_PORT ?? '18080';
export const BASE_URL = `http://127.0.0.1:${APP_PORT}`;
export const TOKEN = process.env.AEM_MCP_TOKEN ?? 'e2e-secret-token';
export const SSE_URL = `${BASE_URL}/sse`;
export const authHeader = { Authorization: `Bearer ${TOKEN}` };
