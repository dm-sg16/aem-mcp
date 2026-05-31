package com.adobe.mcp.config;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.util.List;

/**
 * Authenticates the legacy shared bearer token during the dual-auth migration window. Registered
 * BEFORE {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider}
 * in the {@link org.springframework.security.authentication.ProviderManager}: a presented bearer
 * that matches the configured legacy token is accepted as
 * {@value #LEGACY_PRINCIPAL}; any other bearer returns {@code null} so the JWT provider gets a
 * chance to validate it. Use the {@code caller=legacy:service-account} count in the audit log
 * to track migration progress.
 */
public class LegacyTokenAuthenticationProvider implements AuthenticationProvider {

    public static final String LEGACY_PRINCIPAL = "legacy:service-account";
    public static final String LEGACY_ROLE = "ROLE_LEGACY_SERVICE_ACCOUNT";

    private final String expectedToken;

    public LegacyTokenAuthenticationProvider(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof BearerTokenAuthenticationToken bearer)) {
            return null;
        }
        if (!constantTimeEquals(bearer.getToken(), expectedToken)) {
            return null;
        }
        PreAuthenticatedAuthenticationToken token = new PreAuthenticatedAuthenticationToken(
                LEGACY_PRINCIPAL, "N/A", List.of(new SimpleGrantedAuthority(LEGACY_ROLE)));
        token.setAuthenticated(true);
        return token;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
