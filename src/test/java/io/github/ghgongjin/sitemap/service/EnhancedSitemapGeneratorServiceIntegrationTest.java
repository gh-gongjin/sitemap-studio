package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @ClassName EnhancedSitemapGeneratorServiceIntegrationTest
 * @Description 增强版站点地图生成服务集成测试
 * @Author gj
 * @Date 2026/3/8
 * @Version 1.0
 * 
 * @Disabled 注释掉以避免实际网络请求，在实际测试环境中启用
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("需要实际网络连接，在CI/CD环境中启用")
class EnhancedSitemapGeneratorServiceIntegrationTest {

    @Test
    void testRealWebsiteCrawl() {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        
        // 测试实际网站爬取
        String url = "https://example.com";
        String sitemap = service.generateSitemap(url, false, false);
        
        assertNotNull(sitemap, "生成的站点地图不应该为null");
        assertTrue(sitemap.contains("<?xml"), "应该是有效的XML格式");
        assertTrue(sitemap.contains("<urlset"), "应该包含urlset标签");
        
        // 验证基本结构
        assertTrue(sitemap.contains("<loc>"), "应该包含loc标签");
        assertTrue(sitemap.contains("<lastmod>"), "应该包含lastmod标签");
        assertTrue(sitemap.contains("<priority>"), "应该包含priority标签");
        assertTrue(sitemap.contains("<changefreq>"), "应该包含changefreq标签");
        
        // 统计信息测试
        Map<String, Object> stats = service.getCrawlStats(url);
        assertNotNull(stats, "统计信息不应该为null");
        assertTrue(stats.containsKey("service"), "应该包含服务信息");
        assertTrue(stats.containsKey("version"), "应该包含版本信息");
    }

    @Test
    void testDownloadxaiComCrawl() {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        
        // 测试目标网站
        String url = "https://downloadxai.com";
        String sitemap = service.generateSitemap(url, false, false);
        
        assertNotNull(sitemap, "生成的站点地图不应该为null");
        
        // 验证包含预期内容
        assertTrue(sitemap.contains("downloadxai.com"), "应该包含目标域名");
        
        // 验证XML结构
        int urlCount = countOccurrences(sitemap, "<url>");
        assertTrue(urlCount > 0, "应该至少找到一个URL");
        
        System.out.println("找到 " + urlCount + " 个URL");
        System.out.println("生成的站点地图大小: " + sitemap.length() + " 字符");
        
        // 输出前几个URL用于验证
        String[] lines = sitemap.split("\n");
        int urlPrinted = 0;
        for (String line : lines) {
            if (line.contains("<loc>") && urlPrinted < 5) {
                System.out.println("URL: " + line.trim());
                urlPrinted++;
            }
        }
    }

    @Test
    void testPerformanceAndCoverage() {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        
        // 测试多个网站
        String[] testUrls = {
            "https://example.com",
            "https://httpbin.org",
            "https://jsonplaceholder.typicode.com"
        };
        
        for (String url : testUrls) {
            long startTime = System.currentTimeMillis();
            
            String sitemap = service.generateSitemap(url, false, false);
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            
            assertNotNull(sitemap, "站点地图不应该为null: " + url);
            assertTrue(sitemap.contains("<?xml"), "应该是有效的XML: " + url);
            
            int urlCount = countOccurrences(sitemap, "<url>");
            
            System.out.println("\n网站: " + url);
            System.out.println("爬取时间: " + duration + "ms");
            System.out.println("找到URL数量: " + urlCount);
            System.out.println("文件大小: " + sitemap.length() + " 字符");
            
            // 验证性能要求
            assertTrue(duration < 30000, "爬取时间应该小于30秒: " + url);
            assertTrue(urlCount > 0, "应该至少找到一个URL: " + url);
        }
    }

    @Test
    void testErrorHandlingAndFallback() {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        
        // 测试无效URL
        String[] invalidUrls = {
            "not-a-url",
            "ftp://example.com",
            "http://nonexistent-domain-12345.com",
            "https://localhost:9999/nonexistent"
        };
        
        for (String url : invalidUrls) {
            try {
                String sitemap = service.generateSitemap(url, false, false);
                
                // 即使URL无效，也应该有回退结果
                assertNotNull(sitemap, "即使URL无效也应该有结果: " + url);
                assertTrue(sitemap.contains("<?xml"), "应该是有效的XML: " + url);
                
                System.out.println("无效URL处理成功: " + url);
                
            } catch (Exception e) {
                // 不应该抛出异常，应该有回退机制
                fail("不应该抛出异常，应该有回退机制: " + url + " - " + e.getMessage());
            }
        }
    }

    @Test
    void testImageAndVideoSitemap() {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        
        // 测试包含图片的站点地图
        String url = "https://example.com";
        
        String sitemapWithoutImages = service.generateSitemap(url, false, false);
        String sitemapWithImages = service.generateSitemap(url, true, false);
        String sitemapWithVideos = service.generateSitemap(url, false, true);
        String sitemapWithBoth = service.generateSitemap(url, true, true);
        
        // 验证不同选项生成不同的XML命名空间
        assertFalse(sitemapWithoutImages.contains("xmlns:image"), "不包含图片时不应该有image命名空间");
        assertFalse(sitemapWithoutImages.contains("xmlns:video"), "不包含视频时不应该有video命名空间");
        
        assertTrue(sitemapWithImages.contains("xmlns:image"), "包含图片时应该有image命名空间");
        assertFalse(sitemapWithImages.contains("xmlns:video"), "只包含图片时不应该有video命名空间");
        
        assertFalse(sitemapWithVideos.contains("xmlns:image"), "只包含视频时不应该有image命名空间");
        assertTrue(sitemapWithVideos.contains("xmlns:video"), "包含视频时应该有video命名空间");
        
        assertTrue(sitemapWithBoth.contains("xmlns:image"), "包含图片和视频时应该有image命名空间");
        assertTrue(sitemapWithBoth.contains("xmlns:video"), "包含图片和视频时应该有video命名空间");
        
        System.out.println("图片和视频站点地图测试通过");
    }

    /**
     * 计算字符串中某个子串出现的次数
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        
        return count;
    }
}