package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName SeoReportServiceTest
 * @Description SEO 报告落库与读取单元测试
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class SeoReportServiceTest {

    private static final String TASK = "task-1";
    private static final String SITE = "https://example.com";

    private SeoAuditService auditService;
    private SeoReportRepository repository;
    private SeoReportService service;

    @BeforeEach
    void setUp() {
        auditService = new SeoAuditService();
        repository = mock(SeoReportRepository.class);
        when(repository.save(any(SeoReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new SeoReportService(auditService, repository,
                new org.springframework.context.support.StaticMessageSource());
    }

    @Test
    void shouldPersistSummaryWhenAuditHasData() {
        // Given
        auditService.beginAudit(TASK);
        auditService.recordBroken(TASK, SITE + "/missing", 404);

        // When
        SeoReport saved = service.save(TASK, SITE);

        // Then
        assertThat(saved).isNotNull();
        assertThat(saved.getTaskId()).isEqualTo(TASK);
        assertThat(saved.getSiteUrl()).isEqualTo(SITE);
        assertThat(saved.getBrokenLinks()).isEqualTo(1);
        assertThat(saved.getErrorCount()).isEqualTo(1);
        assertThat(saved.getIssuesJson()).contains("broken-link");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldReturnNullAndSkipWriteWhenAuditMissing() {
        // When
        SeoReport saved = service.save("unknown-task", SITE);

        // Then
        assertThat(saved).isNull();
        verify(repository, never()).save(any(SeoReport.class));
    }

    @Test
    void shouldReuseExistingRowWhenSameTaskSavedTwice() {
        // Given
        auditService.beginAudit(TASK);
        SeoReport existing = new SeoReport();
        existing.setId(7L);
        existing.setTaskId(TASK);
        when(repository.findByTaskId(TASK)).thenReturn(Optional.of(existing));

        // When
        SeoReport saved = service.save(TASK, SITE);

        // Then
        assertThat(saved.getId()).isEqualTo(7L);
        verify(repository).save(existing);
    }

    @Test
    void shouldRoundTripIssuesThroughJson() {
        // Given
        auditService.beginAudit(TASK);
        auditService.recordPage(TASK, new SeoAuditService.PageSeo(SITE + "/", 200, 100,
                "", "描述", 1, SITE + "/", false, 0, 0));

        // When
        SeoReport saved = service.save(TASK, SITE);
        List<SeoAuditService.Issue> issues = service.parseIssues(saved.getIssuesJson());

        // Then
        assertThat(issues).extracting(SeoAuditService.Issue::rule).contains("missing-title");
        assertThat(issues.get(0).severity()).isNotNull();
        assertThat(issues.get(0).url()).isEqualTo(SITE + "/");
    }

    @Test
    void shouldReturnEmptyListWhenIssueJsonIsBlankOrInvalid() {
        // Then
        assertThat(service.parseIssues(null)).isEmpty();
        assertThat(service.parseIssues("")).isEmpty();
        assertThat(service.parseIssues("not-a-json")).isEmpty();
    }

    @Test
    void shouldReportExistenceWhenReportStored() {
        // Given
        when(repository.findByTaskId(TASK)).thenReturn(Optional.of(new SeoReport()));

        // Then
        assertThat(service.hasReport(TASK)).isTrue();
        assertThat(service.hasReport(null)).isFalse();
        assertThat(service.hasReport("nope")).isFalse();
    }

    @Test
    void shouldReturnEmptyOptionalWhenTaskIdIsNull() {
        // Then
        assertThat(service.findByTaskId(null)).isEmpty();
    }

    @Test
    void shouldReturnRecentReportsFromRepository() {
        // Given
        List<SeoReport> rows = List.of(new SeoReport(), new SeoReport());
        when(repository.findTop20ByOrderByCreatedAtDesc()).thenReturn(rows);

        // Then
        assertThat(service.recent()).hasSize(2);
    }
}
