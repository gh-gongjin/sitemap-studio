package io.github.ghgongjin.sitemap.config;

import io.github.ghgongjin.sitemap.service.CrawlProgressService;
import io.github.ghgongjin.sitemap.service.CrawlUrlPolicy;
import io.github.ghgongjin.sitemap.service.EnhancedSitemapGeneratorService;
import io.github.ghgongjin.sitemap.service.PageRenderer;
import io.github.ghgongjin.sitemap.service.PlaywrightPageRenderer;
import io.github.ghgongjin.sitemap.service.SafeHttpFetcher;
import io.github.ghgongjin.sitemap.service.ProxyHealthChecker;
import io.github.ghgongjin.sitemap.service.ProxyPool;
import io.github.ghgongjin.sitemap.service.RealSitemapGeneratorService;
import io.github.ghgongjin.sitemap.service.SeoAuditService;
import io.github.ghgongjin.sitemap.service.SimpleSitemapGeneratorService;
import io.github.ghgongjin.sitemap.service.SitemapGeneratorService;
import io.github.ghgongjin.sitemap.service.SmartRendererSelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * @ClassName SitemapServiceConfig
 * @Description 站点地图服务配置
 * @Author gj
 * @Date 2026/3/8
 * @Version 1.0
 */
@Slf4j
@Configuration
public class SitemapServiceConfig {

    @Value("${sitemap.generator.renderer.enabled:false}")
    private boolean rendererEnabled;

    @Value("${sitemap.generator.renderer.pool-size:2}")
    private int rendererPoolSize;

    @Value("${sitemap.generator.renderer.timeout-seconds:15}")
    private int rendererTimeoutSeconds;

    @Value("${sitemap.generator.renderer.spa-threshold:50}")
    private int rendererSpaThreshold;

    @Value("${sitemap.generator.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${sitemap.generator.proxy.list:}")
    private String proxyList;

    @Value("${sitemap.generator.proxy.health-check-interval-ms:60000}")
    private long proxyHealthCheckIntervalMs;

    @Value("${sitemap.generator.proxy.health-check-timeout-ms:5000}")
    private int proxyHealthCheckTimeoutMs;

    /**
     * 主要服务：增强版站点地图生成服务
     */
    @Bean
    @Primary
    public EnhancedSitemapGeneratorService enhancedSitemapGeneratorService(CrawlProgressService progressService,
                                                                          SeoAuditService seoAuditService) {
        log.info("配置增强版站点地图生成服务（主要服务）");
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        service.setProgressService(progressService);
        service.setSeoAuditService(seoAuditService);
        CrawlUrlPolicy crawlUrlPolicy = new CrawlUrlPolicy();
        SafeHttpFetcher safeHttpFetcher = new SafeHttpFetcher(crawlUrlPolicy);
        service.setCrawlUrlPolicy(crawlUrlPolicy);
        service.setSafeHttpFetcher(safeHttpFetcher);

        // 初始化页面渲染器（如果启用且 Playwright 可用）
        if (rendererEnabled && isPlaywrightAvailable()) {
            try {
                PageRenderer pageRenderer = new PlaywrightPageRenderer(
                        rendererPoolSize, rendererTimeoutSeconds, crawlUrlPolicy, safeHttpFetcher);
                SmartRendererSelector rendererSelector = new SmartRendererSelector(rendererSpaThreshold);
                service.setPageRenderer(pageRenderer);
                service.setRendererSelector(rendererSelector);
                log.info("已启用 Playwright 页面渲染器（池大小：{}，超时：{}秒）", rendererPoolSize, rendererTimeoutSeconds);
            } catch (Exception e) {
                log.warn("Playwright 渲染器初始化失败，将使用纯 Jsoup 模式：{}", e.getMessage());
            }
        } else if (rendererEnabled) {
            log.warn("Playwright 未在 classpath 中找到，将使用纯 Jsoup 模式");
        }

        // 初始化代理池（如果启用）
        if (proxyEnabled && proxyList != null && !proxyList.trim().isEmpty()) {
            try {
                java.util.List<String> proxies = java.util.Arrays.asList(proxyList.split(","));
                ProxyPool proxyPool = new ProxyPool(proxies);
                service.setProxyPool(proxyPool);
                
                // 启动健康检查
                ProxyHealthChecker healthChecker = new ProxyHealthChecker(proxyPool, proxyHealthCheckIntervalMs, proxyHealthCheckTimeoutMs);
                healthChecker.start();
                
                log.info("已启用代理池，共 {} 个代理，健康检查间隔：{}ms", proxies.size(), proxyHealthCheckIntervalMs);
            } catch (Exception e) {
                log.warn("代理池初始化失败，将使用直连模式：{}", e.getMessage());
            }
        }

        return service;
    }

    /**
     * 检查 Playwright 是否在 classpath 中
     */
    private boolean isPlaywrightAvailable() {
        try {
            Class.forName("com.microsoft.playwright.Playwright");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * 备用服务：真实爬取服务
     * 注意：这个服务现在不自动注册为Bean，通过配置类手动创建
     */
    @Bean
    public SitemapGeneratorService realSitemapGeneratorService() {
        log.info("配置真实站点地图生成服务（备用）");
        return new RealSitemapGeneratorService();
    }
    
    /**
     * 简单服务：用于回退
     */
    @Bean
    public SitemapGeneratorService simpleSitemapGeneratorService() {
        log.info("配置简单站点地图生成服务（回退用）");
        return new SimpleSitemapGeneratorService();
    }
}