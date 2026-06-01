package com.adobe.mcp.aem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "aem")
public class AemProperties {

    @NotBlank
    private String baseUrl;

    /**
     * Optional context root if AEM is deployed under a sub-path (e.g. "/WC2"). When set, the
     * client and probes prepend it to every outbound HTTP URI. JCR paths (allow-list,
     * inspectNode arg, inspect-node-path) stay context-root-independent. Empty by default.
     */
    private String contextRoot = "";

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    @NotEmpty
    private List<String> allowedPathPrefixes;

    @Min(1)
    private int defaultLimit = 20;

    @Min(1)
    private int maxLimit = 100;

    @Min(0)
    private int maxDepth = 3;

    private boolean bundleHealthEnabled = false;

    @Valid
    private Health health = new Health();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getContextRoot() { return contextRoot; }
    public void setContextRoot(String contextRoot) { this.contextRoot = contextRoot == null ? "" : contextRoot; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public List<String> getAllowedPathPrefixes() { return allowedPathPrefixes; }
    public void setAllowedPathPrefixes(List<String> allowedPathPrefixes) { this.allowedPathPrefixes = allowedPathPrefixes; }

    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }

    public int getMaxLimit() { return maxLimit; }
    public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }

    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }

    public boolean isBundleHealthEnabled() { return bundleHealthEnabled; }
    public void setBundleHealthEnabled(boolean bundleHealthEnabled) { this.bundleHealthEnabled = bundleHealthEnabled; }

    public Health getHealth() { return health; }
    public void setHealth(Health health) { this.health = health; }

    @AssertTrue(message = "aem.default-limit must be <= aem.max-limit; every aem.allowed-path-prefixes entry must start with '/'; aem.context-root must be empty or start with '/' and not end with '/'")
    public boolean isConsistent() {
        if (defaultLimit > maxLimit) {
            return false;
        }
        if (allowedPathPrefixes == null || allowedPathPrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : allowedPathPrefixes) {
            if (prefix == null || !prefix.startsWith("/")) {
                return false;
            }
        }
        // contextRoot is never null: the field defaults to "" and the setter normalises null to "".
        if (!contextRoot.isEmpty()) {
            if (!contextRoot.startsWith("/") || contextRoot.endsWith("/") || contextRoot.contains("//")) {
                return false;
            }
        }
        return true;
    }

    /** Probe-path overrides for the per-tool actuator health indicators. */
    public static class Health {
        /**
         * When null or empty, the inspectNode probe uses the first allowed prefix + ".0.json".
         * Pattern accepts empty (= "use default") so {@code aem.health.inspect-node-path:} with no
         * value in YAML is a valid no-op rather than a validation failure.
         */
        @Pattern(regexp = "^$|^/.*$", message = "must start with '/' (or be empty to use the default)")
        private String inspectNodePath;

        public String getInspectNodePath() { return inspectNodePath; }
        public void setInspectNodePath(String inspectNodePath) { this.inspectNodePath = inspectNodePath; }
    }
}
