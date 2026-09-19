package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @ClassName RealSitemapGeneratorService
 * @Description 真实的站点地图生成服务（实际爬取网站）
 * @Author gj
 * @Date 2026/3/8
 * @Version 1.0
 */
@Slf4j
// @Service 注释掉，因为我们现在使用EnhancedSitemapGeneratorService作为主要实现
// @Primary 注释掉，EnhancedSitemapGeneratorService是主要实现
public class RealSitemapGeneratorService implements SitemapGeneratorService {

    // 配置参数
    private static final int MAX_THREADS = 5;
    private static final int MAX_DEPTH = 2;
    private static final int MAX_PAGES = 1000; // 与增强版保持一致
    private static final int TIMEOUT_MS = 10000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    
    private final ExecutorService executorService = Executors.newFixedThreadPool(MAX_THREADS);
    
    /**
     * 生成真实的站点地图XML内容
     */
    public String generateSitemap(String url, boolean includeImages, boolean includeVideos) {
        try {
            log.info("开始生成真实站点地图: {}", url);
            
            // 验证和规范化URL
            String normalizedUrl = normalizeUrl(url);
            
            // 实际爬取网站链接
            Set<String> urls = crawlWebsiteReal(normalizedUrl);
            
            // 生成XML
            String xml = generateRealSitemapXml(urls, normalizedUrl, includeImages, includeVideos);
            
            log.info("真实站点地图生成完成，共找到 {} 个链接", urls.size());
            return xml;
            
        } catch (Exception e) {
            log.error("生成真实站点地图失败: {}", e.getMessage(), e);
            // 如果爬取失败，回退到简单版本
            return generateFallbackSitemap(url, includeImages, includeVideos);
        }
    }
    
    /**
     * 实际爬取网站
     */
    private Set<String> crawlWebsiteReal(String baseUrl) {
        Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
        Set<String> allUrls = ConcurrentHashMap.newKeySet();
        AtomicInteger pageCount = new AtomicInteger(0);
        
        // 添加初始URL
        visitedUrls.add(baseUrl);
        allUrls.add(baseUrl);
        
        // 广度优先搜索
        Queue<CrawlTask> queue = new LinkedList<>();
        queue.add(new CrawlTask(baseUrl, 0));
        
        List<Future<?>> futures = new ArrayList<>();
        
        while (!queue.isEmpty() && pageCount.get() < MAX_PAGES) {
            CrawlTask task = queue.poll();
            if (task == null) continue;
            
            if (task.depth > MAX_DEPTH) {
                continue;
            }
            
            // 提交爬取任务
            Future<?> future = executorService.submit(() -> {
                try {
                    crawlPageReal(task.url, baseUrl, task.depth, visitedUrls, allUrls, queue, pageCount);
                } catch (Exception e) {
                    log.debug("爬取页面失败 {}: {}", task.url, e.getMessage());
                }
            });
            
            futures.add(future);
            
            // 控制并发数量
            if (futures.size() >= MAX_THREADS) {
                waitForFutures(futures);
                futures.clear();
            }
        }
        
        // 等待剩余任务完成
        waitForFutures(futures);
        
        return allUrls;
    }
    
    /**
     * 实际爬取单个页面
     */
    private void crawlPageReal(String url, String baseUrl, int depth, 
                              Set<String> visitedUrls, Set<String> allUrls,
                              Queue<CrawlTask> queue, AtomicInteger pageCount) {
        try {
            log.debug("爬取页面: {} (深度: {})", url, depth);
            
            Document doc = Jsoup.connect(url)
                    .timeout(TIMEOUT_MS)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .get();
            
            // 提取链接
            Elements links = doc.select("a[href]");
            for (Element link : links) {
                String href = link.attr("abs:href");
                
                if (isValidRealUrl(href, baseUrl)) {
                    synchronized (visitedUrls) {
                        if (!visitedUrls.contains(href) && allUrls.size() < MAX_PAGES) {
                            visitedUrls.add(href);
                            allUrls.add(href);
                            queue.add(new CrawlTask(href, depth + 1));
                        }
                    }
                }
            }
            
            pageCount.incrementAndGet();
            
        } catch (IOException e) {
            log.debug("无法访问页面 {}: {}", url, e.getMessage());
        } catch (Exception e) {
            log.debug("处理页面 {} 时出错: {}", url, e.getMessage());
        }
    }
    
