package io.github.ghgongjin.sitemap.service;

/**
 * 页面渲染器接口
 * 用于支持 Headless Browser 渲染 JS 动态页面
 */
public interface PageRenderer {

    /**
     * 渲染指定 URL 的页面
     *
     * @param url 页面 URL
     * @return 渲染后的 HTML 内容
     * @throws Exception 渲染失败时抛出异常
     */
    String render(String url) throws Exception;

    /**
     * 检查渲染器是否可用
     *
     * @return true 如果渲染器可用
     */
    boolean isAvailable();

    /**
     * 释放渲染器资源
     */
    void close();
}
