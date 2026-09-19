package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @ClassName AutoSiteSchedulerTest
 * @Description 自动更新调度单元测试（同步执行器，验证排期与串行分发）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class AutoSiteSchedulerTest {

    private AutoSiteService autoSiteService;
    private AutoSiteUpdater autoSiteUpdater;
    private ExecutorService worker;
    private AutoSiteScheduler scheduler;

    @BeforeEach
    void setUp() {
        autoSiteService = mock(AutoSiteService.class);
        autoSiteUpdater = mock(AutoSiteUpdater.class);
        worker = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(worker).execute(any(Runnable.class));
        scheduler = new AutoSiteScheduler(autoSiteService, autoSiteUpdater, worker);
    }

    @Test
    void shouldMarkRunningAndDispatchWhenSitesDue() {
        // Given
        AutoSite first = site(1L);
        AutoSite second = site(2L);
        when(autoSiteService.dueSites()).thenReturn(List.of(first, second));
        when(autoSiteService.markRunning(1L)).thenReturn(first);
        when(autoSiteService.markRunning(2L)).thenReturn(second);

        // When
        scheduler.scan();

        // Then
        verify(autoSiteService).markRunning(1L);
        verify(autoSiteService).markRunning(2L);
        verify(autoSiteUpdater).update(first);
        verify(autoSiteUpdater).update(second);
    }

    @Test
    void shouldDoNothingWhenNoSitesDue() {
        // Given
        when(autoSiteService.dueSites()).thenReturn(List.of());

        // When
        scheduler.scan();

        // Then
        verifyNoInteractions(autoSiteUpdater);
        verify(autoSiteService, never()).markRunning(anyLong());
    }

    @Test
    void shouldSkipFailedSiteAndContinueWhenMarkRunningThrows() {
        // Given
        AutoSite broken = site(1L);
        AutoSite healthy = site(2L);
        when(autoSiteService.dueSites()).thenReturn(List.of(broken, healthy));
        when(autoSiteService.markRunning(1L)).thenThrow(new IllegalArgumentException("站点不存在"));
        when(autoSiteService.markRunning(2L)).thenReturn(healthy);

        // When
        scheduler.scan();

        // Then
        verify(autoSiteUpdater, never()).update(broken);
        verify(autoSiteUpdater).update(healthy);
    }

    @Test
    void shouldSwallowScanFailureAndSkipRoundWhenDueQueryThrows() {
        // Given
        when(autoSiteService.dueSites()).thenThrow(new RuntimeException("db down"));

        // When
        scheduler.scan();

        // Then
        verifyNoInteractions(autoSiteUpdater);
    }

    @Test
    void shouldShutdownWorkerWhenContextCloses() {
        // When
        scheduler.shutdown();

        // Then
        verify(worker).shutdownNow();
    }

    @Test
    void shouldNotDispatchWhenSubmitRejected() {
        // Given
        AutoSite site = site(1L);
        when(autoSiteService.dueSites()).thenReturn(List.of(site));
        when(autoSiteService.markRunning(1L)).thenReturn(site);
        doThrow(new java.util.concurrent.RejectedExecutionException("closed"))
                .when(worker).execute(any(Runnable.class));

        // When
        scheduler.scan();

        // Then
        verify(autoSiteUpdater, never()).update(any(AutoSite.class));
    }

    private AutoSite site(Long id) {
        AutoSite site = new AutoSite();
        site.setId(id);
        site.setUrl("https://example.com/" + id);
        site.setIntervalHours(24);
        site.setEnabled(true);
        return site;
    }
}
