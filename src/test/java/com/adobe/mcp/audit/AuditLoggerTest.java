package com.adobe.mcp.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.adobe.mcp.config.LegacyTokenAuthenticationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLoggerTest {

    private AuditLogger audit;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        audit = new AuditLogger();
        Logger logger = (Logger) LoggerFactory.getLogger("AEM_MCP_AUDIT");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("AEM_MCP_AUDIT")).detachAppender(appender);
        SecurityContextHolder.clearContext();
    }

    @Test
    void explicit_caller_wins_over_security_context() {
        SecurityContextHolder.getContext().setAuthentication(jwtAuthFor("alice"));

        audit.record("searchContent", "explicit-caller", Map.of("path", "/content/x"));

        assertThat(callerOnLastEvent()).isEqualTo("explicit-caller");
    }

    @Test
    void null_caller_with_jwt_resolves_to_principal_claim_name() {
        SecurityContextHolder.getContext().setAuthentication(jwtAuthFor("dmartinez"));

        audit.record("inspectNode", null, Map.of("path", "/content/y", "depth", 1));

        assertThat(callerOnLastEvent()).isEqualTo("dmartinez");
    }

    @Test
    void null_caller_with_legacy_pre_auth_resolves_to_legacy_principal() {
        PreAuthenticatedAuthenticationToken token = new PreAuthenticatedAuthenticationToken(
                LegacyTokenAuthenticationProvider.LEGACY_PRINCIPAL, "N/A",
                List.of(new SimpleGrantedAuthority(LegacyTokenAuthenticationProvider.LEGACY_ROLE)));
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);

        audit.record("bundleHealth", null, Map.of());

        assertThat(callerOnLastEvent()).isEqualTo(LegacyTokenAuthenticationProvider.LEGACY_PRINCIPAL);
    }

    @Test
    void null_caller_with_no_security_context_falls_back_to_service_account() {
        SecurityContextHolder.clearContext();

        audit.record("aem_connectivity_probe", null, Map.of("phase", "startup"));

        assertThat(callerOnLastEvent()).isEqualTo("service-account");
    }

    @Test
    void null_caller_with_unauthenticated_token_falls_back_to_service_account() {
        TestingAuthenticationToken unauth = new TestingAuthenticationToken("nobody", "");
        unauth.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauth);

        audit.record("searchContent", null, Map.of("path", "/content/x"));

        assertThat(callerOnLastEvent()).isEqualTo("service-account");
    }

    private static JwtAuthenticationToken jwtAuthFor(String name) {
        Jwt jwt = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .claim("sub", "uuid-" + name)
                .claim("preferred_username", name)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(), name);
    }

    private String callerOnLastEvent() {
        ILoggingEvent last = appender.list.get(appender.list.size() - 1);
        return last.getMDCPropertyMap().get("caller");
    }
}
