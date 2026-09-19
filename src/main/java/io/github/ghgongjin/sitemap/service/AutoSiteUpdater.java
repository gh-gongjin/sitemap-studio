package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.service.push.PushOutcome;
import io.github.ghgongjin.sitemap.service.push.SitemapPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * @ClassName AutoSiteUpdater
 * @Description 单站点自动更新执行：抓取 → 记录版本 → 落 SEO 报告 → 推送；失败保留旧版
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoSiteUpdater {

    private final AutoSiteService autoSiteService;
    private final EnhancedSitemapGeneratorService enhancedSitemapGeneratorService;
    private final CrawlProgressService progressService;
    private final SeoReportService seoReportService;
    private final SitemapPushService sitemapPushService;

    /**
     * 同步执行一次站点更新（由调度器单线程串行调用）
     */
    public boolean update(AutoSite site) {
        String taskId = buildTaskId(site.getId());
        log.info("自动更新开始：{}（taskId={}）", site.getUrl(), taskId);
        try {
            String sitemapXml = enhancedSitemapGeneratorService.generateSitemapWithProgress(
                    site.getUrl(), site.isIncludeImages(), site.isIncludeVideos(), site.isIncludeNews(), taskId);
            autoSiteService.recordSuccess(site.getId(), taskId, sitemapXml, resolveUrlCount(taskId));
            saveSeoReport(taskId, site.getUrl());
            pushLatestVersion(site);
            return true;
        } catch (Exception e) {
            log.error("自动更新失败：{}，原因：{}", site.getUrl(), e.getMessage());
            autoSiteService.recordFailure(site.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 推送与更新主流程完全隔离：推送异常不影响更新结果与站点状态
     */
    private void pushLatestVersion(AutoSite site) {
        try {
            PushOutcome outcome = sitemapPushService.push(site.getId());
            if (!outcome.success() && !outcome.skipped()) {
                log.warn("自动更新后推送站点地图失败：{}，原因：{}", site.getUrl(), outcome.detail());
            }
        } catch (Exception e) {
            log.warn("自动更新后推送站点地图异常：{}，原因：{}", site.getUrl(), e.getMessage());
        }
    }

    private int resolveUrlCount(String taskId) {
        CrawlProgressService.TaskResult result = progressService.getTaskResult(taskId);
        return result == null ? 0 : result.getTotalPages();
    }

    private void saveSeoReport(String taskId, String url) {
        try {
            // 自动更新由调度线程执行，不属于任何请求用户，报告暂无归属；
            // Task 4 给 AutoSite 补 user_id 后改传站点归属用户
            seoReportService.save(taskId, url, null);
        } catch (Exception e) {
            log.warn("自动更新 SEO 报告保存失败：taskId={}, {}", taskId, e.getMessage());
        }
    }

    private String buildTaskId(Long siteId) {
        return "auto-" + siteId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
