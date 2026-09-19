package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrawlUrlPolicyTest {

    @Test
    void shouldAllowPublicHttpUrl() {
        CrawlUrlPolicy policy = publicPolicy();
        assertThat(policy.validate("https://example.com/page").getHost()).isEqualTo("example.com");
    }

    @Test
    void shouldRejectMixedPublicAndPrivateDns() {
        CrawlUrlPolicy policy = new CrawlUrlPolicy(host -> {
            try {
                return new InetAddress[]{
                        InetAddress.getByName("93.184.216.34"),
                        InetAddress.getByName("127.0.0.1")
                };
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException(e);
            }
        });
        assertThatThrownBy(() -> policy.validate("https://mixed.example/"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldRejectDnsFailure() {
        CrawlUrlPolicy policy = new CrawlUrlPolicy(host -> {
            throw new SecurityException(new java.net.UnknownHostException(host));
        });
        assertThatThrownBy(() -> policy.validate("https://missing.example/"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldRejectCredentialsAndNonHttp() {
        CrawlUrlPolicy policy = publicPolicy();
        assertThatThrownBy(() -> policy.validate("ftp://example.com/file")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> policy.validate("https://user:pass@example.com/")).isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldRejectLoopbackAndPrivateHosts() throws Exception {
        CrawlUrlPolicy policy = new CrawlUrlPolicy();
        assertThatThrownBy(() -> policy.validate("http://127.0.0.1/"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> policy.validate("http://192.168.1.1/admin"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> policy.validate("http://localhost/"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void shouldRejectCrossHostWhenScoped() {
        CrawlUrlPolicy policy = publicPolicy();
        assertThat(policy.isAllowed("https://other.example/", "https://example.com")).isFalse();
        assertThat(policy.isAllowed("https://example.com/path", "https://example.com")).isTrue();
    }

    private static CrawlUrlPolicy publicPolicy() {
        return new CrawlUrlPolicy(host -> {
            try {
                return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException(e);
            }
        });
    }
}
