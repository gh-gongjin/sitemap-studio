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

class VideoSitemapTest {

    private static final String BASE = "https://93.184.216.34";
    private static final String PAGE = BASE + "/video";

    @Test
    void shouldEmitRequiredFieldsWhenVideoElementIsComplete() throws Exception {
        String xml = crawlAndGenerate("""
                <html><head>
                <meta property="og:title" content="页面标题">
                <meta name="description" content="页面描述">
                <meta property="og:image" content="/social.jpg">
                </head><body>
                <video src="/media/demo.mp4" poster="/poster.jpg" title="视频标题"></video>
                </body></html>
                """);

        assertThat(xml).contains(
                "<video:thumbnail_loc>" + BASE + "/poster.jpg</video:thumbnail_loc>",
                "<video:title>视频标题</video:title>",
                "<video:description>页面描述</video:description>",
                "<video:content_loc>" + BASE + "/media/demo.mp4</video:content_loc>");
    }

    @Test
    void shouldFallBackToPageMetadataWhenVideoElementLacksFields() throws Exception {
        String xml = crawlAndGenerate("""
                <html><head>
                <title>页面标题</title>
                <meta name="description" content="页面描述">
                <meta property="og:image" content="/social.jpg">
                </head><body>
                <video src="/media/demo.mp4"></video>
                </body></html>
                """);

        assertThat(xml).contains(
                "<video:thumbnail_loc>" + BASE + "/social.jpg</video:thumbnail_loc>",
                "<video:title>页面标题</video:title>",
                "<video:description>页面描述</video:description>",
                "<video:content_loc>" + BASE + "/media/demo.mp4</video:content_loc>");
    }

    @Test
    void shouldSkipVideoEntryWhenRequiredFieldsMissing() throws Exception {
        String xml = crawlAndGenerate("""
                <html><head><title>页面标题</title></head><body>
                <video src="/media/demo.mp4"></video>
                </body></html>
                """);

        assertThat(xml).doesNotContain("<video:video>");
    }

    @Test
    void shouldEmitPlayerLocWhenIframeEmbedIsFound() throws Exception {
        String xml = crawlAndGenerate("""
                <html><head>
                <title>页面标题</title>
                <meta name="description" content="页面描述">
                <meta property="og:image" content="/social.jpg">
                </head><body>
                <iframe src="https://www.youtube.com/embed/abc123" title="嵌入视频"></iframe>
                </body></html>
                """);

        assertThat(xml).contains(
                "<video:player_loc>https://www.youtube.com/embed/abc123</video:player_loc>",
                "<video:title>嵌入视频</video:title>");
    }

    @Test
    void shouldUseContentLocForOpenGraphVideoFile() throws Exception {
        String xml = crawlAndGenerate("""
                <html><head>
                <title>页面标题</title>
                <meta name="description" content="页面描述">
                <meta property="og:image" content="/social.jpg">
                <meta property="og:video" content="/media/clip.mp4">
                </head><body></body></html>
                """);

        assertThat(xml).contains("<video:content_loc>" + BASE + "/media/clip.mp4</video:content_loc>");
    }

    private String crawlAndGenerate(String html) throws Exception {
        EnhancedSitemapGeneratorService service = new EnhancedSitemapGeneratorService();
        Map<String, Object> pages = process(service, html);
        String xml = ReflectionTestUtils.invokeMethod(service, "generateSitemapXml",
                pages, BASE, false, true, false, null);
        assertThat(xml).isNotNull();
        return xml;
    }

    private Map<String, Object> process(EnhancedSitemapGeneratorService service, String html) throws Exception {
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
                pages, new HashSet<String>(), queue, null, 1, false, true, false, null);
        return pages;
    }
}
