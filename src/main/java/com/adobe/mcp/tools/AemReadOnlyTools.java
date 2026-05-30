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
