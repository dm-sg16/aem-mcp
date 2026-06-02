package com.adobe.mcp.health;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AemToolHealthIndicatorTest {

    private static final String PROBE_PATH = "/probe.json";

    private record Fixture(RestClient client, MockRestServiceServer server) {}

    private static Fixture buildClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://aem.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(builder.build(), server);
    }

    private static Supplier<ResponseEntity<Void>> probe(RestClient client) {
        return () -> client.get().uri(PROBE_PATH).retrieve().toBodilessEntity();
    }

    @Test
    void reports_up_with_status_and_latency_on_200() {
        Fixture f = buildClient();
        f.server().expect(requestTo("http://aem.test" + PROBE_PATH))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, probe(f.client())).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("httpStatus", 200);
        assertThat(health.getDetails()).containsKey("latencyMs");
        assertThat(health.getDetails()).containsEntry("probePath", PROBE_PATH);
        assertThat(health.getDetails()).doesNotContainKey("category");
    }

    @Test
    void reports_down_unauthorized_on_401() {
        Fixture f = buildClient();
        f.server().expect(requestTo("http://aem.test" + PROBE_PATH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, probe(f.client())).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("category", "unauthorized");
        assertThat(health.getDetails()).containsEntry("httpStatus", 401);
    }

    @Test
    void reports_down_forbidden_on_403() {
        Fixture f = buildClient();
        f.server().expect(requestTo("http://aem.test" + PROBE_PATH))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, probe(f.client())).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("category", "forbidden");
        assertThat(health.getDetails()).containsEntry("httpStatus", 403);
    }

    @Test
    void reports_down_not_found_on_404() {
        Fixture f = buildClient();
        f.server().expect(requestTo("http://aem.test" + PROBE_PATH))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, probe(f.client())).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("category", "not_found");
        assertThat(health.getDetails()).containsEntry("httpStatus", 404);
    }

    @Test
    void reports_down_aem_server_error_on_502() {
        Fixture f = buildClient();
        f.server().expect(requestTo("http://aem.test" + PROBE_PATH))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, probe(f.client())).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("category", "aem_server_error");
        assertThat(health.getDetails()).containsEntry("httpStatus", 502);
    }

    @Test
    void reports_down_unreachable_on_connect_exception() {
        Supplier<ResponseEntity<Void>> failing = () -> {
            throw new ResourceAccessException("boom", new ConnectException("connection refused"));
        };

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, failing).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("category", "unreachable");
        assertThat(health.getDetails()).containsEntry("probePath", PROBE_PATH);
        assertThat(health.getDetails()).doesNotContainKey("httpStatus");
    }

    @Test
    void reports_down_unreachable_on_unknown_host() {
        Supplier<ResponseEntity<Void>> failing = () -> {
            throw new ResourceAccessException("boom", new UnknownHostException("nope.invalid"));
        };

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, failing).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("category", "unreachable");
    }

    @Test
    void reports_down_timeout_on_socket_timeout() {
        Supplier<ResponseEntity<Void>> failing = () -> {
            throw new ResourceAccessException("boom", new SocketTimeoutException("read timed out"));
        };

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, failing).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("category", "timeout");
    }

    @Test
    void logs_underlying_cause_so_ssl_handshake_failures_are_diagnosable() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AemToolHealthIndicator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
        try {
            Supplier<ResponseEntity<Void>> failing = () -> {
                throw new ResourceAccessException("I/O error",
                        new SSLHandshakeException("PKIX path building failed: unable to find valid "
                                + "certification path to requested target"));
            };

            new AemToolHealthIndicator("searchContent", PROBE_PATH, failing).health();

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        String msg = event.getFormattedMessage();
                        assertThat(msg).contains("searchContent");
                        assertThat(msg).contains("unreachable");
                        assertThat(msg).contains("SSLHandshakeException");
                        assertThat(msg).contains("PKIX path building failed");
                    });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void getToolName_returnsConfiguredName() {
        AemToolHealthIndicator indicator = new AemToolHealthIndicator("searchContent", PROBE_PATH, () -> null);
        assertThat(indicator.getToolName()).isEqualTo("searchContent");
    }

    @Test
    void omits_probePath_detail_when_null_on_success() {
        Fixture f = buildClient();
        f.server().expect(requestTo("http://aem.test" + PROBE_PATH))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Health health = new AemToolHealthIndicator("tool", null, probe(f.client())).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).doesNotContainKey("probePath");
    }

    @Test
    void omits_probePath_detail_when_null_on_http_error() {
        Fixture f = buildClient();
        f.server().expect(requestTo("http://aem.test" + PROBE_PATH))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Health health = new AemToolHealthIndicator("tool", null, probe(f.client())).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).doesNotContainKey("probePath");
    }

    @Test
    void omits_probePath_and_handles_null_cause_on_resource_access_error() {
        // ResourceAccessException with no cause -> exercises the (cause != null ? cause : e) branch
        // and the probePath == null branch together.
        Supplier<ResponseEntity<Void>> failing = () -> {
            throw new ResourceAccessException("I/O error, no cause");
        };

        Health health = new AemToolHealthIndicator("tool", null, failing).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("category", "unreachable");
        assertThat(health.getDetails()).doesNotContainKey("probePath");
    }

    @Test
    void reports_unknown_disabled_when_supplier_returns_null() {
        Supplier<ResponseEntity<Void>> disabled = () -> null;

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, disabled).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("category", "disabled_by_config");
        assertThat(health.getDetails()).doesNotContainKey("httpStatus");
    }

    @Test
    void details_never_contain_base_url_or_username() {
        Fixture f = buildClient();
        f.server().expect(requestTo("http://aem.test" + PROBE_PATH))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Health health = new AemToolHealthIndicator("tool", PROBE_PATH, probe(f.client())).health();

        assertThat(health.getDetails().keySet())
                .doesNotContain("baseUrl", "username", "password", "url");
    }
}
