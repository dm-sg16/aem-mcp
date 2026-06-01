package com.adobe.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AemCredentialsFailureAnalyzerTest {

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
