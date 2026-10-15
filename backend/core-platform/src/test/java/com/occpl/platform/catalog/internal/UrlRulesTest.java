package com.occpl.platform.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlRulesTest {

    @Test
    void acceptsHttps() {
        assertThat(UrlRules.check("appUrl", "https://inventory.occpl.com/app", false)).isEmpty();
        assertThat(UrlRules.check("apiBaseUrl", "https://10.0.4.12:8443", false)).isEmpty(); // private network service
        assertThat(UrlRules.check("apiBaseUrl", null, false)).isEmpty();
    }

    @Test
    void localHostsOnlyInDevelopment() {
        for (String url : new String[] {"http://localhost:4300", "http://inventory.localhost", "http://127.0.0.1:9000",
                "https://localhost", "https://127.0.0.1"}) {
            assertThat(UrlRules.check("apiBaseUrl", url, true)).as(url).isEmpty();
            assertThat(UrlRules.check("apiBaseUrl", url, false)).as(url).isPresent();
        }
    }

    @Test
    void rejectsUnsafeUrls() {
        for (boolean dev : new boolean[] {false, true}) {
            assertThat(UrlRules.check("appUrl", "http://inventory.occpl.com", dev)).isPresent();
            assertThat(UrlRules.check("appUrl", "https://user:pass@inventory.occpl.com", dev)).isPresent();
            assertThat(UrlRules.check("appUrl", "https://inventory.occpl.com/?x=1", dev)).isPresent();
            assertThat(UrlRules.check("appUrl", "/relative", dev)).isPresent();
            assertThat(UrlRules.check("appUrl", "javascript:alert(1)", dev)).isPresent();
            assertThat(UrlRules.check("appUrl", "ftp://files.occpl.com", dev)).isPresent();
            assertThat(UrlRules.check("apiBaseUrl", "http://169.254.169.254", dev)).isPresent();
        }
        assertThat(UrlRules.check("apiBaseUrl", "https://169.254.169.254/latest", false)).isPresent();
        assertThat(UrlRules.check("apiBaseUrl", "https://[::1]:8443", false)).isPresent();
        assertThat(UrlRules.check("apiBaseUrl", "https://[fe80::1]", false)).isPresent();
        assertThat(UrlRules.check("apiBaseUrl", "https://0.0.0.0", false)).isPresent();
    }

    @Test
    void normalizesTrailingSlashes() {
        assertThat(UrlRules.normalize(" https://x.occpl.com/app/ ")).isEqualTo("https://x.occpl.com/app");
    }
}
