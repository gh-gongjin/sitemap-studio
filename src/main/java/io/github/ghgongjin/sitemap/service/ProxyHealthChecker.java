package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 代理健康检查器
 * 定期检测代理可用性
 */
@Slf4j
public class ProxyHealthChecker {

    private final ProxyPool proxyPool;
    private final ScheduledExecutorService scheduler;
    private final long checkIntervalMs;
    private final int timeoutMs;
    private final String testUrl;

    public ProxyHealthChecker(ProxyPool proxyPool, long checkIntervalMs, int timeoutMs) {
        this.proxyPool = proxyPool;
        this.checkIntervalMs = checkIntervalMs;
        this.timeoutMs = timeoutMs;
        this.testUrl = "http://www.google.com"; // 用于测试的 URL
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proxy-health-checker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动健康检查
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::checkAllProxies, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        log.info("代理健康检查已启动，间隔：{}ms", checkIntervalMs);
    }

    /**
     * 停止健康检查
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("代理健康检查已停止");
    }

    /**
     * 检查所有代理
     */
    private void checkAllProxies() {
        log.debug("开始代理健康检查");
        int availableBefore = proxyPool.getAvailableCount();

        // 获取所有代理并逐个检查
        for (int i = 0; i < 10; i++) { // 最多检查 10 个
            ProxyPool.ProxyInfo proxy = proxyPool.getNextProxyInfo();
            if (proxy == null) {
                break;
            }
            checkProxy(proxy);
        }

        int availableAfter = proxyPool.getAvailableCount();
        if (availableBefore != availableAfter) {
            log.info("代理健康检查完成，可用代理数：{} -> {}", availableBefore, availableAfter);
        }
    }

    /**
     * 检查单个代理
     */
    private void checkProxy(ProxyPool.ProxyInfo proxy) {
        try {
            URL url = new URL(testUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy.toProxy());
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(false);

            if (proxy.hasAuth()) {
                conn.setRequestProperty("Proxy-Authorization", proxy.getAuthHeader());
            }

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            // 任何响应（包括 4xx）都表示代理可用
            if (responseCode > 0) {
                proxyPool.markSuccess(proxy);
                log.debug("代理健康检查通过：{}", proxy);
            }
        } catch (Exception e) {
            proxyPool.markFailure(proxy);
            log.debug("代理健康检查失败：{} - {}", proxy, e.getMessage());
        }
    }
}
