# Build Plan — AEM Read-Only MCP Server

> **For the building agent:** This document is self-contained. Follow the tasks in order. Each
> task that creates a file includes the file’s full contents in a fenced block — reproduce it
> verbatim unless a task says otherwise. Do **not** add features beyond the scope below. The
> Security Constraints section is non-negotiable; if any instruction elsewhere would violate it,
> stop and surface the conflict instead of proceeding.

-----

## 1. Objective

Build a thin, **read-only** Model Context Protocol (MCP) server that sits in front of an Adobe
Experience Manager (AEM) **on-prem author** instance and exposes three read-only tools to MCP
clients such as Claude Code:

1. `searchContent` — QueryBuilder search (find pages/components/templates/assets).
1. `inspectNode` — return a page/node subtree as JSON to a capped depth.
1. `bundleHealth` — OSGi bundle status (off by default; needs elevated AEM rights).

The server is built on Spring Boot + Spring AI’s MCP Server Boot Starter and is intended to be
**centrally hosted** (one instance, developers connect over HTTP), not run per-laptop.

## 2. Non-Goals (do NOT implement)

- No write, create, update, or delete operations of any kind.
- No content replication / activation / publishing.
- No workflow start/advance/delegate.
- No package install/build, no JCR write, no OSGi config changes.
- No bundling of credentials into source, config, or the container image.
- No exposing of more than the three tools listed. More tools is not better.

## 3. Security Constraints (MUST)

These are mandatory and define the project’s value proposition (safe-by-default):

1. **Read-only AEM service account.** The server authenticates to AEM as a dedicated principal
   with read access only to approved trees. Never `admin`. Credentials are injected at runtime.
1. **Path allow-list enforced in-process.** Any node read whose path is not under a configured
   prefix is rejected *before* an HTTP request is sent to AEM.
1. **Result/depth caps.** QueryBuilder hit counts and node-inspection depth are clamped to
   server-side maximums to protect context size and author-instance load.
1. **Secrets only from environment / secrets manager.** `AEM_USERNAME`, `AEM_PASSWORD`,
   `AEM_BASE_URL` come from env. `.mcp.json` holds only the server URL. Nothing secret is committed
   or baked into the image.
1. **Audit trail.** Every tool call is logged (tool, caller, params) to a dedicated logger for SIEM
   shipment. Note the identity caveat in Task 7 — a shared service account cannot, by itself,
   attribute calls to an individual developer.
1. **Read-only container.** Runs as non-root; production filesystem is read-only.

## 4. Architecture

```
Claude Code (developer)
        │  MCP over HTTP (SSE; default endpoint /sse)
        ▼
AEM Read-Only MCP Server  (this project, centrally hosted)
        │  HTTPS + Basic Auth (read-only service account)
        ▼
AEM on-prem AUTHOR instance
   • /bin/querybuilder.json      (searchContent)
   • /{path}.{depth}.tidy.json   (inspectNode, Sling GET servlet)
   • /system/console/bundles.json (bundleHealth, optional/elevated)
```

Data flow to be aware of for compliance: content returned by AEM travels through this server into
the MCP client’s model context. Confirm your organization permits internal content to reach the
chosen LLM before production rollout.

## 5. Tech Stack & Versions

- Java 17
- Spring Boot 3.4.2 (parent)
- Spring AI 1.0.0 (BOM) — MCP Server Boot Starter
- Maven build
- Transport: **SSE** via `spring-ai-starter-mcp-server-webmvc` (default). Streamable HTTP is an
  opt-in upgrade — see Task 9 notes.

Keep the Spring Boot and Spring AI versions aligned when bumping; 3.4.x is the baseline Spring AI
1.0.0 GA targets.

## 6. Prerequisites

- JDK 17 and Maven available (build machine needs Maven Central access).
- A read-only AEM service account provisioned with read access to the trees you will allow-list.
- Network reachability from where the server runs to the AEM author instance.
- For containerized deploy: Docker, and a Kubernetes namespace + secrets manager for Phase 1.

## 7. Project Structure (target)

