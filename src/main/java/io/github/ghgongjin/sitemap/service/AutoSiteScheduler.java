package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @ClassName AutoSiteScheduler
 * @Description 自动更新调度：周期性扫描到期站点，交给单线程执行器串行更新
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "sitemap.auto-update.enabled", havingValue = "true", matchIfMissing = true)
public class AutoSiteScheduler {

    private final AutoSiteService autoSiteService;
    private final AutoSiteUpdater autoSiteUpdater;
    private final ExecutorService worker;

    @Autowired
    public AutoSiteScheduler(AutoSiteService autoSiteService, AutoSiteUpdater autoSiteUpdater) {
        this(autoSiteService, autoSiteUpdater, singleThreadWorker());
    }

    AutoSiteScheduler(AutoSiteService autoSiteService, AutoSiteUpdater autoSiteUpdater, ExecutorService worker) {
        this.autoSiteService = autoSiteService;
        this.autoSiteUpdater = autoSiteUpdater;
        this.worker = worker;
    }

    /**
     * 每次先占坑（RUNNING + 下次执行推到未来），再提交执行，避免重复触发
     */
    @Scheduled(initialDelayString = "${sitemap.auto-update.initial-delay-ms:30000}",
            fixedDelayString = "${sitemap.auto-update.scan-interval-ms:60000}")
    public void scan() {
        List<AutoSite> due;
        try {
            due = autoSiteService.dueSites();
        } catch (Exception e) {
            log.error("自动更新扫描失败：{}", e.getMessage(), e);
            return;
        }
        if (due.isEmpty()) {
            return;
        }
        log.info("自动更新扫描：{} 个站点到期", due.size());
        for (AutoSite site : due) {
            try {
                AutoSite running = autoSiteService.markRunning(site.getId());
                worker.execute(() -> autoSiteUpdater.update(running));
            } catch (Exception e) {
                log.error("自动更新排期失败：siteId={}, {}", site.getId(), e.getMessage(), e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }

    private static ExecutorService singleThreadWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "auto-site-updater");
            t.setDaemon(true);
            return t;
        });
    }
}
