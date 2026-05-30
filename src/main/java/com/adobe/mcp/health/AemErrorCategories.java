package com.adobe.mcp.health;

/**
 * Maps HTTP status codes from AEM into the structured error vocabulary used by both the MCP
 * tool error envelopes and the per-tool health indicators. Single source of truth so the two
 * surfaces can't drift.
 */
public final class AemErrorCategories {

    private AemErrorCategories() {}

    public static String categoryForStatus(int httpStatus) {
        return switch (httpStatus) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 408, 504 -> "timeout";
            case 500, 502, 503 -> "aem_server_error";
            default -> "aem_http_error";
        };
    }

    public static String hintForStatus(int httpStatus) {
        return switch (httpStatus) {
            case 401, 403 -> "AEM rejected the service-account credentials or the principal lacks read access to this path. Have the platform team grant read on the allow-listed trees.";
            case 404 -> "Path not found in AEM. Confirm the node exists and is under an allow-listed prefix.";
            case 408, 504 -> "AEM did not respond in time. Retry, or check author-instance load.";
            case 500, 502, 503 -> "AEM returned a server error. Check the author instance and try again.";
            default -> "AEM returned HTTP " + httpStatus + ".";
        };
    }
}
