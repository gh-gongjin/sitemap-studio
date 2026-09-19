package io.github.ghgongjin.sitemap.service.push;

/**
 * @ClassName PushSettings
 * @Description 推送设置表单输入（password/privateKey 为明文，仅在保存过程中短暂持有）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public record PushSettings(
        boolean enabled,
        String protocol,
        String host,
        int port,
        String username,
        String authType,
        String password,
        String privateKey,
        String remoteDir,
        String sitemapFileName,
        boolean indexNowEnabled,
        String indexNowKey) {

    @Override
    public String toString() {
        return "PushSettings[enabled=" + enabled + ", protocol=" + protocol + ", host=" + host
                + ", port=" + port + ", username=" + username + ", authType=" + authType
                + ", remoteDir=" + remoteDir + ", sitemapFileName=" + sitemapFileName
                + ", indexNowEnabled=" + indexNowEnabled + ", credentials=***]";
    }
}
