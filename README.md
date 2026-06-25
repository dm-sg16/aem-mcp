# aem-mcp — Read-Only MCP Server for AEM

A thin, read-only [Model Context Protocol](https://modelcontextprotocol.io)
server that sits in front of an on-prem Adobe Experience Manager (AEM)
author instance and exposes three tools to MCP clients such as Claude
Code. Centrally hosted; developers connect over HTTP.

The full build plan and rationale live in
[`docs/plans/plan.md`](docs/plans/plan.md). This README is the quick
reference.

## Tools

| Tool            | Purpose                                                              | AEM endpoint                       |
| --------------- | -------------------------------------------------------------------- | ---------------------------------- |
| `searchContent` | QueryBuilder search (pages / components / templates / assets)        | `/bin/querybuilder.json`           |
| `inspectNode`   | Return a page or node subtree as JSON to a capped depth              | `/{path}.{depth}.tidy.json`        |
| `bundleHealth`  | OSGi bundle status (off by default; needs elevated AEM rights)       | `/system/console/bundles.json`     |

`searchContent` requires at least one of `type`, `fulltext`, or
`property` — a path-only call returns a structured `missing_predicate`
error to keep result sizes bounded.

## Security posture

Safe-by-default. The server enforces seven constraints documented in
§3 of the plan; the runtime essentials:

- **Read-only AEM service account.** Never `admin`. Basic auth, credentials from env.
- **In-process path allow-list.** Configured `aem.allowed-path-prefixes`
  is enforced *before* any HTTP call to AEM. Boundary-matched so
  `/content/public` does not match `/content/public-internal`.
- **Hardened path validation.** Rejects `..`, `//`, NUL, ISO control
  chars, and any segment containing `.` (blocks Sling-selector
  smuggling like `.infinity.json`).
- **Result and depth caps.** QueryBuilder hits and node-inspection depth
  are clamped server-side.
- **Bearer-token auth on `/sse`.** Spring Security
  (`spring-boot-starter-oauth2-resource-server`) accepts EITHER an
  IDP-issued JWT (validated against `aem-mcp.oidc.issuer-uri` /
  `jwk-set-uri`) OR — while the dual-auth migration window is open —
  the legacy shared secret `aem-mcp.token`. The JWT
  `preferred_username` claim flows into the audit log's `caller` field
  for per-developer attribution; legacy-bearer calls log as
  `caller=legacy:service-account` so migration progress is grep-able.
  Actuator probes (`/actuator/health/**`) are exempt. See the
  [Authentication](#authentication) section below.
- **Structured audit log.** ECS-encoded JSON, one event per tool call,
  with `tool`, `caller`, and `param.*` fields suitable for SIEM
  shipment.
- **Per-tool AEM connectivity probes.** Startup probe plus four
  unauthenticated actuator endpoints (`/actuator/health/aem` aggregate
  and `/actuator/health/aem-{search,inspect,bundle}`) verify the
  MCP↔AEM link without contributing to readiness — AEM blips don't
  restart the container.
- **Read-only container.** Non-root, `read_only: true`, with a writable
  `tmpfs` mounted at `/tmp`.

## Prerequisites

- JDK 17 (toolchain compiles to Java 17 bytecode; building on JDK 21 also works)
- Maven 3.9+
- An AEM read-only service account with read access to the trees you intend to allow-list
- Network reachability from the server to the AEM author instance
- For the containerized deploy: Docker Desktop (or any host with Docker Engine + Compose v2)

## Project layout

```
.
├── docs/plans/plan.md             # The authoritative build plan
├── pom.xml
├── Dockerfile
├── .dockerignore
├── compose.yaml                   # Docker Compose stack (recommended deploy)
├── secrets/
│   └── aem-mcp-secrets.properties.example   # Copy and fill in, then chmod 600
├── certs/                         # Optional TLS truststore for internal AEM certs
│   └── README.md                  # How to trust a self-signed / internal-CA AEM cert
├── .mcp.json.example              # Sample Claude Code registration (no secrets)
└── src/main/
    ├── java/com/adobe/mcp/
    │   ├── AemMcpApplication.java
    │   ├── aem/
    │   │   ├── AemClient.java        # Path allow-list + segment-encoded GETs
    │   │   └── AemProperties.java    # Bound from aem.* with @AssertTrue validation
    │   ├── audit/
    │   │   └── AuditLogger.java      # MDC -> ECS JSON; resolves caller from SecurityContext
    │   ├── config/
    │   │   ├── AemClientConfig.java       # RestClient bean (basic auth + timeouts)
    │   │   ├── AemMcpAuthProperties.java  # Bound from aem-mcp.* (legacy + OIDC)
    │   │   ├── LegacyTokenAuthenticationProvider.java  # Legacy shared-bearer path
    │   │   ├── SecurityConfig.java        # Spring Security: dual-auth resource-server chain
    │   │   └── ToolsConfig.java
    │   └── tools/
    │       └── AemReadOnlyTools.java # @Tool methods exposed to MCP clients
    └── resources/
        └── application.yml
```

## Configuration

The non-secret base URL is bound from an environment variable. The three secret values
(`aem.username`, `aem.password`, `aem-mcp.token`) come from one of two property sources:

- **Docker Compose**: `secrets/aem-mcp-secrets.properties` on the host, mounted into the
  container at `/run/secrets/aem-mcp-secrets.properties`. Imported by Spring Boot via
  `spring.config.import` in [`application.yml`](src/main/resources/application.yml).
- **`java -jar` / `mvn spring-boot:run` (dev)**: the same env-var names work as a fallback
  because the placeholders in `application.yml` are
  `${AEM_USERNAME:}` / `${AEM_PASSWORD:}` / `${AEM_MCP_TOKEN:}`.

| Setting             | Compose source                                              | Env-var fallback   | Notes                                                                  |
| ------------------- | ----------------------------------------------------------- | ------------------ | ---------------------------------------------------------------------- |
| `AEM_BASE_URL`      | `compose.yaml` `environment:` block (not secret)            | `AEM_BASE_URL`     | Base URL of the AEM author instance                                    |
| `aem.context-root`  | `application.yml` or `compose.yaml` `environment:`          | `AEM_CONTEXT_ROOT` | Optional sub-path AEM is mounted at, e.g. `/WC2`. Empty by default.    |
| `aem.username`      | `secrets/aem-mcp-secrets.properties` → `aem.username=...`   | `AEM_USERNAME`     | Read-only AEM service account                                          |
| `aem.password`      | `secrets/aem-mcp-secrets.properties` → `aem.password=...`   | `AEM_PASSWORD`     | Service account password                                               |
| `aem-mcp.token`     | `secrets/aem-mcp-secrets.properties` → `aem-mcp.token=...`  | `AEM_MCP_TOKEN`    | Legacy shared bearer (dual-auth window; see [Authentication](#authentication))            |

`@NotBlank` validation fails fast at startup if any required value is missing from both
sources.

Per-deployment knobs in `application.yml`:

| Property                       | Default                        | Notes                                                                 |
| ------------------------------ | ------------------------------ | --------------------------------------------------------------------- |
| `aem.allowed-path-prefixes`    | `/content/yoursite`, `/content/dam/yoursite` | Boundary-matched; replace for your repository |
| `aem.default-limit`            | `20`                           | Default QueryBuilder hits                                             |
| `aem.max-limit`                | `100`                          | Hard cap on QueryBuilder hits                                         |
| `aem.max-depth`                | `3`                            | Hard cap on `inspectNode` depth                                       |
| `aem.bundle-health-enabled`    | `false`                        | Set `true` only if the service account has `/system/console` access  |
| `aem-mcp.auth.legacy-bearer-enabled` | `true`                   | Accept the legacy shared bearer in addition to JWTs (dual-auth window) |
| `aem-mcp.oidc.issuer-uri`      | (unset)                        | OIDC discovery URL. Set this OR `jwk-set-uri`.                       |
| `aem-mcp.oidc.jwk-set-uri`     | (unset)                        | JWKS endpoint. Preferred when OIDC discovery is blocked.             |
| `aem-mcp.oidc.audience`        | (unset)                        | Optional required `aud` claim                                        |
| `aem-mcp.oidc.principal-claim` | `preferred_username`           | JWT claim used as the audit-log `caller`. Falls back to `sub`.       |

## Run with Docker Compose (recommended)

```bash
# 1. Create the secrets file from the template.
cp secrets/aem-mcp-secrets.properties.example secrets/aem-mcp-secrets.properties
chmod 600 secrets/aem-mcp-secrets.properties

# 2. Edit the file: set aem.username, aem.password, and aem-mcp.token.
#    Generate a strong token with: openssl rand -hex 32
$EDITOR secrets/aem-mcp-secrets.properties

# 3. (Optional) point at your AEM author. Default is a placeholder.
#    Edit AEM_BASE_URL under `environment:` in compose.yaml.

# 4. Build and start.
docker compose up -d
docker compose logs -f aem-readonly-mcp
```

The container binds to `127.0.0.1:8080` by default (loopback only). The MCP SSE endpoint is
`/sse`; actuator probes live at `/actuator/health/{liveness,readiness}`. Secrets are mounted
read-only at `/run/secrets/aem-mcp-secrets.properties` and Spring Boot imports them via
`spring.config.import` — they do **not** appear in `docker inspect` env nor in `ps`.

To stop: `docker compose down`. To rebuild after a code change: `docker compose up -d --build`.

### Verify AEM connectivity

The server runs a per-tool AEM connectivity probe at startup (look for the `AEM connectivity
check:` line in `docker compose logs`) and exposes the same probes on demand at four
unauthenticated actuator endpoints:

```bash
# Aggregate — one call covers all three tools
curl -sS http://localhost:8080/actuator/health/aem | jq

# Per-tool — useful when you want to know which AEM endpoint is unhappy
curl -sS http://localhost:8080/actuator/health/aem-search   | jq   # searchContent
curl -sS http://localhost:8080/actuator/health/aem-inspect  | jq   # inspectNode
curl -sS http://localhost:8080/actuator/health/aem-bundle   | jq   # bundleHealth
```

Status code is `200` when the probe is UP or UNKNOWN, `503` when DOWN. Map DOWN categories to
root causes:

| Category              | Likely cause                                                                          |
| --------------------- | ------------------------------------------------------------------------------------- |
| `unauthorized`        | AEM rejected the service-account credentials                                          |
| `forbidden`           | Service account lacks read access to that AEM endpoint                                |
| `not_found`           | The probed path doesn't exist in AEM (often: `aem.allowed-path-prefixes[0]` is wrong) |
| `aem_server_error`    | AEM returned a 5xx — author instance unhealthy                                        |
| `timeout`             | AEM didn't respond inside the configured read timeout                                 |
| `unreachable`         | DNS / connect refused / TLS — wrong `AEM_BASE_URL` or network blocked                 |
| `disabled_by_config`  | (`aem-bundle` only) `aem.bundle-health-enabled=false` — expected default              |

These endpoints do not require the bearer token and do not contribute to
`/actuator/health/readiness`, so AEM blips don't restart the container.

When a probe is DOWN at the connection layer, the app log carries the underlying
cause (the health detail omits it to keep the unauthenticated endpoint topology-safe):

```
WARN  ... AEM probe for tool 'searchContent' is DOWN (unreachable):
      SSLHandshakeException: PKIX path building failed ... unable to find valid
      certification path to requested target
```

### Trusting an internal AEM TLS certificate

A `PKIX path building failed` handshake error means the JVM doesn't trust AEM's
certificate — typical for an author instance with a self-signed or internal-CA cert.
Fix it by importing the cert into a truststore (never by disabling verification):
copy the AEM author or, preferably, your internal CA certificate into `certs/`, build a
truststore, and uncomment the pre-written `volumes:` mount and `JAVA_TOOL_OPTIONS`
override in `compose.yaml`. Step-by-step instructions are in
[`certs/README.md`](certs/README.md).

If the log shows `ConnectException` / `UnknownHostException` instead, it's host/port,
DNS, or a blocked network path — not TLS — and the truststore won't help.

## Run locally without Docker (dev loop)

```bash
export AEM_BASE_URL="https://author.internal.example.com:4502"
export AEM_USERNAME="svc-aem-readonly"
export AEM_PASSWORD="********"
export AEM_MCP_TOKEN="$(openssl rand -hex 32)"

mvn clean package
java -jar target/aem-readonly-mcp-1.0.0.jar
```

Same listen port (`8080`), same endpoints. Useful for quick iteration; the Compose path is
the recommended deploy.

## Authentication

The `/sse` endpoint accepts a JWT issued by your OIDC provider; during
the migration window the legacy shared bearer (`aem-mcp.token`) is also
accepted.

**JWT (preferred).** Each developer obtains an IDP-issued JWT via their
IDP CLI (`okta login`, `gcloud auth print-identity-token`, etc.) and
exports it as `AEM_MCP_TOKEN`. The `.mcp.json` shape is unchanged from
the legacy flow — Claude Code just expands `${AEM_MCP_TOKEN}` into the
`Authorization` header. The JWT's `preferred_username` claim populates
the audit-log `caller` field (falls back to `sub` if absent — logged at
WARN once per process).

**Legacy shared bearer.** While
`aem-mcp.auth.legacy-bearer-enabled=true` (the default during
migration), the server also accepts the literal value of
`aem-mcp.token`. Calls authenticated this way log as
`caller=legacy:service-account`. Once all developers have moved to
JWTs, flip the flag to `false` (the property is bound from
`AEM_MCP_LEGACY_BEARER_ENABLED`); a follow-up release deletes the
legacy code path.

```bash
# Track migration progress by counting legacy-path audit events:
docker compose logs aem-readonly-mcp | grep '"caller":"legacy:service-account"' | wc -l
```

**Known limitation — JWT expiry on long SSE streams.** The MCP SSE
stream is a long-lived GET that holds whatever auth context it had when
the connection opened. Subsequent JSON-RPC POSTs are re-validated and
will 401 once the JWT's `exp` passes — at which point Claude Code must
reconnect. Mitigate by issuing JWTs with TTL ≥ 24 h.

## Register with Claude Code

```bash
# Local
claude mcp add --transport sse --scope local aem-readonly http://localhost:8080/sse \
  --header "Authorization: Bearer ${AEM_MCP_TOKEN}"

# Central (shared via git-tracked .mcp.json)
claude mcp add --transport sse --scope project aem-readonly https://aem-mcp.internal.example.com/sse \
  --header "Authorization: Bearer \${AEM_MCP_TOKEN}"
```

`.mcp.json.example` shows the JSON form. Note: the CLI flag is
`--transport sse`; the equivalent JSON field is `"type": "sse"`. Both
refer to the same setting.

Verify with `/mcp` inside Claude Code; the `aem-readonly` server should
list three tools.

## Error model

Tool methods never leak raw stack traces. AEM failures and validation
errors come back as a structured JSON string:

```json
{ "error": "<short_code>", "status": <int|null>, "hint": "<actionable>" }
```

Common codes:

| Code                | When                                                        |
| ------------------- | ----------------------------------------------------------- |
| `invalid_argument`  | Path validation failure (allow-list, traversal, selector)  |
| `missing_predicate` | `searchContent` called without `type`/`fulltext`/`property` |
| `aem_http_error`    | AEM returned a non-2xx; `status` carries the HTTP code     |
| `aem_unreachable`   | Network/connect failure                                     |
| `aem_call_failed`   | Other RestClient errors                                     |
| `unauthorized`      | Missing or wrong bearer token on `/sse`                     |

## Development

```bash
mvn test            # Run unit tests + JaCoCo (build fails under 100% line coverage)
mvn -DskipTests package
mvn spring-boot:run # Hot-run from source (env vars still required)
```

The JUnit suite has 100% line coverage (enforced by the JaCoCo `check` goal),
including the security-critical `assertPathAllowed` boundary cases in
[`AemClientPathAllowListTest`](src/test/java/com/adobe/mcp/aem/AemClientPathAllowListTest.java).
Keep it green when modifying the code.

### End-to-end tests

[`tests/e2e`](tests/e2e) holds a Playwright suite that drives the **running** server
black-box — bearer auth, all actuator health probes, and the full MCP protocol over
SSE (`initialize` / `tools/list` / `tools/call`) against a stubbed AEM. It uses
Playwright's API-request client plus the MCP TypeScript SDK (no browser). See
[`tests/e2e/README.md`](tests/e2e/README.md) to run it.

## Stack

- Java 17
- Spring Boot 3.4.2
- Spring AI 1.0.0 (MCP Server WebMVC starter)
- Maven

Spring Boot and Spring AI versions must be bumped together; the Spring
AI BOM tracks Boot minor versions.

## License

See [`LICENSE`](LICENSE).
