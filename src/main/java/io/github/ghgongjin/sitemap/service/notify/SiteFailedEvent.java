package io.github.ghgongjin.sitemap.service.notify;

/**
 * @ClassName SiteFailedEvent
 * @Description 站点自动更新失败事件：携带连续失败次数与失败原因，供通知侧消费
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
public record SiteFailedEvent(Long siteId, int consecutiveFailures, String message) {
}
