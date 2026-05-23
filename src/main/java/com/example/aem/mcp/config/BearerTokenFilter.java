package com.example.aem.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Configuration
public class BearerTokenFilter {

    private static final Set<String> UNAUTHENTICATED_PATHS = Set.of(
            "/", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info");

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> bearerTokenFilterRegistration(
            @Value("${aem-mcp.token:}") String expectedToken) {

        if (!StringUtils.hasText(expectedToken)) {
            throw new IllegalStateException(
                    "AEM_MCP_TOKEN is not set. Refusing to start without a bearer token — set the env var or fail loudly.");
        }

        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String path = req.getRequestURI();
                if (UNAUTHENTICATED_PATHS.contains(path)) {
                    chain.doFilter(req, res);
                    return;
                }
                String header = req.getHeader(HttpHeaders.AUTHORIZATION);
                if (header == null || !header.startsWith("Bearer ")) {
                    unauthorized(res, "Missing bearer token");
                    return;
                }
                String presented = header.substring("Bearer ".length()).trim();
                if (!constantTimeEquals(presented, expectedToken)) {
                    unauthorized(res, "Invalid bearer token");
                    return;
                }
                chain.doFilter(req, res);
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(Integer.MIN_VALUE);
        return reg;
    }

    private static void unauthorized(HttpServletResponse res, String reason) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        res.getOutputStream().write(("{\"error\":\"unauthorized\",\"hint\":\"" + reason + "\"}")
                .getBytes(StandardCharsets.UTF_8));
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
