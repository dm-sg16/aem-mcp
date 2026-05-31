package com.adobe.mcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * When {@code caller} is non-null, that value wins. When null, we read the current
 * {@link SecurityContextHolder} principal — a JWT-authenticated request lands as
 * {@code JwtAuthenticationToken.getName()} (resolved to the configured principal claim by
 * {@link com.adobe.mcp.config.SecurityConfig}); a legacy-bearer-authenticated request lands as
 * {@code legacy:service-account} (see
 * {@link com.adobe.mcp.config.LegacyTokenAuthenticationProvider}). Background threads (e.g.
 * {@link com.adobe.mcp.health.AemStartupProbe}) have no security context and fall through to
 * the literal {@code "service-account"} default, matching pre-OIDC behaviour.
 *
 * <p>Output format is structured JSON so SIEM ingestion does not need a parser. Each call emits
 * a single line containing the standard fields plus an MDC entry per parameter
 * ({@code param.<name>}).
 */
@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AEM_MCP_AUDIT");
    private static final String MSG = "aem.mcp.tool.invoked";

    public void record(String tool, String caller, Map<String, ?> params) {
        String resolved = caller;
        if (resolved == null) {
            resolved = resolveCallerFromSecurityContext();
        }
        MDC.put("tool", tool);
        MDC.put("caller", resolved == null ? "service-account" : resolved);
        try {
            if (params != null) {
                params.forEach((k, v) -> MDC.put("param." + k, v == null ? "" : v.toString()));
            }
            AUDIT.info(MSG);
        } finally {
            MDC.remove("tool");
            MDC.remove("caller");
            if (params != null) {
                params.keySet().forEach(k -> MDC.remove("param." + k));
            }
        }
    }

    private static String resolveCallerFromSecurityContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        return (name == null || name.isBlank()) ? null : name;
    }
}
