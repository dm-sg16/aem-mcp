package com.adobe.mcp.tools;

import com.adobe.mcp.aem.AemClient;
import com.adobe.mcp.aem.AemProperties;
import com.adobe.mcp.audit.AuditLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AemReadOnlyToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AemClient aem;
    private AuditLogger audit;
    private AemProperties props;
    private AemReadOnlyTools tools;

    @BeforeEach
    void setUp() {
        aem = mock(AemClient.class);
        audit = mock(AuditLogger.class);
        props = new AemProperties();
        props.setBaseUrl("http://aem.test");
        props.setUsername("u");
        props.setPassword("p");
        props.setAllowedPathPrefixes(List.of("/content/public"));
        props.setDefaultLimit(20);
        props.setMaxLimit(100);
        props.setMaxDepth(3);
        tools = new AemReadOnlyTools(aem, props, audit);
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- searchContent ---------------------------------------------------------------------

    @Test
    void searchContent_happyPath_allPredicates_and_limitWithinCap() {
        JsonNode node = json("{\"hits\":[{\"jcr:path\":\"/content/public/a\"}]}");
        when(aem.queryBuilder(anyMap())).thenReturn(node);

        String out = tools.searchContent("/content/public", "cq:Page", "term",
                "sling:resourceType", "foundation/page", 5);

        assertThat(out).isEqualTo(node.toString());
    }

    @Test
    void searchContent_propertyWithNullValue_and_nullLimitUsesDefault_and_nullResult() {
        when(aem.queryBuilder(anyMap())).thenReturn(null);

        String out = tools.searchContent("/content/public", null, null, "prop", null, null);

        assertThat(out).isEqualTo("{}");
    }

    @Test
    void searchContent_typeOnly_and_limitAboveMaxIsClamped() {
        when(aem.queryBuilder(anyMap())).thenReturn(json("{}"));

        String out = tools.searchContent("/content/public", "cq:Page", null, null, null, 100000);

        assertThat(out).isEqualTo("{}");
    }

    @Test
    void searchContent_limitBelowOneUsesDefault() {
        when(aem.queryBuilder(anyMap())).thenReturn(json("{}"));

        assertThat(tools.searchContent("/content/public", "cq:Page", null, null, null, 0))
                .isEqualTo("{}");
    }

    @Test
    void searchContent_fulltextOnly() {
        when(aem.queryBuilder(anyMap())).thenReturn(json("{}"));

        assertThat(tools.searchContent("/content/public", null, "term", null, null, null))
                .isEqualTo("{}");
    }

    @Test
    void searchContent_missingPredicate() {
        String out = tools.searchContent("/content/public", null, null, null, null, null);

        assertThat(out).contains("\"error\":\"missing_predicate\"");
    }

    @Test
    void searchContent_invalidArgument_fromAllowListWithEscapedMessage() {
        doThrow(new IllegalArgumentException("bad \"path\" with \\ and \n newline \t tab \r and  ctrl"))
                .when(aem).assertPathAllowed(anyString());

        String out = tools.searchContent("/content/secret", "cq:Page", null, null, null, null);

        assertThat(out).contains("\"error\":\"invalid_argument\"");
        assertThat(out).contains("\\\"path\\\"");   // escaped quotes
        assertThat(out).contains("\\\\");            // escaped backslash
        assertThat(out).contains("\\n");
        assertThat(out).contains("\\t");
        assertThat(out).contains("\\r");
        assertThat(out).contains("\\u0001");         // control char
    }

    @Test
    void searchContent_invalidArgument_withNullMessageYieldsEmptyHint() {
        doThrow(new IllegalArgumentException()).when(aem).assertPathAllowed(anyString());

        String out = tools.searchContent("/x", "cq:Page", null, null, null, null);

        assertThat(out).isEqualTo("{\"error\":\"invalid_argument\",\"status\":null,\"hint\":\"\"}");
    }

    @Test
    void searchContent_httpError_isMapped() {
        when(aem.queryBuilder(anyMap())).thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        String out = tools.searchContent("/content/public", "cq:Page", null, null, null, null);

        assertThat(out).contains("\"error\":\"aem_http_error\"");
        assertThat(out).contains("\"status\":403");
    }

    @Test
    void searchContent_unreachable() {
        when(aem.queryBuilder(anyMap())).thenThrow(new ResourceAccessException("io"));

        assertThat(tools.searchContent("/content/public", "cq:Page", null, null, null, null))
                .contains("\"error\":\"aem_unreachable\"");
    }

    @Test
    void searchContent_callFailed() {
        when(aem.queryBuilder(anyMap())).thenThrow(new RestClientException("boom"));

        assertThat(tools.searchContent("/content/public", "cq:Page", null, null, null, null))
                .contains("\"error\":\"aem_call_failed\"");
    }

    // ---- inspectNode -----------------------------------------------------------------------

    @Test
    void inspectNode_happyPath_depthWithinCap() {
        JsonNode node = json("{\"jcr:primaryType\":\"cq:Page\"}");
        when(aem.getNode(anyString(), anyInt())).thenReturn(node);

        assertThat(tools.inspectNode("/content/public/home", 2)).isEqualTo(node.toString());
    }

    @Test
    void inspectNode_nullDepthDefaultsToOne_and_nullResult() {
        when(aem.getNode(anyString(), anyInt())).thenReturn(null);

        assertThat(tools.inspectNode("/content/public/home", null)).isEqualTo("{}");
    }

    @Test
    void inspectNode_negativeDepthDefaultsToOne() {
        when(aem.getNode(anyString(), anyInt())).thenReturn(json("{}"));

        assertThat(tools.inspectNode("/content/public/home", -5)).isEqualTo("{}");
    }

    @Test
    void inspectNode_depthAboveMaxIsClamped() {
        when(aem.getNode(anyString(), anyInt())).thenReturn(json("{}"));

        assertThat(tools.inspectNode("/content/public/home", 99)).isEqualTo("{}");
    }

    @Test
    void inspectNode_invalidArgument() {
        doThrow(new IllegalArgumentException("nope")).when(aem).assertPathAllowed(anyString());

        assertThat(tools.inspectNode("/bad", 1)).contains("\"error\":\"invalid_argument\"");
    }

    @Test
    void inspectNode_httpError() {
        when(aem.getNode(anyString(), anyInt())).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertThat(tools.inspectNode("/content/public/home", 1)).contains("\"status\":404");
    }

    @Test
    void inspectNode_unreachable() {
        when(aem.getNode(anyString(), anyInt())).thenThrow(new ResourceAccessException("io"));

        assertThat(tools.inspectNode("/content/public/home", 1)).contains("\"error\":\"aem_unreachable\"");
    }

    @Test
    void inspectNode_callFailed() {
        when(aem.getNode(anyString(), anyInt())).thenThrow(new RestClientException("boom"));

        assertThat(tools.inspectNode("/content/public/home", 1)).contains("\"error\":\"aem_call_failed\"");
    }

    // ---- bundleHealth ----------------------------------------------------------------------

    @Test
    void bundleHealth_disabledByDefault() {
        assertThat(tools.bundleHealth()).contains("\"disabled\":true");
    }

    @Test
    void bundleHealth_enabled_happyPath() {
        props.setBundleHealthEnabled(true);
        JsonNode node = json("{\"status\":\"ok\"}");
        when(aem.bundlesStatus()).thenReturn(node);

        assertThat(tools.bundleHealth()).isEqualTo(node.toString());
    }

    @Test
    void bundleHealth_enabled_nullResult() {
        props.setBundleHealthEnabled(true);
        when(aem.bundlesStatus()).thenReturn(null);

        assertThat(tools.bundleHealth()).isEqualTo("{}");
    }

    @Test
    void bundleHealth_enabled_httpError() {
        props.setBundleHealthEnabled(true);
        when(aem.bundlesStatus()).thenThrow(new HttpClientErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(tools.bundleHealth()).contains("\"status\":500");
    }

    @Test
    void bundleHealth_enabled_unreachable() {
        props.setBundleHealthEnabled(true);
        when(aem.bundlesStatus()).thenThrow(new ResourceAccessException("io"));

        assertThat(tools.bundleHealth()).contains("\"error\":\"aem_unreachable\"");
    }

    @Test
    void bundleHealth_enabled_callFailed() {
        props.setBundleHealthEnabled(true);
        when(aem.bundlesStatus()).thenThrow(new RestClientException("boom"));

        assertThat(tools.bundleHealth()).contains("\"error\":\"aem_call_failed\"");
    }
}
