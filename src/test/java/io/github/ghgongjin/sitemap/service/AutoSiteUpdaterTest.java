package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.service.push.SitemapPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
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

    private AutoSiteService autoSiteService;
    private EnhancedSitemapGeneratorService enhancedService;
    private CrawlProgressService progressService;
    private SeoReportService seoReportService;
    private SitemapPushService pushService;
    private AutoSiteUpdater updater;

    @BeforeEach
    void setUp() {
        autoSiteService = mock(AutoSiteService.class);
        enhancedService = mock(EnhancedSitemapGeneratorService.class);
        progressService = mock(CrawlProgressService.class);
        seoReportService = mock(SeoReportService.class);
        pushService = mock(SitemapPushService.class);
        updater = new AutoSiteUpdater(autoSiteService, enhancedService, progressService, seoReportService, pushService);
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
    void shouldPersistSeoReportWithSameTaskWhenUpdateSucceeds() {
        // Given
        AutoSite site = site(1L);
        when(enhancedService.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyString())).thenReturn(XML);
        when(progressService.getTaskResult(anyString())).thenReturn(result(3));

        // When
        updater.update(site);

        // Then
        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);
        verify(autoSiteService).recordSuccess(eq(1L), taskId.capture(), anyString(), anyInt());
        verify(seoReportService).save(eq(taskId.getValue()), eq(SITE));
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
        doThrow(new RuntimeException("db down")).when(seoReportService).save(anyString(), anyString());

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

    private AutoSite site(Long id) {
        AutoSite site = new AutoSite();
        site.setId(id);
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
