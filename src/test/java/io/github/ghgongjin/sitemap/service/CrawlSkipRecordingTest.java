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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName CrawlSkipRecordingTest
 * @Description 跳过计数接线：robots 禁止、非 HTML 内容、noindex 页面各记一次跳过；
 *              正常页面不记（断链路径由 SeoAuditCrawlHookTest 覆盖）
 * @Author gj
 * @Date 2026/9/20
 * @Version 1.0
 */
class CrawlSkipRecordingTest {

    private static final String BASE = "https://93.184.216.34";
    private static final String PAGE = BASE + "/page";
    private static final String TASK_ID = "skip-task";

    @Test
    void shouldCountSkippedPageWhenNoindex() throws Exception {
        SeoAuditService audit = mock(SeoAuditService.class);

        process(newService(audit), 200, "text/html",
                "<html><head><meta name='robots' content='noindex'>"
                        + "<title>T</title></head><body><h1>H</h1></body></html>", null);

        verify(audit).recordSkipped(TASK_ID, PAGE, "noindex");
    }

    @Test
    void shouldCountSkippedPageWhenNonHtmlContentType() throws Exception {
        SeoAuditService audit = mock(SeoAuditService.class);

        process(newService(audit), 200, "application/pdf", "%PDF-1.4", null);

        verify(audit).recordSkipped(TASK_ID, PAGE, "non-html");
    }

    @Test
    void shouldCountSkippedPageWhenRobotsDisallowed() throws Exception {
        SeoAuditService audit = mock(SeoAuditService.class);
        RobotsTxtParser parser = mock(RobotsTxtParser.class);
        when(parser.isAllowed(PAGE)).thenReturn(false);

        process(newService(audit), 200, "text/html",
                "<html><head><title>T</title></head><body><h1>H</h1></body></html>", parser);

        verify(audit).recordSkipped(TASK_ID, PAGE, "robots-disallow");
    }

    @Test
    void shouldNotCountSkippedWhenPageRecordedNormally() throws Exception {
        SeoAuditService audit = mock(SeoAuditService.class);

        Map<String, Object> pages = process(newService(audit), 200, "text/html",
                "<html><head><title>T</title></head><body><h1>H</h1></body></html>", null);

        assertThat(pages).containsKey(PAGE);
        verify(audit, never()).recordSkipped(any(), any(), any());
    }

    private EnhancedSitemapGeneratorService newService(SeoAuditService audit) {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        service.setCrawlUrlPolicy(new CrawlUrlPolicy(host -> {
            try {
                return new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")};
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException(e);
            }
        }));
        service.setSafeHttpFetcher(mock(SafeHttpFetcher.class));
        service.setSeoAuditService(audit);
        return service;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> process(EnhancedSitemapGeneratorService service, int statusCode,
                                        String contentType, String body, RobotsTxtParser parser) throws Exception {
        SafeHttpFetcher fetcher = (SafeHttpFetcher) ReflectionTestUtils.getField(service, "safeHttpFetcher");
        when(fetcher.fetch(eq(PAGE), eq(BASE), anyInt(), any())).thenReturn(new SafeHttpFetcher.Response(
                PAGE, statusCode, contentType, Map.of(), body.getBytes(StandardCharsets.UTF_8)));

        Class<?> taskClass = Class.forName(EnhancedSitemapGeneratorService.class.getName() + "$CrawlTask");
        var constructor = taskClass.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance(PAGE, 0);
        Map<String, Object> pages = new HashMap<>();
        ReflectionTestUtils.invokeMethod(service, "processCrawlTaskWithProgress", task, BASE,
                pages, new HashSet<String>(), new LinkedBlockingQueue<>(),
                TASK_ID, 1, false, false, false, parser);
        return pages;
    }
}
