package io.github.ghgongjin.sitemap.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CrawlDiscoveryTest {
    private static final String BASE = "https://93.184.216.34";
    private static final String PAGE = BASE + "/section/index";

    @Test
    void shouldDiscoverRelativeLinksWhenHtmlIsRendered() throws Exception {
        var service = new EnhancedSitemapGeneratorService();
        PageRenderer renderer = mock(PageRenderer.class);
        SmartRendererSelector selector = mock(SmartRendererSelector.class);
        when(renderer.isAvailable()).thenReturn(true);
        when(selector.needsRendering(anyString(), eq(renderer))).thenReturn(true);
        when(renderer.render(PAGE)).thenReturn("<a href='child'>Child</a><area href='/mapped'>");
        service.setPageRenderer(renderer);
        service.setRendererSelector(selector);

        CrawlResult result = process(service, "<div id='app'></div>");

        assertThat(result.queue()).extracting(task -> ReflectionTestUtils.getField(task, "url"))
                .contains(BASE + "/section/child", BASE + "/mapped");
    }

    @Test
    void shouldResolveRelativeCanonicalWhenHtmlIsRendered() throws Exception {
        var service = new EnhancedSitemapGeneratorService();
        PageRenderer renderer = mock(PageRenderer.class);
        SmartRendererSelector selector = mock(SmartRendererSelector.class);
        when(renderer.isAvailable()).thenReturn(true);
        when(selector.needsRendering(anyString(), eq(renderer))).thenReturn(true);
        when(renderer.render(PAGE)).thenReturn("<link rel='canonical' href='canonical'>");
        service.setPageRenderer(renderer);
        service.setRendererSelector(selector);

        CrawlResult result = process(service, "<div id='app'></div>");

        assertThat(result.pages()).containsKey(BASE + "/section/canonical");
    }

    @Test
    void shouldFollowLinksWithoutIndexingWhenPageHasNoindexFollow() throws Exception {
        CrawlResult result = process(new EnhancedSitemapGeneratorService(),
                "<meta name='robots' content='noindex, follow'><a href='/child'>Child</a>");

        assertThat(result.pages()).isEmpty();
        assertThat(result.queue()).extracting(task -> ReflectionTestUtils.getField(task, "url"))
                .containsExactly(BASE + "/child");
    }

    @Test
    void shouldNotFollowLinksWhenPageHasNofollow() throws Exception {
        CrawlResult result = process(new EnhancedSitemapGeneratorService(),
                "<meta name='robots' content='nofollow'><a href='/child'>Child</a>");

        assertThat(result.pages()).containsKey(PAGE);
        assertThat(result.queue()).isEmpty();
    }

    @Test
    void shouldNotIndexOrFollowWhenPageHasNone() throws Exception {
        CrawlResult result = process(new EnhancedSitemapGeneratorService(),
                "<meta name='robots' content='none'><a href='/child'>Child</a>");

        assertThat(result.pages()).isEmpty();
        assertThat(result.queue()).isEmpty();
    }

    private CrawlResult process(EnhancedSitemapGeneratorService service, String html) throws Exception {
        Document document = Jsoup.parse(html, PAGE);
        SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
        SafeHttpFetcher.Response response = new SafeHttpFetcher.Response(
                PAGE, 200, "text/html", Map.of(), html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
                pages, new HashSet<String>(), queue, null, 1, false, false, false, null);
        return new CrawlResult(pages, queue);
    }

    private record CrawlResult(Map<String, Object> pages, LinkedBlockingQueue<Object> queue) {}
}
