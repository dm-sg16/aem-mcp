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
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(props.getContextRoot() + "/bin/querybuilder.json");
        predicates.forEach(uri::queryParam);
        return aem.get()
                .uri(uri.build().toUriString())
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode getNode(String path, int depth) {
        assertPathAllowed(path);
        String[] segments = Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        String encodedPath = UriComponentsBuilder.fromPath("/")
                .pathSegment(segments)
                .build()
                .encode()
                .toUriString();
        String uri = props.getContextRoot() + encodedPath + "." + depth + ".tidy.json";
        return aem.get()
                .uri(uri)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode bundlesStatus() {
        return aem.get()
                .uri(props.getContextRoot() + "/system/console/bundles.json")
                .retrieve()
                .body(JsonNode.class);
    }

    public void assertPathAllowed(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path must be an absolute repository path starting with '/'.");
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\0' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("Path contains illegal control characters.");
            }
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i == 0) {
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
                throw new IllegalArgumentException("Path segments must not contain '.' (Sling selectors are not permitted).");
            }
        }
        boolean allowed = props.getAllowedPathPrefixes().stream()
                .anyMatch(p -> path.equals(p) || path.startsWith(p.endsWith("/") ? p : p + "/"));
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Path '" + path + "' is outside the allowed prefixes " + props.getAllowedPathPrefixes());
        }
    }
}
