package com.adobe.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Verifies the post-migration state: with {@code aem-mcp.auth.legacy-bearer-enabled=false} the
 * legacy shared bearer no longer authenticates — only valid JWTs do.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {SecurityConfigLegacyDisabledTest.TestApp.class, SecurityConfigLegacyDisabledTest.TestConfig.class})
@TestPropertySource(properties = {
        "aem.base-url=http://aem.invalid",
        "aem.username=svc",
        "aem.password=svc",
        "aem.allowed-path-prefixes[0]=/content/test",
        "aem-mcp.token=legacy-shared-secret-for-tests",
        "aem-mcp.auth.legacy-bearer-enabled=false",
        "aem-mcp.oidc.jwk-set-uri=https://idp.test.invalid/jwks",
        "spring.ai.mcp.server.enabled=false",
        "management.endpoint.health.probes.enabled=false"
})
class SecurityConfigLegacyDisabledTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtDecoder jwtDecoder;

    @Test
    void legacy_token_is_rejected_when_flag_is_off() {
        // JWT decoder will be hit because legacy provider isn't registered. It treats the
        // plain string as a JWT, fails to parse → 401 via BadJwtException.
        Mockito.when(jwtDecoder.decode(any())).thenThrow(new BadJwtException("not a jwt"));

        ResponseEntity<String> response = getWithAuth("Bearer legacy-shared-secret-for-tests");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    private ResponseEntity<String> getWithAuth(String authHeader) {
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        return client.method(HttpMethod.GET)
                .uri("/nonexistent-path")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .onStatus(s -> true, (req, res) -> { /* swallow */ })
                .toEntity(String.class);
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
