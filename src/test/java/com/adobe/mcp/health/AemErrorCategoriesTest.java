package com.adobe.mcp.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

class AemErrorCategoriesTest {

    @ParameterizedTest
    @CsvSource({
            "401, unauthorized",
            "403, forbidden",
            "404, not_found",
            "408, timeout",
            "504, timeout",
            "500, aem_server_error",
            "502, aem_server_error",
            "503, aem_server_error",
            "418, aem_http_error",
            "429, aem_http_error"
    })
    void categoryForStatus(int status, String expected) {
        assertThat(AemErrorCategories.categoryForStatus(status)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({ "401", "403" })
    void hintForStatus_authErrors(int status) {
        assertThat(AemErrorCategories.hintForStatus(status)).contains("credentials");
    }

    @Test
    void hintForStatus_notFound() {
        assertThat(AemErrorCategories.hintForStatus(404)).contains("not found");
    }

    @ParameterizedTest
    @CsvSource({ "408", "504" })
    void hintForStatus_timeouts(int status) {
        assertThat(AemErrorCategories.hintForStatus(status)).contains("did not respond");
    }

    @ParameterizedTest
    @CsvSource({ "500", "502", "503" })
    void hintForStatus_serverErrors(int status) {
        assertThat(AemErrorCategories.hintForStatus(status)).contains("server error");
    }

    @Test
    void hintForStatus_default() {
        assertThat(AemErrorCategories.hintForStatus(418)).isEqualTo("AEM returned HTTP 418.");
    }

    @Test
    void privateConstructorIsInvocableForCoverage() throws Exception {
        Constructor<AemErrorCategories> ctor = AemErrorCategories.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
