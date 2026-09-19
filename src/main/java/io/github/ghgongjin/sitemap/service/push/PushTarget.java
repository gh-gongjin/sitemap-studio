package io.github.ghgongjin.sitemap.service.push;

/**
 * @ClassName PushTarget
 * @Description 一次推送的连接目标（凭据已解密，仅存在于内存）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public record PushTarget(
        PushProtocol protocol,
        String host,
        int port,
        String username,
        String password,
        String privateKeyPem,
        String remoteDir,
        String hostKeyFingerprint,
        int timeoutMs) {

    public static final int DEFAULT_TIMEOUT_MS = 30_000;

    public boolean usesPrivateKey() {
        return privateKeyPem != null && !privateKeyPem.isBlank();
    }

    public String remoteDirOrEmpty() {
        return remoteDir == null ? "" : remoteDir.trim();
    }

    public int timeoutMsOrDefault() {
        return timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }
}
