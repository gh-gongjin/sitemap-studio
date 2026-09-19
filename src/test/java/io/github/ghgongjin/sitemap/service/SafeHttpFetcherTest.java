package io.github.ghgongjin.sitemap.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeHttpFetcherTest {

    private HttpServer server;
    private String origin;
    private CrawlUrlPolicy policy;
    private SafeHttpFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        origin = "http://example.test:" + server.getAddress().getPort();
        policy = new CrawlUrlPolicy(host -> {
            try {
                if ("other.test".equals(host)) {
                    return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                }
                return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException(e);
            }
        }) {
            @Override
            public InetAddress[] resolvePublicAddresses(String host) {
                try {
                    if ("other.test".equals(host)) {
                        throw new SecurityException("禁止跨域访问");
                    }
                    return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception e) {
                    throw new SecurityException(e);
                }
            }
        };
        fetcher = new SafeHttpFetcher(policy);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldFollowRelativeRedirectAndKeepFinalUrl() throws Exception {
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            byte[] body = "<html>ok</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SafeHttpFetcher.Response response = fetcher.fetch(origin + "/start", origin, 3000, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.url()).isEqualTo(origin + "/final");
        assertThat(response.text()).contains("ok");
    }

    @Test
    void shouldRejectRedirectOutsideScopeBeforeSecondHop() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/start", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://other.test/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> fetcher.fetch(origin + "/start", origin, 3000, null))
                .isInstanceOf(SecurityException.class);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void shouldDetectRedirectLoop() throws Exception {
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> fetcher.fetch(origin + "/loop", origin, 3000, null))
                .isInstanceOf(IOException.class);
    }
}
