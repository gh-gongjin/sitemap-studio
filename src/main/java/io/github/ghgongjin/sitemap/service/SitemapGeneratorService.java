package io.github.ghgongjin.sitemap.service;

import java.util.Map;

/**
 * @ClassName SitemapGeneratorService
 * @Description 站点地图生成服务接口
 * @Author gj
 * @Date 2026/3/8
 * @Version 1.0
 */
public interface SitemapGeneratorService {
    
    /**
     * 生成站点地图XML内容
     */
    String generateSitemap(String url, boolean includeImages, boolean includeVideos);
    
    /**
     * 生成站点地图XML内容（含新闻条目开关）。默认实现忽略新闻参数，由支持新闻的生成器覆盖。
     */
    default String generateSitemap(String url, boolean includeImages, boolean includeVideos, boolean includeNews) {
        return generateSitemap(url, includeImages, includeVideos);
    }
    
    /**
     * 获取爬取统计信息
     */
    Map<String, Object> getCrawlStats(String url);
}