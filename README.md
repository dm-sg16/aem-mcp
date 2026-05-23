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
- **Bearer-token auth on `/sse`.** Shared secret from `AEM_MCP_TOKEN`;
  the server refuses to start if it is unset. Actuator probes are
  exempt. OIDC / mTLS are tracked as Phase 2.
- **Structured audit log.** ECS-encoded JSON, one event per tool call,
  with `tool`, `caller`, and `param.*` fields suitable for SIEM
  shipment.
- **Read-only container.** Non-root, `readOnlyRootFilesystem: true`,
  `emptyDir` mounted at `/tmp`.

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
├── .mcp.json.example              # Sample Claude Code registration (no secrets)
└── src/main/
    ├── java/com/example/aem/mcp/
    │   ├── AemMcpApplication.java
    │   ├── aem/
    │   │   ├── AemClient.java        # Path allow-list + segment-encoded GETs
    │   │   └── AemProperties.java    # Bound from aem.* with @AssertTrue validation
    │   ├── audit/
    │   │   └── AuditLogger.java      # MDC -> ECS JSON
    │   ├── config/
    │   │   ├── AemClientConfig.java  # RestClient bean (basic auth + timeouts)
    │   │   ├── BearerTokenFilter.java
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
| `aem.username`      | `secrets/aem-mcp-secrets.properties` → `aem.username=...`   | `AEM_USERNAME`     | Read-only AEM service account                                          |
| `aem.password`      | `secrets/aem-mcp-secrets.properties` → `aem.password=...`   | `AEM_PASSWORD`     | Service account password                                               |
| `aem-mcp.token`     | `secrets/aem-mcp-secrets.properties` → `aem-mcp.token=...`  | `AEM_MCP_TOKEN`    | Shared bearer token guarding `/sse` (`openssl rand -hex 32`)           |

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
mvn test            # Run unit tests (path allow-list suite)
mvn -DskipTests package
mvn spring-boot:run # Hot-run from source (env vars still required)
```

The unit tests in
[`AemClientPathAllowListTest`](src/test/java/com/example/aem/mcp/aem/AemClientPathAllowListTest.java)
cover the security-critical `assertPathAllowed` boundary cases. Keep
them green when modifying `AemClient`.

## Stack

- Java 17
- Spring Boot 3.4.2
- Spring AI 1.0.0 (MCP Server WebMVC starter)
- Maven

Spring Boot and Spring AI versions must be bumped together; the Spring
AI BOM tracks Boot minor versions.

## License

See [`LICENSE`](LICENSE).
