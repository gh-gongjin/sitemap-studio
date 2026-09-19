package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @ClassName SeoAuditCrawlHookTest
 * @Description 验证爬取流程中的 SEO 采集钩子（成功页、断链、未注入时的兼容性）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class SeoAuditCrawlHookTest {

    private static final String BASE = "https://93.184.216.34";
    private static final String PAGE = BASE + "/page";
    private static final String TASK_ID = "seo-task";

    @Test
    void shouldRecordPageSampleWhenCrawlSucceeds() throws Exception {
        // Given
        SeoAuditService audit = new SeoAuditService();
        EnhancedSitemapGeneratorService service = newService(audit);

        // When
        process(service, 200, """
                <html><head><title>缺少描述的页面</title></head>
                <body><p>无 H1</p><img src="/a.png"><img src="/b.png" alt="有替代文本"></body></html>
                """, TASK_ID);

        // Then
        SeoAuditService.AuditSummary summary = audit.summarize(TASK_ID);
        assertThat(summary.pagesAudited()).isEqualTo(1);
        assertThat(summary.issues()).extracting(SeoAuditService.Issue::rule)
                .contains("missing-description", "missing-h1", "image-missing-alt");
        SeoAuditService.Issue altIssue = summary.issues().stream()
                .filter(i -> i.rule().equals("image-missing-alt"))
                .findFirst().orElseThrow();
        assertThat(altIssue.detail()).isEqualTo("1/2");
    }

    @Test
    void shouldRecordBrokenLinkWhenFetchReturnsNotFound() throws Exception {
        // Given
        SeoAuditService audit = new SeoAuditService();
        EnhancedSitemapGeneratorService service = newService(audit);

        // When
        Map<String, Object> pages = process(service, 404, "<html><head><title>404</title></head></html>", TASK_ID);

        // Then
        SeoAuditService.AuditSummary summary = audit.summarize(TASK_ID);
        assertThat(summary.brokenLinks()).isEqualTo(1);
        assertThat(summary.errorCount()).isEqualTo(1);
        assertThat(summary.pagesAudited()).isZero();
        assertThat(pages).isEmpty();
    }

    @Test
    void shouldSkipAuditWhenSeoServiceNotInjected() throws Exception {
        // Given
        EnhancedSitemapGeneratorService service = newService(null);

        // When
        Map<String, Object> pages = process(service, 200, """
                <html><head><title>正常页面</title></head><body><h1>标题</h1></body></html>
                """, TASK_ID);

        // Then
        assertThat(pages).hasSize(1);
    }

    @Test
    void shouldSkipAuditWhenTaskIdIsNull() throws Exception {
        // Given
        SeoAuditService audit = new SeoAuditService();
        EnhancedSitemapGeneratorService service = newService(audit);

        // When
        process(service, 200, """
                <html><head><title>无任务标识</title></head><body><h1>标题</h1></body></html>
                """, null);

        // Then
        assertThat(audit.summarize(null).pagesAudited()).isZero();
    }

    @Test
    void shouldRecordNoindexPageWhenPageHasNoindexDirective() throws Exception {
        // Given
        SeoAuditService audit = new SeoAuditService();
        EnhancedSitemapGeneratorService service = newService(audit);

        // When
        Map<String, Object> pages = process(service, 200, """
                <html><head><title>隐私页面</title>
                <meta name="robots" content="noindex">
                </head><body><h1>标题</h1></body></html>
                """, TASK_ID);

        // Then
        assertThat(pages).isEmpty();
        SeoAuditService.AuditSummary summary = audit.summarize(TASK_ID);
        assertThat(summary.pagesAudited()).isEqualTo(1);
        assertThat(summary.issues()).extracting(SeoAuditService.Issue::rule).contains("noindex");
    }

    private EnhancedSitemapGeneratorService newService(SeoAuditService audit) {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
        service.setCrawlUrlPolicy(new CrawlUrlPolicy(host -> {
            try {
                return new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")};
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException(e);
            }
        }));
        service.setSafeHttpFetcher(fetcher);
        if (audit != null) {
            audit.beginAudit(TASK_ID);
            service.setSeoAuditService(audit);
        }
        return service;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> process(EnhancedSitemapGeneratorService service, int statusCode,
                                        String html, String taskId) throws Exception {
        SafeHttpFetcher fetcher = (SafeHttpFetcher) ReflectionTestUtils.getField(service, "safeHttpFetcher");
        SafeHttpFetcher.Response response = new SafeHttpFetcher.Response(
                PAGE, statusCode, "text/html", Map.of(), html.getBytes(StandardCharsets.UTF_8));
        when(fetcher.fetch(eq(PAGE), eq(BASE), anyInt(), any())).thenReturn(response);

        Class<?> taskClass = Class.forName(EnhancedSitemapGeneratorService.class.getName() + "$CrawlTask");
        var constructor = taskClass.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance(PAGE, 0);
        Map<String, Object> pages = new HashMap<>();
        var queue = new LinkedBlockingQueue<>();
        ReflectionTestUtils.invokeMethod(service, "processCrawlTaskWithProgress", task, BASE,
                pages, new HashSet<String>(), queue, taskId, 1, false, false, false, null);
        return pages;
    }
}
