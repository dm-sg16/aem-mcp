package com.adobe.mcp.config;

import com.adobe.mcp.aem.AemProperties;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AemCredentialsFailureAnalyzerTest {

    @Test
    void getOrder_isHighestPrecedence() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE, new AemCredentialsFailureAnalyzer().getOrder());
    }

    @Test
    void analyze_onRealBindValidationException_producesCredentialsAnalysis() {
        // Drive a real validated bind so the analyzer's analyze(Throwable) path runs end to end.
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("aem.base-url", "http://aem.test");
        source.put("aem.username", "");   // blank -> @NotBlank failure
        source.put("aem.password", "");   // blank -> @NotBlank failure
        source.put("aem.allowed-path-prefixes[0]", "/content/public");

        SpringValidatorAdapter validator = new SpringValidatorAdapter(
                Validation.buildDefaultValidatorFactory().getValidator());
        ValidationBindHandler handler = new ValidationBindHandler(validator);

        Throwable thrown = catchThrowable(() ->
                new Binder(source).bind("aem", Bindable.of(AemProperties.class), handler));
        assertNotNull(thrown);

        FailureAnalysis analysis = new AemCredentialsFailureAnalyzer().analyze(thrown);

        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("aem.username"));
        assertTrue(analysis.getDescription().contains("aem.password"));
        assertTrue(analysis.getAction().contains("secrets/aem-mcp-secrets.properties"));
    }

    @Test
    void picksOutBlankUsernameAndPasswordInDeclarationOrder() {
        List<ObjectError> errors = List.of(
                new FieldError("aem", "username", "must not be blank"),
                new FieldError("aem", "password", "must not be blank"));

        Set<String> blank = AemCredentialsFailureAnalyzer.blankCredentialFields(errors);

        assertEquals(List.of("aem.username", "aem.password"), List.copyOf(blank));
    }

    @Test
    void ignoresNonCredentialFieldsAndGlobalErrors() {
        List<ObjectError> errors = List.of(
                new FieldError("aem", "baseUrl", "must not be blank"),
                new ObjectError("aem", "some global validation error"));

        assertTrue(AemCredentialsFailureAnalyzer.blankCredentialFields(errors).isEmpty());
    }

    @Test
    void defersWhenNoCredentialFieldIsAtFault() {
        assertNull(AemCredentialsFailureAnalyzer.analysisFor(Set.of(), new RuntimeException("boom")));
    }

    @Test
    void producesActionableMessageNamingSecretsFileAndEnvVars() {
        RuntimeException root = new RuntimeException("boom");

        FailureAnalysis analysis = AemCredentialsFailureAnalyzer.analysisFor(
                Set.of("aem.username", "aem.password"), root);

        assertNotNull(analysis);
        assertEquals(root, analysis.getCause());
        assertTrue(analysis.getDescription().contains("aem.username"));
        assertTrue(analysis.getDescription().contains("aem.password"));
        assertTrue(analysis.getAction().contains("secrets/aem-mcp-secrets.properties"));
        assertTrue(analysis.getAction().contains("AEM_USERNAME"));
        assertTrue(analysis.getAction().contains("AEM_PASSWORD"));
    }
}
