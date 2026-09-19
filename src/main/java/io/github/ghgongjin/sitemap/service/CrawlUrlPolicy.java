package io.github.ghgongjin.sitemap.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class CrawlUrlPolicy {

    private final Function<String, InetAddress[]> resolver;

    public CrawlUrlPolicy() {
        this(host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new SecurityException("DNS 解析失败: " + host, e);
            }
        });
    }

    CrawlUrlPolicy(Function<String, InetAddress[]> resolver) {
        this.resolver = resolver;
    }

    public URI validate(String url) {
        return validate(url, null);
    }

    public URI validate(String url, String scopeBase) {
        if (url == null || url.isBlank()) {
            throw new SecurityException("禁止访问空 URL");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new SecurityException("非法 URL: " + url);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new SecurityException("URL 必须包含协议和主机: " + url);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SecurityException("只允许 http/https: " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new SecurityException("禁止带凭据的 URL: " + url);
        }
        resolvePublicAddresses(uri.getHost());
        if (scopeBase != null) {
            URI base;
            try {
                base = URI.create(scopeBase);
            } catch (Exception e) {
                throw new SecurityException("非法范围基准: " + scopeBase);
            }
            if (!sameHost(uri, base) || !compatiblePort(uri, base)) {
                throw new SecurityException("禁止跨域访问: " + url);
            }
        }
        return uri;
    }

    public boolean isAllowed(String url, String scopeBase) {
        try {
            validate(url, scopeBase);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    public InetAddress[] resolvePublicAddresses(String host) {
        if (host == null || host.isBlank()) {
            throw new SecurityException("主机名为空");
        }
        InetAddress[] resolved;
        try {
            resolved = resolver.apply(host);
        } catch (Exception e) {
            throw new SecurityException("DNS 解析失败: " + host);
        }
        if (resolved == null || resolved.length == 0) {
            throw new SecurityException("DNS 解析失败: " + host);
        }
        List<InetAddress> publicAddresses = new ArrayList<>();
        for (InetAddress address : resolved) {
            if (isPublic(address)) {
                publicAddresses.add(address);
            }
        }
        if (publicAddresses.size() != resolved.length) {
            throw new SecurityException("拒绝包含内网地址的主机: " + host);
        }
        return publicAddresses.toArray(InetAddress[]::new);
    }

    private boolean sameHost(URI uri, URI base) {
        return uri.getHost() != null && uri.getHost().equalsIgnoreCase(base.getHost());
    }

    private boolean compatiblePort(URI uri, URI base) {
        return effectivePort(uri) == effectivePort(base);
    }

    private int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address ipv4) {
            int first = ipv4.getAddress()[0] & 0xFF;
            int second = ipv4.getAddress()[1] & 0xFF;
            if (first == 0 || first == 127 || first == 10 || first == 100 && second >= 64 && second <= 127
                    || first == 169 && second == 254 || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168 || first == 198 && (second == 18 || second == 19)
                    || first >= 224) {
                return false;
            }
        }
        if (address instanceof Inet6Address ipv6) {
            byte[] bytes = ipv6.getAddress();
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0xfc || first == 0xfd || first == 0xfe && (second & 0xC0) == 0x80) {
                return false;
            }
            if (ipv6.isIPv4CompatibleAddress() || ipv6.getHostAddress().startsWith("::ffff:")) {
                return false;
            }
        }
        return true;
    }
}