```
aem-readonly-mcp/
├── pom.xml
├── Dockerfile
├── .dockerignore
├── README.md
├── .mcp.json.example
├── k8s/
│   └── deployment.yaml
└── src/main/
    ├── java/com/example/aem/mcp/
    │   ├── AemMcpApplication.java
    │   ├── aem/
    │   │   ├── AemProperties.java
    │   │   └── AemClient.java
    │   ├── tools/
    │   │   └── AemReadOnlyTools.java
    │   ├── audit/
    │   │   └── AuditLogger.java
    │   └── config/
    │       ├── AemClientConfig.java
    │       └── ToolsConfig.java
    └── resources/
        └── application.yml
```

-----

## Build Tasks

### Task 1 — `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.2</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>aem-readonly-mcp</artifactId>
    <version>1.0.0</version>
    <name>aem-readonly-mcp</name>
    <description>Read-only MCP server over AEM on-prem (QueryBuilder, node inspect, bundle health)</description>

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.0.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Swap to spring-ai-starter-mcp-server-streamable-webmvc for streamable HTTP transport. -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### Task 2 — `src/main/java/com/example/aem/mcp/AemMcpApplication.java`

```java
package com.example.aem.mcp;

import com.example.aem.mcp.aem.AemProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AemProperties.class)
public class AemMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(AemMcpApplication.class, args);
    }
}
```

### Task 3 — `src/main/java/com/example/aem/mcp/aem/AemProperties.java`

Typed configuration + guardrails bound from `aem.*`. Credentials come from the environment.

```java
package com.example.aem.mcp.aem;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "aem")
public class AemProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    @NotEmpty
    private List<String> allowedPathPrefixes;

    @Min(1)
    private int defaultLimit = 20;

    @Min(1)
    private int maxLimit = 100;

    @Min(0)
    private int maxDepth = 3;

    private boolean bundleHealthEnabled = false;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public List<String> getAllowedPathPrefixes() { return allowedPathPrefixes; }
    public void setAllowedPathPrefixes(List<String> allowedPathPrefixes) { this.allowedPathPrefixes = allowedPathPrefixes; }

    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }

    public int getMaxLimit() { return maxLimit; }
    public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }

    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }

    public boolean isBundleHealthEnabled() { return bundleHealthEnabled; }
    public void setBundleHealthEnabled(boolean bundleHealthEnabled) { this.bundleHealthEnabled = bundleHealthEnabled; }
}
```

### Task 4 — `src/main/java/com/example/aem/mcp/config/AemClientConfig.java`

`RestClient` bean with basic auth + conservative timeouts.

```java
package com.example.aem.mcp.config;

import com.example.aem.mcp.aem.AemProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AemClientConfig {

    @Bean
    public RestClient aemRestClient(AemProperties props) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(15));
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(settings);

        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(props.getUsername(), props.getPassword()))
                .build();
    }
}
```

> Note for the agent: `ClientHttpRequestFactories` / `ClientHttpRequestFactorySettings` live in
> `org.springframework.boot.web.client` in Spring Boot 3.4.x. If you target a different Boot
> version where these are relocated/deprecated, adjust the imports accordingly but keep the same
> behaviour (5s connect, 15s read, basic auth from properties).

### Task 5 — `src/main/java/com/example/aem/mcp/aem/AemClient.java`

Thin read-only HTTP adapter + path allow-listing.

```java
package com.example.aem.mcp.aem;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class AemClient {

    private final RestClient aem;
    private final AemProperties props;

    public AemClient(RestClient aemRestClient, AemProperties props) {
        this.aem = aemRestClient;
        this.props = props;
    }

    public JsonNode queryBuilder(Map<String, String> predicates) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/bin/querybuilder.json");
        predicates.forEach(uri::queryParam);
        return aem.get()
                .uri(uri.build().toUriString())
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getNode(String path, int depth) {
        assertPathAllowed(path);
        String uri = path + "." + depth + ".tidy.json";
        return aem.get()
                .uri(uri)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode bundlesStatus() {
        return aem.get()
                .uri("/system/console/bundles.json")
                .retrieve()
                .body(JsonNode.class);
    }

    public void assertPathAllowed(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path must be an absolute repository path starting with '/'.");
        }
        boolean allowed = props.getAllowedPathPrefixes().stream().anyMatch(path::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Path '" + path + "' is outside the allowed prefixes " + props.getAllowedPathPrefixes());
        }
    }
}
```

