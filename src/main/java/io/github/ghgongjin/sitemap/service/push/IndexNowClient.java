package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.service.SitemapEntryParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @ClassName IndexNowClient
 * @Description IndexNow 提交（Bing 等搜索引擎即时收录）：key 生成、loc 提取、HTTP 提交
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Component
public class IndexNowClient {

    public static final String ENDPOINT = "https://api.indexnow.org/indexnow";
    /** IndexNow 单次提交的 URL 数量上限 */
    static final int MAX_URLS = 10_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient restClient;

    public IndexNowClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(ENDPOINT).build();
    }

    /**
     * 生成 32 位十六进制 key（IndexNow 要求 8-128 位十六进制或 UUID 风格）
     */
    public static String generateKey() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 提取站点地图中的全部 URL（jsoup 解析，不加载外部实体）
     */
    public static List<String> extractUrls(String sitemapXml) {
        return SitemapEntryParser.parse(sitemapXml).entries().stream()
                .map(SitemapEntryParser.Entry::url)
                .filter(url -> !url.isBlank())
                .limit(MAX_URLS)
                .toList();
    }

    /**
     * 提交 URL 列表；失败时抛出 INDEXNOW_FAILED（调用方记录后不影响站点地图推送结果）
     */
    public void submit(String siteUrl, String key, List<String> urls) throws PushTransportException {
        URI site = URI.create(siteUrl);
        String keyLocation = site.getScheme() + "://" + site.getAuthority() + "/" + key + ".txt";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("host", site.getHost());
        payload.put("key", key);
        payload.put("keyLocation", keyLocation);
        payload.put("urlList", urls);
        log.info("IndexNow 提交：host={}，{} 个 URL", site.getHost(), urls.size());
        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new PushTransportException(PushErrorCode.INDEXNOW_FAILED,
                    "IndexNow 拒绝提交（HTTP " + e.getStatusCode().value() + "）", e);
        } catch (RestClientException e) {
            throw new PushTransportException(PushErrorCode.INDEXNOW_FAILED,
                    "IndexNow 提交失败：" + e.getMessage(), e);
        }
    }
}
