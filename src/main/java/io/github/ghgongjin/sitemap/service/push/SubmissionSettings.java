package io.github.ghgongjin.sitemap.service.push;

/**
 * @ClassName SubmissionSettings
 * @Description 搜索引擎提交设置表单输入（baiduToken/serviceAccountJson 为明文，仅保存过程短暂持有）
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
public record SubmissionSettings(
        boolean baiduEnabled,
        String baiduSite,
        String baiduToken,
        boolean gscEnabled,
        String gscSiteUrl,
        String gscSitemapUrl,
        String serviceAccountJson) {

    @Override
    public String toString() {
        return "SubmissionSettings[baiduEnabled=" + baiduEnabled + ", baiduSite=" + baiduSite
                + ", gscEnabled=" + gscEnabled + ", gscSiteUrl=" + gscSiteUrl
                + ", gscSitemapUrl=" + gscSitemapUrl + ", credentials=***]";
    }
}
