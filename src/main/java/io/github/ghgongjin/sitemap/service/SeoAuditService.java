package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName SeoAuditService
 * @Description SEO 健康度采集：爬取过程中逐页采集信号，任务结束后汇总为问题清单与评分
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Service
public class SeoAuditService {

    public static final int SLOW_PAGE_MS = 2000;
    public static final int TITLE_MAX_LENGTH = 60;
    public static final int DESCRIPTION_MAX_LENGTH = 160;

    private static final int MAX_ISSUES = 500;
    private static final long AUDIT_EXPIRE_MS = 30 * 60 * 1000L;
    private static final int MAX_CACHED_AUDITS = 50;
    private static final int ERROR_WEIGHT = 10;
    private static final int WARNING_WEIGHT = 3;
    private static final int INFO_WEIGHT = 1;

    private final Map<String, AuditState> audits = new ConcurrentHashMap<>();

    public SeoAuditService() {
        startCleanupScheduler();
    }

    public void beginAudit(String taskId) {
        if (taskId == null) {
            return;
        }
        if (audits.size() >= MAX_CACHED_AUDITS) {
            audits.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().startedAt))
                    .ifPresent(oldest -> audits.remove(oldest.getKey()));
        }
        audits.putIfAbsent(taskId, new AuditState());
    }

    /**
     * 记录一个已成功抓取的 HTML 页面样本
     */
    public void recordPage(String taskId, PageSeo page) {
        AuditState state = stateOf(taskId);
        if (state == null || page == null) {
            return;
        }
        state.pagesAudited++;
        for (Issue issue : evaluate(page)) {
            addIssue(state, issue);
        }
    }

    /**
     * 记录跳过的页面：不纳入站点地图与评分（noindex、非 HTML、robots 禁止等）
     */
    public void recordSkipped(String taskId, String url, String reason) {
        AuditState state = stateOf(taskId);
        if (state == null) {
            return;
        }
        state.skippedPages++;
    }

    /**
     * 记录断链：抓取返回 4xx/5xx
     */
    public void recordBroken(String taskId, String url, int statusCode) {
        AuditState state = stateOf(taskId);
        if (state == null) {
            return;
        }
        state.brokenLinks++;
        addIssue(state, new Issue("broken-link", Severity.ERROR, url, String.valueOf(statusCode)));
    }

    /**
     * 记录无法访问：重试后仍失败
     */
    public void recordUnreachable(String taskId, String url, String reason) {
        AuditState state = stateOf(taskId);
        if (state == null) {
            return;
        }
        state.brokenLinks++;
        addIssue(state, new Issue("unreachable", Severity.ERROR, url, reason == null ? "" : reason));
    }

    public AuditSummary summarize(String taskId) {
        AuditState state = taskId == null ? null : audits.get(taskId);
        if (state == null) {
            return AuditSummary.empty();
        }
        List<Issue> issues;
        boolean truncated;
        synchronized (state.issues) {
            issues = new ArrayList<>(state.issues);
            truncated = state.truncated;
        }
        issues.sort(Comparator.comparingInt((Issue i) -> i.severity().rank())
                .thenComparing(Issue::rule));

        int errors = 0;
        int warnings = 0;
        int infos = 0;
        for (Issue issue : issues) {
            switch (issue.severity()) {
                case ERROR -> errors++;
                case WARNING -> warnings++;
                case INFO -> infos++;
            }
        }

        int pages = state.pagesAudited;
        double weight = ERROR_WEIGHT * errors + WARNING_WEIGHT * warnings + (double) INFO_WEIGHT * infos;
        double worst = (double) ERROR_WEIGHT * Math.max(1, pages);
        int score = (int) Math.round(100 * Math.max(0, 1 - weight / worst));

        return new AuditSummary(score, pages, state.brokenLinks, state.skippedPages, errors, warnings, infos, truncated,
                Collections.unmodifiableList(issues));
    }

    public boolean hasAudit(String taskId) {
        return taskId != null && audits.containsKey(taskId);
    }

    private AuditState stateOf(String taskId) {
        return taskId == null ? null : audits.get(taskId);
    }

    private void addIssue(AuditState state, Issue issue) {
        synchronized (state.issues) {
            if (state.issues.size() >= MAX_ISSUES) {
                state.truncated = true;
                return;
            }
            state.issues.add(issue);
        }
    }

    private List<Issue> evaluate(PageSeo page) {
        List<Issue> issues = new ArrayList<>();

        String title = trimToNull(page.title());
        if (title == null) {
            issues.add(new Issue("missing-title", Severity.WARNING, page.url(), ""));
        } else if (title.length() > TITLE_MAX_LENGTH) {
            issues.add(new Issue("long-title", Severity.WARNING, page.url(), String.valueOf(title.length())));
        }

        String description = trimToNull(page.metaDescription());
        if (description == null) {
            issues.add(new Issue("missing-description", Severity.WARNING, page.url(), ""));
        } else if (description.length() > DESCRIPTION_MAX_LENGTH) {
            issues.add(new Issue("long-description", Severity.WARNING, page.url(),
                    String.valueOf(description.length())));
        }

        if (page.h1Count() == 0) {
            issues.add(new Issue("missing-h1", Severity.WARNING, page.url(), ""));
        } else if (page.h1Count() > 1) {
            issues.add(new Issue("multiple-h1", Severity.WARNING, page.url(), String.valueOf(page.h1Count())));
        }

        if (page.noindex()) {
            issues.add(new Issue("noindex", Severity.WARNING, page.url(), ""));
        }

        String canonical = trimToNull(page.canonical());
        if (canonical == null) {
            issues.add(new Issue("missing-canonical", Severity.INFO, page.url(), ""));
        } else if (!canonical.equals(page.url())) {
            issues.add(new Issue("canonical-mismatch", Severity.INFO, page.url(), canonical));
        }

        if (page.imagesMissingAlt() > 0) {
            issues.add(new Issue("image-missing-alt", Severity.WARNING, page.url(),
                    page.imagesMissingAlt() + "/" + page.imagesTotal()));
        }

        if (page.elapsedMs() > SLOW_PAGE_MS) {
            issues.add(new Issue("slow-page", Severity.INFO, page.url(), String.valueOf(page.elapsedMs())));
        }

        return issues;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void startCleanupScheduler() {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "seo-audit-cleanup");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(this::cleanupExpired, 5, 5, TimeUnit.MINUTES);
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        audits.entrySet().removeIf(entry -> now - entry.getValue().startedAt > AUDIT_EXPIRE_MS);
    }

    public enum Severity {
        ERROR(0), WARNING(1), INFO(2);

        private final int rank;

        Severity(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    public record PageSeo(String url, int statusCode, long elapsedMs,
                          String title, String metaDescription, int h1Count,
                          String canonical, boolean noindex,
                          int imagesTotal, int imagesMissingAlt) {
    }

    public record Issue(String rule, Severity severity, String url, String detail) {
    }

    public record AuditSummary(int score, int pagesAudited, int brokenLinks, int skippedPages,
                               int errorCount, int warningCount, int infoCount,
                               boolean truncated, List<Issue> issues) {

        static AuditSummary empty() {
            return new AuditSummary(0, 0, 0, 0, 0, 0, 0, false, List.of());
        }
    }

    private static final class AuditState {
        private final long startedAt = System.currentTimeMillis();
        private final List<Issue> issues = new ArrayList<>();
        private int pagesAudited;
        private int brokenLinks;
        private int skippedPages;
        private boolean truncated;
    }
}
