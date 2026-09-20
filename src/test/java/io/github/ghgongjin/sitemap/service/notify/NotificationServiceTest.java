package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.config.NotifyProperties;
import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @ClassName NotificationServiceTest
 * @Description 触发矩阵：三条件 × 开关 × 边沿规则 × 邮件配额（通道用真实假实现记录调用）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class NotificationServiceTest {

    static class FakeChannel implements NotifyChannel {
        private final String name;
        final List<NotificationPayload> sent = new ArrayList<>();

        FakeChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean send(AutoSite site, NotificationPayload payload) {
            sent.add(payload);
            return true;
        }
    }

    private AutoSiteRepository siteRepository;
    private AutoSiteVersionRepository versionRepository;
    private SeoReportRepository seoReportRepository;
    private NotifyProperties props;
    private FakeChannel webhook;
    private FakeChannel email;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        siteRepository = mock(AutoSiteRepository.class);
        versionRepository = mock(AutoSiteVersionRepository.class);
        seoReportRepository = mock(SeoReportRepository.class);
        props = new NotifyProperties();
        webhook = new FakeChannel("webhook");
        email = new FakeChannel("email");
        service = new NotificationService(siteRepository, versionRepository, seoReportRepository,
                List.of(webhook, email), props);
    }

    private AutoSite configuredSite() {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");
        site.setNotifyWebhookUrl("http://n8n.internal/webhook");
        site.setNotifyEmail("owner@example.com");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        return site;
    }

    private static SiteUpdatedEvent changed(int added, int removed, int changed, Integer seoErrors,
                                            int versionNumber, boolean first) {
        return new SiteUpdatedEvent(1L, 10L, versionNumber, added, removed, changed, seoErrors, first);
    }

    @Test
    void shouldDispatchBothChannelsWhenUrlChanged() {
        configuredSite();
        service.handleUpdated(changed(2, 0, 0, null, 2, false));
        assertThat(webhook.sent).hasSize(1);
        assertThat(email.sent).hasSize(1);
        assertThat(webhook.sent.get(0).type()).isEqualTo(NotificationType.CHANGED);
    }

    @Test
    void shouldStaySilentOnFirstVersionAndWhenNoChange() {
        configuredSite();
        service.handleUpdated(changed(0, 0, 0, null, 1, true));
        service.handleUpdated(changed(0, 0, 0, 1, 2, false));
        assertThat(webhook.sent).isEmpty();
        assertThat(email.sent).isEmpty();
    }

    @Test
    void shouldStaySilentWhenEveryChannelUnconfigured() {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUrl("https://example.com");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        service.handleUpdated(changed(5, 0, 0, null, 2, false));
        assertThat(webhook.sent).isEmpty();
    }

    @Test
    void shouldRespectOnChangeSwitchButNotForSeoTrigger() {
        AutoSite site = configuredSite();
        site.setNotifyOnChange(false);
        service.handleUpdated(changed(5, 0, 0, null, 2, false));
        assertThat(webhook.sent).isEmpty();

        // SEO 跨越不受 onChange 开关抑制：阈值 3、本次 5、上一版报告 1
        site.setNotifySeoErrorThreshold(3);
        AutoSiteVersion previous = new AutoSiteVersion();
        previous.setTaskId("prev-task");
        when(versionRepository.findBySiteIdAndVersionNumber(1L, 1)).thenReturn(Optional.of(previous));
        SeoReport prevReport = new SeoReport();
        prevReport.setErrorCount(1);
        when(seoReportRepository.findByTaskId("prev-task")).thenReturn(Optional.of(prevReport));
        service.handleUpdated(changed(0, 0, 0, 5, 2, false));
        assertThat(webhook.sent).hasSize(1);
    }

    @Test
    void shouldNotRenotifySeoWhilePreviousVersionAlreadyAtOrAboveThreshold() {
        AutoSite site = configuredSite();
        site.setNotifySeoErrorThreshold(3);
        AutoSiteVersion previous = new AutoSiteVersion();
        previous.setTaskId("prev-task");
        when(versionRepository.findBySiteIdAndVersionNumber(1L, 1)).thenReturn(Optional.of(previous));
        SeoReport prevReport = new SeoReport();
        prevReport.setErrorCount(9);   // 上一版已跨：边沿不成立
        when(seoReportRepository.findByTaskId("prev-task")).thenReturn(Optional.of(prevReport));

        service.handleUpdated(changed(0, 0, 0, 5, 2, false));
        assertThat(webhook.sent).isEmpty();
    }

    @Test
    void shouldTreatMissingPreviousVersionOrReportAsZeroForEdgeTrigger() {
        AutoSite site = configuredSite();
        site.setNotifySeoErrorThreshold(3);
        when(versionRepository.findBySiteIdAndVersionNumber(anyLong(), anyInt())).thenReturn(Optional.empty());

        service.handleUpdated(changed(0, 0, 0, 4, 2, false));
        assertThat(webhook.sent).hasSize(1);
    }

    @Test
    void shouldNotifyFailureOnlyAtReminderCounts() {
        configuredSite();
        service.handleFailed(new SiteFailedEvent(1L, 2, "timeout"));
        assertThat(webhook.sent).isEmpty();
        service.handleFailed(new SiteFailedEvent(1L, 3, "timeout"));
        assertThat(webhook.sent).hasSize(1);
        assertThat(webhook.sent.get(0).type()).isEqualTo(NotificationType.FAILED);
        service.handleFailed(new SiteFailedEvent(1L, 11, "timeout"));
        assertThat(webhook.sent).hasSize(1);
    }

    @Test
    void shouldNotifyFailureAtFirstAndTenthReminder() {
        configuredSite();
        service.handleFailed(new SiteFailedEvent(1L, 1, "timeout"));
        assertThat(webhook.sent).hasSize(1);
        assertThat(webhook.sent.get(0).type()).isEqualTo(NotificationType.FAILED);

        service.handleFailed(new SiteFailedEvent(1L, 2, "timeout"));
        assertThat(webhook.sent).hasSize(1);

        service.handleFailed(new SiteFailedEvent(1L, 10, "timeout"));
        assertThat(webhook.sent).hasSize(2);
        assertThat(webhook.sent.get(1).type()).isEqualTo(NotificationType.FAILED);

        service.handleFailed(new SiteFailedEvent(1L, 11, "timeout"));
        assertThat(webhook.sent).hasSize(2);
    }

    @Test
    void shouldDispatchWebhookOnlyWhenNotifyEmailBlank() {
        AutoSite site = configuredSite();
        site.setNotifyEmail("   ");
        service.handleUpdated(changed(3, 0, 0, null, 2, false));
        assertThat(webhook.sent).hasSize(1);
        assertThat(email.sent).isEmpty();
    }

    @Test
    void shouldRespectFailureSwitchAndGlobalKillSwitch() {
        AutoSite site = configuredSite();
        site.setNotifyOnFailure(false);
        service.handleFailed(new SiteFailedEvent(1L, 1, "boom"));
        assertThat(webhook.sent).isEmpty();

        site.setNotifyOnFailure(true);
        props.setEnabled(false);
        service.handleFailed(new SiteFailedEvent(1L, 1, "boom"));
        service.handleUpdated(changed(9, 0, 0, null, 2, false));
        assertThat(webhook.sent).isEmpty();
    }

    @Test
    void shouldSkipEmailBeyondDailyQuotaButKeepWebhook() {
        configuredSite();
        props.setMaxEmailsPerSitePerDay(2);
        service.handleUpdated(changed(1, 0, 0, null, 2, false));
        service.handleUpdated(changed(1, 0, 0, null, 2, false));
        service.handleUpdated(changed(1, 0, 0, null, 2, false));
        assertThat(webhook.sent).hasSize(3);
        assertThat(email.sent).hasSize(2);
    }

    @Test
    void testShouldDeliverToConfiguredChannelsAndReportOutcome() {
        configuredSite();
        NotifyOutcome outcome = service.test(1L);
        assertThat(outcome.success()).isTrue();
        assertThat(webhook.sent).hasSize(1);
        assertThat(webhook.sent.get(0).type()).isEqualTo(NotificationType.TEST);
        assertThat(email.sent).hasSize(1);
    }

    @Test
    void testShouldFailWhenNoChannelConfiguredOrSiteMissing() {
        when(siteRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.test(1L).success()).isFalse();

        AutoSite bare = new AutoSite();
        bare.setId(1L);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(bare));
        NotifyOutcome outcome = service.test(1L);
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.messageKey()).isEqualTo("auto.notify.err.noChannel");
    }

    @Test
    void dispatchShouldSwallowChannelExceptions() {
        configuredSite();
        FakeChannel broken = new FakeChannel("webhook") {
            @Override
            public boolean send(AutoSite site, NotificationPayload payload) {
                throw new IllegalStateException("boom");
            }
        };
        NotificationService mixed = new NotificationService(siteRepository, versionRepository,
                seoReportRepository, List.of(broken, webhook), props);
        mixed.handleUpdated(changed(1, 0, 0, null, 2, false));
        assertThat(webhook.sent).hasSize(1);   // 一个通道炸不影响另一个
    }

    @Test
    void listenerShouldNotRethrowWhenRepositoryFails() {
        when(siteRepository.findById(1L)).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> service.onSiteUpdated(changed(1, 0, 0, null, 2, false)))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.onSiteFailed(new SiteFailedEvent(1L, 1, "boom")))
                .doesNotThrowAnyException();
    }
}
