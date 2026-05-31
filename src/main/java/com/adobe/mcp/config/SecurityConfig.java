package com.adobe.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring Security wiring for the dual-auth migration window. Composes two
 * {@link AuthenticationProvider}s behind the standard resource-server bearer filter:
 * <ol>
 *   <li>{@link LegacyTokenAuthenticationProvider} (only when {@code aem-mcp.auth.legacy-bearer-enabled=true})
 *       — constant-time equality against {@code aem-mcp.token};
 *   <li>{@link JwtAuthenticationProvider} backed by a {@link NimbusJwtDecoder} built from
 *       {@code aem-mcp.oidc.jwk-set-uri} when set, else
 *       {@link JwtDecoders#fromIssuerLocation(String)} on {@code aem-mcp.oidc.issuer-uri}.
 * </ol>
 * The JWT converter resolves {@code JwtAuthenticationToken.getName()} to the configured
 * principal claim (default {@code preferred_username}) with {@code sub} as fallback;
 * {@link com.adobe.mcp.audit.AuditLogger} then pulls that name from the security context so
 * call sites pass {@code caller=null} and inherit per-developer attribution.
 *
 * <p>Known limitation: the SSE stream's initial auth context lives for the lifetime of the
 * open GET. Subsequent JSON-RPC POSTs are re-validated, so an expired JWT 401s on the next
 * POST — clients must reconnect. Pick a JWT TTL ≥ 24h to keep this rare.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationManager dualAuthManager) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Probes and root — same matrix the deleted BearerTokenFilter allowed.
                        // Wildcard covers /actuator/health and every per-tool group below it
                        // (aem, aem-search, aem-inspect, aem-bundle, liveness, readiness).
                        .requestMatchers(HttpMethod.GET, "/", "/actuator/health/**", "/actuator/info").permitAll()
                        // Spring's internal /error forward must be reachable when an upstream
                        // 4xx/5xx happens — otherwise the dispatcher loops back through auth and
                        // a 404 turns into a 401 with no body.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        // Resolver returns the same manager every time; the resolver hook is
                        // simply the Spring-supported way to inject a custom AuthenticationManager
                        // into the BearerTokenAuthenticationFilter.
                        .authenticationManagerResolver(request -> dualAuthManager));
        return http.build();
    }

    @Bean
    public AuthenticationManager dualAuthManager(AemMcpAuthProperties props, JwtDecoder jwtDecoder) {
        List<AuthenticationProvider> providers = new ArrayList<>();
        if (props.getAuth().isLegacyBearerEnabled()) {
            providers.add(new LegacyTokenAuthenticationProvider(props.getToken()));
        }
        JwtAuthenticationProvider jwtProvider = new JwtAuthenticationProvider(jwtDecoder);
        // Converter is built inline (not a @Bean) because a Converter<Jwt,...> bean is picked up
        // by Spring MVC's ApplicationConversionService — and lambdas lose generic types at
        // runtime, so it fails to register as a Formatter. Building it here keeps it out of the
        // MVC type-conversion graph.
        jwtProvider.setJwtAuthenticationConverter(buildJwtAuthenticationConverter(props));
        providers.add(jwtProvider);
        return new ProviderManager(providers);
    }

    @Bean
    public JwtDecoder jwtDecoder(AemMcpAuthProperties props) {
        String jwkSetUri = props.getOidc().getJwkSetUri();
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            // Skips OIDC discovery — points directly at the JWKS endpoint. Preferred for
            // dev/test or when the IDP's discovery doc isn't reachable from the server.
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }
        // Triggers an OIDC discovery call at first request. If the IDP is briefly unreachable
        // at boot, the first request 503s and subsequent ones recover.
        return JwtDecoders.fromIssuerLocation(props.getOidc().getIssuerUri());
    }

    /**
     * Resolves {@link JwtAuthenticationToken#getName()} to the configured principal claim, with
     * {@code sub} as fallback. Logs at WARN once per process the first time the configured claim
     * is absent — silent fallback to {@code sub} would otherwise show up as audit-log rows full
     * of UUIDs with no signal that the claim is misconfigured.
     */
    private static Converter<Jwt, JwtAuthenticationToken> buildJwtAuthenticationConverter(AemMcpAuthProperties props) {
        String claim = props.getOidc().getPrincipalClaim();
        AtomicBoolean missingClaimWarned = new AtomicBoolean(false);
        return jwt -> {
            String name = jwt.getClaimAsString(claim);
            if (name == null || name.isBlank()) {
                if (missingClaimWarned.compareAndSet(false, true)) {
                    LOG.warn("Configured principal claim '{}' is absent from JWT; falling back to 'sub'. "
                            + "Audit logs will show subject UUIDs until the IDP issues the claim or "
                            + "aem-mcp.oidc.principal-claim is reconfigured.", claim);
                }
                name = jwt.getSubject();
            }
            return new JwtAuthenticationToken(jwt, List.of(), name);
        };
    }
}
