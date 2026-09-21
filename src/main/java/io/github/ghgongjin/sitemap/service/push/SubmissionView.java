package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.PushConfig;

/**
 * @ClassName SubmissionView
 * @Description 搜索引擎提交配置视图（供页面渲染；token 与 JSON 明文永不出现，client_email 是明文列）
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
public record SubmissionView(
        boolean baiduEnabled,
        String baiduSite,
        boolean hasBaiduToken,
        boolean gscEnabled,
        String gscSiteUrl,
        String gscSitemapUrl,
        boolean hasGscJson,
        String gscClientEmail) {

    public static SubmissionView of(PushConfig config) {
        return new SubmissionView(
                config.isBaiduEnabled(),
                config.getBaiduSite(),
                hasText(config.getBaiduTokenEnc()),
                config.isGscEnabled(),
                config.getGscSiteUrl(),
                config.getGscSitemapUrl(),
                hasText(config.getGscServiceAccountJsonEnc()),
                config.getGscClientEmail());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
