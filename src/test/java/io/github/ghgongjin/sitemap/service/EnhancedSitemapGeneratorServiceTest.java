package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @ClassName EnhancedSitemapGeneratorServiceTest
 * @Description 增强版站点地图生成服务测试
 * @Author gj
 * @Date 2026/3/8
 * @Version 1.0
 */
@ExtendWith(MockitoExtension.class)
class EnhancedSitemapGeneratorServiceTest {

    private EnhancedSitemapGeneratorService service;

    @BeforeEach
    void setUp() {
        service = new EnhancedSitemapGeneratorService();
        service.setCrawlUrlPolicy(new CrawlUrlPolicy(host -> {
            try {
                if ("example.com".equalsIgnoreCase(host)) {
                    return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                }
                return InetAddress.getAllByName(host);
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException(e);
            }
        }));
    }

    @Test
    void testServiceInstantiation() {
        assertNotNull(service, "服务应该能正常实例化");
        assertTrue(service instanceof SitemapGeneratorService, "应该实现SitemapGeneratorService接口");
    }

    @Test
    void testGetCrawlStats() {
        Map<String, Object> stats = service.getCrawlStats("https://test.com");
        
        assertNotNull(stats, "统计信息不应该为null");
        assertEquals("EnhancedSitemapGeneratorService", stats.get("service"), "服务名称应该正确");
        assertEquals("2.0", stats.get("version"), "版本号应该正确");
        
        assertTrue(stats.containsKey("features"), "应该包含特性列表");
        assertTrue(stats.containsKey("config"), "应该包含配置信息");
        
        // 验证配置参数
        Map<String, Object> config = (Map<String, Object>) stats.get("config");
        assertNotNull(config, "配置信息不应该为null");
        
        assertEquals(20, config.get("maxThreads"));
        assertEquals(10, config.get("maxDepth"));
        assertEquals(EnhancedSitemapGeneratorService.MAX_PAGES, config.get("maxPages"));
        assertEquals(30000, config.get("timeoutMs"));
        assertEquals(3, config.get("maxRetries"), "最大重试次数应该为3");
    }

    @Test
    void testGenerateSitemapWithInvalidUrlThrowsException() {
        String invalidUrl = "not-a-valid-url";
        assertThrows(Exception.class, () -> service.generateSitemap(invalidUrl, false, false),
            "无效URL应该抛出异常而不是回退到简单版本");
    }

    @Test
    void testSsrfProtectionRejectsInternalAddresses() throws Exception {
        var method = EnhancedSitemapGeneratorService.class.getDeclaredMethod(
            "isEnhancedValidUrl", String.class, String.class, RobotsTxtParser.class
        );
        method.setAccessible(true);
        String baseUrl = "https://example.com";

        assertFalse((boolean) method.invoke(service, "http://127.0.0.1/admin", baseUrl, null),
            "应拒绝127.0.0.1");
        assertFalse((boolean) method.invoke(service, "http://localhost/admin", baseUrl, null),
            "应拒绝localhost");
        assertFalse((boolean) method.invoke(service, "http://10.0.0.1/internal", baseUrl, null),
            "应拒绝10.x.x.x");
        assertFalse((boolean) method.invoke(service, "http://172.16.0.1/internal", baseUrl, null),
            "应拒绝172.16.x.x");
        assertFalse((boolean) method.invoke(service, "http://192.168.1.1/internal", baseUrl, null),
            "应拒绝192.168.x.x");
        assertFalse((boolean) method.invoke(service, "http://0.0.0.0/", baseUrl, null),
            "应拒绝0.0.0.0");
        assertFalse((boolean) method.invoke(service, "http://[::1]/", baseUrl, null),
            "应拒绝IPv6回环地址");
    }

    @Test
    void testSsrfProtectionAllowsPublicAddresses() throws Exception {
        var method = EnhancedSitemapGeneratorService.class.getDeclaredMethod(
            "isEnhancedValidUrl", String.class, String.class, RobotsTxtParser.class
        );
        method.setAccessible(true);
        String baseUrl = "https://example.com";

        assertTrue((boolean) method.invoke(service, "https://example.com/page", baseUrl, null),
            "应允许同域公开地址");
        assertTrue((boolean) method.invoke(service, "https://example.com/blog/post", baseUrl, null),
            "应允许同域公开地址");
    }

    @Test
    void testRejectNonHttpProtocols() throws Exception {
        var method = EnhancedSitemapGeneratorService.class.getDeclaredMethod(
            "isEnhancedValidUrl", String.class, String.class, RobotsTxtParser.class
        );
        method.setAccessible(true);
        String baseUrl = "https://example.com";

        assertFalse((boolean) method.invoke(service, "ftp://example.com/file", baseUrl, null),
            "应拒绝ftp协议");
        assertFalse((boolean) method.invoke(service, "file:///etc/passwd", baseUrl, null),
            "应拒绝file协议");
        assertFalse((boolean) method.invoke(service, "javascript:alert(1)", baseUrl, null),
            "应拒绝javascript协议");
    }

