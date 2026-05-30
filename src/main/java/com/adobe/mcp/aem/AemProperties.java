package com.adobe.mcp.aem;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "aem")
public class AemProperties {

    @NotBlank
    private String baseUrl;

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

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

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

    @AssertTrue(message = "aem.default-limit must be <= aem.max-limit, and every aem.allowed-path-prefixes entry must start with '/'")
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
        return true;
    }
}
