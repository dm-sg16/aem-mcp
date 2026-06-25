package com.adobe.mcp.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * End-to-end Spring Security wiring test. Uses a real Spring context with a Mockito-backed
 * {@link JwtDecoder} so we don't need a live OIDC provider. Exercises the dual-auth window on
 * a non-existent path so authenticated requests resolve to 404 (proving auth passed) and
 * rejected requests resolve to 401.
 *
 * <p>The legacy-flag-off case lives in {@link SecurityConfigLegacyDisabledTest} below — Spring
 * test-property overrides happen at context-load time, so toggling a flag mid-class would
 * require a context restart per case.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {SecurityConfigTest.TestApp.class, SecurityConfigTest.TestConfig.class})
@TestPropertySource(properties = {
        "aem.base-url=http://aem.invalid",
        "aem.username=svc",
        "aem.password=svc",
        "aem.allowed-path-prefixes[0]=/content/test",
        "aem-mcp.token=legacy-shared-secret-for-tests",
        "aem-mcp.auth.legacy-bearer-enabled=true",
        "aem-mcp.oidc.jwk-set-uri=https://idp.test.invalid/jwks",
        "spring.ai.mcp.server.enabled=false",
        "management.endpoint.health.probes.enabled=false"
})
class SecurityConfigTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtDecoder jwtDecoder;

    @BeforeEach
    void resetMockDecoder() {
        Mockito.reset(jwtDecoder);
    }

    @AfterEach
    void clearMockDecoder() {
        Mockito.reset(jwtDecoder);
    }

    @Test
    void valid_jwt_passes_auth_and_request_reaches_dispatcher() {
        Jwt jwt = jwtFor("alice");
        Mockito.when(jwtDecoder.decode(eq("valid.jwt"))).thenReturn(jwt);

        ResponseEntity<String> response = getWithAuth("/nonexistent-path", "Bearer valid.jwt");

        // Auth passed; nonexistent path then resolves to 404 — proves the request crossed the
        // security chain.
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void invalid_jwt_is_rejected_with_401() {
        // BadJwtException (not JwtException) is what real decoders throw for malformed/expired
        // tokens; the resource-server provider maps it to InvalidBearerTokenException → 401.
        Mockito.when(jwtDecoder.decode(any())).thenThrow(new BadJwtException("bad"));

        ResponseEntity<String> response = getWithAuth("/nonexistent-path", "Bearer not.a.real.jwt");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void legacy_token_is_accepted_while_legacy_flag_is_on() {
        // Decoder never called for the legacy path, but Mockito will fail loudly if it is.
        ResponseEntity<String> response = getWithAuth(
                "/nonexistent-path", "Bearer legacy-shared-secret-for-tests");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        Mockito.verifyNoInteractions(jwtDecoder);
    }

    @Test
    void no_authorization_header_is_rejected_with_401() {
        ResponseEntity<String> response = getWithAuth("/nonexistent-path", null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void actuator_health_is_reachable_without_auth() {
        ResponseEntity<String> response = getWithAuth("/actuator/health", null);

        // 200 (UP) or 503 (DOWN) both indicate auth was permitted — the security chain let it
        // through to the actuator. We assert "not 401" and "not 404" rather than a specific
        // status because the global show-details=never could affect the body but not the route.
        int status = response.getStatusCode().value();
        assertThat(status).isNotIn(401, 404);
    }

    @Test
    void error_path_is_permitted_so_dispatcher_can_render_errors() {
        // Directly hitting /error in a dev/test profile typically yields a 200 with a Whitelabel
        // page; what we care about is that auth didn't intercept it (no 401).
        ResponseEntity<String> response = getWithAuth("/error", null);

        assertThat(response.getStatusCode().value()).isNotEqualTo(401);
    }

    private ResponseEntity<String> getWithAuth(String path, String authHeader) {
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        RestClient.RequestHeadersSpec<?> spec = client.method(HttpMethod.GET).uri(path);
        if (authHeader != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return spec.retrieve()
                .onStatus(s -> true, (req, res) -> { /* swallow — we want the entity, not exceptions */ })
                .toEntity(String.class);
    }

    private static Jwt jwtFor(String name) {
        return Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .claim("sub", "uuid-" + name)
                .claim("preferred_username", name)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages = "com.adobe.mcp")
    static class TestApp {
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        JwtDecoder mockJwtDecoder() {
            return Mockito.mock(JwtDecoder.class);
        }
    }
}
