package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.PushLogRepository;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName PushConfigServiceTest
 * @Description 推送配置服务单元测试：校验、凭据加密与保留、视图脱敏
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class PushConfigServiceTest {

    private static final Long SITE_ID = 1L;
    private static final String PEM = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----";

    @TempDir
    Path tempDir;

    private PushConfigRepository configRepository;
    private PushLogRepository logRepository;
    private CredentialCipher cipher;
    private PushConfigService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(PushConfigRepository.class);
        logRepository = mock(PushLogRepository.class);
        cipher = new CredentialCipher("", tempDir.resolve("push.key").toString());
        service = new PushConfigService(configRepository, logRepository, cipher);
    }

    @Test
    void shouldEncryptCredentialsAndPersistWhenSavingNewConfig() {
        // Given
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        when(configRepository.save(any(PushConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        PushConfig saved = service.save(SITE_ID, passwordSettings("s3cret"));

        // Then
        assertThat(saved.getPasswordEnc()).startsWith("v1:");
        assertThat(cipher.decrypt(saved.getPasswordEnc())).isEqualTo("s3cret");
        assertThat(saved.getProtocol()).isEqualTo("SFTP");
        assertThat(saved.getHost()).isEqualTo("sftp.example.com");
        assertThat(saved.getPort()).isEqualTo(22);
        assertThat(saved.getSitemapFileName()).isEqualTo("sitemap.xml");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldKeepStoredPasswordWhenFormPasswordBlankOnUpdate() {
        // Given
        PushConfig existing = new PushConfig();
        existing.setId(9L);
        existing.setSiteId(SITE_ID);
        existing.setPasswordEnc(cipher.encrypt("old-secret"));
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(existing));
        when(configRepository.save(any(PushConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        PushConfig saved = service.save(SITE_ID, passwordSettings(""));

        // Then
        assertThat(cipher.decrypt(saved.getPasswordEnc())).isEqualTo("old-secret");
    }

    @Test
    void shouldStoreEncryptedPrivateKeyWhenPrivateKeyProvided() {
        // Given
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        when(configRepository.save(any(PushConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        PushConfig saved = service.save(SITE_ID, settings(true, "SFTP", 22, "PRIVATE_KEY", "", PEM));

        // Then
        assertThat(cipher.decrypt(saved.getPrivateKeyEnc())).isEqualTo(PEM);
    }

    @Test
    void shouldRejectWhenPasswordAuthHasNoStoredOrEnteredPassword() {
        // Given
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.save(SITE_ID, passwordSettings("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密码");
        verify(configRepository, never()).save(any(PushConfig.class));
    }

    @Test
    void shouldRejectWhenPrivateKeyAuthHasNoStoredOrEnteredKey() {
        // Given
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.save(SITE_ID, settings(true, "SFTP", 22, "PRIVATE_KEY", "", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("私钥");
    }

    @Test
    void shouldRejectWhenPrivateKeyAuthRequestedForFtp() {
        // When & Then
        assertThatThrownBy(() -> service.save(SITE_ID, settings(true, "FTP", 21, "PRIVATE_KEY", "", PEM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SFTP");
        verify(configRepository, never()).save(any(PushConfig.class));
    }

    @Test
    void shouldRejectWhenProtocolUnknown() {
        // When & Then
        assertThatThrownBy(() -> service.save(SITE_ID, settings(true, "SCP", 22, "PASSWORD", "s3cret", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
    }

    @Test
    void shouldRejectWhenPortOutOfRange() {
        // When & Then
        assertThatThrownBy(() -> service.save(SITE_ID, settings(true, "SFTP", 0, "PASSWORD", "s3cret", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端口");
        assertThatThrownBy(() -> service.save(SITE_ID, settings(true, "SFTP", 70000, "PASSWORD", "s3cret", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端口");
    }

    @Test
    void shouldRejectWhenSitemapFileNameContainsPathSeparator() {
        // When & Then
        assertThatThrownBy(() -> service.save(SITE_ID, new PushSettings(true, "SFTP", "sftp.example.com", 22,
                "deployer", "PASSWORD", "s3cret", "", "/var/www", "../evil.xml", false, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件名");
        assertThatThrownBy(() -> service.save(SITE_ID, new PushSettings(true, "SFTP", "sftp.example.com", 22,
                "deployer", "PASSWORD", "s3cret", "", "/var/www", "sub/sitemap.xml", false, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件名");
    }

    @Test
    void shouldRejectWhenIndexNowEnabledButKeyNotHex() {
        // When & Then
        assertThatThrownBy(() -> service.save(SITE_ID, new PushSettings(true, "SFTP", "sftp.example.com", 22,
                "deployer", "PASSWORD", "s3cret", "", "/var/www", "sitemap.xml", true, "not-a-hex-key!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IndexNow");
    }

    @Test
    void shouldRejectWhenHostBlank() {
        // When & Then
        assertThatThrownBy(() -> service.save(SITE_ID, new PushSettings(true, "SFTP", "  ", 22,
                "deployer", "PASSWORD", "s3cret", "", "/var/www", "sitemap.xml", false, "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机");
    }

    @Test
    void shouldExposeViewWithoutCredentialsWhenConfigExists() {
        // Given
        PushConfig config = new PushConfig();
        config.setSiteId(SITE_ID);
        config.setEnabled(true);
        config.setProtocol("SFTP");
        config.setHost("sftp.example.com");
        config.setPort(22);
        config.setUsername("deployer");
        config.setAuthType("PASSWORD");
        config.setPasswordEnc(cipher.encrypt("secret-cipher-content"));
        config.setPrivateKeyEnc(cipher.encrypt(PEM));
        config.setRemoteDir("/var/www");
        config.setSitemapFileName("sitemap.xml");
        config.setHostKeyFingerprint("SHA256:abc");
        config.setIndexNowEnabled(true);
        config.setIndexNowKey("a".repeat(32));
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(config));

        // When
        Optional<PushConfigView> view = service.view(SITE_ID);

        // Then
        assertThat(view).isPresent();
        assertThat(view.get().hasPassword()).isTrue();
        assertThat(view.get().hasPrivateKey()).isTrue();
        assertThat(view.get().host()).isEqualTo("sftp.example.com");
        assertThat(view.get().indexNowKey()).isEqualTo("a".repeat(32));
        assertThat(view.get().toString())
                .doesNotContain("secret-cipher-content")
                .doesNotContain("BEGIN OPENSSH");
    }

    private PushSettings passwordSettings(String password) {
        return settings(true, "SFTP", 22, "PASSWORD", password, "");
    }

    private PushSettings settings(boolean enabled, String protocol, int port, String authType,
                                  String password, String privateKey) {
        return new PushSettings(enabled, protocol, "sftp.example.com", port, "deployer", authType,
                password, privateKey, "/var/www", "sitemap.xml", false, "");
    }
}
