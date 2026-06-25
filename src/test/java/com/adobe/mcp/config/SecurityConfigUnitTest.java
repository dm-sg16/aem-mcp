package com.adobe.mcp.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level coverage for the branches in {@link SecurityConfig} that the full-context
 * {@link SecurityConfigTest} doesn't exercise: the {@code issuer-uri} side of {@code jwtDecoder}
 * (full context uses {@code jwk-set-uri}), and the missing-principal-claim path in the JWT
 * authentication converter.
 */
class SecurityConfigUnitTest {

    private final SecurityConfig config = new SecurityConfig();

    @Test
    void jwt_decoder_uses_jwk_set_uri_when_configured() {
        AemMcpAuthProperties props = new AemMcpAuthProperties();
        props.getOidc().setJwkSetUri("https://idp.test.invalid/jwks");

        JwtDecoder decoder = config.jwtDecoder(props);

        assertThat(decoder).isNotNull();
    }

    @Test
    void jwt_decoder_falls_back_to_issuer_uri_discovery() throws IOException, JOSEException {
        // Spin up an in-process HTTP server that serves the OIDC discovery document AND a
        // minimal one-key JWKS so the JwtDecoders.fromIssuerLocation call SUCCEEDS. A throwing
        // call leaves line 102's post-statement JaCoCo probe untripped, which would keep the
        // issuer-uri branch permanently uncovered.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String issuerUri = "http://127.0.0.1:" + server.getAddress().getPort();
        String discoveryDoc = "{\"issuer\":\"" + issuerUri + "\","
                + "\"jwks_uri\":\"" + issuerUri + "/jwks\","
                + "\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"],"
                + "\"response_types_supported\":[\"id_token\"]}";
        RSAKey rsaKey = new RSAKeyGenerator(2048)
                .keyID("test-key")
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .generate();
        String jwksJson = new JWKSet(rsaKey.toPublicJWK()).toString();
        server.createContext("/.well-known/openid-configuration", exchange -> serveJson(exchange, discoveryDoc));
        server.createContext("/jwks", exchange -> serveJson(exchange, jwksJson));
        server.start();
        try {
            AemMcpAuthProperties props = new AemMcpAuthProperties();
            props.getOidc().setIssuerUri(issuerUri);

            JwtDecoder decoder = config.jwtDecoder(props);

            assertThat(decoder).isNotNull();
        } finally {
            server.stop(0);
        }
    }

    private static void serveJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @Test
    void converter_resolves_principal_from_configured_claim() {
        Converter<Jwt, JwtAuthenticationToken> converter =
                SecurityConfig.jwtAuthenticationConverter("preferred_username");

        Jwt jwt = jwt(b -> b.claim("preferred_username", "alice").claim("sub", "uuid-alice"));

        JwtAuthenticationToken auth = converter.convert(jwt);

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alice");
    }

    @Test
    void converter_falls_back_to_sub_when_principal_claim_is_absent_and_only_warns_once() {
        Converter<Jwt, JwtAuthenticationToken> converter =
                SecurityConfig.jwtAuthenticationConverter("preferred_username");

        Jwt missing = jwt(b -> b.claim("sub", "uuid-only"));

        // First call hits both the claim-absent branch AND the compareAndSet=true WARN branch.
        JwtAuthenticationToken first = converter.convert(missing);
        // Second call hits the claim-absent branch AGAIN but compareAndSet=false (no second WARN).
        JwtAuthenticationToken second = converter.convert(missing);

        assertThat(first).isNotNull();
        assertThat(first.getName()).isEqualTo("uuid-only");
        assertThat(second).isNotNull();
        assertThat(second.getName()).isEqualTo("uuid-only");
    }

    @Test
    void converter_falls_back_to_sub_when_principal_claim_is_blank() {
        Converter<Jwt, JwtAuthenticationToken> converter =
                SecurityConfig.jwtAuthenticationConverter("preferred_username");

        Jwt blankClaim = jwt(b -> b.claim("preferred_username", "").claim("sub", "uuid-only"));

        JwtAuthenticationToken auth = converter.convert(blankClaim);

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("uuid-only");
    }

    private static Jwt jwt(java.util.function.Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        claims.accept(builder);
        return builder.build();
    }
}
