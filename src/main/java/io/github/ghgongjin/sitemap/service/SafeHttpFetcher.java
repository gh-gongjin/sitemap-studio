package io.github.ghgongjin.sitemap.service;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SafeHttpFetcher {

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final CrawlUrlPolicy policy;

    public SafeHttpFetcher() {
        this(new CrawlUrlPolicy());
    }

    public SafeHttpFetcher(CrawlUrlPolicy policy) {
        this.policy = policy;
    }

    public Response fetch(String url, String scopeBase, int timeoutMs, ProxyPool.ProxyInfo proxy) throws IOException {
        URI current = policy.validate(url, scopeBase);
        Set<String> seen = new HashSet<>();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!seen.add(current.toString())) {
                throw new IOException("检测到重定向循环: " + current);
            }
            FetchHop hopResult = executeOnce(current, timeoutMs, proxy);
            if (hopResult.statusCode() >= 300 && hopResult.statusCode() <= 399) {
                String location = hopResult.headers().get("location");
                if (location == null || location.isBlank()) {
                    throw new IOException("重定向缺少 Location: " + current);
                }
                current = policy.validate(current.resolve(location).toString(), scopeBase);
                continue;
            }
            return new Response(current.toString(), hopResult.statusCode(), hopResult.contentType(),
                    hopResult.headers(), hopResult.body());
        }
        throw new IOException("重定向次数超过上限: " + url);
    }

    private FetchHop executeOnce(URI original, int timeoutMs, ProxyPool.ProxyInfo proxy) throws IOException {
        InetAddress[] addresses = policy.resolvePublicAddresses(original.getHost());
        URI ipUri = replaceHost(original, addresses[0].getHostAddress());
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.of(timeoutMs, TimeUnit.MILLISECONDS))
                .setResponseTimeout(Timeout.of(timeoutMs, TimeUnit.MILLISECONDS))
                .setRedirectsEnabled(false)
                .setCircularRedirectsAllowed(false)
                .build();
        HttpGet request = new HttpGet(ipUri);
        request.setConfig(config);
        request.setHeader("Host", hostHeader(original));
        request.setHeader("User-Agent", USER_AGENT);
        request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        try (CloseableHttpClient client = buildClient(original, timeoutMs, proxy);
             ClassicHttpResponse httpResponse = client.executeOpen(null, request, null)) {
            byte[] body = httpResponse.getEntity() == null ? new byte[0]
                    : EntityUtils.toByteArray(httpResponse.getEntity(), MAX_BODY_BYTES);
            if (body.length > MAX_BODY_BYTES) {
                throw new IOException("响应体超过限制");
            }
            Map<String, String> headers = new HashMap<>();
            for (Header header : httpResponse.getHeaders()) {
                String name = header.getName().toLowerCase(Locale.ROOT);
                if (name.equals("content-encoding") || name.equals("transfer-encoding") || name.equals("content-length")) {
                    continue;
                }
                headers.put(name, header.getValue());
            }
            String contentType = headers.getOrDefault("content-type", "");
            return new FetchHop(httpResponse.getCode(), contentType, headers, body);
        }
    }

    private CloseableHttpClient buildClient(URI original, int timeoutMs, ProxyPool.ProxyInfo proxy) {
        var tls = DefaultClientTlsStrategy.createSystemDefault();
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy((socket, target, port, attachment, context) ->
                        tls.upgrade(socket, original.getHost(), original.getPort() == -1 ? port : original.getPort(),
                                attachment, context))
                .build();
        var builder = HttpClients.custom()
                .setConnectionManager(manager)
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.of(timeoutMs, TimeUnit.MILLISECONDS))
                        .setResponseTimeout(Timeout.of(timeoutMs, TimeUnit.MILLISECONDS))
                        .setRedirectsEnabled(false)
                        .build());
        if (proxy != null) {
            builder.setProxy(new HttpHost(proxy.getHost(), proxy.getPort()));
            if (proxy.hasAuth()) {
                BasicCredentialsProvider credentials = new BasicCredentialsProvider();
                credentials.setCredentials(new AuthScope(proxy.getHost(), proxy.getPort()),
                        new UsernamePasswordCredentials(proxy.getUsername(),
                                proxy.getPassword() == null ? new char[0] : proxy.getPassword().toCharArray()));
                builder.setDefaultCredentialsProvider(credentials);
            }
        }
        return builder.build();
    }

    private URI replaceHost(URI original, String ip) {
        try {
            int port = original.getPort();
            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(original.getScheme()).append("://");
            if (ip.contains(":")) {
                rebuilt.append('[').append(ip).append(']');
            } else {
                rebuilt.append(ip);
            }
            if (port != -1) {
                rebuilt.append(':').append(port);
            }
            rebuilt.append(original.getRawPath() == null || original.getRawPath().isEmpty() ? "/" : original.getRawPath());
            if (original.getRawQuery() != null) {
                rebuilt.append('?').append(original.getRawQuery());
            }
            return URI.create(rebuilt.toString());
        } catch (Exception e) {
            throw new SecurityException("无法构造固定 IP 请求: " + original);
        }
    }

    private String hostHeader(URI original) {
        if (original.getPort() == -1) {
            return original.getHost();
        }
        return original.getHost() + ":" + original.getPort();
    }

    public record Response(String url, int statusCode, String contentType, Map<String, String> headers, byte[] body) {
        public Document parse() throws IOException {
            String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            Parser parser = type.contains("xml") ? Parser.xmlParser() : Parser.htmlParser();
            return Jsoup.parse(new ByteArrayInputStream(body == null ? new byte[0] : body),
                    StandardCharsets.UTF_8.name(), url, parser);
        }

        public String text() {
            return new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        }
    }

    private record FetchHop(int statusCode, String contentType, Map<String, String> headers, byte[] body) {}
}
