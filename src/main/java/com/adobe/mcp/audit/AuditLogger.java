package com.adobe.mcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("AEM_MCP_AUDIT");
    private static final String MSG = "aem.mcp.tool.invoked";

    public void record(String tool, String caller, Map<String, ?> params) {
        MDC.put("tool", tool);
        MDC.put("caller", caller == null ? "service-account" : caller);
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
}
