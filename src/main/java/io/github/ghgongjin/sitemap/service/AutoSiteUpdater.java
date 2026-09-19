package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.service.notify.SiteFailedEvent;
import io.github.ghgongjin.sitemap.service.notify.SiteUpdatedEvent;
import io.github.ghgongjin.sitemap.service.push.PushOutcome;
import io.github.ghgongjin.sitemap.service.push.SitemapPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

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
            SeoReport report = saveSeoReport(taskId, site.getUrl(), site.getUserId());
            publishUpdatedEvent(site, report);
            pushLatestVersion(site);
            return true;
        } catch (Exception e) {
            log.error("自动更新失败：{}，原因：{}", site.getUrl(), e.getMessage());
            AutoSite failed = autoSiteService.recordFailure(site.getId(), e.getMessage());
            publishEvent(new SiteFailedEvent(site.getId(),
                    failed.consecutiveFailuresOrZero(), failed.getLastMessage()));
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

    private SeoReport saveSeoReport(String taskId, String url, Long userId) {
        try {
            // 调度线程没有请求上下文，报告按站点归属用户落库；
            // 存量无归属站点（userId 为空）的报告保持无归属，对任何登录用户不可见
            return seoReportService.save(taskId, url, userId);
        } catch (Exception e) {
            log.warn("自动更新 SEO 报告保存失败：taskId={}, {}", taskId, e.getMessage());
            return null;
        }
    }

    private void publishUpdatedEvent(AutoSite site, SeoReport report) {
        try {
            autoSiteService.latestVersion(site.getId()).ifPresent(version -> publishEvent(
                    new SiteUpdatedEvent(site.getId(), version.getId(), version.getVersionNumber(),
                            version.getDiffAdded(), version.getDiffRemoved(), version.getDiffChanged(),
                            report == null ? null : report.getErrorCount(),
                            version.getVersionNumber() == 1)));
        } catch (Exception e) {
            log.warn("站点更新事件发布失败：siteId={}，{}", site.getId(), e.getMessage());
        }
    }

    /** 事件发布异常绝不影响更新结果（监听器异步，此处仅防御发布环节本身） */
    private void publishEvent(Object event) {
        try {
            events.publishEvent(event);
        } catch (Exception e) {
            log.warn("通知事件发布异常：{}", e.getMessage());
        }
    }

    private String buildTaskId(Long siteId) {
        return "auto-" + siteId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
