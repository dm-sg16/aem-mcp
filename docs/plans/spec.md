# AEM Read-Only MCP Server — Specification

> Companion to [`plan.md`](plan.md). The plan is the task-by-task build record (with code
> snippets); this spec is the contract reference for the running system.

## 1. What it is

A Spring Boot 3.4 service that exposes a small, allow-listed set of **read-only** AEM
operations to MCP clients (Claude Code, Claude Desktop, MCP Inspector, custom SDK clients) over
SSE. Designed to be hosted alongside an AEM author instance and queried by AI agents that need
content metadata without ever being able to write, replicate, or otherwise mutate AEM state.

The deployment unit is a single OCI image running under Docker Compose. Multi-host /
high-availability hosting is deliberately out of scope.

## 2. Scope

**In scope (Phase 1, current):**

- Three read-only tools exposed over MCP (`searchContent`, `inspectNode`, `bundleHealth`)
- Path allow-list enforced before every AEM call
- Shared bearer token on `/sse`
- Structured ECS JSON audit log, one line per tool call
- File-mounted secrets via Docker Compose `secrets:`
- Health probes for container orchestration

**Out of scope (see §10):**

- Any write/replicate/delete operation
- Per-developer identity (OIDC, mTLS)
- Asset binary transfer
- Multi-tenant or shared deployment

## 3. Tool contracts

All three tools return either a tool-specific success payload or a structured error envelope
`{"error": "<code>", "status": <int|null>, "hint": "<string>"}`. Error codes the agent must
handle:

| Code              | When                                                              |
| ----------------- | ----------------------------------------------------------------- |
| `path_not_allowed`| Requested path is outside `aem.allowed-path-prefixes`             |
| `invalid_path`    | Path contains `..`, `//`, a control char, or a segment with `.`   |
| `missing_predicate` | `searchContent` invoked with none of `type`/`fulltext`/`property` |
| `unauthorized`    | AEM rejected the service-account credentials (401)                |
| `forbidden`       | AEM 403 — service account lacks read access                       |
| `not_found`       | AEM 404 — path does not resolve                                   |
| `timeout`         | AEM did not respond within the configured timeout                 |
| `unreachable`     | DNS / connection refused / TLS failure                            |
| `disabled`        | Tool currently disabled by configuration (e.g. `bundleHealth`)    |

### 3.1 `searchContent`

QueryBuilder-backed search under an allow-listed root.

| Param         | Type     | Required | Notes                                                  |
| ------------- | -------- | -------- | ------------------------------------------------------ |
| `path`        | string   | yes      | Must start with one of `aem.allowed-path-prefixes`     |
| `type`        | string   | one of   | JCR primary type, e.g. `cq:Page`                       |
| `fulltext`    | string   | one of   | Full-text query string                                 |
| `property`    | object   | one of   | Map of `{name: value}` for `property.N_property` constraints |
| `limit`       | int      | no       | Capped at `aem.max-limit` (default 100)                |

At least one of `type` / `fulltext` / `property` must be supplied; otherwise
`missing_predicate`. Result is the raw QueryBuilder JSON (`hits`, `total`, `success`).

### 3.2 `inspectNode`

Fetch a JCR node as JSON (Sling `.N.json` selector).

| Param   | Type   | Required | Notes                                              |
| ------- | ------ | -------- | -------------------------------------------------- |
| `path`  | string | yes      | Must start with one of `aem.allowed-path-prefixes` |
| `depth` | int    | no       | Clamped at `aem.max-depth` (default 3)             |

Returns the node's JSON representation; non-existent paths surface as `not_found`.

### 3.3 `bundleHealth`

OSGi bundle status summary. Disabled by default; gated by `aem.bundle-health-enabled` because
the service account typically does not have `/system/console` access.

- Disabled: returns `{"error": "disabled", "hint": "Enable aem.bundle-health-enabled..."}`
- Enabled: returns `{"active": <int>, "resolved": <int>, "installed": <int>, "fragments": <int>}`

## 4. Path security model

Every tool call goes through `AemClient.assertPathAllowed(path)` before any outbound HTTP. The
check enforces, in order:

1. Path is non-blank, starts with `/`, and contains no control characters
2. Path does not contain `..` segments, `//`, or any segment containing `.` (blocks Sling
   selectors like `.infinity.json`, `.tidy.json`, etc.)
3. Path matches one of `aem.allowed-path-prefixes` with a **boundary check** — a configured
   prefix of `/content/public` will NOT match a request for `/content/public-internal/...`

Per-segment URL encoding is applied before the AEM call so that spaces, unicode, and other
safe-but-special characters survive the round trip.

## 5. Transport and authentication

- Transport: Server-Sent Events at `/sse` (the default Spring AI MCP server transport). The
  streamable-HTTP transport is available behind a one-line config change (see commented
  `protocol: STREAMABLE` block in `application.yml`).
