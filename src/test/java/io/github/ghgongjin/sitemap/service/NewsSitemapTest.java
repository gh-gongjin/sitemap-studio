package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

class NewsSitemapTest {

    private static final String BASE = "https://93.184.216.34";
    private static final String PAGE = BASE + "/news/1";

    @Test
    void shouldEmitNewsEntryWhenArticlePublishedWithin48Hours() throws Exception {
        String published = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2).toString();
        String xml = crawlAndGenerate("""
                <html lang="zh-CN"><head>
                <title>页面标题</title>
                <meta property="og:type" content="article">
                <meta property="og:title" content="最新发布">
                <meta property="og:site_name" content="示例新闻网">
                <meta property="article:published_time" content="%s">
                </head><body></body></html>
                """.formatted(published), true);

        assertThat(xml).contains(
                "<news:name>示例新闻网</news:name>",
                "<news:language>zh</news:language>",
                "<news:title>最新发布</news:title>",
                "<news:publication_date>");
    }

    @Test
    void shouldFallBackToPageTitleAndHostWhenOgMetadataMissing() throws Exception {
        String published = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString();
        String xml = crawlAndGenerate("""
                <html lang="en"><head>
                <title>Fallback Headline</title>
                <meta name="article:published_time" content="%s">
                </head><body></body></html>
                """.formatted(published), true);

        assertThat(xml).contains(
                "<news:name>93.184.216.34</news:name>",
                "<news:language>en</news:language>",
                "<news:title>Fallback Headline</news:title>");
    }

    @Test
    void shouldSkipNewsEntryWhenPublishedOutside48Hours() throws Exception {
        String published = OffsetDateTime.now(ZoneOffset.UTC).minusHours(72).toString();
        String xml = crawlAndGenerate("""
                <html lang="zh-CN"><head>
                <title>旧文章</title>
                <meta property="og:type" content="article">
                <meta property="article:published_time" content="%s">
                </head><body></body></html>
                """.formatted(published), true);

        assertThat(xml).doesNotContain("<news:news>");
    }

    @Test
    void shouldSkipNewsEntryWhenPageIsNotArticleLike() throws Exception {
        String xml = crawlAndGenerate("""
                <html lang="zh-CN"><head>
                <title>普通页面</title>
                <meta property="og:type" content="website">
                </head><body></body></html>
                """, true);

        assertThat(xml).doesNotContain("<news:news>");
    }

    @Test
    void shouldSkipNewsEntryWhenLanguageIsMissing() throws Exception {
        String published = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString();
        String xml = crawlAndGenerate("""
                <html><head>
                <title>无语言标记</title>
                <meta property="og:type" content="article">
                <meta property="article:published_time" content="%s">
                </head><body></body></html>
                """.formatted(published), true);

        assertThat(xml).doesNotContain("<news:news>");
    }

    @Test
    void shouldSkipNewsEntryWhenPublicationDateIsUnparsable() throws Exception {
        String xml = crawlAndGenerate("""
                <html lang="zh-CN"><head>
                <title>坏日期</title>
                <meta property="og:type" content="article">
                <meta property="article:published_time" content="not-a-date">
                </head><body></body></html>
                """, true);

        assertThat(xml).doesNotContain("<news:news>");
    }

    @Test
    void shouldOmitNewsBlockWhenIncludeNewsDisabled() throws Exception {
        String published = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString();
        String xml = crawlAndGenerate("""
                <html lang="zh-CN"><head>
                <title>开关关闭</title>
                <meta property="og:type" content="article">
                <meta property="article:published_time" content="%s">
                </head><body></body></html>
                """.formatted(published), false);

        assertThat(xml).doesNotContain("<news:news>", "xmlns:news");
    }

    private String crawlAndGenerate(String html, boolean includeNews) throws Exception {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        Map<String, Object> pages = process(service, html, includeNews);
        String xml = ReflectionTestUtils.invokeMethod(service, "generateSitemapXml",
                pages, BASE, false, false, includeNews, null);
        assertThat(xml).isNotNull();
        return xml;
    }

    private Map<String, Object> process(EnhancedSitemapGeneratorService service, String html, boolean includeNews)
            throws Exception {
        SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
        SafeHttpFetcher.Response response = new SafeHttpFetcher.Response(
                PAGE, 200, "text/html", Map.of(), html.getBytes(StandardCharsets.UTF_8));
        when(fetcher.fetch(eq(PAGE), eq(BASE), anyInt(), any())).thenReturn(response);
        service.setCrawlUrlPolicy(new CrawlUrlPolicy(host -> {
            try {
                return new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")};
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException(e);
            }
        }));
        service.setSafeHttpFetcher(fetcher);
        Class<?> taskClass = Class.forName(EnhancedSitemapGeneratorService.class.getName() + "$CrawlTask");
        var constructor = taskClass.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance(PAGE, 0);
        Map<String, Object> pages = new HashMap<>();
        var queue = new LinkedBlockingQueue<>();
        ReflectionTestUtils.invokeMethod(service, "processCrawlTaskWithProgress", task, BASE,
                pages, new HashSet<String>(), queue, null, 1, false, false, includeNews, null);
        return pages;
    }
}
