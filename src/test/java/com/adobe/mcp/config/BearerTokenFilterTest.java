package com.adobe.mcp.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BearerTokenFilterTest {

    private static OncePerRequestFilter filterFor(String token) {
        FilterRegistrationBean<OncePerRequestFilter> reg =
                new BearerTokenFilter().bearerTokenFilterRegistration(token);
        return (OncePerRequestFilter) reg.getFilter();
    }

    private static MockHttpServletResponse run(OncePerRequestFilter filter, String uri, String authHeader,
                                               MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        if (authHeader != null) {
            req.addHeader("Authorization", authHeader);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    void beanFactory_refusesEmptyToken() {
        assertThatThrownBy(() -> new BearerTokenFilter().bearerTokenFilterRegistration(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aem-mcp.token is empty");
    }

    @Test
    void unauthenticatedPath_passesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run(filterFor("secret"), "/actuator/health", null, chain);

        assertThat(chain.getRequest()).isNotNull(); // chain proceeded
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void protectedPath_missingHeader_unauthorized() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run(filterFor("secret"), "/sse", null, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(res.getContentAsString()).contains("Missing bearer token");
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void protectedPath_nonBearerHeader_unauthorized() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run(filterFor("secret"), "/sse", "Basic abc", chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(res.getContentAsString()).contains("Missing bearer token");
    }

    @Test
    void protectedPath_wrongLengthToken_unauthorized() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run(filterFor("secret"), "/sse", "Bearer short", chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(res.getContentAsString()).contains("Invalid bearer token");
    }

    @Test
    void protectedPath_sameLengthWrongToken_unauthorized() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run(filterFor("secret"), "/sse", "Bearer sxcret", chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(res.getContentAsString()).contains("Invalid bearer token");
    }

    @Test
    void protectedPath_validToken_passesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run(filterFor("secret"), "/sse", "Bearer secret", chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void constantTimeEquals_coversNullAndEqualityBranches() throws Exception {
        Method m = BearerTokenFilter.class.getDeclaredMethod("constantTimeEquals", String.class, String.class);
        m.setAccessible(true);

        assertThat((boolean) m.invoke(null, (String) null, "x")).isFalse();
        assertThat((boolean) m.invoke(null, "x", (String) null)).isFalse();
        assertThat((boolean) m.invoke(null, "abc", "ab")).isFalse();   // length mismatch
        assertThat((boolean) m.invoke(null, "abc", "abd")).isFalse();  // same length, differs
        assertThat((boolean) m.invoke(null, "abc", "abc")).isTrue();   // equal
    }
}