### Task 6 — `src/main/java/com/example/aem/mcp/audit/AuditLogger.java`

```java
package com.example.aem.mcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IDENTITY CAVEAT: a remote MCP server reached by multiple developers attributes every downstream
 * AEM call to the single read-only service account, not the individual developer. The 'caller'
 * field is only as good as what the transport forwards. For true per-developer attribution,
 * propagate caller identity from the MCP client into this logger before claiming individual
 * accountability to auditors.
 */
@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AEM_MCP_AUDIT");

    public void record(String tool, String caller, Map<String, ?> params) {
        AUDIT.info("tool={} caller={} params={}", tool, caller == null ? "service-account" : caller, params);
    }
}
```

### Task 7 — `src/main/java/com/example/aem/mcp/tools/AemReadOnlyTools.java`

The three `@Tool` methods. The `@Tool` description text is what the model reads to decide when to
call each tool — keep it accurate. All methods are read-only and audit every call.

```java
package com.example.aem.mcp.tools;

import com.example.aem.mcp.aem.AemClient;
import com.example.aem.mcp.aem.AemProperties;
import com.example.aem.mcp.audit.AuditLogger;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AemReadOnlyTools {

    private final AemClient aem;
    private final AemProperties props;
    private final AuditLogger audit;

    public AemReadOnlyTools(AemClient aem, AemProperties props, AuditLogger audit) {
        this.aem = aem;
        this.props = props;
        this.audit = audit;
    }

    @Tool(description = """
            Search AEM content using QueryBuilder. Use this to answer questions like
            'which pages use a given component or template', 'find content fragments of a model',
            or 'find assets by name'. Returns a compact list of matching repository paths with a
            few key properties. Read-only.""")
    public String searchContent(
            @ToolParam(description = "Repository path to search under, e.g. /content/yoursite. Must be within the allowed trees.")
            String path,
            @ToolParam(description = "JCR node type to match, e.g. cq:Page, dam:Asset, nt:unstructured. Optional.", required = false)
            String type,
            @ToolParam(description = "Full-text search term. Optional.", required = false)
            String fulltext,
            @ToolParam(description = "A single property name to match, e.g. sling:resourceType or cq:template. Optional.", required = false)
            String property,
            @ToolParam(description = "Value the named property must equal. Required only if 'property' is set.", required = false)
            String propertyValue,
            @ToolParam(description = "Max number of hits to return. Capped by the server.", required = false)
            Integer limit) {

        aem.assertPathAllowed(path);
        int effectiveLimit = clampLimit(limit);

        Map<String, String> p = new LinkedHashMap<>();
        p.put("path", path);
        if (StringUtils.hasText(type)) {
            p.put("type", type);
        }
        if (StringUtils.hasText(fulltext)) {
            p.put("fulltext", fulltext);
        }
        if (StringUtils.hasText(property)) {
            p.put("property", property);
            p.put("property.value", propertyValue == null ? "" : propertyValue);
        }
        p.put("p.hits", "selective");
        p.put("p.properties", "jcr:title jcr:description sling:resourceType cq:template jcr:primaryType");
        p.put("p.limit", String.valueOf(effectiveLimit));
        p.put("p.guessTotal", "true");

        audit.record("searchContent", null, p);

        JsonNode result = aem.queryBuilder(p);
        return result == null ? "{}" : result.toString();
    }

    @Tool(description = """
            Inspect the content structure of a single AEM page or node as JSON, to the requested
            depth. Use this to understand a page's components, properties, and child nodes without
            opening CRXDE. Depth is capped by the server. Read-only.""")
    public String inspectNode(
            @ToolParam(description = "Absolute repository path of the node/page, e.g. /content/yoursite/en/home. Must be within the allowed trees.")
            String path,
            @ToolParam(description = "Tree depth to return (0 = just this node). Capped by the server.", required = false)
            Integer depth) {

        aem.assertPathAllowed(path);
        int effectiveDepth = clampDepth(depth);

        Map<String, Object> auditParams = new LinkedHashMap<>();
        auditParams.put("path", path);
        auditParams.put("depth", effectiveDepth);
        audit.record("inspectNode", null, auditParams);

        JsonNode node = aem.getNode(path, effectiveDepth);
        return node == null ? "{}" : node.toString();
    }

    @Tool(description = """
            Report OSGi bundle health on the AEM instance (which bundles are Active vs
            Resolved/Installed/failed). Use this to debug 'why isn't my component rendering'.
            May be disabled by configuration if the service account lacks console access. Read-only.""")
    public String bundleHealth() {
        if (!props.isBundleHealthEnabled()) {
            return "{\"disabled\":true,\"reason\":\"Bundle health is turned off in this deployment "
                    + "(requires an elevated AEM principal). Ask the platform team to enable it if needed.\"}";
        }
        audit.record("bundleHealth", null, Map.of());
        JsonNode status = aem.bundlesStatus();
        return status == null ? "{}" : status.toString();
    }

    private int clampLimit(Integer requested) {
        if (requested == null || requested < 1) {
            return props.getDefaultLimit();
        }
        return Math.min(requested, props.getMaxLimit());
    }

    private int clampDepth(Integer requested) {
        if (requested == null || requested < 0) {
            return 1;
        }
        return Math.min(requested, props.getMaxDepth());
    }
}
```

