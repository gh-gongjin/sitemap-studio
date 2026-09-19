package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.PushConfig;

import java.time.LocalDateTime;

/**
 * @ClassName PushConfigView
 * @Description 推送配置视图（供页面渲染，不含任何凭据内容）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public record PushConfigView(
        boolean enabled,
        String protocol,
        String host,
        int port,
        String username,
        String authType,
        String remoteDir,
        String sitemapFileName,
        String hostKeyFingerprint,
        boolean indexNowEnabled,
        String indexNowKey,
        boolean hasPassword,
        boolean hasPrivateKey,
        LocalDateTime lastPushAt,
        String lastPushStatus,
        String lastPushError) {

    public static PushConfigView of(PushConfig config) {
        return new PushConfigView(
                config.isEnabled(),
                config.getProtocol(),
                config.getHost(),
                config.getPort(),
                config.getUsername(),
                config.getAuthType(),
                config.getRemoteDir(),
                config.getSitemapFileName(),
                config.getHostKeyFingerprint(),
                config.isIndexNowEnabled(),
                config.getIndexNowKey(),
                hasText(config.getPasswordEnc()),
                hasText(config.getPrivateKeyEnc()),
                config.getLastPushAt(),
                config.getLastPushStatus(),
                config.getLastPushError());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
