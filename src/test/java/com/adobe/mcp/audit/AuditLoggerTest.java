package com.adobe.mcp.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLoggerTest {

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AEM_MCP_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        return appender;
    }

    @Test
    void record_withParamsAndCaller_logsOnceAndClearsMdc() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        Map<String, Object> params = new HashMap<>();
        params.put("path", "/content/public");
        params.put("nullValue", null);

        new AuditLogger().record("searchContent", "alice", params);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMessage()).isEqualTo("aem.mcp.tool.invoked");
        // MDC fully cleared after the call.
        assertThat(MDC.get("tool")).isNull();
        assertThat(MDC.get("caller")).isNull();
        assertThat(MDC.get("param.path")).isNull();
        assertThat(MDC.get("param.nullValue")).isNull();
    }

    @Test
    void record_withNullCallerAndNullParams() {
        ListAppender<ILoggingEvent> appender = attachAppender();

        new AuditLogger().record("bundleHealth", null, null);

        assertThat(appender.list).hasSize(1);
        assertThat(MDC.get("caller")).isNull();
    }
}