### Task 8 — `src/main/java/com/example/aem/mcp/config/ToolsConfig.java`

```java
package com.example.aem.mcp.config;

import com.example.aem.mcp.tools.AemReadOnlyTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolsConfig {

    @Bean
    public ToolCallbackProvider aemTools(AemReadOnlyTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
```

### Task 9 — `src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: aem-readonly-mcp
  ai:
    mcp:
      server:
        name: aem-readonly-mcp
        version: 1.0.0
        type: SYNC
        # Default transport is SSE (endpoint /sse). To use streamable HTTP, change the pom
        # dependency to spring-ai-starter-mcp-server-streamable-webmvc and uncomment:
        # protocol: STREAMABLE
        # streamable-http:
        #   mcp-endpoint: /mcp
        capabilities:
          tool: true
          resource: false
          prompt: false

aem:
  base-url: ${AEM_BASE_URL:https://author.internal.example.com:4502}
  username: ${AEM_USERNAME}
  password: ${AEM_PASSWORD}
  allowed-path-prefixes:
    - /content/yoursite
    - /content/dam/yoursite
  default-limit: 20
  max-limit: 100
  max-depth: 3
  bundle-health-enabled: false

logging:
  level:
    AEM_MCP_AUDIT: INFO
```

### Task 10 — `.mcp.json.example`

Committed registration form for clients. Holds only the URL — no secrets.

```json
{
  "mcpServers": {
    "aem-readonly": {
      "type": "sse",
      "url": "https://aem-mcp.internal.example.com/sse"
    }
  }
}
```

### Task 11 — `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build /build/target/aem-readonly-mcp-1.0.0.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### Task 12 — `.dockerignore`

```
target/
*.iml
.idea/
.git/
.gitignore
*.log
.env
*.env
```

### Task 13 — `k8s/deployment.yaml` (Phase 1 deploy)

Create the Secret out of band (or via your secrets manager), then apply this manifest.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: aem-readonly-mcp
  labels:
    app: aem-readonly-mcp
spec:
  replicas: 1
  selector:
    matchLabels:
      app: aem-readonly-mcp
  template:
    metadata:
      labels:
        app: aem-readonly-mcp
    spec:
      securityContext:
        runAsNonRoot: true
      containers:
        - name: aem-readonly-mcp
          image: registry.internal.example.com/aem-readonly-mcp:1.0.0
          ports:
            - containerPort: 8080
          env:
            - name: AEM_BASE_URL
              value: "https://author.internal.example.com:4502"
            - name: AEM_USERNAME
              valueFrom:
                secretKeyRef:
                  name: aem-mcp-credentials
                  key: AEM_USERNAME
            - name: AEM_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: aem-mcp-credentials
                  key: AEM_PASSWORD
          resources:
            requests:
              cpu: "100m"
              memory: "256Mi"
            limits:
              cpu: "500m"
              memory: "512Mi"
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
---
apiVersion: v1
kind: Service
metadata:
  name: aem-readonly-mcp
spec:
  selector:
    app: aem-readonly-mcp
  ports:
    - port: 80
      targetPort: 8080
  type: ClusterIP
```

-----

## 8. Build & Run — Phase 0 (local proof-of-concept)

```bash
export AEM_BASE_URL="https://author.internal.example.com:4502"
export AEM_USERNAME="svc-aem-readonly"
export AEM_PASSWORD="********"

mvn clean package
java -jar target/aem-readonly-mcp-1.0.0.jar
```

Server starts on port 8080; SSE endpoint is `/sse`.

## 9. Acceptance Criteria (the agent must verify all)

1. `mvn clean package` succeeds with no compilation errors.
1. The application starts and logs the three tools as registered.
1. With valid AEM env vars and a reachable author instance:
- `searchContent` under an allowed path returns QueryBuilder hits.
- `inspectNode` on an allowed path returns node JSON; depth above `max-depth` is clamped.
- A path outside `allowed-path-prefixes` is rejected before any AEM call.
- `bundleHealth` returns the `disabled` message while `bundle-health-enabled` is false.
1. No credentials appear in source, `application.yml`, `.mcp.json.example`, or the image.
1. Container builds and runs as non-root; secrets are injected, not baked.

## 10. Containerize & Deploy — Phase 1

```bash
docker build -t registry.internal.example.com/aem-readonly-mcp:1.0.0 .
docker run --rm -p 8080:8080 \
  -e AEM_BASE_URL="https://author.internal.example.com:4502" \
  -e AEM_USERNAME="svc-aem-readonly" \
  -e AEM_PASSWORD="********" \
  registry.internal.example.com/aem-readonly-mcp:1.0.0

# Kubernetes
kubectl create secret generic aem-mcp-credentials \
  --from-literal=AEM_USERNAME='svc-aem-readonly' \
  --from-literal=AEM_PASSWORD='********'
kubectl apply -f k8s/deployment.yaml
```

The same artifact serves both phases — only the host and secret injection differ.

## 11. Register with Claude Code

```bash
# Phase 0 (local)
claude mcp add --transport sse --scope local aem-readonly http://localhost:8080/sse

# Phase 1 (central, shared via git .mcp.json)
claude mcp add --transport sse --scope project aem-readonly https://aem-mcp.internal.example.com/sse
```

Verify with `/mcp` inside Claude Code; the `aem-readonly` server should list three tools.

## 12. Transport upgrade (optional): SSE → streamable HTTP

1. In `pom.xml`, replace the MCP server artifact with
   `spring-ai-starter-mcp-server-streamable-webmvc` (confirm it exists in your pinned Spring AI
   version first).
1. In `application.yml`, set `spring.ai.mcp.server.protocol: STREAMABLE` and
   `streamable-http.mcp-endpoint: /mcp`.
1. Register clients with `--transport http ... /mcp`.

## 13. Reuse as a template

This project is the seed for a family of read-only internal MCP servers:

1. Copy it; rename `groupId`/`artifactId`/base package.
1. Replace `AemClient` with a thin client for the next system’s read API.
1. Rewrite the `@Tool` methods — aim for 2–5 high-value tools, not a wrapper around everything.
1. Keep all six Security Constraints intact.
1. Record the definition-of-done in the marketplace `DECISION_GUIDE.md`.

## 14. Compliance gates to clear before production (verify, do not assume)

- Permission to send internal content to the chosen LLM (data egress policy / DLP).
- Data classification of the allow-listed trees (no unapproved PII/PHI/regulated content).
- Third-party/vendor risk sign-off for the LLM provider (DPA, residency, retention).
- Per-developer audit attribution if your regime requires it (see Task 7 caveat).
- Change-management / architecture review for a new internet-egressing prod service.
- OSS license + dependency-scanning gate on Spring AI and transitive deps.