package com.adobe.mcp.aem;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AemClientHttpTest {

    private record Fixture(AemClient client, MockRestServiceServer server) {}

    private static Fixture fixture(String contextRoot) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://aem.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AemProperties props = new AemProperties();
        props.setBaseUrl("http://aem.test");
        props.setUsername("u");
        props.setPassword("p");
        props.setAllowedPathPrefixes(List.of("/content/public"));
        props.setContextRoot(contextRoot);
        return new Fixture(new AemClient(builder.build(), props), server);
    }

    @Test
    void queryBuilder_buildsContextRootedUriAndParsesBody() {
        Fixture f = fixture("/WC2");
        f.server().expect(requestTo(startsWith("http://aem.test/WC2/bin/querybuilder.json")))
                .andRespond(withSuccess("{\"hits\":[]}", MediaType.APPLICATION_JSON));

        Map<String, String> predicates = new LinkedHashMap<>();
        predicates.put("type", "cq:Page");
        predicates.put("p.limit", "10");

        JsonNode result = f.client().queryBuilder(predicates);

        assertThat(result.has("hits")).isTrue();
        f.server().verify();
    }

    @Test
    void getNode_buildsSegmentedPathWithDepthSelector() {
        Fixture f = fixture("/WC2");
        f.server().expect(requestTo("http://aem.test/WC2/content/public/en/home.1.tidy.json"))
                .andRespond(withSuccess("{\"jcr:primaryType\":\"cq:Page\"}", MediaType.APPLICATION_JSON));

        JsonNode node = f.client().getNode("/content/public/en/home", 1);

        assertThat(node.get("jcr:primaryType").asText()).isEqualTo("cq:Page");
        f.server().verify();
    }

    @Test
    void getNode_withEmptyContextRoot() {
        Fixture f = fixture("");
        f.server().expect(requestTo("http://aem.test/content/public/home.0.tidy.json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(f.client().getNode("/content/public/home", 0)).isNotNull();
        f.server().verify();
    }

    @Test
    void getNode_rejectsDisallowedPathBeforeCall() {
        Fixture f = fixture("");

        assertThatThrownBy(() -> f.client().getNode("/content/private/x", 1))
                .isInstanceOf(IllegalArgumentException.class);
        f.server().verify(); // no HTTP call expected or made
    }

    @Test
    void bundlesStatus_hitsConsoleEndpoint() {
        Fixture f = fixture("/WC2");
        f.server().expect(requestTo("http://aem.test/WC2/system/console/bundles.json"))
                .andRespond(withSuccess("{\"s\":[]}", MediaType.APPLICATION_JSON));

        assertThat(f.client().bundlesStatus().has("s")).isTrue();
        f.server().verify();
    }

    @Test
    void assertPathAllowed_acceptsPrefixWithTrailingSlash() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://aem.test");
        AemProperties props = new AemProperties();
        props.setAllowedPathPrefixes(List.of("/content/public/"));
        AemClient client = new AemClient(builder.build(), props);

        client.assertPathAllowed("/content/public/home"); // does not throw
    }
}
