package com.occpl.platform.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlRulesTest {

    @Test
    void acceptsHttpsAndLocalHttp() {
        assertThat(UrlRules.check("appUrl", "https://inventory.occpl.com/app")).isEmpty();
        assertThat(UrlRules.check("appUrl", "http://localhost:4300")).isEmpty();
        assertThat(UrlRules.check("appUrl", "http://inventory.localhost")).isEmpty();
        assertThat(UrlRules.check("apiBaseUrl", null)).isEmpty();
    }

    @Test
    void rejectsUnsafeUrls() {
        assertThat(UrlRules.check("appUrl", "http://inventory.occpl.com")).isPresent();
        assertThat(UrlRules.check("appUrl", "https://user:pass@inventory.occpl.com")).isPresent();
        assertThat(UrlRules.check("appUrl", "https://inventory.occpl.com/?x=1")).isPresent();
        assertThat(UrlRules.check("appUrl", "/relative")).isPresent();
        assertThat(UrlRules.check("appUrl", "javascript:alert(1)")).isPresent();
        assertThat(UrlRules.check("appUrl", "ftp://files.occpl.com")).isPresent();
    }

    @Test
    void normalizesTrailingSlashes() {
        assertThat(UrlRules.normalize(" https://x.occpl.com/app/ ")).isEqualTo("https://x.occpl.com/app");
    }
}