- Authentication: every request to `/sse` must carry `Authorization: Bearer <aem-mcp.token>`.
  The token is a shared secret, sourced from either the mounted secrets file or the
  `AEM_MCP_TOKEN` env var. `BearerTokenFilter` refuses to start the server if no token is
  configured.
- Unauthenticated paths: `/actuator/health/liveness`, `/actuator/health/readiness`, and `/`.
  Everything else, including `/actuator/info`, requires the token.
- Failure response: `401 Unauthorized` with `WWW-Authenticate: Bearer` and body
  `{"error":"unauthorized", "hint": "Missing/invalid bearer token"}`.

## 6. Configuration surface

### 6.1 Secret values

Loaded by Spring Boot from `/run/secrets/aem-mcp-secrets.properties` (mounted by Compose) via
`spring.config.import`. Both keys also accept env-var fallback for local `java -jar` dev:

| Property         | Env-var fallback | Purpose                                |
| ---------------- | ---------------- | -------------------------------------- |
| `aem.username`   | `AEM_USERNAME`   | AEM service-account login              |
| `aem.password`   | `AEM_PASSWORD`   | AEM service-account password           |
| `aem-mcp.token`  | `AEM_MCP_TOKEN`  | Shared bearer token guarding `/sse`    |

The secrets file is gitignored; only the `.example` template is committed.

### 6.2 Non-secret runtime config

Lives in `compose.yaml` `environment:` (or env at dev time):

| Env var             | Default                                       | Purpose                                                    |
| ------------------- | --------------------------------------------- | ---------------------------------------------------------- |
| `AEM_BASE_URL`      | `https://author.internal.example.com:4502`    | AEM author HTTP(S) endpoint                                |
| `AEM_CONTEXT_ROOT`  | `""` (root-mounted AEM)                       | Optional sub-path, e.g. `/WC2`. Empty = root-mounted AEM.  |

### 6.3 Per-deployment knobs (`application.yml`)

| Property                       | Default                                      | Notes                                 |
| ------------------------------ | -------------------------------------------- | ------------------------------------- |
| `aem.allowed-path-prefixes`    | `/content/yoursite`, `/content/dam/yoursite` | Replace for your repository           |
| `aem.default-limit`            | `20`                                         | Default QueryBuilder hit count        |
| `aem.max-limit`                | `100`                                        | Hard cap on QueryBuilder hits         |
| `aem.max-depth`                | `3`                                          | Hard cap on `inspectNode` depth       |
| `aem.bundle-health-enabled`    | `false`                                      | Set `true` only with console access   |
| `aem.health.inspect-node-path` | (unset → first allow-listed prefix + `.0.json`) | Path probed by the `inspectNode` actuator probe (see §8) |
| `aem.context-root`             | `""` (root-mounted AEM)                      | Optional sub-path, e.g. `/WC2`. Prepended to every outbound HTTP URI. JCR paths in allow-list and tool args are unaffected. |

## 7. Deployment

### 7.1 Recommended path — Docker Compose

```bash
cp secrets/aem-mcp-secrets.properties.example secrets/aem-mcp-secrets.properties
chmod 600 secrets/aem-mcp-secrets.properties
$EDITOR secrets/aem-mcp-secrets.properties       # set aem.username, aem.password, aem-mcp.token
docker compose up -d --build
```

The Compose stack enforces:

