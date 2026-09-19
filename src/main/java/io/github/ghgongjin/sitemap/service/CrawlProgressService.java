package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CrawlProgressService {

    private static final int MAX_CACHED_RESULTS = 50;
    private static final long TASK_EXPIRE_MS = 30 * 60 * 1000L;

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, ProgressInfo> progressMap = new ConcurrentHashMap<>();
    private final Map<String, TaskResult> resultMap = new ConcurrentHashMap<>();

    public CrawlProgressService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        startCleanupScheduler();
    }

    public void startTask(String taskId, String url) {
        startTask(taskId, url, false, false, false);
    }

    public void startTask(String taskId, String url, boolean includeImages, boolean includeVideos) {
        startTask(taskId, url, includeImages, includeVideos, false);
    }

    public void startTask(String taskId, String url, boolean includeImages, boolean includeVideos, boolean includeNews) {
        TaskResult result = new TaskResult(taskId, "running", url, includeImages, includeVideos, includeNews);
        if (resultMap.putIfAbsent(taskId, result) != null) {
            return;
        }
        progressMap.putIfAbsent(taskId, new ProgressInfo(taskId, url));

        log.info("爬取任务开始：{}, URL: {}", taskId, url);
        sendProgress(taskId, "started", "开始爬取...", 0, 0, 0, "");
    }

    public void updateProgress(String taskId, int crawledPages, int totalPages, String currentUrl) {
        ProgressInfo info = progressMap.get(taskId);
        if (info != null) {
            info.update(crawledPages, currentUrl);

            double percentage = totalPages > 0 ? (crawledPages * 100.0 / totalPages) : 0;

            sendProgress(taskId, "progressing",
                    "正在爬取：" + truncateUrl(currentUrl),
                    (int) percentage, crawledPages, totalPages, currentUrl);

            log.debug("进度更新 - {}: {}/{} ({}%), 当前：{}",
                    taskId, crawledPages, totalPages, (int) percentage, truncateUrl(currentUrl));
        }
    }

    public void completeTask(String taskId, int totalUrls, String sitemapXml) {
        ProgressInfo info = progressMap.remove(taskId);
        log.info("爬取任务完成：{}, 总 URL 数：{}", taskId, totalUrls);

        TaskResult result = resultMap.get(taskId);
        if (result != null) {
            result.markCompleted(totalUrls, sitemapXml);
        }

        sendProgress(taskId, "completed",
                "爬取完成！共找到 " + totalUrls + " 个 URL",
                100, totalUrls, totalUrls, "");
    }

    public void failTask(String taskId, String errorMessage) {
        ProgressInfo info = progressMap.remove(taskId);
        log.error("爬取任务失败：{}, 错误：{}", taskId, errorMessage);

        TaskResult result = resultMap.get(taskId);
        if (result != null) {
            int crawledPages = info != null ? info.getCrawledPages() : 0;
            result.markFailed(errorMessage, crawledPages);
        }

        int percentage = info != null ? info.getPercentage() : 0;
        int crawledPages = info != null ? info.getCrawledPages() : 0;
        sendProgress(taskId, "failed",
                "爬取失败：" + errorMessage,
                percentage, crawledPages, 0, "");
    }

    public TaskResult getTaskResult(String taskId) {
        return resultMap.get(taskId);
    }

    public ProgressInfo getProgress(String taskId) {
        return progressMap.get(taskId);
    }

    private void sendProgress(String taskId, String status, String message,
                              int percentage, int crawledPages, int totalPages,
                              String currentUrl) {
        try {
            Map<String, Object> progressData = new ConcurrentHashMap<>();
            progressData.put("taskId", taskId);
            progressData.put("status", status);
            progressData.put("message", message);
            progressData.put("percentage", percentage);
            progressData.put("crawledPages", crawledPages);
            progressData.put("totalPages", totalPages);
            progressData.put("currentUrl", currentUrl);
            progressData.put("timestamp", System.currentTimeMillis());

            messagingTemplate.convertAndSend("/topic/progress/" + taskId, progressData);
        } catch (Exception e) {
            log.error("发送进度消息失败：{}", e.getMessage(), e);
        }
    }

    private void startCleanupScheduler() {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-cleanup");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(this::cleanupExpiredTasks, 5, 5, TimeUnit.MINUTES);
    }

    private void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, TaskResult> entry : resultMap.entrySet()) {
            if (now - entry.getValue().createdAt > TASK_EXPIRE_MS) {
                resultMap.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("清理过期任务：{} 个", removed);
        }
    }

    private String truncateUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.length() <= 80) return url;
        return url.substring(0, 77) + "...";
    }

    public static class TaskResult {
        private final String taskId;
        private final String url;
        private final boolean includeImages;
        private final boolean includeVideos;
        private final boolean includeNews;
        private final long createdAt;
        private volatile String status;
        private volatile String errorMessage;
        private volatile String sitemapXml;
        private volatile int totalPages;
        private volatile int crawledPages;

        public TaskResult(String taskId, String status, String url) {
            this(taskId, status, url, false, false, false);
        }

        public TaskResult(String taskId, String status, String url, boolean includeImages, boolean includeVideos) {
            this(taskId, status, url, includeImages, includeVideos, false);
        }

        public TaskResult(String taskId, String status, String url, boolean includeImages, boolean includeVideos,
                          boolean includeNews) {
            this.taskId = taskId;
            this.status = status;
            this.url = url;
            this.includeImages = includeImages;
            this.includeVideos = includeVideos;
            this.includeNews = includeNews;
            this.createdAt = System.currentTimeMillis();
        }

        public void markCompleted(int totalPages, String sitemapXml) {
            this.totalPages = totalPages;
            this.crawledPages = totalPages;
            this.sitemapXml = sitemapXml;
            this.status = "completed";
        }

        public void markFailed(String errorMessage, int crawledPages) {
            this.errorMessage = errorMessage;
            this.crawledPages = crawledPages;
            this.status = "failed";
        }

        public String getTaskId() { return taskId; }
        public String getUrl() { return url; }
        public boolean isIncludeImages() { return includeImages; }
        public boolean isIncludeVideos() { return includeVideos; }
        public boolean isIncludeNews() { return includeNews; }
        public String getStatus() { return status; }
        public String getErrorMessage() { return errorMessage; }
        public String getSitemapXml() { return sitemapXml; }
        public int getTotalPages() { return totalPages; }
        public int getCrawledPages() { return crawledPages; }
        public long getCreatedAt() { return createdAt; }
    }

    public static class ProgressInfo {
        private final String taskId;
        private final String url;
        private int crawledPages;
        private String currentUrl;
        private final long startTime;

        public ProgressInfo(String taskId, String url) {
            this.taskId = taskId;
            this.url = url;
            this.crawledPages = 0;
            this.currentUrl = "";
            this.startTime = System.currentTimeMillis();
        }

        public void update(int crawledPages, String currentUrl) {
            this.crawledPages = crawledPages;
            this.currentUrl = currentUrl;
        }

        public int getPercentage() {
            return Math.min(100, crawledPages);
        }

        public int getCrawledPages() { return crawledPages; }
        public String getCurrentUrl() { return currentUrl; }
        public long getElapsedTime() { return System.currentTimeMillis() - startTime; }
    }
}
