package io.github.ghgongjin.sitemap.service.notify;

/**
 * @ClassName SiteUpdatedEvent
 * @Description 站点自动更新成功事件：携带本次版本的 diff 计数与 SEO 报告错误数，供通知侧消费
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
public record SiteUpdatedEvent(Long siteId, Long versionId, int versionNumber,
                               int diffAdded, int diffRemoved, int diffChanged,
                               Integer seoErrorCount, boolean firstVersion) {
}
