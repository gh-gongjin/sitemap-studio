package io.github.ghgongjin.sitemap.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @ClassName SitemapEntryParser
 * @Description 解析已生成的站点地图 XML，供预览页渲染地址列表（无状态纯函数）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public final class SitemapEntryParser {

    private SitemapEntryParser() {
    }

    public record Entry(String url, String lastmod, String priority) {
    }

    public record SitemapEntries(List<Entry> entries, int imageCount, int videoCount, int newsCount, String lastmod) {
    }

    public static SitemapEntries parse(String sitemapXml) {
        if (sitemapXml == null || sitemapXml.isBlank()) {
            return new SitemapEntries(Collections.emptyList(), 0, 0, 0, "");
        }

        Document doc = Jsoup.parse(sitemapXml, "", Parser.xmlParser());
        List<Entry> entries = new ArrayList<>();
        int imageCount = 0;
        int videoCount = 0;
        int newsCount = 0;
        String latestLastmod = "";

        for (Element urlEl : doc.select("url")) {
            String loc = childText(urlEl, "loc");
            if (loc.isEmpty()) {
                continue;
            }
            String lastmod = childText(urlEl, "lastmod");
            entries.add(new Entry(loc, lastmod, childText(urlEl, "priority")));

            for (Element child : urlEl.children()) {
                String tag = child.tagName();
                if ("image:image".equalsIgnoreCase(tag)) {
                    imageCount++;
                } else if ("video:video".equalsIgnoreCase(tag)) {
                    videoCount++;
                } else if ("news:news".equalsIgnoreCase(tag)) {
                    newsCount++;
                }
            }

            // 生成端写入的是 yyyy-MM-dd，按字符串比较即可取最新
            if (lastmod.compareTo(latestLastmod) > 0) {
                latestLastmod = lastmod;
            }
        }

        return new SitemapEntries(entries, imageCount, videoCount, newsCount, latestLastmod);
    }

    private static String childText(Element parent, String tag) {
        for (Element child : parent.children()) {
            if (child.tagName().equalsIgnoreCase(tag)) {
                return child.text().trim();
            }
        }
        return "";
    }
}
