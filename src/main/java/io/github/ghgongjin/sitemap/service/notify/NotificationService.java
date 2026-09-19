package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.config.NotifyProperties;
import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @ClassName NotificationService
 * @Description 告警唯一监听器：事件 → 触发判定（URL 变化 / 连续失败 1·3·10 / SEO 跨阈值边沿）→
 *              载荷组装 → 双通道分发。全部入口异步且吞异常，任何失败不影响爬取主流程
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final Set<Integer> FAILURE_REMINDERS = Set.of(1, 3, 10);

    private final AutoSiteRepository siteRepository;
    private final AutoSiteVersionRepository versionRepository;
    private final SeoReportRepository seoReportRepository;
    private final List<NotifyChannel> channels;
    private final NotifyProperties props;

    private final Map<String, AtomicInteger> mailQuota = new ConcurrentHashMap<>();

    @EventListener
    @Async("notifyExecutor")
    public void onSiteUpdated(SiteUpdatedEvent event) {
        try {
            handleUpdated(event);
        } catch (Exception e) {
            log.warn("更新通知处理异常：siteId={}，{}", event == null ? null : event.siteId(), e.getMessage());
        }
    }

    @EventListener
    @Async("notifyExecutor")
    public void onSiteFailed(SiteFailedEvent event) {
        try {
            handleFailed(event);
        } catch (Exception e) {
            log.warn("失败通知处理异常：siteId={}，{}", event == null ? null : event.siteId(), e.getMessage());
        }
    }

    void handleUpdated(SiteUpdatedEvent event) {
        if (!props.isEnabled() || event.firstVersion()) {
            return;
        }
        AutoSite site = findConfiguredSite(event.siteId());
        if (site == null) {
            return;
        }
        boolean urlTriggered = event.diffAdded() + event.diffRemoved() + event.diffChanged() > 0
                && site.isNotifyOnChangeEffective();
        if (!urlTriggered && !seoCrossed(site, event)) {
            return;
        }
        dispatch(site, NotificationPayload.ofChanged(site, event));
    }

    void handleFailed(SiteFailedEvent event) {
        if (!props.isEnabled()) {
            return;
        }
        AutoSite site = findConfiguredSite(event.siteId());
        if (site == null || !site.isNotifyOnFailureEffective()
                || !FAILURE_REMINDERS.contains(event.consecutiveFailures())) {
            return;
        }
        dispatch(site, NotificationPayload.ofFailed(site, event));
    }

    public NotifyOutcome test(Long siteId) {
        AutoSite site = siteId == null ? null : siteRepository.findById(siteId).orElse(null);
        if (site == null) {
            return new NotifyOutcome(false, "auto.error.notFound", null);
        }
        if (!site.hasNotifyChannelConfigured()) {
            return new NotifyOutcome(false, "auto.notify.err.noChannel", null);
        }
        NotificationPayload payload = NotificationPayload.ofTest(site);
        boolean delivered = false;
        for (NotifyChannel channel : channels) {
            if (!applies(channel, site)) {
                continue;
            }
            try {
                delivered |= channel.send(site, payload);
            } catch (Exception e) {
                log.warn("测试通知通道异常：channel={}，{}", channel.name(), e.getMessage());
            }
        }
        return delivered
                ? new NotifyOutcome(true, "auto.notify.flash.testSent", null)
                : new NotifyOutcome(false, "auto.notify.flash.testFailed", "投递未成功 delivery attempted but failed");
    }

    private boolean seoCrossed(AutoSite site, SiteUpdatedEvent event) {
        int threshold = site.notifySeoErrorThresholdOrOff();
        if (threshold < 0 || event.seoErrorCount() == null || event.seoErrorCount() < threshold) {
            return false;
        }
        return previousSeoErrorCount(event) < threshold;
    }

    private int previousSeoErrorCount(SiteUpdatedEvent event) {
        return versionRepository.findBySiteIdAndVersionNumber(event.siteId(), event.versionNumber() - 1)
                .flatMap(prev -> seoReportRepository.findByTaskId(prev.getTaskId()))
                .map(SeoReport::getErrorCount)
                .orElse(0);
    }

    private AutoSite findConfiguredSite(Long siteId) {
        if (siteId == null) {
            return null;
        }
        return siteRepository.findById(siteId)
                .filter(AutoSite::hasNotifyChannelConfigured)
                .orElse(null);
    }

    private void dispatch(AutoSite site, NotificationPayload payload) {
        for (NotifyChannel channel : channels) {
            if (!applies(channel, site)) {
                continue;
            }
            if ("email".equals(channel.name()) && !mailQuotaAllows(site)) {
                continue;
            }
            try {
                if (!channel.send(site, payload)) {
                    log.warn("通知投递未成功：siteId={}，channel={}，type={}",
                            site.getId(), channel.name(), payload.type());
                }
            } catch (Exception e) {
                log.warn("通知投递异常：siteId={}，channel={}，{}",
                        site.getId(), channel.name(), e.getMessage());
            }
        }
    }

    private static boolean applies(NotifyChannel channel, AutoSite site) {
        return switch (channel.name()) {
            case "webhook" -> site.getNotifyWebhookUrl() != null && !site.getNotifyWebhookUrl().isBlank();
            case "email" -> site.getNotifyEmail() != null && !site.getNotifyEmail().isBlank();
            default -> true;
        };
    }

    private boolean mailQuotaAllows(AutoSite site) {
        String today = LocalDate.now().toString();
        mailQuota.keySet().removeIf(key -> !key.endsWith(":" + today));
        String key = site.getId() + ":" + today;
        int sent = mailQuota.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (sent > props.getMaxEmailsPerSitePerDay()) {
            log.warn("邮件通知当日配额已用完：siteId={}", site.getId());
            return false;
        }
        return true;
    }
}
