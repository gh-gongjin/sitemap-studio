package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @ClassName SitemapXmlParser
 * @Description sitemap.xml 解析器，从站点地图文件中发现 URL
 * @Author gj
 * @Date 2026/9/15
 * @Version 1.0
 */
@Slf4j
public class SitemapXmlParser {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_SITEMAP_NESTING = 3;

    /**
     * 从多个来源解析 sitemap 并收集所有 URL
     * @param baseUrl 基础 URL
     * @param robotsSitemapUrls robots.txt 中发现的 Sitemap URL 列表
     * @return 发现的 URL 集合（URL → lastmod，lastmod 可为 null）
     */
    public Map<String, String> parseAll(String baseUrl, List<String> robotsSitemapUrls) {
        return parseAll(baseUrl, robotsSitemapUrls, new CrawlUrlPolicy(), new SafeHttpFetcher(), TIMEOUT_MS);
    }

    public Map<String, String> parseAll(String baseUrl, List<String> robotsSitemapUrls,
                                        CrawlUrlPolicy policy, SafeHttpFetcher fetcher, int timeoutMs) {
        Map<String, String> discoveredUrls = new ConcurrentHashMap<>();
        Set<String> processedSitemaps = Collections.synchronizedSet(new HashSet<>());

        List<String> sitemapUrls = new ArrayList<>();

        // 优先使用 robots.txt 中声明的 sitemap
        if (robotsSitemapUrls != null && !robotsSitemapUrls.isEmpty()) {
            sitemapUrls.addAll(robotsSitemapUrls);
            log.info("从 robots.txt 发现 {} 个 Sitemap", robotsSitemapUrls.size());
        }

        // 始终尝试默认的 /sitemap.xml
        String defaultSitemap = baseUrl + "/sitemap.xml";
        if (!sitemapUrls.contains(defaultSitemap)) {
            sitemapUrls.add(defaultSitemap);
        }

        // 也尝试 /sitemap_index.xml
        String indexSitemap = baseUrl + "/sitemap_index.xml";
        if (!sitemapUrls.contains(indexSitemap)) {
            sitemapUrls.add(indexSitemap);
        }

        for (String sitemapUrl : sitemapUrls) {
            parseSitemapRecursive(sitemapUrl, baseUrl, discoveredUrls, processedSitemaps, 0, policy, fetcher, timeoutMs);
        }

        log.info("sitemap.xml 解析完成，共发现 {} 个 URL", discoveredUrls.size());
        return discoveredUrls;
    }

    /**
     * 递归解析 sitemap（支持 sitemap index 嵌套）
     */
    private void parseSitemapRecursive(String sitemapUrl, String baseUrl, Map<String, String> discoveredUrls,
                                        Set<String> processedSitemaps, int depth,
                                        CrawlUrlPolicy policy, SafeHttpFetcher fetcher, int timeoutMs) {
        if (depth > MAX_SITEMAP_NESTING) {
            log.warn("sitemap 嵌套层级过深，跳过: {}", sitemapUrl);
            return;
        }
        if (!processedSitemaps.add(sitemapUrl)) {
            return;
        }

        try {
            log.info("解析 sitemap: {} (深度: {})", sitemapUrl, depth);

            if (!policy.isAllowed(sitemapUrl, baseUrl)) {
                log.warn("拒绝不安全的 sitemap: {}", sitemapUrl);
                return;
            }
            SafeHttpFetcher.Response response = fetcher.fetch(sitemapUrl, baseUrl, timeoutMs, null);

            if (response.statusCode() != 200) {
                log.debug("sitemap 不存在或无法访问 (HTTP {}): {}", response.statusCode(), sitemapUrl);
                return;
            }

            Document doc = response.parse();
            if (doc == null) {
                return;
            }

            // 检查是否为 sitemap index（包含 <sitemap> 标签）
            Elements sitemapElements = doc.select("sitemap loc");
            if (!sitemapElements.isEmpty()) {
                log.info("发现 sitemap index，包含 {} 个子 sitemap", sitemapElements.size());
                for (Element loc : sitemapElements) {
                    String childSitemapUrl = loc.text().trim();
                    if (!childSitemapUrl.isEmpty()) {
                        parseSitemapRecursive(childSitemapUrl, baseUrl, discoveredUrls, processedSitemaps, depth + 1,
                                policy, fetcher, timeoutMs);
                    }
                }
                return;
            }

            // 解析普通 sitemap 中的 <url> <loc> 标签
            Elements urlElements = doc.select("url loc");
            int added = 0;
            for (Element loc : urlElements) {
                String url = loc.text().trim();
                if (url.isEmpty()) continue;

                // 尝试获取 lastmod
                String lastmod = "";
                Element urlElement = loc.parent();
                if (urlElement != null) {
                    Elements lastmodElements = urlElement.select("lastmod");
                    if (!lastmodElements.isEmpty()) {
                        lastmod = lastmodElements.first().text().trim();
                    }
                }

                if (!policy.isAllowed(url, baseUrl)) {
                    continue;
                }
                discoveredUrls.put(url, lastmod);
                added++;
            }
            log.debug("从 {} 中解析出 {} 个 URL", sitemapUrl, added);

        } catch (Exception e) {
            log.warn("解析 sitemap 失败: {} - {}", sitemapUrl, e.getMessage());
        }
    }
}
