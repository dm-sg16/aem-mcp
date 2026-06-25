package com.adobe.mcp.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the cross-field validator in {@link AemMcpAuthProperties#isConsistent()} and the
 * setter normalisations. The full Spring context wiring is exercised in {@link SecurityConfigTest}.
 */
class AemMcpAuthPropertiesTest {

    @Test
    void consistent_default_with_token_and_jwk_set_uri() {
        AemMcpAuthProperties props = newProps();
        props.setToken("secret");
        props.getOidc().setJwkSetUri("https://idp/jwks");

        assertThat(props.isConsistent()).isTrue();
    }

    @Test
    void inconsistent_when_legacy_enabled_but_token_blank() {
        AemMcpAuthProperties props = newProps();
        // legacy is enabled by default; leave token blank
        props.getOidc().setIssuerUri("https://idp");

        assertThat(props.isConsistent()).isFalse();
    }

    @Test
    void consistent_when_legacy_disabled_and_token_blank() {
        AemMcpAuthProperties props = newProps();
        props.getAuth().setLegacyBearerEnabled(false);
        props.getOidc().setIssuerUri("https://idp");

        assertThat(props.isConsistent()).isTrue();
    }

    @Test
    void inconsistent_when_both_issuer_uri_and_jwk_set_uri_blank() {
        AemMcpAuthProperties props = newProps();
        props.setToken("secret");
        // Neither oidc.issuer-uri nor oidc.jwk-set-uri set.

        assertThat(props.isConsistent()).isFalse();
    }

    @Test
    void inconsistent_when_principal_claim_blank() {
        AemMcpAuthProperties props = newProps();
        props.setToken("secret");
        props.getOidc().setJwkSetUri("https://idp/jwks");
        props.getOidc().setPrincipalClaim("");

        assertThat(props.isConsistent()).isFalse();
    }

    @Test
    void setters_normalise_null_inputs_to_empty_string() {
        AemMcpAuthProperties.Oidc oidc = new AemMcpAuthProperties.Oidc();
        oidc.setIssuerUri(null);
        oidc.setJwkSetUri(null);
        oidc.setAudience(null);
        oidc.setPrincipalClaim(null);

        assertThat(oidc.getIssuerUri()).isEmpty();
        assertThat(oidc.getJwkSetUri()).isEmpty();
        assertThat(oidc.getAudience()).isEmpty();
        assertThat(oidc.getPrincipalClaim()).isEmpty();

        AemMcpAuthProperties props = new AemMcpAuthProperties();
        props.setToken(null);
        assertThat(props.getToken()).isEmpty();
    }

    @Test
    void getters_round_trip_set_values() {
        AemMcpAuthProperties.Oidc oidc = new AemMcpAuthProperties.Oidc();
        oidc.setIssuerUri("https://idp/");
        oidc.setJwkSetUri("https://idp/jwks");
        oidc.setAudience("aem-mcp");
        oidc.setPrincipalClaim("email");

        assertThat(oidc.getIssuerUri()).isEqualTo("https://idp/");
        assertThat(oidc.getJwkSetUri()).isEqualTo("https://idp/jwks");
        assertThat(oidc.getAudience()).isEqualTo("aem-mcp");
        assertThat(oidc.getPrincipalClaim()).isEqualTo("email");

        AemMcpAuthProperties props = new AemMcpAuthProperties();
        AemMcpAuthProperties.Auth auth = new AemMcpAuthProperties.Auth();
        auth.setLegacyBearerEnabled(false);
        props.setAuth(auth);
        props.setOidc(oidc);

        assertThat(props.getAuth().isLegacyBearerEnabled()).isFalse();
        assertThat(props.getOidc()).isSameAs(oidc);
    }

    private static AemMcpAuthProperties newProps() {
        return new AemMcpAuthProperties();
    }
}
