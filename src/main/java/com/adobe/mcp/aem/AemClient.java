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
        // Append the Sling selector/extension to the final segment, then let the RestClient's
        // UriBuilder percent-encode each segment exactly once. Pre-encoding into a String and
        // passing it to uri(String) double-encodes, because uri(String) treats its argument as a
        // URI template and encodes it again (a space becomes %2520 instead of %20). Riding the
        // suffix on the last segment (rather than a separate path() call) keeps its dots literal
        // and avoids a stray '/' between the node and its ".<depth>.tidy.json" selector.
        segments[segments.length - 1] = segments[segments.length - 1] + "." + depth + ".tidy.json";
        return aem.get()
                .uri(uriBuilder -> uriBuilder
                        .path(props.getContextRoot())
                        .pathSegment(segments)
                        .build())
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
                // segments[0] is always the empty leading segment here: the guard above rejects
                // any path that doesn't start with '/', so split("/", -1)[0] is always "".
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
