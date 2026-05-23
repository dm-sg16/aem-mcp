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
- For containerized deploy: Docker + a Kubernetes namespace and secrets manager

## Project layout

```
.
├── docs/plans/plan.md             # The authoritative build plan
├── pom.xml
├── Dockerfile
├── .dockerignore
├── .mcp.json.example              # Sample Claude Code registration (no secrets)
├── k8s/
│   └── deployment.yaml            # Phase 1 manifest (Deployment + Service)
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

Bound from environment variables. Defaults live in
[`src/main/resources/application.yml`](src/main/resources/application.yml).

| Env var          | Required | Purpose                                                                       |
| ---------------- | -------- | ----------------------------------------------------------------------------- |
| `AEM_BASE_URL`   | yes      | Base URL of the AEM author instance, e.g. `https://author.internal:4502`     |
| `AEM_USERNAME`   | yes      | Read-only AEM service account                                                 |
| `AEM_PASSWORD`   | yes      | Service account password                                                      |
| `AEM_MCP_TOKEN`  | yes      | Shared bearer token guarding `/sse` (e.g. `openssl rand -hex 32`)             |

Per-deployment knobs in `application.yml`:

| Property                       | Default                        | Notes                                                                 |
| ------------------------------ | ------------------------------ | --------------------------------------------------------------------- |
| `aem.allowed-path-prefixes`    | `/content/yoursite`, `/content/dam/yoursite` | Boundary-matched; replace for your repository |
| `aem.default-limit`            | `20`                           | Default QueryBuilder hits                                             |
| `aem.max-limit`                | `100`                          | Hard cap on QueryBuilder hits                                         |
| `aem.max-depth`                | `3`                            | Hard cap on `inspectNode` depth                                       |
| `aem.bundle-health-enabled`    | `false`                        | Set `true` only if the service account has `/system/console` access  |

## Build & run — Phase 0 (local proof of concept)

```bash
export AEM_BASE_URL="https://author.internal.example.com:4502"
export AEM_USERNAME="svc-aem-readonly"
export AEM_PASSWORD="********"
export AEM_MCP_TOKEN="$(openssl rand -hex 32)"

mvn clean package
java -jar target/aem-readonly-mcp-1.0.0.jar
```

The server listens on port 8080. The MCP SSE endpoint is `/sse`. Probes
are at `/actuator/health/liveness` and `/actuator/health/readiness`.

## Container & Kubernetes — Phase 1

```bash
docker build -t registry.internal.example.com/aem-readonly-mcp:1.0.0 .

docker run --rm -p 8080:8080 \
  -e AEM_BASE_URL="https://author.internal.example.com:4502" \
  -e AEM_USERNAME="svc-aem-readonly" \
  -e AEM_PASSWORD="********" \
  -e AEM_MCP_TOKEN="$(openssl rand -hex 32)" \
  registry.internal.example.com/aem-readonly-mcp:1.0.0

# Kubernetes
kubectl create secret generic aem-mcp-credentials \
  --from-literal=AEM_USERNAME='svc-aem-readonly' \
  --from-literal=AEM_PASSWORD='********' \
  --from-literal=AEM_MCP_TOKEN="$(openssl rand -hex 32)"
kubectl apply -f k8s/deployment.yaml
```

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
