package com.adobe.mcp.config;

import com.adobe.mcp.aem.AemClient;
import com.adobe.mcp.aem.AemProperties;
import com.adobe.mcp.audit.AuditLogger;
import com.adobe.mcp.health.AemHealthIndicatorsConfig;
import com.adobe.mcp.tools.AemReadOnlyTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConfigBeansTest {

    private record Fixture(RestClient client, MockRestServiceServer server) {}

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://aem.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(builder.build(), server);
    }

    private static AemProperties props() {
        AemProperties p = new AemProperties();
        p.setBaseUrl("http://aem.test");
        p.setUsername("u");
        p.setPassword("p");
        p.setAllowedPathPrefixes(List.of("/content/public"));
        return p;
    }

    // ---- AemClientConfig -------------------------------------------------------------------

    @Test
    void aemClientConfig_buildsRestClient() {
        assertThat(new AemClientConfig().aemRestClient(props())).isNotNull();
    }

    // ---- ToolsConfig -----------------------------------------------------------------------

    @Test
    void toolsConfig_buildsCallbackProvider() {
        AemReadOnlyTools tools = new AemReadOnlyTools(mock(AemClient.class), props(), mock(AuditLogger.class));
        ToolCallbackProvider provider = new ToolsConfig().aemTools(tools);
        assertThat(provider).isNotNull();
        assertThat(provider.getToolCallbacks()).isNotEmpty();
    }

    // ---- AemHealthIndicatorsConfig ---------------------------------------------------------

    @Test
    void searchContentHealth_probesQueryBuilder() {
        Fixture f = fixture();
        f.server().expect(requestTo("http://aem.test/bin/querybuilder.json?type=cq:Page&p.limit=0"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        HealthIndicator hi = new AemHealthIndicatorsConfig().searchContentHealth(f.client(), props());
        assertThat(hi.health().getStatus()).isEqualTo(Status.UP);
        f.server().verify();
    }

    @Test
    void inspectNodeHealth_default_usesFirstPrefix() {
        Fixture f = fixture();
        f.server().expect(requestTo("http://aem.test/content/public.0.json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        HealthIndicator hi = new AemHealthIndicatorsConfig().inspectNodeHealth(f.client(), props());
        assertThat(hi.health().getStatus()).isEqualTo(Status.UP);
        f.server().verify();
    }

    @Test
    void inspectNodeHealth_usesConfiguredOverride() {
        Fixture f = fixture();
        AemProperties props = props();
        props.getHealth().setInspectNodePath("/content/public/landing");
        f.server().expect(requestTo("http://aem.test/content/public/landing"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        HealthIndicator hi = new AemHealthIndicatorsConfig().inspectNodeHealth(f.client(), props);
        assertThat(hi.health().getStatus()).isEqualTo(Status.UP);
        f.server().verify();
    }

    @Test
    void inspectNodeHealth_blankOverrideFallsBackToDefault() {
        Fixture f = fixture();
        AemProperties props = props();
        props.getHealth().setInspectNodePath("   "); // blank -> default path
        f.server().expect(requestTo("http://aem.test/content/public.0.json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        HealthIndicator hi = new AemHealthIndicatorsConfig().inspectNodeHealth(f.client(), props);
        assertThat(hi.health().getStatus()).isEqualTo(Status.UP);
        f.server().verify();
    }

    @Test
    void inspectNodeHealth_nullHealthFallsBackToDefault() {
        Fixture f = fixture();
        AemProperties props = props();
        props.setHealth(null);
        f.server().expect(requestTo("http://aem.test/content/public.0.json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        HealthIndicator hi = new AemHealthIndicatorsConfig().inspectNodeHealth(f.client(), props);
        assertThat(hi.health().getStatus()).isEqualTo(Status.UP);
        f.server().verify();
    }

    @Test
    void bundleHealth_enabled_probesConsole() {
        Fixture f = fixture();
        AemProperties props = props();
        props.setBundleHealthEnabled(true);
        f.server().expect(requestTo("http://aem.test/system/console/bundles.json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        HealthIndicator hi = new AemHealthIndicatorsConfig().bundleHealthHealth(f.client(), props);
        assertThat(hi.health().getStatus()).isEqualTo(Status.UP);
        f.server().verify();
    }

    @Test
    void bundleHealth_disabled_reportsUnknownWithoutCall() {
        Fixture f = fixture();
        AemProperties props = props(); // bundle-health-enabled defaults to false

        HealthIndicator hi = new AemHealthIndicatorsConfig().bundleHealthHealth(f.client(), props);
        Health health = hi.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("category", "disabled_by_config");
        f.server().verify(); // no request made
    }
}
