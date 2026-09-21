package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.PushLogRepository;
import io.github.ghgongjin.sitemap.repository.SubmissionLogRepository;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @ClassName SubmissionSettingsTest
 * @Description 搜索引擎提交设置保存单元测试：校验、加密、凭据保留与脱敏视图
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
class SubmissionSettingsTest {

    private static final Long SITE_ID = 1L;
    private static final String GOOD_JSON = """
            {"type":"service_account","client_email":"sa@proj.iam.gserviceaccount.com",\
            "token_uri":"https://oauth2.googleapis.com/token",\
            "private_key":"-----BEGIN PRIVATE KEY-----\\nMIIBVwIBADAN\\n-----END PRIVATE KEY-----"}""";

    @TempDir
    Path tempDir;

    private PushConfigRepository configRepository;
    private CredentialCipher cipher;
    private PushConfigService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(PushConfigRepository.class);
        cipher = new CredentialCipher("", tempDir.resolve("push.key").toString());
        service = new PushConfigService(configRepository, mock(PushLogRepository.class),
                mock(SubmissionLogRepository.class), cipher);
        when(configRepository.save(any(PushConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 用真实 PKCS#8 私钥的合法 JSON（测试内生成一次，避免 mock parse） */
    private static String validGscJson() {
        try {
            var generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + java.util.Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
            return "{\"type\":\"service_account\",\"client_email\":\"sa@p.iam.gserviceaccount.com\","
                    + "\"token_uri\":\"https://oauth2.googleapis.com/token\",\"private_key\":"
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(pem) + "}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SubmissionSettings baiduOnly(String site, String token) {
        return new SubmissionSettings(true, site, token, false, "", "", "");
    }

    private static SubmissionSettings gscOnly(String property, String sitemapUrl, String json) {
        return new SubmissionSettings(false, "", "", true, property, sitemapUrl, json);
    }

    @Test
    void shouldCreateStandaloneConfigWhenNoPushConfigExists() {
        // Given 站点从未配过推送
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());

        // When
        PushConfig saved = service.saveSubmission(SITE_ID, baiduOnly("https://example.com", "abcd1234EFGH"));

        // Then 传输列有占位默认（not null 约束），提交列已写入
        assertThat(saved.getProtocol()).isEqualTo("SFTP");
        assertThat(saved.getHost()).isEqualTo("_submission_only_");
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.isBaiduEnabled()).isTrue();
        assertThat(saved.getBaiduSite()).isEqualTo("https://example.com");
        assertThat(cipher.decrypt(saved.getBaiduTokenEnc())).isEqualTo("abcd1234EFGH");
    }

    @Test
    void shouldKeepStoredTokenWhenFormTokenBlankOnUpdate() {
        PushConfig existing = new PushConfig();
        existing.setSiteId(SITE_ID);
        existing.setBaiduTokenEnc(cipher.encrypt("old-token-123"));
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(existing));

        PushConfig saved = service.saveSubmission(SITE_ID, baiduOnly("https://example.com", "  "));

        assertThat(cipher.decrypt(saved.getBaiduTokenEnc())).isEqualTo("old-token-123");
    }

    @Test
    void shouldThrowWhenBaiduEnabledWithoutAnyStoredToken() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID, baiduOnly("https://example.com", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }

    @Test
    void shouldThrowWhenBaiduSiteHasPathOrPort() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID, baiduOnly("https://example.com/seo", "tok12345")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("站点");
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID, baiduOnly("https://example.com:8443", "tok12345")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("站点");
    }

