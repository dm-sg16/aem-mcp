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
1. **Secrets out-of-band.** In the Docker Compose deploy the three secret values
   (`aem.username`, `aem.password`, `aem-mcp.token`) live in a gitignored properties file at
   `./secrets/aem-mcp-secrets.properties`, mounted read-only into the container at
   `/run/secrets/aem-mcp-secrets.properties` and imported by Spring Boot via
   `spring.config.import`. They never appear in `docker inspect` env nor in `ps`. For plain
   `java -jar` development the same names work as `AEM_USERNAME` / `AEM_PASSWORD` /
   `AEM_MCP_TOKEN` env vars (the application.yml placeholders accept either). `AEM_BASE_URL`
   is not secret and lives in `compose.yaml`'s `environment:` block. `.mcp.json` holds only the
   server URL and env-var *references* (e.g. `${AEM_MCP_TOKEN}`) — never literal secrets.
   Nothing secret is committed or baked into the image.
1. **Audit trail.** Every tool call is logged (tool, caller, params) to a dedicated logger for SIEM
   shipment. Note the identity caveat in Task 7 — a shared service account cannot, by itself,
   attribute calls to an individual developer.
1. **Read-only container.** Runs as non-root; production filesystem is read-only.
1. **Transport authentication on `/sse`.** A shared bearer token (the `aem-mcp.token` property,
   sourced from the secrets file or the `AEM_MCP_TOKEN` env var) is required on every MCP
   request; the server refuses to start if the token is unset. Only the probe endpoints under
   `/actuator/health/*` are exempt. Per-developer identity (OIDC or mTLS) is the Phase 2
   successor — see §4 "Future hardening".

## 4. Architecture

```
Claude Code (developer)
        │  MCP over HTTP (SSE; default endpoint /sse)
        │  Authorization: Bearer ${AEM_MCP_TOKEN}      (shared secret, Phase 1)
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

### Future hardening (Phase 2 — out of scope for this build)

The Phase 1 design is deliberately read-only and uses a shared bearer token. The following items
are explicitly **not** in scope today; record them so the next iteration does not have to
re-discover them:

1. Replace the shared-secret bearer with OIDC or mTLS so the server can attribute each call to
   the individual developer rather than the service account.
1. Propagate that authenticated principal into `AuditLogger.record(...)` (the `caller` parameter)
   so the audit trail is per-developer rather than per-service-account.
1. If write tools are ever added, introduce a separate `aem.writable-path-prefixes` allow-list
   (defaulting empty) and a distinct `@WriteTool` marker so the read-only and write surfaces
   cannot be confused at runtime.
1. Add per-caller rate limits / quotas once real usage patterns are known.

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
- For the containerized deploy: Docker Desktop (or any host with Docker Engine + Compose v2).

## 7. Project Structure (target)

```
aem-readonly-mcp/
├── pom.xml
├── Dockerfile
├── .dockerignore
├── README.md
├── .mcp.json.example
├── compose.yaml
├── secrets/
│   ├── .gitkeep
│   └── aem-mcp-secrets.properties.example
└── src/main/
    ├── java/com/adobe/mcp/
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
    │       ├── BearerTokenFilter.java
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

    <groupId>com.adobe</groupId>
    <artifactId>aem-readonly-mcp</artifactId>
    <version>1.0.0</version>
    <name>aem-readonly-mcp</name>
    <description>Read-only MCP server over AEM on-prem (QueryBuilder, node inspect, bundle health)</description>

    <properties>
        <java.version>17</java.version>
        <!-- Spring Boot and Spring AI must be bumped together: the Spring AI BOM tracks Boot
             minor versions, and mismatches surface as runtime ClassNotFoundExceptions in the
             MCP starter. Confirm the pair before changing either. -->
        <spring-boot.version>3.4.2</spring-boot.version>
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
            <artifactId>spring-boot-starter-actuator</artifactId>
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

### Task 2 — `src/main/java/com/adobe/mcp/AemMcpApplication.java`

```java
package com.adobe.mcp;

import com.adobe.mcp.aem.AemProperties;
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

### Task 3 — `src/main/java/com/adobe/mcp/aem/AemProperties.java`

Typed configuration + guardrails bound from `aem.*`. Credentials come from the environment.

```java
package com.adobe.mcp.aem;

