# End-to-end automation (Playwright)

Black-box automation for the running MCP server. Because the server is a headless
service (no browser UI), this suite uses **Playwright's API-request client** for the
HTTP surface and the **official MCP TypeScript SDK** for the protocol surface — not
a browser. No Playwright browser binaries are downloaded or needed.

## What it covers

| Spec | Surface |
| --- | --- |
| `specs/auth.spec.ts` | `BearerTokenFilter`: `/sse` and the MCP message endpoint reject missing/non-bearer/wrong tokens; actuator health/info paths are reachable unauthenticated. |
| `specs/health.spec.ts` | `/actuator/health`, `liveness`, `readiness`, the aggregate `aem` group, and the per-tool `aem-search` / `aem-inspect` / `aem-bundle` groups (status + exposed probe details). |
| `specs/mcp.spec.ts` | Full MCP flow over SSE: `initialize`, `tools/list`, and `tools/call` for all three tools, including every error envelope (`missing_predicate`, `invalid_argument`, `aem_http_error`, `aem_unreachable`). |

## How it runs

`playwright.config.ts` boots two processes and waits for them before any test:

1. **`aem-stub.mjs`** — a deterministic stand-in for AEM (QueryBuilder, Sling node GETs,
   OSGi bundles console). Special path segments drive the server's error branches:
   `.../forbidden...` → HTTP 403, `.../unreachable...` → the socket is dropped.
2. **The app** — `java -jar ../../target/aem-readonly-mcp-1.0.0.jar`, pointed at the stub
   with a known bearer token and `aem.bundle-health-enabled=true`.

Both are torn down when the run finishes.

## Running

```bash
# 1. Build the app jar (from the repo root).
mvn -DskipTests package

# 2. Install Node deps (browsers are intentionally skipped).
cd tests/e2e
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install

# 3. Run.
npm test                 # or: npx playwright test
npm run test:report      # open the last HTML report
```

### Overrides (env vars)

| Var | Default | Purpose |
| --- | --- | --- |
| `APP_PORT` | `18080` | Port the app listens on. |
| `AEM_STUB_PORT` | `14502` | Port the stub AEM listens on. |
| `AEM_MCP_TOKEN` | `e2e-secret-token` | Bearer token shared by the app and the specs. |
| `APP_JAR` | `../../target/aem-readonly-mcp-1.0.0.jar` | Path to the built jar. |

Set `CI=1` to fail on `test.only`, enable one retry, and always launch fresh servers.

> **Scope note:** "100%" here means the full externally observable surface — every HTTP
> endpoint, every MCP tool, and every error envelope — is automated and green. JaCoCo
> *line* coverage is enforced separately by the JUnit unit suite (`mvn test`).
