package com.adobe.mcp.health;

import com.adobe.mcp.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AemStartupProbeTest {

    @Test
    void logsWarnAndAuditsWhenAnyToolDown_andSkipsMissingIndicator() {
        AuditLogger audit = mock(AuditLogger.class);
        Map<String, HealthIndicator> indicators = new HashMap<>();
        // httpStatus + latency present -> exercises the (httpStatus,latencyMs) append branch.
        indicators.put("searchContent", () -> Health.up()
                .withDetail("httpStatus", 200).withDetail("latencyMs", 5L).build());
        // DOWN with category, no httpStatus -> exercises the category-else branch + anyDown=true.
        indicators.put("inspectNode", () -> Health.down()
                .withDetail("category", "unreachable").build());
        // bundleHealth deliberately absent -> exercises the indicator == null continue.

        new AemStartupProbe(indicators, audit).onApplicationEvent(null);

        // Only the two present tools are audited.
        verify(audit, times(2)).record(eq("aem_connectivity_probe"), isNull(), anyMap());
    }

    @Test
    void logsInfoWhenAllUp_andHandlesHttpStatusWithoutLatency() {
        AuditLogger audit = mock(AuditLogger.class);
        Map<String, HealthIndicator> indicators = new HashMap<>();
        // httpStatus present, latency absent -> exercises the latency == null branch.
        indicators.put("searchContent", () -> Health.up().withDetail("httpStatus", 200).build());
        indicators.put("inspectNode", () -> Health.up()
                .withDetail("httpStatus", 200).withDetail("latencyMs", 3L).build());
        // UP with neither httpStatus nor category -> exercises the category == null else branch.
        indicators.put("bundleHealth", () -> Health.up().build());

        new AemStartupProbe(indicators, audit).onApplicationEvent(null);

        verify(audit, times(3)).record(eq("aem_connectivity_probe"), isNull(), anyMap());
    }
}
