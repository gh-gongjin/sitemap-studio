package io.github.ghgongjin.sitemap.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapEntryParserTest {

    private static final String RICH_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"\
             xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"\
             xmlns:video="http://www.google.com/schemas/sitemap-video/1.1"\
             xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
              <url>
                <loc>https://example.com/</loc>
                <lastmod>2026-09-18</lastmod>
                <priority>1.0</priority>
                <image:image><image:loc>https://example.com/a.png</image:loc></image:image>
                <image:image><image:loc>https://example.com/b.png</image:loc></image:image>
              </url>
              <url>
                <loc>https://example.com/blog</loc>
                <lastmod>2026-09-25</lastmod>
                <priority>0.7</priority>
                <video:video><video:player_loc>https://example.com/v.mp4</video:player_loc></video:video>
              </url>
              <url>
                <loc>https://example.com/news/1</loc>
                <lastmod>2026-09-26</lastmod>
                <news:news>
                  <news:publication><news:name>Example</news:name><news:language>zh</news:language></news:publication>
                  <news:publication_date>2026-09-26</news:publication_date>
                  <news:title>标题</news:title>
                </news:news>
              </url>
            </urlset>
            """;

    @Test
    void shouldReturnEmptyResultWhenXmlIsMissing() {
        assertThat(SitemapEntryParser.parse(null).entries()).isEmpty();
        assertThat(SitemapEntryParser.parse("   ").entries()).isEmpty();
        assertThat(SitemapEntryParser.parse("").imageCount()).isZero();
        assertThat(SitemapEntryParser.parse("").newsCount()).isZero();
        assertThat(SitemapEntryParser.parse(null).lastmod()).isEmpty();
    }

    @Test
    void shouldReadLocLastmodAndPriorityInDocumentOrder() {
        var parsed = SitemapEntryParser.parse(RICH_XML);

        assertThat(parsed.entries()).extracting(SitemapEntryParser.Entry::url)
                .containsExactly("https://example.com/", "https://example.com/blog",
                        "https://example.com/news/1");
        assertThat(parsed.entries()).extracting(SitemapEntryParser.Entry::lastmod)
                .containsExactly("2026-09-18", "2026-09-25", "2026-09-26");
        assertThat(parsed.entries()).extracting(SitemapEntryParser.Entry::priority)
                .containsExactly("1.0", "0.7", "");
    }

    @Test
    void shouldCountNamespacedMediaEntries() {
        var parsed = SitemapEntryParser.parse(RICH_XML);

        assertThat(parsed.imageCount()).isEqualTo(2);
        assertThat(parsed.videoCount()).isEqualTo(1);
        assertThat(parsed.newsCount()).isEqualTo(1);
    }

    @Test
    void shouldPickLatestLastmodAcrossEntries() {
        assertThat(SitemapEntryParser.parse(RICH_XML).lastmod()).isEqualTo("2026-09-26");
    }

    @Test
    void shouldSkipUrlEntriesWithoutLocAndKeepMetadataEmpty() {
        var parsed = SitemapEntryParser.parse("<urlset><url><priority>0.5</priority></url>"
                + "<url><loc>https://example.com/kept</loc></url></urlset>");

        assertThat(parsed.entries()).extracting(SitemapEntryParser.Entry::url)
                .containsExactly("https://example.com/kept");
        assertThat(parsed.entries().get(0).lastmod()).isEmpty();
        assertThat(parsed.entries().get(0).priority()).isEmpty();
        assertThat(parsed.lastmod()).isEmpty();
    }

    @Test
    void shouldNotThrowOnGarbageInput() {
        var parsed = SitemapEntryParser.parse("this is definitely not xml");

        assertThat(parsed.entries()).isEmpty();
        assertThat(parsed.imageCount()).isZero();
        assertThat(parsed.videoCount()).isZero();
        assertThat(parsed.newsCount()).isZero();
    }
}
