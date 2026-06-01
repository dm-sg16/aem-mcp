package com.adobe.mcp.config;

import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the generic Spring Boot "must not be blank" binding failure for the AEM service-account
 * credentials into an actionable startup message that names where the values come from (the
 * Docker Compose secrets file vs. the env-var fallback). Mirrors the fail-fast contract of
 * {@link BearerTokenFilter}: the server still refuses to start without credentials, but now it
 * tells the operator exactly how to supply them instead of crash-looping on a cryptic
 * "Update your application's configuration".
 *
 * <p>Registered via {@code META-INF/spring.factories}. Runs ahead of Spring Boot's built-in
 * {@code BindValidationFailureAnalyzer} (HIGHEST_PRECEDENCE) but defers — returns {@code null} —
 * for any binding failure that isn't about {@code aem.username} / {@code aem.password}, so the
 * default diagnostics still apply to every other property.
 */
public class AemCredentialsFailureAnalyzer extends AbstractFailureAnalyzer<BindValidationException>
        implements Ordered {

    private static final Set<String> CREDENTIAL_FIELDS = Set.of("username", "password");

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, BindValidationException cause) {
        return analysisFor(blankCredentialFields(cause.getValidationErrors().getAllErrors()), rootFailure);
    }

    /** Field names ("aem.username" / "aem.password") that failed validation, in declaration order. */
    static Set<String> blankCredentialFields(List<? extends ObjectError> errors) {
        Set<String> blank = new LinkedHashSet<>();
        for (ObjectError error : errors) {
            if (error instanceof FieldError fieldError && CREDENTIAL_FIELDS.contains(fieldError.getField())) {
                blank.add("aem." + fieldError.getField());
            }
        }
        return blank;
    }

    /**
     * Builds the actionable failure analysis, or {@code null} when no credential field is at fault
     * so the next (built-in) analyzer can handle the binding error instead.
     */
    static FailureAnalysis analysisFor(Set<String> blankCredentials, Throwable rootFailure) {
        if (blankCredentials.isEmpty()) {
            return null;
        }

        String description = "The AEM service-account credentials are missing: "
                + String.join(", ", blankCredentials) + " resolved to an empty value. "
                + "The server refuses to start without them.";

        String action = """
                Supply the AEM service-account credentials, then restart:

                  - Docker Compose (recommended): set aem.username and aem.password in
                    secrets/aem-mcp-secrets.properties. Copy it from
                    secrets/aem-mcp-secrets.properties.example, fill in real values, then
                    `chmod 600 secrets/aem-mcp-secrets.properties`. Compose mounts it at
                    /run/secrets/aem-mcp-secrets.properties and Spring Boot imports it.

                  - java -jar / mvn spring-boot:run: export the AEM_USERNAME and AEM_PASSWORD
                    environment variables.

                Use a read-only AEM service account — never `admin`.""";

        return new FailureAnalysis(description, action, rootFailure);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
