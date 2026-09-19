package io.github.ghgongjin.sitemap.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.ghgongjin.sitemap.config.NotifyProperties;
import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName WebhookSenderTest
 * @Description webhook 通道：URL 策略拦截、JSON 载荷、HMAC 签名头、失败重试 1 次
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class WebhookSenderTest {

    record Call(String url, String body, String signature, int timeoutMs) {}

    static class FakeTransport implements WebhookSender.Transport {
        final List<Call> calls = new ArrayList<>();
        int failuresBeforeSuccess;
        RuntimeException error = new RuntimeException("connection reset");

        @Override
        public void postJson(String url, String body, String signature, int timeoutMs) throws Exception {
            calls.add(new Call(url, body, signature, timeoutMs));
            if (calls.size() <= failuresBeforeSuccess) {
                throw error;
            }
        }
    }

    @TempDir
    Path tmp;

    private WebhookSender sender(FakeTransport transport, boolean allowPrivate) {
        NotifyProperties props = new NotifyProperties();
        props.setAllowPrivateNetwork(allowPrivate);
        CredentialCipher cipher = new CredentialCipher("", tmp.resolve("push.key").toString());
        return new WebhookSender(new WebhookUrlPolicy(allowPrivate, host -> new InetAddress[]{loopback()}),
                props, cipher, new ObjectMapper(), transport);
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AutoSite siteWithWebhook(String secretEnc) {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");
        site.setNotifyWebhookUrl("http://n8n.internal:5678/webhook");
        site.setNotifyWebhookSecretEnc(secretEnc);
        return site;
    }

    private static NotificationPayload payload() {
        SiteUpdatedEvent event = new SiteUpdatedEvent(1L, 2L, 2, 3, 1, 2, 5, false);
        return NotificationPayload.ofChanged(siteWithWebhook(null), event);
    }

    @Test
    void shouldSendSignedJsonWhenSecretConfigured() throws Exception {
        // Given：secret 明文 "s3cr3t" 经 CredentialCipher 加密
        CredentialCipher cipher = new CredentialCipher("", tmp.resolve("push.key").toString());
        FakeTransport transport = new FakeTransport();
        WebhookSender sender = sender(transport, true);
        AutoSite site = siteWithWebhook(cipher.encrypt("s3cr3t"));

        assertThat(sender.send(site, payload())).isTrue();

        Call call = transport.calls.get(0);
        assertThat(call.body()).contains("\"type\":\"CHANGED\"").contains("\"added\":3");
        String expected = "sha256=" + hmacHex("s3cr3t", call.body());
        assertThat(call.signature()).isEqualTo(expected);
        assertThat(call.timeoutMs()).isEqualTo(10000);
    }

    @Test
    void shouldOmitSignatureHeaderWhenNoSecret() {
        FakeTransport transport = new FakeTransport();
        WebhookSender sender = sender(transport, true);

        assertThat(sender.send(siteWithWebhook(null), payload())).isTrue();
        assertThat(transport.calls.get(0).signature()).isNull();
    }

    @Test
    void shouldRetryOnceThenSucceedAndGiveUpAfterSecondFailure() {
        FakeTransport ok = new FakeTransport();
        ok.failuresBeforeSuccess = 1;
        assertThat(sender(ok, true).send(siteWithWebhook(null), payload())).isTrue();
        assertThat(ok.calls).hasSize(2);

        FakeTransport dead = new FakeTransport();
        dead.failuresBeforeSuccess = 5;
        assertThat(sender(dead, true).send(siteWithWebhook(null), payload())).isFalse();
        assertThat(dead.calls).hasSize(2);
    }

    @Test
    void shouldRefuseToSendWhenPolicyRejectsUrl() {
        FakeTransport transport = new FakeTransport();
        WebhookSender sender = sender(transport, false);   // 私网关闭，站点配的是内网地址

        assertThat(sender.send(siteWithWebhook(null), payload())).isFalse();
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void shouldFailQuietlyWhenSecretUndecryptable() {
        FakeTransport transport = new FakeTransport();
        WebhookSender sender = sender(transport, true);

        assertThat(sender.send(siteWithWebhook("garbage-not-a-ciphertext"), payload())).isFalse();
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void shouldMatchRfc4231VectorWhenHmac() throws Exception {
        // RFC 4231 Test Case 2：key="Jefe"，data="what do ya want for nothing?" 的官方 HMAC-SHA256 结果，
        // 证明 hmacSha256Hex 并非"自洽的克隆算法"，而是标准算法本身
        assertThat(WebhookSender.hmacSha256Hex("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void shouldSignExactlyWireBytesWhenSentViaRealHttpTransport() throws Exception {
        // Given：本机 127.0.0.1 临时端口真实 HTTP 服务器，捕获原始请求体字节与签名头
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<byte[]> bodyRef = new AtomicReference<>();
        AtomicReference<String> signatureRef = new AtomicReference<>();
        server.createContext("/hook", exchange -> {
            bodyRef.set(exchange.getRequestBody().readAllBytes());
            signatureRef.set(exchange.getRequestHeaders().getFirst("X-Sitemap-Signature"));
            byte[] ok = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            String secret = "s3cr3t";
            CredentialCipher cipher = new CredentialCipher("", tmp.resolve("push.key").toString());
            NotifyProperties props = new NotifyProperties();
            props.setAllowPrivateNetwork(true);
            // 真实生产传输（RestClientTransport，非 Fake）；URL 策略走既有包私有 resolver 缝解析 127.0.0.1
            WebhookSender sender = new WebhookSender(
                    new WebhookUrlPolicy(true, host -> new InetAddress[]{loopback()}),
                    props, cipher, new ObjectMapper(), new WebhookSender.RestClientTransport());
            AutoSite site = siteWithWebhook(cipher.encrypt(secret));
            site.setNotifyWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");

            // When
            assertThat(sender.send(site, payload())).isTrue();
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();

            // Then：服务端用独立 javax.crypto 对"实际收到的原始字节"重算 HMAC——签名字节 == 线上传输字节
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(bodyRef.get()));
            assertThat(signatureRef.get()).isEqualTo(expected);
            String body = new String(bodyRef.get(), StandardCharsets.UTF_8);
            assertThat(new ObjectMapper().readTree(body).get("type").asText()).isEqualTo("CHANGED");
            assertThat(body).contains("\"added\":3");
        } finally {
            server.stop(0);
        }
    }

    private static String hmacHex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
