package com.adobe.mcp.health;

import com.adobe.mcp.aem.AemProperties;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@Configuration
public class AemHealthIndicatorsConfig {

    /** Probes /bin/querybuilder.json with a zero-result query — cheapest QueryBuilder call. */
    @Bean("searchContent")
    public HealthIndicator searchContentHealth(RestClient aemRestClient) {
        String probePath = "/bin/querybuilder.json?type=cq:Page&p.limit=0";
        return new AemToolHealthIndicator("searchContent", probePath,
                () -> aemRestClient.get().uri(probePath).retrieve().toBodilessEntity());
    }

    /**
     * Probes a Sling node GET. Defaults to the first allow-listed prefix + ".0.json" when no
     * explicit override is set, so the probe exercises a real configured tree.
     */
    @Bean("inspectNode")
    public HealthIndicator inspectNodeHealth(RestClient aemRestClient, AemProperties props) {
        String configured = props.getHealth() == null ? null : props.getHealth().getInspectNodePath();
        String probePath = (configured != null && !configured.isBlank())
                ? configured
                : props.getAllowedPathPrefixes().get(0) + ".0.json";
        return new AemToolHealthIndicator("inspectNode", probePath,
                () -> aemRestClient.get().uri(probePath).retrieve().toBodilessEntity());
    }

    /**
     * Probes /system/console/bundles.json only when {@code aem.bundle-health-enabled=true}.
     * Otherwise returns the null sentinel so the base indicator reports UNKNOWN with
     * {@code category=disabled_by_config}, mirroring the tool's behaviour.
     */
    @Bean("bundleHealth")
    public HealthIndicator bundleHealthHealth(RestClient aemRestClient, AemProperties props) {
        String probePath = "/system/console/bundles.json";
        return new AemToolHealthIndicator("bundleHealth", probePath, () -> {
            if (!props.isBundleHealthEnabled()) {
                return null;
            }
            ResponseEntity<Void> resp = aemRestClient.get().uri(probePath).retrieve().toBodilessEntity();
            return resp;
        });
    }
}