    @Test
    @org.junit.jupiter.api.Disabled("旧测试实际访问公网，将由本地受控站点测试替换")
    void testGenerateSitemapWithValidUrl() {
        // 注意：这是一个单元测试，不进行实际网络请求
        // 实际网络请求应该在集成测试中进行
        
        String testUrl = "https://example.com";
        String result = service.generateSitemap(testUrl, false, false);
        
        assertNotNull(result, "生成结果不应该为null");
        assertTrue(result.startsWith("<?xml"), "应该以XML声明开头");
        assertTrue(result.contains("<urlset"), "应该包含urlset标签");
        assertTrue(result.contains("</urlset>"), "应该正确闭合urlset标签");
    }

    @Test
    void testCalculatePriority() throws Exception {
        // 使用反射测试私有方法
        var method = EnhancedSitemapGeneratorService.class.getDeclaredMethod(
            "calculatePriority", String.class, String.class
        );
        method.setAccessible(true);
        
        String baseUrl = "https://example.com";
        
        // 测试首页优先级
        String priority = (String) method.invoke(service, baseUrl, baseUrl);
        assertEquals("1.0", priority, "首页优先级应该为1.0");
        
        // 测试频道页优先级
        priority = (String) method.invoke(service, baseUrl + "/channel/test", baseUrl);
        assertEquals("0.9", priority, "频道页优先级应该为0.9");
        
        // 测试关于页优先级
        priority = (String) method.invoke(service, baseUrl + "/about", baseUrl);
        assertEquals("0.8", priority, "关于页优先级应该为0.8");
        
        // 测试博客页优先级
        priority = (String) method.invoke(service, baseUrl + "/blog", baseUrl);
        assertEquals("0.7", priority, "博客页优先级应该为0.7");
        
        // 测试默认优先级
        priority = (String) method.invoke(service, baseUrl + "/other", baseUrl);
        assertEquals("0.5", priority, "其他页面优先级应该为0.5");
    }

    @Test
    void testCalculateChangefreq() throws Exception {
        // 使用反射测试私有方法
        var method = EnhancedSitemapGeneratorService.class.getDeclaredMethod(
            "calculateChangefreq", String.class
        );
        method.setAccessible(true);
        
        // 测试博客页更新频率
        String changefreq = (String) method.invoke(service, "https://example.com/blog");
        assertEquals("weekly", changefreq, "博客页更新频率应该为weekly");
        
        // 测试频道页更新频率
        changefreq = (String) method.invoke(service, "https://example.com/channel/test");
        assertEquals("daily", changefreq, "频道页更新频率应该为daily");
        
        // 测试默认更新频率
        changefreq = (String) method.invoke(service, "https://example.com/other");
        assertEquals("monthly", changefreq, "其他页面更新频率应该为monthly");
    }

    @Test
    void testEscapeXml() throws Exception {
        // 使用反射测试私有方法
        var method = EnhancedSitemapGeneratorService.class.getDeclaredMethod(
            "escapeXml", String.class
        );
        method.setAccessible(true);
        
        // 测试XML转义
        String escaped = (String) method.invoke(service, "test & test < > \" '");
        assertEquals("test &amp; test &lt; &gt; &quot; &apos;", escaped, "XML字符应该正确转义");
        
        // 测试普通字符串
        escaped = (String) method.invoke(service, "normal string");
        assertEquals("normal string", escaped, "普通字符串不应该被转义");
    }

    @Test
    void testIsImportantUrl() throws Exception {
        // 使用反射测试私有方法
        var method = EnhancedSitemapGeneratorService.class.getDeclaredMethod(
            "isImportantUrl", String.class
        );
        method.setAccessible(true);
        
        // 测试重要URL
        boolean isImportant = (boolean) method.invoke(service, "https://example.com/channel/test");
        assertTrue(isImportant, "频道URL应该被认为是重要的");
        
        isImportant = (boolean) method.invoke(service, "https://example.com/about");
        assertTrue(isImportant, "关于页URL应该被认为是重要的");
        
        isImportant = (boolean) method.invoke(service, "https://example.com/");
        assertTrue(isImportant, "首页URL应该被认为是重要的");
        
        // 测试非重要URL
        isImportant = (boolean) method.invoke(service, "https://example.com/other/page");
        assertFalse(isImportant, "普通页面不应该被认为是重要的");
    }
}