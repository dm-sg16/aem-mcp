package com.adobe.mcp.health;

import com.adobe.mcp.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fires the three per-tool health indicators once on {@link ApplicationReadyEvent} so the boot
 * log carries a clear AEM-connectivity verdict before the first MCP request lands. Failures are
 * logged + audited but never throw — the server keeps serving even when AEM is offline at
 * startup (per design decision: tolerate transient AEM blips, don't crash-loop the container).
 */
@Component
public class AemStartupProbe implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(AemStartupProbe.class);

    private static final List<String> TOOLS = List.of("searchContent", "inspectNode", "bundleHealth");

    private final Map<String, HealthIndicator> indicators;
    private final AuditLogger audit;

    public AemStartupProbe(Map<String, HealthIndicator> indicators, AuditLogger audit) {
        this.indicators = indicators;
        this.audit = audit;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        StringBuilder summary = new StringBuilder("AEM connectivity check:");
        boolean anyDown = false;

        for (String tool : TOOLS) {
            HealthIndicator indicator = indicators.get(tool);
            if (indicator == null) {
                continue;
            }
            Health health = indicator.health();
            String status = health.getStatus().getCode();
            Object latencyMs = health.getDetails().get("latencyMs");
            Object httpStatus = health.getDetails().get("httpStatus");
            Object category = health.getDetails().get("category");

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("phase", "startup");
            params.put("tool", tool);
            params.put("status", status);
            if (latencyMs != null) {
                params.put("latencyMs", latencyMs);
            }
            if (httpStatus != null) {
                params.put("httpStatus", httpStatus);
            }
            if (category != null) {
                params.put("category", category);
            }
            audit.record("aem_connectivity_probe", null, params);

            summary.append(' ').append(tool).append('=').append(status);
            if (httpStatus != null) {
                summary.append('(').append(httpStatus);
                if (latencyMs != null) {
                    summary.append(',').append(latencyMs).append("ms");
                }
                summary.append(')');
            } else if (category != null) {
                summary.append('(').append(category).append(')');
            }

            if ("DOWN".equals(status)) {
                anyDown = true;
            }
        }

        if (anyDown) {
            summary.append(" — server will continue serving");
            LOG.warn(summary.toString());
        } else {
            LOG.info(summary.toString());
        }
    }
}
