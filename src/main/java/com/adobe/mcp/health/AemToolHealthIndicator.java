package com.adobe.mcp.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.function.Supplier;

/**
 * Generic Spring Boot health indicator for an AEM-backed MCP tool. Wraps a probe supplier
 * that performs a single GET against AEM and reports UP/DOWN/UNKNOWN with diagnostic details.
 *
 * <p>Returning {@code null} from the supplier is a sentinel for "tool disabled by
 * configuration" — the indicator reports {@link Status#UNKNOWN} with
 * {@code category=disabled_by_config} and makes no further calls. This keeps the
 * {@code bundleHealth} case simple without a subclass.
 *
 * <p>Response details deliberately exclude the AEM base URL and the service-account username
 * so {@code /actuator/health/<tool>} (which is unauthenticated for monitoring scrape) cannot
 * leak topology.
 */
public class AemToolHealthIndicator extends AbstractHealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(AemToolHealthIndicator.class);

    private final String toolName;
    private final Supplier<ResponseEntity<Void>> probe;
    private final String probePath;

    /**
     * @param toolName  used in log lines and audit entries (e.g. "searchContent")
     * @param probePath the configured probe path, surfaced in UP details for debuggability
     * @param probe     performs the GET; returns null to signal UNKNOWN (disabled by config)
     */
    public AemToolHealthIndicator(String toolName, String probePath, Supplier<ResponseEntity<Void>> probe) {
        this.toolName = toolName;
        this.probePath = probePath;
        this.probe = probe;
    }

    public String getToolName() {
        return toolName;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        long startNanos = System.nanoTime();
        try {
            ResponseEntity<Void> response = probe.get();
            long latencyMs = elapsedMs(startNanos);

            if (response == null) {
                builder.status(Status.UNKNOWN)
                        .withDetail("category", "disabled_by_config")
                        .withDetail("latencyMs", 0L);
                return;
            }

            int status = response.getStatusCode().value();
            builder.up()
                    .withDetail("httpStatus", status)
                    .withDetail("latencyMs", latencyMs);
            if (probePath != null) {
                builder.withDetail("probePath", probePath);
            }
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            builder.down()
                    .withDetail("category", AemErrorCategories.categoryForStatus(status))
                    .withDetail("httpStatus", status)
                    .withDetail("latencyMs", elapsedMs(startNanos));
            if (probePath != null) {
                builder.withDetail("probePath", probePath);
            }
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            String category = (cause instanceof SocketTimeoutException) ? "timeout" : "unreachable";
            builder.down()
                    .withDetail("category", category)
                    .withDetail("latencyMs", elapsedMs(startNanos));
            if (probePath != null) {
                builder.withDetail("probePath", probePath);
            }
            // The health details deliberately omit the base URL and the underlying exception to
            // keep the unauthenticated /actuator/health/<tool> endpoint topology-safe. Logs are
            // operator-only, so surface the real cause here — without it, "unreachable" can't be
            // told apart from a DNS miss, a refused connection, or a TLS-trust failure
            // (SSLHandshakeException: "PKIX path building failed ... unable to find valid
            // certification path"). WARN because a connection that never established is
            // actionable; the full stack (incl. nested cert-chain causes) follows at DEBUG.
            Throwable reported = (cause != null) ? cause : e;
            LOG.warn("AEM probe for tool '{}' is DOWN ({}): {}: {}",
                    toolName, category, reported.getClass().getSimpleName(), reported.getMessage());
            LOG.debug("AEM probe for tool '{}' connectivity failure detail", toolName, e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