import jakarta.validation.constraints.AssertTrue;
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

    @AssertTrue(message = "aem.default-limit must be <= aem.max-limit, and every aem.allowed-path-prefixes entry must start with '/'")
    public boolean isConsistent() {
        if (defaultLimit > maxLimit) {
            return false;
        }
        if (allowedPathPrefixes == null || allowedPathPrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : allowedPathPrefixes) {
            if (prefix == null || !prefix.startsWith("/")) {
                return false;
            }
        }
        return true;
    }
}
```

### Task 4 — `src/main/java/com/adobe/mcp/config/AemClientConfig.java`

`RestClient` bean with basic auth + conservative timeouts.

```java
package com.adobe.mcp.config;

import com.adobe.mcp.aem.AemProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AemClientConfig {

    @Bean
    public RestClient aemRestClient(AemProperties props) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(15));
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(props.getUsername(), props.getPassword()))
                .build();
    }
}
```

> Note for the agent: `ClientHttpRequestFactoryBuilder` / `ClientHttpRequestFactorySettings` live
> in `org.springframework.boot.http.client` in Spring Boot 3.4.x (the older
> `org.springframework.boot.web.client.ClientHttpRequestFactories` API is deprecated). If you
> target a different Boot version where these are relocated, adjust the imports accordingly but
> keep the same behaviour (5s connect, 15s read, basic auth from properties).

### Task 5 — `src/main/java/com/adobe/mcp/aem/AemClient.java`

Thin read-only HTTP adapter + path allow-listing.

```java
package com.adobe.mcp.aem;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
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
        // Build the URI per segment so each segment is properly path-encoded, then append the
        // depth selector + .tidy.json extension. Segment validation in assertPathAllowed already
        // rejected '.' inside segments, which prevents callers from smuggling Sling selectors
        // like ".infinity" past this point.
        String[] segments = Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        String encodedPath = UriComponentsBuilder.fromPath("/")
                .pathSegment(segments)
                .build()
                .encode()
                .toUriString();
        String uri = encodedPath + "." + depth + ".tidy.json";
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

    /**
     * Validates {@code path} against the allow-list with prefix-boundary matching, and rejects
     * any path containing traversal sequences, empty segments, NUL bytes, control characters,
     * or '.' inside a segment (which would otherwise be interpreted as a Sling selector and
     * could bypass intent — e.g. {@code /allowed/page.infinity.json}).
     */
    public void assertPathAllowed(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path must be an absolute repository path starting with '/'.");
        }
        // Reject NUL and any other ISO control character outright.
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\0' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("Path contains illegal control characters.");
            }
        }
        // Reject empty segments (which represent '//') and traversal segments before we even
        // look at the allow-list — a single leading '/' is allowed by split() producing one
        // empty leading token, which we filter out below.
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i == 0) {
                // leading '/' produces an empty first segment
                if (!segment.isEmpty()) {
                    throw new IllegalArgumentException("Path must start with '/'.");
                }
                continue;
            }
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Path must not contain empty segments ('//').");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Path must not contain traversal segments ('.' or '..').");
            }
            if (segment.indexOf('.') >= 0) {
                // A '.' inside a segment is a Sling selector boundary, not part of the resource
                // path. Reject so callers cannot reach /content/page.infinity.json or similar.
                throw new IllegalArgumentException("Path segments must not contain '.' (Sling selectors are not permitted).");
            }
        }
        // Boundary-match against the allow-list: '/content/public' must NOT match
        // '/content/public-internal'. Allow an exact match or a strict child path.
        boolean allowed = props.getAllowedPathPrefixes().stream()
                .anyMatch(p -> path.equals(p) || path.startsWith(p.endsWith("/") ? p : p + "/"));
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Path '" + path + "' is outside the allowed prefixes " + props.getAllowedPathPrefixes());
        }
    }
}
```

### Task 6 — `src/main/java/com/adobe/mcp/audit/AuditLogger.java`

```java
package com.adobe.mcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IDENTITY CAVEAT: a remote MCP server reached by multiple developers attributes every downstream
 * AEM call to the single read-only service account, not the individual developer. The 'caller'
 * field is only as good as what the transport forwards. For true per-developer attribution,
 * propagate caller identity from the MCP client into this logger before claiming individual
 * accountability to auditors. See §4 "Future hardening (Phase 2)".
 *
 * Output format is structured JSON so SIEM ingestion does not need a parser. Each call emits a
 * single line containing the standard fields plus an MDC entry per parameter (`param.<name>`).
 */
