package com.adobe.mcp.aem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AemClientPathAllowListTest {

    private static AemClient newClient(List<String> prefixes) {
        return newClient(prefixes, "");
    }

    private static AemClient newClient(List<String> prefixes, String contextRoot) {
        AemProperties props = new AemProperties();
        props.setBaseUrl("http://localhost");
        props.setUsername("u");
        props.setPassword("p");
        props.setAllowedPathPrefixes(prefixes);
        props.setContextRoot(contextRoot);
        return new AemClient(null, props);
    }

    private static AemProperties props(List<String> prefixes, String contextRoot) {
        AemProperties p = new AemProperties();
        p.setBaseUrl("http://localhost");
        p.setUsername("u");
        p.setPassword("p");
        p.setAllowedPathPrefixes(prefixes);
        p.setContextRoot(contextRoot);
        return p;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/content/public",
            "/content/public/home",
            "/content/public/nested/leaf",
            "/content/dam/yoursite/file"
    })
    void acceptsPathsUnderAllowedPrefix(String path) {
        AemClient client = newClient(List.of("/content/public", "/content/dam/yoursite"));
        assertDoesNotThrow(() -> client.assertPathAllowed(path));
    }

    @Test
    void rejectsBoundaryBypass() {
        AemClient client = newClient(List.of("/content/public"));
        assertThrows(IllegalArgumentException.class,
                () -> client.assertPathAllowed("/content/public-internal/secret"));
        assertThrows(IllegalArgumentException.class,
                () -> client.assertPathAllowed("/content/publicfoo"));
    }

    @Test
    void rejectsOutsideAllowList() {
        AemClient client = newClient(List.of("/content/public"));
        assertThrows(IllegalArgumentException.class,
                () -> client.assertPathAllowed("/content/private/secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/content/public/../private",
            "/content/public//home",
            "/content/public/./home",
            "/content/public/home.infinity.json",
            "/content/public/page.html",
            "/content/public/page.tidy.json"
    })
    void rejectsTraversalAndSlingSelectors(String path) {
        AemClient client = newClient(List.of("/content/public"));
        assertThrows(IllegalArgumentException.class, () -> client.assertPathAllowed(path));
    }

    @Test
    void rejectsControlCharacters() {
        AemClient client = newClient(List.of("/content/public"));
        for (char c : new char[] { '\n', '\t', '\r', '\0', 0x01, 0x1f }) {
            String path = "/content/public/foo" + c + "bar";
            assertThrows(IllegalArgumentException.class,
                    () -> client.assertPathAllowed(path),
                    "Should reject control char 0x" + Integer.toHexString(c));
        }
    }

    @Test
    void rejectsTrailingSlash() {
        AemClient client = newClient(List.of("/content/public"));
        assertThrows(IllegalArgumentException.class,
                () -> client.assertPathAllowed("/content/public/"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "relative/path", "//absolute-looking" })
    void rejectsNonAbsoluteOrMalformedPaths(String path) {
        AemClient client = newClient(List.of("/content/public"));
        assertThrows(IllegalArgumentException.class, () -> client.assertPathAllowed(path));
    }

    @Test
    void rejectsNullPath() {
        AemClient client = newClient(List.of("/content/public"));
        assertThrows(IllegalArgumentException.class, () -> client.assertPathAllowed(null));
    }

    @Test
    void contextRoot_jcr_paths_unchanged_in_allow_list() {
        // The allow-list operates on JCR paths regardless of HTTP context root.
        AemClient client = newClient(List.of("/content/portal"), "/WC2");
        assertDoesNotThrow(() -> client.assertPathAllowed("/content/portal"));
        assertDoesNotThrow(() -> client.assertPathAllowed("/content/portal/home"));
        // A request containing the context root verbatim is NOT a JCR path and must be rejected.
        assertThrows(IllegalArgumentException.class,
                () -> client.assertPathAllowed("/WC2/content/portal"));
    }

    @Test
    void context_root_empty_by_default() {
        AemProperties p = new AemProperties();
        assertEquals("", p.getContextRoot());
    }

    @Test
    void context_root_setter_normalises_null_to_empty() {
        AemProperties p = new AemProperties();
        p.setContextRoot(null);
        assertEquals("", p.getContextRoot());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "/WC2", "/wc2", "/a/b", "/portal" })
    void context_root_consistent_check_accepts(String value) {
        AemProperties p = props(List.of("/content/portal"), value);
        assertTrue(p.isConsistent(), "Should accept context-root: '" + value + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = { "WC2", "/WC2/", "//WC2", "/WC2//inner", "/" })
    void context_root_consistent_check_rejects(String value) {
        AemProperties p = props(List.of("/content/portal"), value);
        assertFalse(p.isConsistent(), "Should reject context-root: '" + value + "'");
    }
}