    @Test
    void shouldThrowWhenBaiduTokenHasIllegalCharacters() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID, baiduOnly("https://example.com", "tok en 1"))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExtractClientEmailAndEncryptJsonWhenSaveValidGsc() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());

        PushConfig saved = service.saveSubmission(SITE_ID,
                gscOnly("sc-domain:example.com", "https://example.com/sitemap.xml", validGscJson()));

        assertThat(saved.isGscEnabled()).isTrue();
        assertThat(saved.getGscSiteUrl()).isEqualTo("sc-domain:example.com");
        assertThat(saved.getGscClientEmail()).isEqualTo("sa@p.iam.gserviceaccount.com");
        assertThat(saved.getGscServiceAccountJsonEnc()).startsWith("v1:");
    }

    @Test
    void shouldThrowWhenGscJsonInvalidOnSave() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID,
                gscOnly("https://example.com/", "https://example.com/sitemap.xml", "{\"type\":\"x\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service_account");
    }

    @Test
    void shouldThrowWhenGscEnabledWithoutJsonStored() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID,
                gscOnly("https://example.com/", "https://example.com/sitemap.xml", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void shouldKeepStoredGscJsonWithoutRevalidationWhenFormJsonBlankOnUpdate() {
        // Given：存量已加密凭据故意放不可解密密文——留空保存若触发重校验/重解密即抛错
        PushConfig existing = new PushConfig();
        existing.setSiteId(SITE_ID);
        existing.setGscServiceAccountJsonEnc("v1:corrupted-not-a-real-cipher-text");
        existing.setGscClientEmail("old@p.iam.gserviceaccount.com");
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(existing));

        // When：表单启用 GSC 且站点/sitemap 合法，但 JSON 字段留空
        PushConfig saved = service.saveSubmission(SITE_ID,
                gscOnly("sc-domain:example.com", "https://example.com/sitemap.xml", "   "));

        // Then：原样沿用已存密文与 client_email，不重写、不重校验旧值
        assertThat(saved.getGscServiceAccountJsonEnc()).isEqualTo("v1:corrupted-not-a-real-cipher-text");
        assertThat(saved.getGscClientEmail()).isEqualTo("old@p.iam.gserviceaccount.com");
        assertThat(saved.isGscEnabled()).isTrue();
    }

    @Test
    void shouldAcceptBothSiteUrlShapesForGsc() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        PushConfig domain = service.saveSubmission(SITE_ID,
                gscOnly("sc-domain:example.com", "https://example.com/sitemap.xml", validGscJson()));
        PushConfig prefix = service.saveSubmission(SITE_ID,
                gscOnly("https://example.com/", "https://example.com/sitemap.xml", validGscJson()));
        assertThat(domain.getGscSiteUrl()).isEqualTo("sc-domain:example.com");
        assertThat(prefix.getGscSiteUrl()).isEqualTo("https://example.com/");
    }

    @Test
    void shouldThrowWhenSitemapUrlNotAbsolute() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveSubmission(SITE_ID,
                gscOnly("sc-domain:example.com", "/sitemap.xml", validGscJson())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sitemap");
    }

    @Test
    void shouldExposeOnlyNonSecretFieldsInView() {
        PushConfig config = new PushConfig();
        config.setSiteId(SITE_ID);
        config.setBaiduEnabled(true);
        config.setBaiduSite("https://example.com");
        config.setBaiduTokenEnc(cipher.encrypt("secret-token-1"));
        config.setGscEnabled(true);
        config.setGscClientEmail("sa@p.iam.gserviceaccount.com");
        config.setGscServiceAccountJsonEnc(cipher.encrypt(GOOD_JSON));

        SubmissionView view = SubmissionView.of(config);

        assertThat(view.baiduEnabled()).isTrue();
        assertThat(view.baiduSite()).isEqualTo("https://example.com");
        assertThat(view.hasBaiduToken()).isTrue();
        assertThat(view.hasGscJson()).isTrue();
        assertThat(view.gscClientEmail()).isEqualTo("sa@p.iam.gserviceaccount.com");
        // 结构级防泄漏：视图记录没有任何凭据字段，也没有 JSON 字段
        assertThat(Arrays.stream(SubmissionView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("baiduEnabled", "baiduSite", "hasBaiduToken",
                        "gscEnabled", "gscSiteUrl", "gscSitemapUrl", "hasGscJson", "gscClientEmail");
        assertThat(view.toString()).doesNotContain("secret-token-1");
    }
}
