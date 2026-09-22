package io.github.ghgongjin.sitemap.service.submission;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * @ClassName SubmissionHttp
 * @Description 提交通道共享 HTTP 管道（包私有工具）：出网超时请求工厂由两客户端共用，
 *              消除 GoogleSitemapClient → BaiduPushClient 的跨类静态依赖
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
final class SubmissionHttp {

    private SubmissionHttp() {
    }

    /** spec §5.3：连接/读取超时各 timeoutMs——对齐 notify 通道 WebhookSender 的出网先例 */
    static SimpleClientHttpRequestFactory timeoutRequestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return factory;
    }

    /** 查询串/路径段百分号编码：空格按 RFC 3986 编为 %20 而非 '+' */
    static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
