package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @ClassName SimpleSitemapGeneratorService
 * @Description 简化的站点地图生成服务（避免网络爬虫问题）
 * @Author gj
 * @Date 2026/3/8
 * @Version 1.0
 */
@Slf4j
public class SimpleSitemapGeneratorService implements SitemapGeneratorService {

    /**
     * 生成站点地图XML内容（简化版）
     */
    public String generateSitemap(String url, boolean includeImages, boolean includeVideos) {
        try {
            log.info("开始生成站点地图（简化版）: {}", url);
            
            // 验证URL
            String normalizedUrl = normalizeUrl(url);
            
            // 生成XML（不实际爬取，生成示例站点地图）
            String xml = generateSimpleSitemapXml(normalizedUrl, includeImages, includeVideos);
            
            log.info("站点地图生成完成（简化版）");
            return xml;
            
        } catch (Exception e) {
            log.error("生成站点地图失败（简化版）: {}", e.getMessage(), e);
            throw new RuntimeException("生成站点地图失败: " + e.getMessage(), e);
        }
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
     * 生成简化的站点地图XML
     */
    private String generateSimpleSitemapXml(String baseUrl, boolean includeImages, boolean includeVideos) {
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
        
        // 生成示例URL（不实际爬取）
        List<String> urls = generateExampleUrls(baseUrl);
        
        String lastMod = getCurrentDate();
        
        for (String url : urls) {
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(url)).append("</loc>\n");
            xml.append("    <lastmod>").append(lastMod).append("</lastmod>\n");
            xml.append("    <changefreq>weekly</changefreq>\n");
            xml.append("    <priority>0.8</priority>\n");
            
            // 如果包含图片，添加图片信息
            if (includeImages) {
                xml.append("    <image:image>\n");
                xml.append("      <image:loc>").append(escapeXml(url)).append("/image.jpg</image:loc>\n");
                xml.append("      <image:title>").append(escapeXml(getDomain(baseUrl))).append(" Image</image:title>\n");
                xml.append("    </image:image>\n");
            }
            
            // 如果包含视频，添加视频信息
            if (includeVideos) {
                xml.append("    <video:video>\n");
                xml.append("      <video:thumbnail_loc>").append(escapeXml(url)).append("/thumbnail.jpg</video:thumbnail_loc>\n");
                xml.append("      <video:title>").append(escapeXml(getDomain(baseUrl))).append(" Video</video:title>\n");
                xml.append("      <video:description>Video content from ").append(escapeXml(getDomain(baseUrl))).append("</video:description>\n");
                xml.append("      <video:content_loc>").append(escapeXml(url)).append("/video.mp4</video:content_loc>\n");
                xml.append("      <video:duration>120</video:duration>\n");
                xml.append("    </video:video>\n");
            }
            
            xml.append("  </url>\n");
        }
        
        xml.append("</urlset>");
        
        return xml.toString();
    }
    
    /**
     * 生成示例URL列表
     */
    private List<String> generateExampleUrls(String baseUrl) {
        List<String> urls = new ArrayList<>();
        
        // 确保baseUrl不以斜杠结尾
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        
        // 添加常见页面
        urls.add(baseUrl);
        urls.add(baseUrl + "/index.html");
        urls.add(baseUrl + "/home");
        urls.add(baseUrl + "/about");
        urls.add(baseUrl + "/about-us");
        urls.add(baseUrl + "/contact");
        urls.add(baseUrl + "/contact-us");
        urls.add(baseUrl + "/products");
        urls.add(baseUrl + "/services");
        urls.add(baseUrl + "/blog");
        urls.add(baseUrl + "/news");
        urls.add(baseUrl + "/articles");
        urls.add(baseUrl + "/faq");
        urls.add(baseUrl + "/help");
        urls.add(baseUrl + "/support");
        urls.add(baseUrl + "/privacy");
        urls.add(baseUrl + "/privacy-policy");
        urls.add(baseUrl + "/terms");
        urls.add(baseUrl + "/terms-of-service");
        urls.add(baseUrl + "/sitemap.xml");
        urls.add(baseUrl + "/robots.txt");
        
        return urls;
    }
    
    /**
     * 获取当前日期
     */
    private String getCurrentDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(new Date());
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
     * 获取爬取统计信息（简化版）
     */
    public Map<String, Object> getCrawlStats(String url) {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            String normalizedUrl = normalizeUrl(url);
            String domain = getDomain(normalizedUrl);
            
            stats.put("domain", domain);
            stats.put("title", "站点地图生成器 - " + domain);
            stats.put("pageLinks", 20); // 示例链接数
            stats.put("pageImages", 5); // 示例图片数
            stats.put("description", "自动生成的站点地图");
            stats.put("keywords", "sitemap, xml, seo");
            
        } catch (Exception e) {
            log.warn("获取统计信息失败: {}", e.getMessage());
        }
        
        return stats;
    }
}