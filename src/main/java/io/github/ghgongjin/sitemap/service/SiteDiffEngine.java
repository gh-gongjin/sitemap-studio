package io.github.ghgongjin.sitemap.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @ClassName SiteDiffEngine
 * @Description 站点地图相邻版本 diff 纯函数：added/removed 按 loc 精确匹配，changed 为同 loc 且 lastmod 字符串不同
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public final class SiteDiffEngine {

    /**
     * @param xmlOld 上一版完整 XML
     * @param xmlNew 本版完整 XML
     * @throws IllegalArgumentException 任一入参为 null（历史数据缺失，按「无法比较」处理）
     */
    public static SiteDiff diff(String xmlOld, String xmlNew) {
        if (xmlOld == null || xmlNew == null) {
            throw new IllegalArgumentException("sitemap xml missing");
        }
        Map<String, String> oldEntries = lastmodByLoc(xmlOld);
        Map<String, String> newEntries = lastmodByLoc(xmlNew);
        List<String> added = newEntries.keySet().stream()
                .filter(loc -> !oldEntries.containsKey(loc))
                .sorted().toList();
        List<String> removed = oldEntries.keySet().stream()
                .filter(loc -> !newEntries.containsKey(loc))
                .sorted().toList();
        List<String> changed = newEntries.entrySet().stream()
                .filter(e -> oldEntries.containsKey(e.getKey())
                        && !e.getValue().equals(oldEntries.get(e.getKey())))
                .map(Map.Entry::getKey)
                .sorted().toList();
        return new SiteDiff(added, removed, changed);
    }

    private static Map<String, String> lastmodByLoc(String xml) {
        Map<String, String> map = new LinkedHashMap<>();
        for (SitemapEntryParser.Entry entry : SitemapEntryParser.parse(xml).entries()) {
            // 重复 loc：后出现者覆盖（生成器不产重复，仅防御）
            map.put(entry.url(), entry.lastmod());
        }
        return map;
    }

    public record SiteDiff(List<String> added, List<String> removed, List<String> changed) {

        public int total() {
            return added.size() + removed.size() + changed.size();
        }
    }

    private SiteDiffEngine() {
    }
}
