package com.adobe.mcp.aem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AemClientPathAllowListTest {

    private static AemClient newClient(List<String> prefixes) {
        AemProperties props = new AemProperties();
        props.setBaseUrl("http://localhost");
        props.setUsername("u");
        props.setPassword("p");
        props.setAllowedPathPrefixes(prefixes);
        return new AemClient(null, props);
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
}