- Non-root execution (image's `USER app`)
- Read-only root filesystem with a 64 MiB tmpfs at `/tmp`
- `cap_drop: ALL` + `no-new-privileges:true`
- Loopback-only port binding (`127.0.0.1:8080:8080`)
- File-mounted secrets at `/run/secrets/aem-mcp-secrets.properties`
- `restart: unless-stopped`
- TCP healthcheck on port 8080 (the slim JRE image has no curl)
- `pull_policy: build` so Compose never tries to pull `aem-readonly-mcp:1.0.0` from a registry

### 7.2 Local dev path — `java -jar`

```bash
export AEM_BASE_URL=... AEM_USERNAME=... AEM_PASSWORD=... AEM_MCP_TOKEN=...
mvn clean package
java -jar target/aem-readonly-mcp-1.0.0.jar
```

Same JAR, same listen port, same `/sse` and probe endpoints. Useful for fast iteration.

### 7.3 Air-gapped / restricted Docker environments

The default Dockerfile pulls Red Hat UBI 9 OpenJDK images from
`registry.access.redhat.com/ubi9/openjdk-17:1.20` (build) and
`registry.access.redhat.com/ubi9/openjdk-17-runtime:1.20` (runtime). UBI is freely
redistributable without a Red Hat subscription and is reachable from most corporate networks
where Docker Hub egress is blocked. If your environment mirrors UBI to an internal registry,
change the `FROM` lines in `Dockerfile` and `docs/plans/plan.md` Task 11 to your mirror's
hostname and tag.

If even `registry.access.redhat.com` is blocked, fall back to pre-building the JAR with
`mvn package` and using a single-stage Dockerfile against whatever JRE 17 image your registry
does carry — the runtime needs only `java -jar`, nothing else.

## 8. Observability

- **Audit log.** One line per MCP tool call, emitted as ECS JSON on stdout with
  `aem.mcp.tool.invoked` and a `tool=<name>` field. Includes `caller` (currently a synthetic
  client id pending Phase 2 identity) and `param.*` fields. Grep with:
  ```bash
  docker compose logs aem-readonly-mcp | grep aem.mcp.tool.invoked
  ```
- **Health probes (lifecycle).** `/actuator/health/liveness` and `/actuator/health/readiness`
  return 200 on a healthy instance. The Compose stack uses a TCP probe instead so the slim
  runtime image doesn't need curl/wget; external HTTP probes still work.
- **AEM connectivity probes (per-tool).** Four unauthenticated actuator endpoints exercise the
  exact AEM endpoint and permission scope each MCP tool uses, so operators can pinpoint which
  tool's dependency is broken without invoking the MCP transport:

  | Endpoint                              | Probes                                                                                            | UNKNOWN when                       |
  | ------------------------------------- | ------------------------------------------------------------------------------------------------- | ---------------------------------- |
  | `/actuator/health/aem-search`         | `GET /bin/querybuilder.json?type=cq:Page&p.limit=0` — the `searchContent` tool's endpoint         | never                              |
  | `/actuator/health/aem-inspect`        | `GET ${aem.health.inspect-node-path or first allowed prefix + .0.json}` — the `inspectNode` path  | never                              |
  | `/actuator/health/aem-bundle`         | `GET /system/console/bundles.json` — the `bundleHealth` endpoint                                  | `aem.bundle-health-enabled=false`  |
  | `/actuator/health/aem`                | Aggregate of the three above                                                                      | n/a (rolls up)                     |

  Response is `200` when the probe is UP or UNKNOWN, `503` when DOWN. Details include
  `httpStatus`, `latencyMs`, and a `category` from the shared error vocabulary:
  `unauthorized`, `forbidden`, `not_found`, `aem_server_error`, `timeout`, `unreachable`,
  `disabled_by_config`. Details deliberately exclude `baseUrl`, `username`, and any
  secret-derived value to avoid topology leakage to unauthenticated callers. None of these
  endpoints contribute to `/actuator/health/readiness` — a transient AEM blip does NOT
  restart the container.

- **Startup probe.** On `ApplicationReadyEvent` the server invokes each tool's health
  indicator once and emits:
  1. Three structured audit-log lines via `AuditLogger.record("aem_connectivity_probe", …)`
     — one per tool — with `phase=startup`, `tool=<name>`, `status`, `latencyMs`, and the
     diagnostic fields.
  2. One human-readable INFO/WARN summary line of the form
     `AEM connectivity check: searchContent=UP(200,47ms) inspectNode=UP(200,38ms) bundleHealth=UNKNOWN(disabled_by_config)`,
     visible at the top of `docker compose logs`. If any tool is DOWN, the line is logged at
     WARN level with the trailing text "server will continue serving" — startup never fails
     on a DOWN probe.
- **Application logging.** Spring Boot 3.4's built-in ECS JSON encoder formats all logs as
  single-line JSON on stdout; no `logback-spring.xml` needed. Collect via your host's container
  log driver.

## 9. Acceptance / smoke tests

The shipped state satisfies:

1. `mvn clean package` builds the boot jar; `mvn test` passes 18 path-allow-list tests
2. App starts and logs `Registered tools: 3`
3. `/sse` returns 401 without `Authorization: Bearer`; opens the SSE stream with a valid token
4. `/actuator/health/{liveness,readiness}` return 200
5. `BearerTokenFilter` fails fast on startup if neither the secrets file nor `AEM_MCP_TOKEN`
   provides a token, with a message naming both sources
6. End-to-end via MCP Inspector: connect to `http://localhost:8080/sse` with the bearer token,
   invoke `inspectNode` on an allow-listed path against a reachable AEM author, receive node
   JSON. Errors surface as the structured envelope in §3.

## 10. Future hardening (deliberate non-goals for Phase 1)

- **Per-developer identity.** Replace the shared bearer token with OIDC (carrying user identity
  in JWT claims) or mTLS. The audit log's `caller` field already anticipates this.
- **Write operations.** Out of scope by design. If ever added, they would land behind a
  separate `aem-write-mcp` server with its own threat model and approval workflow.
- **Multi-host / HA.** Re-introduce a Kubernetes manifest, External-Secrets-Operator-backed
  `Secret`, and an ingress. The Compose-only deploy is for the single-host case.
- **Bundle health by default.** Stays off until the service account can safely be granted
  `/system/console` read access.

## 11. References

- Build plan and task-by-task history: [`plan.md`](plan.md)
- Source: `src/main/java/com/adobe/mcp/`
- Compose stack: [`/compose.yaml`](../../compose.yaml)
- Secrets template: [`/secrets/aem-mcp-secrets.properties.example`](../../secrets/aem-mcp-secrets.properties.example)
