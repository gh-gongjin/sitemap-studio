package io.github.ghgongjin.sitemap.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.ghgongjin.sitemap.config.NotifyProperties;
import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * @ClassName WebhookSender
 * @Description webhook 通道：出站前经 WebhookUrlPolicy 校验（SSRF 独立策略），
 *              JSON 载荷 + HMAC-SHA256 签名头（sha256=&lt;hex&gt;，未配 secret 则不带头），
 *              失败 sleep 2000ms 重试 1 次；任何异常只记日志、返回 false，不外抛
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Slf4j
@Component
public class WebhookSender implements NotifyChannel {

    private static final long RETRY_DELAY_MS = 2000L;

    private final WebhookUrlPolicy urlPolicy;
    private final NotifyProperties props;
    private final CredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final Transport transport;

    @Autowired
    public WebhookSender(WebhookUrlPolicy urlPolicy, NotifyProperties props,
                         CredentialCipher cipher, ObjectMapper objectMapper) {
        this(urlPolicy, props, cipher, objectMapper, new RestClientTransport());
    }

    /** 测试缝：末位注入 Transport，包私有，单测以 FakeTransport 替换真实 HTTP */
    WebhookSender(WebhookUrlPolicy urlPolicy, NotifyProperties props, CredentialCipher cipher,
                  ObjectMapper objectMapper, Transport transport) {
        this.urlPolicy = urlPolicy;
        this.props = props;
        this.cipher = cipher;
        // copy 后注册 JavaTimeModule 并输出 ISO-8601：不污染容器共享 Mapper，
        // 且对未注册 jsr310 模块的 Mapper（如单测 new 出的）行为一致
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.transport = transport;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean send(AutoSite site, NotificationPayload payload) {
        if (site.getNotifyWebhookUrl() == null || site.getNotifyWebhookUrl().isBlank()) {
            return false;
        }
        try {
            urlPolicy.validate(site.getNotifyWebhookUrl());
            String body = objectMapper.writeValueAsString(payload);
            String signature = signature(site, body);
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    transport.postJson(site.getNotifyWebhookUrl(), body, signature, props.getWebhookTimeoutMs());
                    return true;
                } catch (Exception e) {
                    log.warn("Webhook 投递第 {} 次失败：siteId={}，{}", attempt, site.getId(), e.getMessage());
                    if (attempt == 1) {
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Webhook 发送中止：siteId={}，{}", site.getId(), e.getMessage());
            return false;
        }
    }

    /** secret 未配置→null（请求不带 X-Sitemap-Signature）；解密失败按安全失败中止发送 */
    private String signature(AutoSite site, String body) throws Exception {
        if (site.getNotifyWebhookSecretEnc() == null || site.getNotifyWebhookSecretEnc().isBlank()) {
            return null;
        }
        try {
            return "sha256=" + hmacSha256Hex(cipher.decrypt(site.getNotifyWebhookSecretEnc()), body);
        } catch (IllegalStateException e) {
            throw new SecurityException("webhook secret decrypt failed");
        }
    }

    static String hmacSha256Hex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    interface Transport {
        /** 非 2xx 与网络异常一律抛出 */
        void postJson(String url, String body, String signatureHeader, int timeoutMs) throws Exception;
    }

    static class RestClientTransport implements Transport {
        @Override
        public void postJson(String url, String body, String signature, int timeoutMs) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
            factory.setReadTimeout(Duration.ofMillis(timeoutMs));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            client.post().uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (signature != null) {
                            headers.set("X-Sitemap-Signature", signature);
                        }
                    })
                    .body(body)
                    .retrieve()
                    // RestClient 默认仅对 4xx/5xx 抛错；3xx（如接收端只回 302）必须显式判为投递失败
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw new RestClientResponseException(
                                "Webhook 响应非 2xx: " + response.getStatusCode(),
                                response.getStatusCode().value(), response.getStatusText(),
                                response.getHeaders(), null, null);
                    })
                    .toBodilessEntity();
        }
    }
}
