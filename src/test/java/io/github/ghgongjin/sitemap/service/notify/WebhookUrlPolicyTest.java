package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.service.notify.WebhookUrlPolicy.WebhookUrlCheck;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName WebhookUrlPolicyTest
 * @Description webhook 地址策略：仅 http(s)、禁凭据、元数据/链路本地无条件拒绝、私网按开关两态
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class WebhookUrlPolicyTest {

    private static InetAddress[] ip(String literal) {
        try {
            return new InetAddress[]{InetAddress.getByName(literal)};
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static WebhookUrlPolicy allowingPrivate(String host, String... addresses) {
        return policy(true, host, addresses);
    }

    private static WebhookUrlPolicy policy(boolean allowPrivate, String host, String... addresses) {
        return new WebhookUrlPolicy(allowPrivate, h -> {
            if (!h.equals(host)) {
                throw new SecurityException("unexpected host");
            }
            return ip(addresses.length == 1 ? addresses[0] : addresses[0]);
        });
    }

    @Test
    void shouldAllowPublicHttps() {
        assertThat(allowingPrivate("example.com", "93.184.216.34")
                .check("https://example.com/hook")).isEqualTo(WebhookUrlCheck.OK);
    }

    @Test
    void shouldRejectNonHttpSchemeAndMalformedAndBlank() {
        WebhookUrlPolicy p = allowingPrivate("example.com", "93.184.216.34");
        assertThat(p.check("ftp://example.com/x")).isEqualTo(WebhookUrlCheck.FORBIDDEN_SCHEME);
        assertThat(p.check("not a url")).isEqualTo(WebhookUrlCheck.MALFORMED);
        assertThat(p.check(null)).isEqualTo(WebhookUrlCheck.MALFORMED);
        assertThat(p.check("")).isEqualTo(WebhookUrlCheck.MALFORMED);
    }

    @Test
    void shouldRejectUrlWithCredentials() {
        assertThat(allowingPrivate("example.com", "93.184.216.34")
                .check("https://user:pw@example.com/hook")).isEqualTo(WebhookUrlCheck.HAS_CREDENTIALS);
    }

    @Test
    void shouldDenyMetadataAndLinkLocalRegardlessOfPrivateSwitch() {
        // 169.254.0.0/16 链路本地/云元数据：allowPrivate=true 也拒绝
        assertThat(allowingPrivate("meta.example.com", "169.254.169.254")
                .check("http://meta.example.com/latest/user-data")).isEqualTo(WebhookUrlCheck.DENIED_ALWAYS);
        assertThat(policy(false, "meta.example.com", "169.254.169.254")
                .check("http://meta.example.com/x")).isEqualTo(WebhookUrlCheck.DENIED_ALWAYS);
    }

    @Test
    void shouldAllowLoopbackAndSiteLocalWhenPrivateAllowed() {
        assertThat(allowingPrivate("n8n.internal", "127.0.0.1")
                .check("http://n8n.internal:5678/webhook")).isEqualTo(WebhookUrlCheck.OK);
        assertThat(allowingPrivate("lan", "192.168.1.50")
                .check("http://lan/webhook")).isEqualTo(WebhookUrlCheck.OK);
        assertThat(allowingPrivate("lan6", "fec0::1")
                .check("http://lan6/webhook")).isEqualTo(WebhookUrlCheck.OK);
    }

    @Test
    void shouldDenyLoopbackAndSiteLocalWhenPrivateDisabled() {
        assertThat(policy(false, "n8n.internal", "127.0.0.1")
                .check("http://n8n.internal/webhook")).isEqualTo(WebhookUrlCheck.DENIED_PRIVATE);
        assertThat(policy(false, "lan", "10.0.0.8")
                .check("http://lan/webhook")).isEqualTo(WebhookUrlCheck.DENIED_PRIVATE);
    }

    @Test
    void shouldDenyWhenAnyResolvedAddressIsBlocked() {
        // 混合解析（公网+内网）在两种开关下都不可发送：私网开关开→按 OK 处理首个？否——逐地址判定，任一私网且开关关才 DENIED_PRIVATE；
        // 开关开时任一 DENIED_ALWAYS 即拒。混合公网+内网+allowPrivate=false：
        WebhookUrlPolicy p = new WebhookUrlPolicy(false, h -> new InetAddress[]{
                ip("93.184.216.34")[0], ip("192.168.0.1")[0]});
        assertThat(p.check("http://mixed.example/hook")).isEqualTo(WebhookUrlCheck.DENIED_PRIVATE);
    }

    @Test
    void shouldReportDnsFailure() {
        WebhookUrlPolicy p = new WebhookUrlPolicy(true, h -> {
            throw new RuntimeException("dns down");
        });
        assertThat(p.check("http://nonexistent.invalid/hook")).isEqualTo(WebhookUrlCheck.DNS_FAILED);
    }

    @Test
    void validateShouldThrowSecurityExceptionOnRejection() {
        // allowPrivate=true：internal→127.0.0.1 属合法回环，validate 正常返回 URI
        WebhookUrlPolicy p = allowingPrivate("internal", "127.0.0.1");
        assertThat(p.validate("http://internal/hook").getHost()).isEqualTo("internal");
        // 非 internal 主机走 resolver 抛异常分支 → DNS_FAILED → validate 包装为 SecurityException
        assertThatThrownBy(() -> p.validate("http://nope.invalid"))
                .isInstanceOf(SecurityException.class);
    }
}
