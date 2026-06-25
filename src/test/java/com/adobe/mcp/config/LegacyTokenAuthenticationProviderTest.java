package com.adobe.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyTokenAuthenticationProviderTest {

    private static final String EXPECTED = "the-legacy-secret";

    private final LegacyTokenAuthenticationProvider provider = new LegacyTokenAuthenticationProvider(EXPECTED);

    @Test
    void authenticates_a_matching_bearer_as_the_legacy_service_account() {
        Authentication result = provider.authenticate(new BearerTokenAuthenticationToken(EXPECTED));

        assertThat(result).isInstanceOf(PreAuthenticatedAuthenticationToken.class);
        assertThat(result.getName()).isEqualTo(LegacyTokenAuthenticationProvider.LEGACY_PRINCIPAL);
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactly(LegacyTokenAuthenticationProvider.LEGACY_ROLE);
    }

    @Test
    void returns_null_for_a_non_matching_bearer_so_the_jwt_provider_can_try() {
        Authentication result = provider.authenticate(new BearerTokenAuthenticationToken("not-the-secret"));

        assertThat(result).isNull();
    }

    @Test
    void returns_null_for_a_non_bearer_authentication_kind() {
        Authentication result = provider.authenticate(
                new UsernamePasswordAuthenticationToken("user", "pass"));

        assertThat(result).isNull();
    }

    @Test
    void supports_bearer_token_only() {
        assertThat(provider.supports(BearerTokenAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }
}
