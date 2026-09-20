package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName SeoAuditServiceTest
 * @Description SEO 健康度采集服务单元测试
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class SeoAuditServiceTest {

    private static final String TASK = "task-1";
    private static final String URL = "https://example.com/";

    private SeoAuditService service;

    @BeforeEach
    void setUp() {
        service = new SeoAuditService();
        service.beginAudit(TASK);
    }

    private SeoAuditService.PageSeo page(String url, String title, String description,
                                         int h1Count, String canonical, boolean noindex,
                                         int imagesTotal, int imagesMissingAlt, long elapsedMs) {
        return new SeoAuditService.PageSeo(url, 200, elapsedMs, title, description,
                h1Count, canonical, noindex, imagesTotal, imagesMissingAlt);
    }

    private SeoAuditService.PageSeo healthyPage() {
        return page(URL, "示例站点主页", "这是一个用于测试的页面描述", 1, URL, false, 2, 0, 100);
    }

    private Set<String> rulesOf(SeoAuditService.AuditSummary summary) {
        return summary.issues().stream().map(SeoAuditService.Issue::rule).collect(Collectors.toSet());
    }

    @Test
    void shouldCountSkippedPagesSeparatelyFromAuditedPages() {
        // Given
        service.recordSkipped(TASK, URL + "/private", "noindex");
        service.recordSkipped(TASK, URL + "/a", "robots-disallow");
        service.recordSkipped(TASK, URL + "/b", "robots-disallow");
        service.recordPage(TASK, healthyPage());

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then: 跳过只进计数，不进问题清单，不影响评分
        assertThat(summary.skippedPages()).isEqualTo(3);
        assertThat(summary.pagesAudited()).isEqualTo(1);
        assertThat(summary.issues()).extracting(SeoAuditService.Issue::rule)
                .doesNotContain("skipped");
        assertThat(summary.score()).isEqualTo(100);
    }

    @Test
    void shouldReturnZeroSkippedWhenNoAuditOrNoSkips() {
        // Then: 无审计任务与未跳过任何页时都是 0
        assertThat(service.summarize("unknown-task").skippedPages()).isZero();
        assertThat(service.summarize(TASK).skippedPages()).isZero();
    }

    @Test
    void shouldReturnPerfectScoreWhenPageIsHealthy() {
        // Given
        service.recordPage(TASK, healthyPage());

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(summary.score()).isEqualTo(100);
        assertThat(summary.pagesAudited()).isEqualTo(1);
        assertThat(summary.errorCount()).isZero();
        assertThat(summary.warningCount()).isZero();
        assertThat(summary.infoCount()).isZero();
        assertThat(summary.issues()).isEmpty();
    }

    @Test
    void shouldCollectAllRulesWhenPageHasSeoProblems() {
        // Given
        String longTitle = "x".repeat(61);
        String longDescription = "y".repeat(161);
        service.recordPage(TASK, page("https://example.com/a", longTitle, longDescription,
                3, "", false, 4, 2, 3000));

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(rulesOf(summary)).containsExactlyInAnyOrder(
                "long-title", "long-description", "multiple-h1",
                "missing-canonical", "image-missing-alt", "slow-page");
    }

    @Test
    void shouldReportMissingFieldsWhenPageIsEmpty() {
        // Given
        service.recordPage(TASK, page("https://example.com/blank", "", null, 0, null, false, 0, 0, 10));

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(rulesOf(summary)).containsExactlyInAnyOrder(
                "missing-title", "missing-description", "missing-h1", "missing-canonical");
    }

    @Test
    void shouldReportNoindexWhenPageIsNoindex() {
        // Given
        service.recordPage(TASK, page(URL, "示例站点主页", "描述内容", 1, URL, true, 0, 0, 50));

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(rulesOf(summary)).containsExactly("noindex");
        assertThat(summary.warningCount()).isEqualTo(1);
    }

    @Test
    void shouldReportCanonicalMismatchWhenCanonicalDiffers() {
        // Given
        service.recordPage(TASK, page(URL, "示例站点主页", "描述内容", 1,
                "https://example.com/other", false, 0, 0, 50));

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(summary.issues()).hasSize(1);
        assertThat(summary.issues().get(0).rule()).isEqualTo("canonical-mismatch");
        assertThat(summary.issues().get(0).detail()).isEqualTo("https://example.com/other");
    }

    @Test
    void shouldCountBrokenLinkAsErrorWhenStatusIsNotSuccess() {
        // Given
        service.recordBroken(TASK, "https://example.com/missing", 404);

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(summary.brokenLinks()).isEqualTo(1);
        assertThat(summary.errorCount()).isEqualTo(1);
        assertThat(summary.issues().get(0).severity()).isEqualTo(SeoAuditService.Severity.ERROR);
        assertThat(summary.issues().get(0).detail()).isEqualTo("404");
    }

    @Test
    void shouldCountUnreachableAsErrorWhenRetriesExhausted() {
        // Given
        service.recordUnreachable(TASK, "https://example.com/timeout", "Connection timed out");

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(summary.brokenLinks()).isEqualTo(1);
        assertThat(summary.errorCount()).isEqualTo(1);
        assertThat(summary.issues().get(0).rule()).isEqualTo("unreachable");
    }

    @Test
    void shouldLowerScoreWhenErrorsExist() {
        // Given
        service.beginAudit("task-2");
        service.recordBroken("task-2", "https://example.com/missing", 500);

        // When
        SeoAuditService.AuditSummary summary = service.summarize("task-2");

        // Then
        assertThat(summary.score()).isZero();
    }

    @Test
    void shouldKeepPartialScoreWhenWarningsOnly() {
        // Given
        service.recordPage(TASK, page(URL, "", "描述内容", 1, URL, false, 0, 0, 50));

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(summary.warningCount()).isEqualTo(1);
        assertThat(summary.score()).isEqualTo(70);
    }

    @Test
    void shouldReturnEmptySummaryWhenTaskUnknown() {
        // When
        SeoAuditService.AuditSummary summary = service.summarize("no-such-task");

        // Then
        assertThat(summary.pagesAudited()).isZero();
        assertThat(summary.score()).isZero();
        assertThat(summary.issues()).isEmpty();
        assertThat(service.hasAudit("no-such-task")).isFalse();
    }

    @Test
    void shouldIgnoreRecordsWhenTaskIdIsNull() {
        // Given
        service.recordPage(null, healthyPage());
        service.recordBroken(null, URL, 404);

        // Then
        assertThat(service.hasAudit(null)).isFalse();
    }

    @Test
    void shouldSortIssuesBySeverityThenRule() {
        // Given
        service.recordBroken(TASK, "https://example.com/broken", 404);
        service.recordPage(TASK, page(URL, "", "描述内容", 1, URL, false, 0, 0, 50));

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(summary.issues()).extracting(SeoAuditService.Issue::severity)
                .containsExactly(SeoAuditService.Severity.ERROR, SeoAuditService.Severity.WARNING);
    }

    @Test
    void shouldTruncateIssuesWhenExceedingLimit() {
        // Given
        for (int i = 0; i < 600; i++) {
            service.recordBroken(TASK, "https://example.com/missing-" + i, 404);
        }

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(summary.truncated()).isTrue();
        assertThat(summary.issues()).hasSize(500);
        assertThat(summary.brokenLinks()).isEqualTo(600);
    }

    @Test
    void shouldTrackMultiplePagesWithinSameTask() {
        // Given
        service.recordPage(TASK, healthyPage());
        service.recordPage(TASK, healthyPage());
        service.recordBroken(TASK, "https://example.com/missing", 404);

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);

        // Then
        assertThat(summary.pagesAudited()).isEqualTo(2);
        assertThat(summary.brokenLinks()).isEqualTo(1);
        assertThat(summary.score()).isEqualTo(50);
    }

    @Test
    void shouldKeepAuditsIsolatedWhenMultipleTasksRecorded() {
        // Given
        service.recordBroken(TASK, "https://example.com/missing", 404);

        // When
        service.beginAudit("task-3");
        service.recordPage("task-3", healthyPage());
        SeoAuditService.AuditSummary summary = service.summarize("task-3");

        // Then
        assertThat(summary.errorCount()).isZero();
        assertThat(summary.score()).isEqualTo(100);
        assertThat(service.summarize(TASK).errorCount()).isEqualTo(1);
    }

    @Test
    void shouldListIssuesInUnmodifiableCollection() {
        // Given
        service.recordBroken(TASK, "https://example.com/missing", 404);

        // When
        SeoAuditService.AuditSummary summary = service.summarize(TASK);
        List<SeoAuditService.Issue> issues = summary.issues();

        // Then
        assertThat(issues).hasSize(1);
        assertThat(issues.getClass().getName()).isNotEqualTo("java.util.ArrayList");
    }
}
