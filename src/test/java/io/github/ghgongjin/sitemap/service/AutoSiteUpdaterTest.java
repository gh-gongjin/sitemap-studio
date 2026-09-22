package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.service.notify.SiteFailedEvent;
import io.github.ghgongjin.sitemap.service.notify.SiteUpdatedEvent;
import io.github.ghgongjin.sitemap.service.push.SitemapPushService;
import io.github.ghgongjin.sitemap.service.submission.SearchEngineSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName AutoSiteUpdaterTest
 * @Description 单站点自动更新执行单元测试
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class AutoSiteUpdaterTest {

    private static final String SITE = "https://example.com";
    private static final String XML = "<urlset></urlset>";
    private static final Long OWNER = 42L;

    private AutoSiteService autoSiteService;
    private EnhancedSitemapGeneratorService enhancedService;
    private CrawlProgressService progressService;
    private SeoReportService seoReportService;
    private SitemapPushService pushService;
    private SearchEngineSubmissionService submissionService;
    private ApplicationEventPublisher events;
    private AutoSiteUpdater updater;

    @BeforeEach
    void setUp() {
        autoSiteService = mock(AutoSiteService.class);
        enhancedService = mock(EnhancedSitemapGeneratorService.class);
        progressService = mock(CrawlProgressService.class);
        seoReportService = mock(SeoReportService.class);
        pushService = mock(SitemapPushService.class);
        submissionService = mock(SearchEngineSubmissionService.class);
        events = mock(ApplicationEventPublisher.class);
        updater = new AutoSiteUpdater(autoSiteService, enhancedService, progressService,
                seoReportService, pushService, submissionService, events);
        // recordSuccess 返回后 updater 会重读最新版本与失败站点，给默认桩
        when(autoSiteService.latestVersion(anyLong())).thenReturn(Optional.of(successVersion(1)));
        when(autoSiteService.recordFailure(anyLong(), any()))
                .thenAnswer(inv -> failedSite(1));
    }

    private static io.github.ghgongjin.sitemap.entity.AutoSiteVersion successVersion(int vno) {
        io.github.ghgongjin.sitemap.entity.AutoSiteVersion version = new io.github.ghgongjin.sitemap.entity.AutoSiteVersion();
        version.setId((long) vno);
        version.setSiteId(1L);
        version.setVersionNumber(vno);
        version.setTaskId("task-" + vno);
        return version;
    }

    private static AutoSite failedSite(int failures) {
        AutoSite s = site(1L);
        s.setConsecutiveFailures(failures);
        s.setLastMessage("站点地图生成失败: 连接超时");
        return s;
    }

    @Test
    void shouldRecordSuccessWhenCrawlCompletes() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(eq(SITE), eq(true), eq(false), eq(true), anyString()))
                .thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(12));

        // When
        boolean updated = updater.update(site);

        // Then
        assertThat(updated).isTrue();
        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);
        verify(autoSiteService).recordSuccess(eq(1L), taskId.capture(), eq(XML), eq(12));
        assertThat(taskId.getValue()).startsWith("auto-1-");
    }

    @Test
    void shouldPersistSeoReportOwnedBySiteOwnerWhenUpdateSucceeds() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(3));

        // When
        updater.update(site);

        // Then: 调度线程没有请求上下文，报告按站点归属用户落库
        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);
        verify(autoSiteService).recordSuccess(eq(1L), taskId.capture(), anyString(), anyInt());
        verify(seoReportService).save(eq(taskId.getValue()), eq(SITE), eq(OWNER));
    }

    @Test
    void shouldPersistReportWithoutOwnerWhenLegacySiteHasNoOwner() {
        // Given: 升级前的存量站点，user_id 为空
        AutoSite site = site(1L);
        site.setUserId(null);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(3));

        // When
        updater.update(site);

        // Then: 报告保持无归属，不会错绑到任何用户
        verify(seoReportService).save(anyString(), eq(SITE), isNull());
    }

    @Test
    void shouldRecordFailureWhenCrawlThrows() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenThrow(new RuntimeException("站点地图生成失败: 连接超时"));

        // When
        boolean updated = updater.update(site);

        // Then
        assertThat(updated).isFalse();
        verify(autoSiteService).recordFailure(1L, "站点地图生成失败: 连接超时");
        verify(autoSiteService, never()).recordSuccess(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldKeepSuccessWhenSeoReportSaveFails() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(5));
        doThrow(new RuntimeException("db down")).when(seoReportService).save(anyString(), anyString(), any());

        // When
        boolean updated = updater.update(site);

        // Then
        assertThat(updated).isTrue();
        verify(autoSiteService).recordSuccess(eq(1L), startsWith("auto-1-"), eq(XML), eq(5));
        verify(autoSiteService, never()).recordFailure(anyLong(), anyString());
    }

    @Test
    void shouldUseZeroCountWhenTaskResultMissing() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(null);

        // When
        updater.update(site);

        // Then
        verify(autoSiteService).recordSuccess(eq(1L), startsWith("auto-1-"), eq(XML), eq(0));
    }

    @Test
    void shouldPushLatestVersionAfterUpdateSucceeds() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(4));

        // When
        updater.update(site);

        // Then
        verify(pushService).push(1L);
    }

    @Test
    void shouldKeepSuccessWhenPushThrows() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(4));
        doThrow(new RuntimeException("push down")).when(pushService).push(1L);

        // When
        boolean updated = updater.update(site);

        // Then
        assertThat(updated).isTrue();
        verify(autoSiteService).recordSuccess(eq(1L), startsWith("auto-1-"), eq(XML), eq(4));
        verify(autoSiteService, never()).recordFailure(anyLong(), anyString());
    }

    @Test
    void shouldNotPushWhenCrawlFails() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenThrow(new RuntimeException("站点地图生成失败: 连接超时"));

        // When
        updater.update(site);

        // Then
        verify(pushService, never()).push(anyLong());
    }

    @Test
    void shouldSubmitAfterUpdateWhenPushSucceeded() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(4));

        // When
        updater.update(site);

        // Then
        verify(submissionService).submit(1L);
    }

    @Test
    void shouldPushBeforeSubmitToSearchEnginesWhenUpdateSucceeds() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(4));

        // When
        updater.update(site);

        // Then：挂点语义——必须先推送、后提交（提交依赖远端已能访问最新 sitemap）
        InOrder order = inOrder(pushService, submissionService);
        order.verify(pushService).push(1L);
        order.verify(submissionService).submit(1L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void shouldKeepSuccessWhenSubmissionThrows() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(4));
        doThrow(new RuntimeException("submit down")).when(submissionService).submit(1L);

        // When & Then: 提交异常不影响更新结果与站点状态
        assertThat(updater.update(site)).isTrue();
        verify(autoSiteService, never()).recordFailure(anyLong(), anyString());
    }

    @Test
    void shouldNotSubmitWhenUpdateFailed() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenThrow(new RuntimeException("站点地图生成失败: 连接超时"));

        // When
        updater.update(site);

        // Then
        verify(submissionService, never()).submit(anyLong());
    }

    @Test
    void shouldPublishSiteUpdatedEventWithDiffCountsWhenSecondVersion() {
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(7));
        io.github.ghgongjin.sitemap.entity.AutoSiteVersion v2 = successVersion(2);
        v2.setDiffAdded(3);
        v2.setDiffRemoved(1);
        v2.setDiffChanged(2);
        when(autoSiteService.latestVersion(1L)).thenReturn(Optional.of(v2));
        io.github.ghgongjin.sitemap.entity.SeoReport report = new io.github.ghgongjin.sitemap.entity.SeoReport();
        report.setErrorCount(5);
        when(seoReportService.save(anyString(), anyString(), any())).thenReturn(report);

        updater.update(site);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        SiteUpdatedEvent event = (SiteUpdatedEvent) captor.getValue();
        assertThat(event.siteId()).isEqualTo(1L);
        assertThat(event.versionNumber()).isEqualTo(2);
        assertThat(event.diffAdded()).isEqualTo(3);
        assertThat(event.seoErrorCount()).isEqualTo(5);
        assertThat(event.firstVersion()).isFalse();
    }

    @Test
    void shouldMarkFirstVersionWithoutSeoReportWhenNoReportSaved() {
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(1));
        // latestVersion 默认桩为版本 1；seoReportService.save 默认返回 null

        updater.update(site);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        SiteUpdatedEvent event = (SiteUpdatedEvent) captor.getValue();
        assertThat(event.firstVersion()).isTrue();
        assertThat(event.seoErrorCount()).isNull();
    }

    @Test
    void shouldPublishSiteFailedEventWithConsecutiveCountWhenCrawlFails() {
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenThrow(new IllegalStateException("boom"));
        when(autoSiteService.recordFailure(anyLong(), any())).thenAnswer(inv -> failedSite(3));

        updater.update(site);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        SiteFailedEvent event = (SiteFailedEvent) captor.getValue();
        assertThat(event.siteId()).isEqualTo(1L);
        assertThat(event.consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void shouldStillRecordSuccessWhenEventPublishThrows() {
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(1));
        org.mockito.Mockito.doThrow(new RuntimeException("publisher down"))
                .when(events).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));

        assertThat(updater.update(site)).isTrue();
        verify(pushService).push(1L);
    }

    private static AutoSite site(Long id) {
        AutoSite site = new AutoSite();
        site.setId(id);
        site.setUserId(OWNER);
        site.setUrl(SITE);
        site.setIncludeImages(true);
        site.setIncludeVideos(false);
        site.setIncludeNews(true);
        site.setIntervalHours(24);
        site.setEnabled(true);
        return site;
    }

    private CrawlProgressService.TaskResult result(int totalPages) {
        CrawlProgressService.TaskResult result = new CrawlProgressService.TaskResult("t", "completed", SITE);
        result.markCompleted(totalPages, XML);
        return result;
    }
}
