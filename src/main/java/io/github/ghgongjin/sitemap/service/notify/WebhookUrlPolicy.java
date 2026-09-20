package io.github.ghgongjin.sitemap.service.notify;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.function.Function;

/**
 * @ClassName WebhookUrlPolicy
 * @Description Webhook 出站地址安全策略。与 CrawlUrlPolicy 语义不同、刻意独立：
 *              云元数据/链路本地/组播/未指定地址无条件拒绝；回环与私网由 allowPrivateNetwork
 *              控制（默认 true——自托管部署挂本机接收端是主场景，UI 有警示）。
 *              CrawlUrlPolicy 的爬取红线不受此处影响，两者互不引用。
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public class WebhookUrlPolicy {

    public enum WebhookUrlCheck {
        OK, MALFORMED, FORBIDDEN_SCHEME, HAS_CREDENTIALS, DNS_FAILED, DENIED_ALWAYS, DENIED_PRIVATE
    }

    private final boolean allowPrivateNetwork;
    private final Function<String, InetAddress[]> resolver;

    public WebhookUrlPolicy(boolean allowPrivateNetwork) {
        this(allowPrivateNetwork, host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (Exception e) {
                throw new IllegalStateException("DNS failed: " + host);
            }
        });
    }

    WebhookUrlPolicy(boolean allowPrivateNetwork, Function<String, InetAddress[]> resolver) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.resolver = resolver;
    }

    /**
     * 保存路径：不抛异常，返回可本地化的检查结论
     */
    public WebhookUrlCheck check(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? null : url.trim());
        } catch (URISyntaxException | NullPointerException e) {
            return WebhookUrlCheck.MALFORMED;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return WebhookUrlCheck.MALFORMED;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return WebhookUrlCheck.FORBIDDEN_SCHEME;
        }
        if (uri.getUserInfo() != null) {
            return WebhookUrlCheck.HAS_CREDENTIALS;
        }
        InetAddress[] resolved;
        try {
            resolved = resolver.apply(uri.getHost());
        } catch (Exception e) {
            return WebhookUrlCheck.DNS_FAILED;
        }
        if (resolved == null || resolved.length == 0) {
            return WebhookUrlCheck.DNS_FAILED;
        }
        for (InetAddress address : resolved) {
            if (deniedAlways(address)) {
                return WebhookUrlCheck.DENIED_ALWAYS;
            }
            if (!allowPrivateNetwork && privateAddress(address)) {
                return WebhookUrlCheck.DENIED_PRIVATE;
            }
        }
        return WebhookUrlCheck.OK;
    }

    /**
     * 发送路径：违规抛 SecurityException（消息仅进日志）
     */
    public URI validate(String url) {
        WebhookUrlCheck check = check(url);
        if (check != WebhookUrlCheck.OK) {
            throw new SecurityException("webhook url rejected: " + check);
        }
        return URI.create(url.trim());
    }

    /** 云元数据/链路本地/组播/未指定：任何开关下都拒绝 */
    private boolean deniedAlways(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            return (ipv4.getAddress()[0] & 0xFF) == 169 && (ipv4.getAddress()[1] & 0xFF) == 254;
        }
        return false;
    }

    /** 回环 + 站点本地 + CGN/基准测试 + IPv6 ULA；仅 allowPrivateNetwork=false 时拒绝 */
    private boolean privateAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            int first = ipv4.getAddress()[0] & 0xFF;
            int second = ipv4.getAddress()[1] & 0xFF;
            return (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19));
        }
        byte[] bytes = address.getAddress();
        return bytes != null && bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
