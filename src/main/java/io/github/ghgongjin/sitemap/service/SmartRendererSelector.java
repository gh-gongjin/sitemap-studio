package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * 智能渲染选择器
 * 检测页面是否为 SPA，决定是否需要使用 Headless Browser 渲染
 */
@Slf4j
public class SmartRendererSelector {

    private final int spaThreshold;

    public SmartRendererSelector(int spaThreshold) {
        this.spaThreshold = spaThreshold;
    }

    /**
     * 判断页面是否需要渲染
     *
     * @param html 原始 HTML
     * @param renderer 页面渲染器
     * @return true 如果需要使用渲染器
     */
    public boolean needsRendering(String html, PageRenderer renderer) {
        if (renderer == null || !renderer.isAvailable()) {
            return false;
        }

        try {
            Document doc = Jsoup.parse(html);

            // 检测 1：检查是否有明显的 SPA 框架标记
            if (hasSpaFrameworkMarkers(doc)) {
                log.debug("检测到 SPA 框架标记");
                return true;
            }

            // 检测 2：检查 DOM 节点数量
            int nodeCount = doc.getAllElements().size();
            if (nodeCount < spaThreshold) {
                log.debug("DOM 节点数 {} 低于阈值 {}", nodeCount, spaThreshold);
                return true;
            }

            // 检测 3：检查文本内容长度
            String text = doc.body() != null ? doc.body().text() : "";
            if (text.trim().length() < 50) {
                log.debug("文本内容长度 {} 过短", text.trim().length());
                return true;
            }

            return false;
        } catch (Exception e) {
            log.warn("SPA 检测失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 检测是否有 SPA 框架标记
     */
    private boolean hasSpaFrameworkMarkers(Document doc) {
        // React
        Elements reactRoot = doc.select("#root, #app, [data-reactroot]");
        if (!reactRoot.isEmpty() && reactRoot.first().children().isEmpty()) {
            return true;
        }

        // Vue
        Elements vueRoot = doc.select("#app");
        if (!vueRoot.isEmpty() && vueRoot.first().children().isEmpty()) {
            return true;
        }

        // Angular
        Elements angularRoot = doc.select("[ng-app], [data-ng-app]");
        if (!angularRoot.isEmpty()) {
            return true;
        }

        // 检查是否有大量空的 div（可能是 SPA 容器）
        Elements emptyDivs = doc.select("div:empty");
        if (emptyDivs.size() > 5) {
            return true;
        }

        return false;
    }
}