@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AEM_MCP_AUDIT");
    private static final String MSG = "aem.mcp.tool.invoked";

    public void record(String tool, String caller, Map<String, ?> params) {
        MDC.put("tool", tool);
        MDC.put("caller", caller == null ? "service-account" : caller);
        try {
            if (params != null) {
                params.forEach((k, v) -> MDC.put("param." + k, v == null ? "" : v.toString()));
            }
            AUDIT.info(MSG);
        } finally {
            MDC.remove("tool");
            MDC.remove("caller");
            if (params != null) {
                params.keySet().forEach(k -> MDC.remove("param." + k));
            }
        }
    }
}
```

> Note for the agent: the JSON encoding is enabled in `application.yml` via Spring Boot 3.4's
> built-in structured logging (`logging.structured.format.console=ecs`). No `logback-spring.xml`
> is needed.

### Task 7 — `src/main/java/com/adobe/mcp/tools/AemReadOnlyTools.java`

The three `@Tool` methods. The `@Tool` description text is what the model reads to decide when to
call each tool — keep it accurate. All methods are read-only and audit every call.

```java
package com.adobe.mcp.tools;

import com.adobe.mcp.aem.AemClient;
import com.adobe.mcp.aem.AemProperties;
import com.adobe.mcp.audit.AuditLogger;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

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
            Search AEM content using QueryBuilder. You MUST narrow the search with at least one
            of: 'type' (JCR node type), 'fulltext' (full-text term), or 'property' (named
            property match). A path-only search is rejected — it would walk the entire allowed
            subtree and flood context. Returns a compact list of matching repository paths with
            a few key properties. Read-only.""")
    public String searchContent(
            @ToolParam(description = "Repository path to search under, e.g. /content/yoursite. Must be within the allowed trees.")
            String path,
            @ToolParam(description = "JCR node type to match, e.g. cq:Page, dam:Asset, nt:unstructured. Required unless 'fulltext' or 'property' is set.", required = false)
            String type,
            @ToolParam(description = "Full-text search term. Required unless 'type' or 'property' is set.", required = false)
            String fulltext,
            @ToolParam(description = "A single property name to match, e.g. sling:resourceType or cq:template. Required unless 'type' or 'fulltext' is set.", required = false)
            String property,
            @ToolParam(description = "Value the named property must equal. Required only if 'property' is set.", required = false)
            String propertyValue,
            @ToolParam(description = "Max number of hits to return. Capped by the server.", required = false)
            Integer limit) {

        try {
            aem.assertPathAllowed(path);
            if (!StringUtils.hasText(type) && !StringUtils.hasText(fulltext) && !StringUtils.hasText(property)) {
                return errorJson("missing_predicate", null,
                        "Provide at least one of 'type', 'fulltext', or 'property' to narrow the search.");
            }
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
        } catch (IllegalArgumentException e) {
            return errorJson("invalid_argument", null, e.getMessage());
        } catch (HttpStatusCodeException e) {
            return mapHttpError(e);
        } catch (ResourceAccessException e) {
            return errorJson("aem_unreachable", null,
                    "Could not reach the AEM author instance. Check network reachability and AEM_BASE_URL.");
        } catch (RestClientException e) {
            return errorJson("aem_call_failed", null, e.getMessage());
        }
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

        try {
            aem.assertPathAllowed(path);
            int effectiveDepth = clampDepth(depth);

            Map<String, Object> auditParams = new LinkedHashMap<>();
            auditParams.put("path", path);
            auditParams.put("depth", effectiveDepth);
            audit.record("inspectNode", null, auditParams);

            JsonNode node = aem.getNode(path, effectiveDepth);
            return node == null ? "{}" : node.toString();
        } catch (IllegalArgumentException e) {
            return errorJson("invalid_argument", null, e.getMessage());
        } catch (HttpStatusCodeException e) {
            return mapHttpError(e);
        } catch (ResourceAccessException e) {
            return errorJson("aem_unreachable", null,
                    "Could not reach the AEM author instance. Check network reachability and AEM_BASE_URL.");
        } catch (RestClientException e) {
            return errorJson("aem_call_failed", null, e.getMessage());
        }
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
        try {
            audit.record("bundleHealth", null, Map.of());
            JsonNode status = aem.bundlesStatus();
            return status == null ? "{}" : status.toString();
        } catch (HttpStatusCodeException e) {
            return mapHttpError(e);
        } catch (ResourceAccessException e) {
            return errorJson("aem_unreachable", null,
                    "Could not reach the AEM author instance. Check network reachability and AEM_BASE_URL.");
        } catch (RestClientException e) {
            return errorJson("aem_call_failed", null, e.getMessage());
        }
    }

    private String mapHttpError(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        String hint = switch (status) {
            case 401, 403 -> "AEM rejected the service-account credentials or the principal lacks read access to this path. Have the platform team grant read on the allow-listed trees.";
            case 404 -> "Path not found in AEM. Confirm the node exists and is under an allow-listed prefix.";
            case 408, 504 -> "AEM did not respond in time. Retry, or check author-instance load.";
            case 500, 502, 503 -> "AEM returned a server error. Check the author instance and try again.";
            default -> "AEM returned HTTP " + status + ".";
        };
        return errorJson("aem_http_error", status, hint);
    }

    private String errorJson(String code, Integer status, String hint) {
        StringBuilder sb = new StringBuilder("{\"error\":\"");
        sb.append(jsonEscape(code)).append("\",\"status\":");
        sb.append(status == null ? "null" : status.toString());
        sb.append(",\"hint\":\"").append(jsonEscape(hint == null ? "" : hint)).append("\"}");
        return sb.toString();
    }

    private String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
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

### Task 8 — `src/main/java/com/adobe/mcp/config/ToolsConfig.java`

```java
package com.adobe.mcp.config;

import com.adobe.mcp.tools.AemReadOnlyTools;
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

### Task 8b — `src/main/java/com/adobe/mcp/config/BearerTokenFilter.java`

Transport-level auth: a single shared bearer token guards `/sse`. The token comes from the
`aem-mcp.token` property (sourced from the mounted secrets file or the `AEM_MCP_TOKEN` env
var). Actuator probes and the root path are allow-listed so container liveness/readiness
probes (Compose's TCP healthcheck, or an external HTTP poller) are not blocked. Per-developer
identity (OIDC or mTLS) is Phase 2 — see §4 "Future hardening".

```java
package com.adobe.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Configuration
public class BearerTokenFilter {

    /** Paths that may be reached without a bearer token (container/external probes, root). */
    private static final Set<String> UNAUTHENTICATED_PATHS = Set.of(
            "/", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info");

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> bearerTokenFilter(
            @Value("${aem-mcp.token:}") String expectedToken) {

        if (!StringUtils.hasText(expectedToken)) {
            throw new IllegalStateException(
                    "aem-mcp.token is empty. Refusing to start without a bearer token. "
                            + "Set it via secrets/aem-mcp-secrets.properties (Docker Compose) "
                            + "or the AEM_MCP_TOKEN env var (local dev).");
        }

        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String path = req.getRequestURI();
                if (UNAUTHENTICATED_PATHS.contains(path)) {
                    chain.doFilter(req, res);
                    return;
                }
                String header = req.getHeader(HttpHeaders.AUTHORIZATION);
                if (header == null || !header.startsWith("Bearer ")) {
                    unauthorized(res, "Missing bearer token");
                    return;
                }
                String presented = header.substring("Bearer ".length()).trim();
                if (!constantTimeEquals(presented, expectedToken)) {
                    unauthorized(res, "Invalid bearer token");
                    return;
                }
                chain.doFilter(req, res);
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(Integer.MIN_VALUE);
        return reg;
    }

    private static void unauthorized(HttpServletResponse res, String reason) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        res.getOutputStream().write(("{\"error\":\"unauthorized\",\"hint\":\"" + reason + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
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
  # Optional file-mounted secrets. When running under Docker Compose this file is mounted from
  # the host (see compose.yaml). Values defined here override the env-var placeholders below.
  # Missing file is fine — `optional:` prefix makes the import a no-op, and the env-var
  # placeholders take over for plain `java -jar` / `mvn spring-boot:run` workflows.
  config:
    import: optional:file:/run/secrets/aem-mcp-secrets.properties
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
  username: ${AEM_USERNAME:}
  password: ${AEM_PASSWORD:}
  allowed-path-prefixes:
    - /content/yoursite
    - /content/dam/yoursite
  default-limit: 20
  max-limit: 100
  max-depth: 3
  bundle-health-enabled: false

# Shared bearer token guarding /sse. MUST resolve to a non-empty value via the secrets file
# import above or the AEM_MCP_TOKEN env var; BearerTokenFilter refuses to start otherwise.
aem-mcp:
  token: ${AEM_MCP_TOKEN:}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never

logging:
  structured:
    format:
      console: ecs   # Spring Boot 3.4 built-in JSON encoder; one line per log event, no logback-spring.xml needed.
  level:
    AEM_MCP_AUDIT: INFO
```

### Task 10 — `.mcp.json.example`

Committed registration form for clients. Holds only the URL and references the bearer token via
an env-var reference (`${AEM_MCP_TOKEN}`) — never the literal token. Claude Code expands the
reference at load time.

```json
{
  "mcpServers": {
    "aem-readonly": {
      "type": "sse",
      "url": "https://aem-mcp.internal.example.com/sse",
      "headers": {
        "Authorization": "Bearer ${AEM_MCP_TOKEN}"
      }
    }
  }
}
```

### Task 11 — `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1

# Base images are Red Hat UBI 9 (Universal Base Image) — RHEL-derived, freely redistributable
# without a Red Hat subscription, and reachable on registry.access.redhat.com from most
# corporate networks where Docker Hub is blocked. Pinned to a specific tag for reproducibility;
# let Dependabot or Renovate manage the bumps.
#
# ---- Build stage ----
# ubi9/openjdk-17 ships JDK 17 + Maven, so no separate `maven:*` image is needed.
FROM registry.access.redhat.com/ubi9/openjdk-17:1.20 AS build
# UBI runs as non-root by default; switch to root for the build so /build is writable. The
# runtime stage gets its non-root user back from the runtime base image, so this only affects
# the discarded build layer.
USER 0
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
# ubi9/openjdk-17-runtime is a slim JRE-only variant. It already runs as non-root uid 185 with
# gid 0 (OpenShift convention — non-zero uid, root group so files are writable by any process
# in the root group). No USER / groupadd / useradd needed.
FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:1.20
COPY --from=build --chown=185:0 /build/target/aem-readonly-mcp-1.0.0.jar /deployments/app.jar
EXPOSE 8080
# Force the JVM to use the writable tmpfs mounted at /tmp by Compose (see compose.yaml),
# since the root filesystem is read-only in production.
ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp"
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
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

### Task 13 — `compose.yaml` + `secrets/aem-mcp-secrets.properties.example`

Single-host containerized deploy. The Compose stack carries forward every security primitive
that the previous Kubernetes manifest enforced (non-root, read-only filesystem, dropped
capabilities, writable `/tmp`, lifecycle restart) and replaces `secretKeyRef` env injection
with file-mounted Compose secrets — the values never reach `docker inspect` or the process
list. Spring Boot picks them up via the `spring.config.import` declared in Task 9's
`application.yml`.

```yaml
# compose.yaml
services:
  aem-readonly-mcp:
    build: .
    image: aem-readonly-mcp:1.0.0
    # Build locally; never try to pull `image:` from a registry.
    pull_policy: build
    container_name: aem-readonly-mcp
    restart: unless-stopped
    ports:
      # Loopback-only by default — this is a developer tool, not a public service.
      - "127.0.0.1:8080:8080"
    environment:
      # Non-secret config. Override per host as needed.
      AEM_BASE_URL: "https://author.internal.example.com:4502"
    secrets:
      - source: aem-mcp-secrets
        # Mounted at /run/secrets/aem-mcp-secrets.properties inside the container.
        target: aem-mcp-secrets.properties
    read_only: true
    tmpfs:
      - /tmp:rw,size=64m
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    healthcheck:
      # TCP probe — the runtime image doesn't ship curl/wget. Actuator HTTP probes are still
      # reachable from the host for richer status.
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/8080 && echo ok || exit 1"]
      interval: 15s
      timeout: 3s
      retries: 3
      start_period: 30s

secrets:
  aem-mcp-secrets:
    file: ./secrets/aem-mcp-secrets.properties
```

```properties
# secrets/aem-mcp-secrets.properties.example
# Copy to aem-mcp-secrets.properties, fill in real values, `chmod 600`. The real file is
# gitignored (see .gitignore: secrets/*.properties).
# Generate a strong bearer token with:  openssl rand -hex 32
aem.username=svc-aem-readonly
aem.password=REPLACE_ME
aem-mcp.token=REPLACE_ME
```

Also add an empty `secrets/.gitkeep` so the directory survives a fresh clone, and append
`secrets/*.properties` + `.env` to `.gitignore`, and `secrets/` + `compose.yaml` to
`.dockerignore`.

### Task 14 — Per-tool AEM connectivity probes (observability)

Adds one Spring Boot health indicator per MCP tool, plus an aggregate group and a startup
listener. Each indicator exercises the same AEM endpoint the corresponding tool would, so
operators can pinpoint which tool's dependency is broken without invoking MCP.

#### `src/main/java/com/adobe/mcp/health/AemErrorCategories.java`

```java
package com.adobe.mcp.health;

/** Shared HTTP-status → structured-category vocabulary for tool errors and health probes. */
public final class AemErrorCategories {
    private AemErrorCategories() {}

    public static String categoryForStatus(int httpStatus) {
        return switch (httpStatus) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 408, 504 -> "timeout";
            case 500, 502, 503 -> "aem_server_error";
            default -> "aem_http_error";
        };
    }

    public static String hintForStatus(int httpStatus) {
        return switch (httpStatus) {
            case 401, 403 -> "AEM rejected the service-account credentials or the principal lacks read access to this path. Have the platform team grant read on the allow-listed trees.";
            case 404 -> "Path not found in AEM. Confirm the node exists and is under an allow-listed prefix.";
            case 408, 504 -> "AEM did not respond in time. Retry, or check author-instance load.";
            case 500, 502, 503 -> "AEM returned a server error. Check the author instance and try again.";
            default -> "AEM returned HTTP " + httpStatus + ".";
        };
    }
}
```

Also update `AemReadOnlyTools.mapHttpError` to delegate to `AemErrorCategories.hintForStatus`,
so tool-error hints and probe categories never drift.

#### `src/main/java/com/adobe/mcp/health/AemToolHealthIndicator.java`

Generic base used by all three per-tool beans. Returning `null` from the probe lambda is a
sentinel for "tool disabled by configuration" → indicator reports `UNKNOWN` with
`category=disabled_by_config` and makes no HTTP call. Maps `HttpStatusCodeException` via
`AemErrorCategories.categoryForStatus`; maps `ResourceAccessException` causes
(`SocketTimeoutException` → `timeout`, `UnknownHostException`/`ConnectException` →
`unreachable`). Details emitted: `httpStatus`, `latencyMs`, `category`, `probePath`. Never
emits `baseUrl`, `username`, or any secret value.

#### `src/main/java/com/adobe/mcp/health/AemHealthIndicatorsConfig.java`

Three `@Bean` declarations wiring `AemToolHealthIndicator` to tool-specific probe lambdas.
Bean names are `searchContent`, `inspectNode`, `bundleHealth` — Spring uses these verbatim as
the health-component keys.

- `searchContent` → `GET /bin/querybuilder.json?type=cq:Page&p.limit=0`
- `inspectNode` → `GET ${aem.health.inspect-node-path or aem.allowed-path-prefixes[0] + ".0.json"}`
- `bundleHealth` → returns null sentinel when `aem.bundle-health-enabled=false`, otherwise
  `GET /system/console/bundles.json`

#### `src/main/java/com/adobe/mcp/health/AemStartupProbe.java`

Implements `ApplicationListener<ApplicationReadyEvent>`. Injects
`Map<String, HealthIndicator>` and `AuditLogger`. On the ready event: invokes each of the
three indicators sequentially, emits one audit-log line per tool with
`tool=aem_connectivity_probe phase=startup`, then a single INFO/WARN human-readable summary
line. Never throws — startup proceeds regardless.

#### `src/main/java/com/adobe/mcp/aem/AemProperties.java` — additions

Add a nested `Health` properties class with a single `inspectNodePath` field
(`@Pattern("^$|^/.*$")` — empty allowed, meaning "use default"). The consumer in
`AemHealthIndicatorsConfig` treats blank as null and substitutes the first allowed prefix +
`".0.json"`.

#### `src/main/java/com/adobe/mcp/config/BearerTokenFilter.java` — additions

Add four paths to `UNAUTHENTICATED_PATHS`:
```
/actuator/health/aem
/actuator/health/aem-search
/actuator/health/aem-inspect
/actuator/health/aem-bundle
```
Also restrict the filter to `DispatcherType.REQUEST` only via
`reg.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST))` so Spring's internal `/error`
forward doesn't re-enter the filter and 401 a path that was allowed through on first pass.

#### `src/main/resources/application.yml` — additions

```yaml
aem:
  # ... existing keys ...
  health:
    # Defaults to first entry of allowed-path-prefixes + ".0.json" when unset.
    inspect-node-path:

management:
  endpoint:
    health:
      # ... existing show-details, probes ...
      # Per-tool AEM connectivity probes plus an aggregate. Each tool gets its own group so it
      # has a dedicated /actuator/health/<group> URL (component-only paths 404 while the global
      # show-details=never is in force).
      group:
        aem:
          include:
            - searchContent
            - inspectNode
            - bundleHealth
          show-details: always
        aem-search:
          include:
            - searchContent
          show-details: always
        aem-inspect:
          include:
            - inspectNode
          show-details: always
        aem-bundle:
          include:
            - bundleHealth
          show-details: always
```

Group names use the `aem-*` prefix to avoid collision with the indicator bean names
(`searchContent`/`inspectNode`/`bundleHealth`). Each per-tool group has `show-details: always`
because the details (status, latency, category) contain no secrets and no topology hints.

#### Tests — `src/test/java/com/adobe/mcp/health/AemToolHealthIndicatorTest.java`

JUnit 5 + Spring `MockRestServiceServer`. ~10 cases covering 200/401/403/404/502,
`ConnectException`, `UnknownHostException`, `SocketTimeoutException`, the null-sentinel
disabled path, and a `doesNotContain("baseUrl","username")` assertion on the details map.

-----

## 8. Build & Run — Phase 0 (local proof-of-concept)

```bash
export AEM_BASE_URL="https://author.internal.example.com:4502"
export AEM_USERNAME="svc-aem-readonly"
export AEM_PASSWORD="********"
# Shared bearer token guarding /sse. Generate a strong random value (e.g. `openssl rand -hex 32`)
# and rotate it through your secrets manager. The server refuses to start without it.
export AEM_MCP_TOKEN="$(openssl rand -hex 32)"

mvn clean package
java -jar target/aem-readonly-mcp-1.0.0.jar
```

Server starts on port 8080; SSE endpoint is `/sse`. Actuator probes (`/actuator/health/liveness`,
`/actuator/health/readiness`) are unauthenticated; every other path requires the bearer token.

## 9. Acceptance Criteria (the agent must verify all)

1. `mvn clean package` succeeds with no compilation errors.
1. The application starts and logs the three tools as registered.
1. Startup fails fast with a clear message if neither the secrets file nor `AEM_MCP_TOKEN`
   provides a value for `aem-mcp.token` (BearerTokenFilter).
1. With valid AEM credentials, the bearer token, and a reachable author instance:
- `searchContent` under an allowed path with at least one of `type`/`fulltext`/`property`
  returns QueryBuilder hits; calling it without any of those returns the `missing_predicate`
  structured error.
- `inspectNode` on an allowed path returns node JSON; depth above `max-depth` is clamped.
- A path outside `allowed-path-prefixes` is rejected before any AEM call.
- A path containing `..`, `//`, a control character, or a segment with `.` (e.g.
  `/content/yoursite/home.infinity.json`) is rejected by `assertPathAllowed` before any AEM call.
- A boundary-bypass attempt (e.g. allow-list `/content/public`, request
  `/content/public-internal/...`) is rejected.
- `bundleHealth` returns the `disabled` message while `bundle-health-enabled` is false.
- AEM errors (401/403/404/timeout/unreachable) surface as `{error,status,hint}` JSON, not
  uncaught exceptions.
1. Calling `/sse` without `Authorization: Bearer <token>` returns 401 with the structured error
   body and `WWW-Authenticate: Bearer` header; the actuator probe endpoints remain reachable
   without the token.
1. `/actuator/health/liveness` and `/actuator/health/readiness` return 200 on a healthy instance.
1. The audit log emits one JSON line per tool call containing `tool`, `caller`, and `param.*`
   fields (verifiable with `grep aem.mcp.tool.invoked`).
1. No credentials appear in source, `application.yml`, `.mcp.json.example`, or the image. The
   real `secrets/aem-mcp-secrets.properties` is gitignored; only the `.example` template is
   committed.
1. `docker compose up -d` builds and starts the container as non-root, with a read-only root
   filesystem and a writable tmpfs at `/tmp`; the secrets file is mounted read-only at
   `/run/secrets/aem-mcp-secrets.properties` and Spring Boot picks up `aem.username`,
   `aem.password`, and `aem-mcp.token` from it. `docker inspect aem-readonly-mcp` shows no
   secret values in `.Config.Env` (only the non-secret `AEM_BASE_URL`).
1. On startup the server emits one WARN/INFO line of the form
   `AEM connectivity check: searchContent=...(...) inspectNode=...(...) bundleHealth=...(...)`,
   plus three audit-log lines `tool=aem_connectivity_probe phase=startup` (one per MCP tool).
   The four endpoints `/actuator/health/aem`, `/actuator/health/aem-search`,
   `/actuator/health/aem-inspect`, `/actuator/health/aem-bundle` respond without bearer auth.
   With AEM unreachable they return 503; with `aem.bundle-health-enabled=false` the bundle
   group reports `UNKNOWN` with `category=disabled_by_config` and makes no outbound call.
   None of the per-tool probes contribute to `/actuator/health/readiness`, which stays 200.

## 10. Containerize & Deploy with Docker Compose

```bash
# 1. Create the secrets file from the template and chmod it.
cp secrets/aem-mcp-secrets.properties.example secrets/aem-mcp-secrets.properties
chmod 600 secrets/aem-mcp-secrets.properties

# 2. Edit the file: set aem.username, aem.password, and aem-mcp.token
#    (`openssl rand -hex 32` for the token).
$EDITOR secrets/aem-mcp-secrets.properties

# 3. (Optional) point at your AEM author. Default is a placeholder.
#    Edit AEM_BASE_URL under `environment:` in compose.yaml.

# 4. Build and start.
docker compose up -d
docker compose logs -f aem-readonly-mcp
```

The same Dockerfile underlies both the local `mvn package` workflow (Phase 0) and this
Compose-based deploy — only the secret-injection path differs (env vars vs. mounted file).

For multi-host or HA hosting you would re-introduce a Kubernetes manifest, an
External-Secrets-Operator-backed `Secret`, and an ingress; that's deliberately out of scope
for this build (see §13 "Reuse as a template" for the divergence points).

## 11. Register with Claude Code

```bash
# Phase 0 (local)
claude mcp add --transport sse --scope local aem-readonly http://localhost:8080/sse \
  --header "Authorization: Bearer ${AEM_MCP_TOKEN}"

# Phase 1 (central, shared via git .mcp.json)
claude mcp add --transport sse --scope project aem-readonly https://aem-mcp.internal.example.com/sse \
  --header "Authorization: Bearer \${AEM_MCP_TOKEN}"
```

> Note on transport spelling: the CLI flag is `--transport sse`, but inside a committed
> `.mcp.json` (see Task 10) the equivalent field is `"type": "sse"`. Both refer to the same
> setting; the JSON field name is what Claude Code parses on disk. Use whichever matches the
> documentation for your Claude Code version, but the example file uses `"type"`.

Verify with `/mcp` inside Claude Code; the `aem-readonly` server should list three tools. A
mis-set or missing token surfaces as a `{"error":"unauthorized",...}` body and `WWW-Authenticate:
Bearer` header from the server.

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
1. Keep all seven Security Constraints intact.
1. Record the definition-of-done in the marketplace `DECISION_GUIDE.md`.

## 14. Compliance gates to clear before production (verify, do not assume)

- Permission to send internal content to the chosen LLM (data egress policy / DLP).
- Data classification of the allow-listed trees (no unapproved PII/PHI/regulated content).
- Third-party/vendor risk sign-off for the LLM provider (DPA, residency, retention).
- Per-developer audit attribution if your regime requires it (see Task 7 caveat).
- Change-management / architecture review for a new internet-egressing prod service.
- OSS license + dependency-scanning gate on Spring AI and transitive deps.