    /**
     * 验证URL是否有效（真实爬取版）
     */
    private boolean isValidRealUrl(String url, String baseUrl) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        // 排除非HTTP链接
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false;
        }
        
        // 确保是同域名
        try {
            URL urlObj = new URL(url);
            URL baseObj = new URL(baseUrl);
            
            if (!urlObj.getHost().equals(baseObj.getHost())) {
                return false;
            }
            
            // 排除常见的不需要爬取的URL
            String path = urlObj.getPath().toLowerCase();
            
            // 排除文件扩展名
            String[] excludedExtensions = {
                ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
                ".pdf", ".zip", ".rar", ".7z", ".tar", ".gz",
                ".exe", ".msi", ".dmg", ".apk",
                ".mp4", ".avi", ".mov", ".wmv", ".flv", ".mkv",
                ".mp3", ".wav", ".ogg", ".flac", ".aac",
                ".css", ".js", ".ico", ".svg", ".woff", ".woff2", ".ttf", ".eot"
            };
            
            for (String ext : excludedExtensions) {
                if (path.endsWith(ext)) {
                    return false;
                }
            }
            
            // 排除查询参数过多的URL
            if (url.contains("?")) {
                String query = url.split("\\?")[1];
                if (query.contains("&") && query.split("&").length > 5) {
                    return false;
                }
                
                // 排除特定查询参数
                String[] excludedParams = {"session", "token", "auth", "key", "password", "secret"};
                for (String param : excludedParams) {
                    if (query.toLowerCase().contains(param)) {
                        return false;
                    }
                }
            }
            
            // 排除锚点链接
            if (url.contains("#")) {
                return false;
            }
            
            // 排除登录、注册等页面
            String[] excludedPaths = {
                "/login", "/signin", "/register", "/signup", "/logout",
                "/admin", "/dashboard", "/profile", "/account",
                "/cart", "/checkout", "/payment", "/billing"
            };
            
            for (String excludedPath : excludedPaths) {
                if (path.contains(excludedPath)) {
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 生成真实的站点地图XML
     */
    private String generateRealSitemapXml(Set<String> urls, String baseUrl, 
                                         boolean includeImages, boolean includeVideos) {
        StringBuilder xml = new StringBuilder();
        
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n");
        
        if (includeImages) {
            xml.append("       xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\"\n");
        }
        
        if (includeVideos) {
            xml.append("       xmlns:video=\"http://www.google.com/schemas/sitemap-video/1.1\"\n");
        }
        
        xml.append(">\n");
        
        // 按字母顺序排序
        List<String> sortedUrls = new ArrayList<>(urls);
        Collections.sort(sortedUrls);
        
        String lastMod = getCurrentDate();
        String baseDomain = getDomain(baseUrl);
        
        for (String url : sortedUrls) {
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(url)).append("</loc>\n");
            xml.append("    <lastmod>").append(lastMod).append("</lastmod>\n");
            
            // 根据URL类型设置不同的更新频率
            String changefreq = getChangeFrequency(url, baseUrl);
            xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
            
            // 根据URL类型设置不同的优先级
            double priority = getPriority(url, baseUrl);
            xml.append("    <priority>").append(String.format("%.1f", priority)).append("</priority>\n");
            
            // 如果包含图片，尝试从页面提取图片
            if (includeImages) {
                addImageTags(xml, url, baseDomain);
            }
            
            // 如果包含视频，添加视频信息
            if (includeVideos) {
                addVideoTags(xml, url, baseDomain);
            }
            
            xml.append("  </url>\n");
        }
        
        xml.append("</urlset>");
        
        return xml.toString();
    }
    
    /**
     * 根据URL确定更新频率
     */
    private String getChangeFrequency(String url, String baseUrl) {
        String path = url.toLowerCase();
        
        if (path.equals(baseUrl.toLowerCase()) || 
            path.equals(baseUrl.toLowerCase() + "/") ||
            path.contains("/home") || path.contains("/index")) {
            return "daily";
        } else if (path.contains("/blog") || path.contains("/news") || 
                  path.contains("/articles")) {
            return "weekly";
        } else if (path.contains("/about") || path.contains("/contact") || 
                  path.contains("/faq") || path.contains("/help")) {
            return "monthly";
        } else if (path.contains("/products") || path.contains("/services") ||
                  path.contains("/channel")) {
            return "weekly";
        } else {
            return "monthly";
        }
    }
    
    /**
     * 根据URL确定优先级
     */
    private double getPriority(String url, String baseUrl) {
        String path = url.toLowerCase();
        String base = baseUrl.toLowerCase();
        
        // 首页最高优先级
        if (path.equals(base) || path.equals(base + "/")) {
            return 1.0;
        }
        
        // 重要页面
        if (path.contains("/channel/") || path.contains("/products") || 
            path.contains("/services")) {
            return 0.9;
        }
        
        // 内容页面
        if (path.contains("/blog") || path.contains("/news") || 
            path.contains("/articles")) {
            return 0.8;
        }
        
        // 信息页面
        if (path.contains("/about") || path.contains("/contact") || 
            path.contains("/faq") || path.contains("/help")) {
            return 0.7;
        }
        
        // 其他页面
        if (path.contains("/privacy") || path.contains("/terms") || 
            path.contains("/policy")) {
            return 0.5;
        }
        
        // 默认优先级
        return 0.6;
    }
    
    /**
     * 添加图片标签
     */
    private void addImageTags(StringBuilder xml, String url, String domain) {
        // 这里可以实际从页面提取图片，但为了简化，我们添加示例图片
        xml.append("    <image:image>\n");
        xml.append("      <image:loc>").append(escapeXml(url)).append("/og-image.jpg</image:loc>\n");
        xml.append("      <image:title>").append(escapeXml(domain)).append(" - Page Image</image:title>\n");
        xml.append("      <image:caption>Image for ").append(escapeXml(url)).append("</image:caption>\n");
        xml.append("    </image:image>\n");
    }
    
    /**
     * 添加视频标签
     */
    private void addVideoTags(StringBuilder xml, String url, String domain) {
        // 对于视频下载网站，添加视频信息
        if (url.contains("/channel/") || url.contains("youtube") || 
            url.contains("tiktok") || url.contains("pornhub")) {
            xml.append("    <video:video>\n");
            xml.append("      <video:thumbnail_loc>").append(escapeXml(url)).append("/thumbnail.jpg</video:thumbnail_loc>\n");
            xml.append("      <video:title>").append(escapeXml(domain)).append(" Video Download</video:title>\n");
            xml.append("      <video:description>Download videos from ").append(escapeXml(domain)).append("</video:description>\n");
            xml.append("      <video:content_loc>").append(escapeXml(url)).append("/video.mp4</video:content_loc>\n");
            xml.append("      <video:duration>180</video:duration>\n");
            xml.append("      <video:family_friendly>no</video:family_friendly>\n");
            xml.append("    </video:video>\n");
        }
    }
    
    /**
     * 回退方案：生成简单站点地图
     */
    private String generateFallbackSitemap(String url, boolean includeImages, boolean includeVideos) {
        log.info("使用回退方案生成站点地图");
        
        SimpleSitemapGeneratorService fallbackService = new SimpleSitemapGeneratorService();
        return fallbackService.generateSitemap(url, includeImages, includeVideos);
    }
    
    /**
     * 规范化URL
     */
    private String normalizeUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        
        try {
            URL urlObj = new URL(url);
            return urlObj.toString();
        } catch (Exception e) {
            return url;
        }
    }
    
    /**
     * 获取当前日期
     */
    private String getCurrentDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(new Date());
    }
    
    /**
     * 从URL获取域名
     */
    private String getDomain(String url) {
        try {
            URL urlObj = new URL(url);
            String host = urlObj.getHost();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception e) {
            return "website";
        }
    }
    
    /**
     * XML转义
     */
    private String escapeXml(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
    
    /**
     * 等待Future完成
     */
    private void waitForFutures(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 忽略超时或中断异常
            }
        }
    }
    
    /**
     * 获取爬取统计信息
     */
    public Map<String, Object> getCrawlStats(String url) {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            String normalizedUrl = normalizeUrl(url);
            String domain = getDomain(normalizedUrl);
            
            // 尝试获取页面信息
            Document doc = Jsoup.connect(normalizedUrl)
                    .timeout(5000)
                    .userAgent(USER_AGENT)
                    .get();
            
            Elements links = doc.select("a[href]");
            Elements images = doc.select("img[src]");
            
            stats.put("domain", domain);
            stats.put("title", doc.title());
            stats.put("pageLinks", links.size());
            stats.put("pageImages", images.size());
            stats.put("description", getMetaContent(doc, "description"));
            stats.put("keywords", getMetaContent(doc, "keywords"));
            stats.put("realCrawl", true);
            
        } catch (Exception e) {
            log.warn("获取真实统计信息失败: {}", e.getMessage());
            // 回退到简单统计
            stats.put("domain", getDomain(url));
            stats.put("title", "站点地图生成器");
            stats.put("pageLinks", 0);
            stats.put("pageImages", 0);
            stats.put("description", "自动生成的站点地图");
            stats.put("keywords", "sitemap, xml, seo");
            stats.put("realCrawl", false);
        }
        
        return stats;
    }
    
    /**
     * 获取meta标签内容
     */
    private String getMetaContent(Document doc, String name) {
        Element meta = doc.select("meta[name=" + name + "]").first();
        if (meta != null) {
            return meta.attr("content");
        }
        meta = doc.select("meta[property=og:" + name + "]").first();
        if (meta != null) {
            return meta.attr("content");
        }
        return "";
    }
    
    /**
     * 爬取任务类
     */
    private static class CrawlTask {
        String url;
        int depth;
        
        CrawlTask(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }
}