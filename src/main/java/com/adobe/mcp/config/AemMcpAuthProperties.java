package com.adobe.mcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code aem-mcp.*}. Holds the legacy shared bearer (Phase 1) plus the OIDC
 * configuration (Phase 2). Cross-field validation enforces:
 * <ul>
 *   <li>{@code aem-mcp.token} non-blank when {@code aem-mcp.auth.legacy-bearer-enabled=true}
 *       (the dual-auth window is open and we need a value to compare against);
 *   <li>at least one of {@code aem-mcp.oidc.issuer-uri} / {@code aem-mcp.oidc.jwk-set-uri}
 *       set, so {@link SecurityConfig} can build a {@code JwtDecoder};
 *   <li>{@code aem-mcp.oidc.principal-claim} non-blank.
 * </ul>
 * Pattern mirrors {@code AemProperties.isConsistent()}.
 */
@Validated
@ConfigurationProperties(prefix = "aem-mcp")
public class AemMcpAuthProperties {

    private String token = "";

    @Valid
    private Auth auth = new Auth();

    @Valid
    private Oidc oidc = new Oidc();

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token == null ? "" : token; }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public Oidc getOidc() { return oidc; }
    public void setOidc(Oidc oidc) { this.oidc = oidc; }

    @AssertTrue(message = "aem-mcp.token must be non-blank when aem-mcp.auth.legacy-bearer-enabled=true; "
            + "at least one of aem-mcp.oidc.issuer-uri or aem-mcp.oidc.jwk-set-uri must be set; "
            + "aem-mcp.oidc.principal-claim must be non-blank")
    public boolean isConsistent() {
        if (auth.legacyBearerEnabled && (token == null || token.isBlank())) {
            return false;
        }
        boolean hasIssuer = oidc.issuerUri != null && !oidc.issuerUri.isBlank();
        boolean hasJwks = oidc.jwkSetUri != null && !oidc.jwkSetUri.isBlank();
        if (!hasIssuer && !hasJwks) {
            return false;
        }
        return oidc.principalClaim != null && !oidc.principalClaim.isBlank();
    }

    public static class Auth {
        /**
         * When true (default during the migration window), the legacy shared bearer
         * {@code aem-mcp.token} is accepted in addition to a valid JWT. Flip to false once
         * all clients have moved to IDP-issued JWTs; legacy code is deleted in a follow-up.
         */
        private boolean legacyBearerEnabled = true;

        public boolean isLegacyBearerEnabled() { return legacyBearerEnabled; }
        public void setLegacyBearerEnabled(boolean legacyBearerEnabled) {
            this.legacyBearerEnabled = legacyBearerEnabled;
        }
    }

    public static class Oidc {
        private String issuerUri = "";
        private String jwkSetUri = "";
        private String audience = "";
        /** JWT claim used as the audit-log {@code caller}; falls back to {@code sub} when absent. */
        private String principalClaim = "preferred_username";

        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri == null ? "" : issuerUri; }

        public String getJwkSetUri() { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri == null ? "" : jwkSetUri; }

        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience == null ? "" : audience; }

        public String getPrincipalClaim() { return principalClaim; }
        public void setPrincipalClaim(String principalClaim) {
            this.principalClaim = principalClaim == null ? "" : principalClaim;
        }
    }
}
