package com.adobe.mcp.aem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AemPropertiesTest {

    @Test
    void gettersAndSettersRoundTrip() {
        AemProperties p = new AemProperties();
        p.setBaseUrl("http://aem.test");
        p.setContextRoot("/WC2");
        p.setUsername("svc");
        p.setPassword("secret");
        p.setAllowedPathPrefixes(List.of("/content/a", "/content/b"));
        p.setDefaultLimit(15);
        p.setMaxLimit(50);
        p.setMaxDepth(4);
        p.setBundleHealthEnabled(true);

        AemProperties.Health health = new AemProperties.Health();
        health.setInspectNodePath("/content/a");
        p.setHealth(health);

        assertThat(p.getBaseUrl()).isEqualTo("http://aem.test");
        assertThat(p.getContextRoot()).isEqualTo("/WC2");
        assertThat(p.getUsername()).isEqualTo("svc");
        assertThat(p.getPassword()).isEqualTo("secret");
        assertThat(p.getAllowedPathPrefixes()).containsExactly("/content/a", "/content/b");
        assertThat(p.getDefaultLimit()).isEqualTo(15);
        assertThat(p.getMaxLimit()).isEqualTo(50);
        assertThat(p.getMaxDepth()).isEqualTo(4);
        assertThat(p.isBundleHealthEnabled()).isTrue();
        assertThat(p.getHealth()).isSameAs(health);
        assertThat(p.getHealth().getInspectNodePath()).isEqualTo("/content/a");
    }

    @Test
    void defaults() {
        AemProperties p = new AemProperties();
        assertThat(p.getContextRoot()).isEmpty();
        assertThat(p.getDefaultLimit()).isEqualTo(20);
        assertThat(p.getMaxLimit()).isEqualTo(100);
        assertThat(p.getMaxDepth()).isEqualTo(3);
        assertThat(p.isBundleHealthEnabled()).isFalse();
        assertThat(p.getHealth()).isNotNull();
        assertThat(p.getHealth().getInspectNodePath()).isNull();
    }

    @Test
    void setContextRoot_normalisesNullToEmpty() {
        AemProperties p = new AemProperties();
        p.setContextRoot(null);
        assertThat(p.getContextRoot()).isEmpty();
    }

    private static AemProperties consistentBase() {
        AemProperties p = new AemProperties();
        p.setAllowedPathPrefixes(List.of("/content/public"));
        p.setDefaultLimit(20);
        p.setMaxLimit(100);
        return p;
    }

    @Test
    void isConsistent_true_forValidConfig() {
        assertThat(consistentBase().isConsistent()).isTrue();
    }

    @Test
    void isConsistent_false_whenDefaultLimitExceedsMax() {
        AemProperties p = consistentBase();
        p.setDefaultLimit(200);
        p.setMaxLimit(100);
        assertThat(p.isConsistent()).isFalse();
    }

    @Test
    void isConsistent_false_whenAllowedPrefixesNull() {
        AemProperties p = consistentBase();
        p.setAllowedPathPrefixes(null);
        assertThat(p.isConsistent()).isFalse();
    }

    @Test
    void isConsistent_false_whenAllowedPrefixesEmpty() {
        AemProperties p = consistentBase();
        p.setAllowedPathPrefixes(List.of());
        assertThat(p.isConsistent()).isFalse();
    }

    @Test
    void isConsistent_false_whenPrefixDoesNotStartWithSlash() {
        AemProperties p = consistentBase();
        p.setAllowedPathPrefixes(List.of("content/public"));
        assertThat(p.isConsistent()).isFalse();
    }

    @Test
    void isConsistent_false_whenPrefixIsNull() {
        AemProperties p = consistentBase();
        p.setAllowedPathPrefixes(java.util.Arrays.asList("/content/public", null));
        assertThat(p.isConsistent()).isFalse();
    }

    @Test
    void isConsistent_false_whenContextRootMalformed() {
        AemProperties p = consistentBase();
        p.setContextRoot("/WC2/");
        assertThat(p.isConsistent()).isFalse();
    }

    @Test
    void isConsistent_true_withValidContextRoot() {
        AemProperties p = consistentBase();
        p.setContextRoot("/WC2");
        assertThat(p.isConsistent()).isTrue();
    }
}
