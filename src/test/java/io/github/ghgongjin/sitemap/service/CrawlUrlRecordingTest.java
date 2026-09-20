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
 * 收录 URL 形态门禁：sitemap 输出必须是重定向/canonical 解析后的最终形态，
 * 尾斜杠是规范 URL 的一部分（MkDocs 等静态站点 301 到带斜杠形式），不得剥除；
 * 队列与去重键仍使用剥斜杠的归一化形态，防止 /a 与 /a/ 双双入图。
 */
class CrawlUrlRecordingTest {
    private static final String BASE = "https://93.184.216.34";

    @Test
    void shouldRecordFinalUrlWithTrailingSlashWhenServerRedirects() throws Exception {
        String taskUrl = BASE + "/docs/x";
        String finalUrl = BASE + "/docs/x/";
        CrawlResult result = process(taskUrl, finalUrl,
                "<html><body><a href='/docs/y/'>Y</a></body></html>");

        assertThat(result.pages().keySet()).containsExactly(finalUrl);
        // 发现链接的入队形态仍为去斜杠的归一化键（去重语义不变）
        assertThat(result.queue()).extracting(task -> ReflectionTestUtils.getField(task, "url"))
                .containsExactly(BASE + "/docs/y");
    }

    @Test
    void shouldRecordCanonicalUrlWithTrailingSlashWhenCanonicalDeclaresSlash() throws Exception {
        String taskUrl = BASE + "/page";
        String canonical = BASE + "/page/";
        CrawlResult result = process(taskUrl, taskUrl,
                "<html><head><link rel='canonical' href='" + canonical + "'></head><body>P</body></html>");

        assertThat(result.pages().keySet()).containsExactly(canonical);
    }

    @Test
    void shouldKeepNonSlashUrlUnchanged() throws Exception {
        String taskUrl = BASE + "/blog/post.html";
        CrawlResult result = process(taskUrl, taskUrl,
                "<html><body>P</body></html>");

        assertThat(result.pages().keySet()).containsExactly(taskUrl);
    }

    private CrawlResult process(String taskUrl, String finalUrl, String html) throws Exception {
        var service = new EnhancedSitemapGeneratorService();
        SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
        SafeHttpFetcher.Response response = new SafeHttpFetcher.Response(
                finalUrl, 200, "text/html", Map.of(), html.getBytes(StandardCharsets.UTF_8));
        when(fetcher.fetch(eq(taskUrl), eq(BASE), anyInt(), any())).thenReturn(response);
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
        Object task = constructor.newInstance(taskUrl, 0);
        Map<String, Object> pages = new HashMap<>();
        var queue = new LinkedBlockingQueue<>();
        ReflectionTestUtils.invokeMethod(service, "processCrawlTaskWithProgress", task, BASE,
                pages, new HashSet<String>(), queue, null, 1, false, false, false, null);
        return new CrawlResult(pages, queue);
    }

    private record CrawlResult(Map<String, Object> pages, LinkedBlockingQueue<Object> queue) {}
}
