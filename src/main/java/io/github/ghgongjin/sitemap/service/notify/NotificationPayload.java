package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;

import java.time.LocalDateTime;

/**
 * @ClassName NotificationPayload
 * @Description 通道无关的通知载荷：由事件 + 站点配置归一化而来，webhook/邮件共用
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public record NotificationPayload(NotificationType type, String siteUrl, Integer versionNumber,
                                  int added, int removed, int changed, Integer seoErrorCount,
                                  Integer seoErrorThreshold, Integer consecutiveFailures,
                                  String failureMessage, LocalDateTime occurredAt) {

    public static NotificationPayload ofChanged(AutoSite site, SiteUpdatedEvent e) {
        return new NotificationPayload(NotificationType.CHANGED, site.getUrl(), e.versionNumber(),
                e.diffAdded(), e.diffRemoved(), e.diffChanged(),
                e.seoErrorCount(), site.notifySeoErrorThresholdOrOff(), null, null, LocalDateTime.now());
    }

    public static NotificationPayload ofFailed(AutoSite site, SiteFailedEvent e) {
        return new NotificationPayload(NotificationType.FAILED, site.getUrl(), null,
                0, 0, 0, null, site.notifySeoErrorThresholdOrOff(),
                e.consecutiveFailures(), e.message(), LocalDateTime.now());
    }

    public static NotificationPayload ofTest(AutoSite site) {
        return new NotificationPayload(NotificationType.TEST, site.getUrl(), null,
                0, 0, 0, null, site.notifySeoErrorThresholdOrOff(), null, null, LocalDateTime.now());
    }
}
