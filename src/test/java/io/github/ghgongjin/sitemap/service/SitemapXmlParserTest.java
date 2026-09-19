package io.github.ghgongjin.sitemap.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SitemapXmlParserTest {
    @Test
    void shouldKeepAllUrlsWhenLastmodIsMissing() throws Exception {
        String base = "https://93.184.216.34";
        Document document = Jsoup.parse("""
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://93.184.216.34/first</loc></url>
                  <url><loc>https://93.184.216.34/second</loc><lastmod>2026-09-17</lastmod></url>
                  <url><loc>https://93.184.216.34/third</loc><lastmod/></url>
                </urlset>
                """, base, Parser.xmlParser());
        SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
        SafeHttpFetcher.Response response = new SafeHttpFetcher.Response(
                base + "/sitemap.xml", 200, "application/xml", Map.of(),
                document.outerHtml().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(fetcher.fetch(contains("sitemap.xml"), eq(base), anyInt(), isNull())).thenReturn(response);
        when(fetcher.fetch(contains("sitemap_index.xml"), eq(base), anyInt(), isNull()))
                .thenReturn(new SafeHttpFetcher.Response(base + "/sitemap_index.xml", 404, "text/plain", Map.of(), new byte[0]));
        Map<String, String> urls = new SitemapXmlParser().parseAll(base, List.of(),
                publicPolicy(), fetcher, 1000);
        assertThat(urls).containsOnlyKeys(base + "/first", base + "/second", base + "/third");
        assertThat(urls.get(base + "/first")).isEmpty();
        assertThat(urls.get(base + "/second")).isEqualTo("2026-09-17");
        assertThat(urls.get(base + "/third")).isEmpty();
    }

    @Test
    void shouldSkipOffOriginChildSitemapWithoutFetchingIt() throws Exception {
        String base = "https://93.184.216.34";
        Document index = Jsoup.parse("""
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>https://evil.example/secret.xml</loc></sitemap>
                </sitemapindex>
                """, base, Parser.xmlParser());
        SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
        when(fetcher.fetch(contains("sitemap.xml"), eq(base), anyInt(), isNull())).thenReturn(
                new SafeHttpFetcher.Response(base + "/sitemap.xml", 200, "application/xml", Map.of(),
                        index.outerHtml().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(fetcher.fetch(contains("sitemap_index.xml"), eq(base), anyInt(), isNull()))
                .thenReturn(new SafeHttpFetcher.Response(base + "/sitemap_index.xml", 404, "text/plain", Map.of(), new byte[0]));

        Map<String, String> urls = new SitemapXmlParser().parseAll(base, List.of(),
                publicPolicy(), fetcher, 1000);

        assertThat(urls).isEmpty();
        verify(fetcher, never()).fetch(contains("evil.example"), any(), anyInt(), any());
    }

    private static CrawlUrlPolicy publicPolicy() {
        return new CrawlUrlPolicy(host -> {
            try {
                return new java.net.InetAddress[]{java.net.InetAddress.getByName("93.184.216.34")};
            } catch (java.net.UnknownHostException e) {
                throw new SecurityException(e);
            }
        });
    }
}
