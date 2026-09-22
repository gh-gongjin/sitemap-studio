package io.github.ghgongjin.sitemap.service.submission;

import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.SubmissionLog;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.SubmissionLogRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchEngineSubmissionServiceTest {

    private static final Long SITE_ID = 1L;

    @TempDir
    Path tempDir;

    private PushConfigRepository configRepository;
    private SubmissionLogRepository logRepository;
    private AutoSiteService autoSiteService;
    private CredentialCipher cipher;
    private BaiduPushClient baiduClient;
    private GoogleSitemapClient gscClient;
    private SearchEngineSubmissionService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(PushConfigRepository.class);
        logRepository = mock(SubmissionLogRepository.class);
        autoSiteService = mock(AutoSiteService.class);
        cipher = new CredentialCipher("", tempDir.resolve("push.key").toString());
        baiduClient = mock(BaiduPushClient.class);
        gscClient = mock(GoogleSitemapClient.class);
        service = new SearchEngineSubmissionService(configRepository, logRepository,
                autoSiteService, cipher, baiduClient, gscClient);
        when(logRepository.save(any(SubmissionLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(logRepository.findBySiteIdOrderByIdDesc(SITE_ID)).thenReturn(List.of());
    }

    private static AutoSiteVersion version(int number, String... urls) {
        AutoSiteVersion entity = new AutoSiteVersion();
        entity.setSiteId(SITE_ID);
        entity.setVersionNumber(number);
        StringBuilder xml = new StringBuilder(
                "<urlset><url><loc>https://example.com/keep</loc><lastmod>2026-01-01</lastmod></url>");
        for (String url : urls) {
            xml.append("<url><loc>").append(url).append("</loc><lastmod>2026-01-02</lastmod></url>");
        }
        entity.setSitemapXml(xml.append("</urlset>").toString());
        return entity;
    }

    private PushConfig baiduConfig() {
        PushConfig config = new PushConfig();
        config.setSiteId(SITE_ID);
        config.setBaiduEnabled(true);
        config.setBaiduSite("https://example.com");
        config.setBaiduTokenEnc(cipher.encrypt("tok123456"));
        return config;
    }

    private PushConfig gscConfig() {
        PushConfig config = new PushConfig();
        config.setSiteId(SITE_ID);
        config.setGscEnabled(true);
        config.setGscSiteUrl("sc-domain:example.com");
        config.setGscSitemapUrl("https://example.com/sitemap.xml");
        config.setGscServiceAccountJsonEnc(cipher.encrypt(validJson()));
        return config;
    }

    private static String validJson() {
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

    @Test
    void shouldSubmitOnlyAddedAndChangedUrlsWhenPreviousExists() throws Exception {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(baiduConfig()));
        when(autoSiteService.latestVersion(SITE_ID))
                .thenReturn(Optional.of(version(2, "https://example.com/new", "https://example.com/mod")));
        when(autoSiteService.version(SITE_ID, 1)).thenReturn(Optional.of(
                version(1, "https://example.com/mod")));
        when(baiduClient.push(anyString(), anyString(), any())).thenReturn(
                new BaiduPushClient.BaiduPushResponse(1, 99));

        SubmissionOutcome outcome = service.submit(SITE_ID);

        assertThat(outcome.success()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(baiduClient).push(eq("https://example.com"), eq("tok123456"), captor.capture());
        assertThat(captor.getValue()).containsExactly("https://example.com/new");
        ArgumentCaptor<SubmissionLog> log = ArgumentCaptor.forClass(SubmissionLog.class);
        verify(logRepository).save(log.capture());
        assertThat(log.getValue().getChannel()).isEqualTo(SubmissionLog.CHANNEL_BAIDU);
        assertThat(log.getValue().getStatus()).isEqualTo(SubmissionLog.STATUS_SUCCESS);
        assertThat(log.getValue().getDetail()).contains("接收 1").contains("剩余配额 99");
    }

    @Test
    void shouldFallBackToFullUrlsWhenPreviousVersionMissing() throws Exception {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(baiduConfig()));
        when(autoSiteService.latestVersion(SITE_ID))
                .thenReturn(Optional.of(version(3, "https://example.com/a", "https://other.org/b")));
        when(autoSiteService.version(SITE_ID, 2)).thenReturn(Optional.empty());
        when(baiduClient.push(anyString(), anyString(), any())).thenReturn(
                new BaiduPushClient.BaiduPushResponse(1, 99));

        service.submit(SITE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(baiduClient).push(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue()).containsExactly("https://example.com/keep", "https://example.com/a");
    }

    @Test
    void shouldFilterForeignHostsAndCountThemInDetail() throws Exception {
        SearchEngineSubmissionService.BaiduUrls urls =
                service.baiduUrls("https://example.com", List.of(
                        "https://example.com/a", "http://example.com/b", "https://cdn.example.com/c",
                        "ftp://example.com/d", "not a url", "https://EXAMPLE.COM/e"));

        assertThat(urls.batch()).containsExactly("https://example.com/a", "http://example.com/b",
                "https://EXAMPLE.COM/e");
        assertThat(urls.filteredOut()).isEqualTo(3);
    }

    @Test
    void shouldTruncateBatchToMaxUrls() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < SearchEngineSubmissionService.BAIDU_MAX_URLS + 5; i++) {
            many.add("https://example.com/p" + i);
        }
        SearchEngineSubmissionService.BaiduUrls urls = service.baiduUrls("https://example.com", many);
        assertThat(urls.batch()).hasSize(SearchEngineSubmissionService.BAIDU_MAX_URLS);
        assertThat(urls.filteredOut()).isZero();
    }

    @Test
    void shouldSkipWithoutLogWhenBothChannelsDisabledOrUnconfigured() {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());
        assertThat(service.submit(SITE_ID).skipped()).isTrue();

        PushConfig disabled = new PushConfig();
        disabled.setSiteId(SITE_ID);
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(disabled));
        assertThat(service.submit(SITE_ID).skipped()).isTrue();

        verify(logRepository, never()).save(any());
    }

    @Test
    void shouldStillSubmitGscWhenBaiduFailsAndReportFailure() throws Exception {
        PushConfig both = baiduConfig();
        both.setGscEnabled(true);
        both.setGscSiteUrl("sc-domain:example.com");
        both.setGscSitemapUrl("https://example.com/sitemap.xml");
        both.setGscServiceAccountJsonEnc(cipher.encrypt(validJson()));
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(both));
        when(autoSiteService.latestVersion(SITE_ID)).thenReturn(Optional.of(version(1)));
        when(baiduClient.push(anyString(), anyString(), any()))
                .thenThrow(new SubmissionClientException(SubmissionErrorCode.BAIDU_REJECTED, "百度拒绝"));

        SubmissionOutcome outcome = service.submit(SITE_ID);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.detail()).contains("百度");
        verify(gscClient).submitSitemap(any(), eq("sc-domain:example.com"),
                eq("https://example.com/sitemap.xml"));
        ArgumentCaptor<SubmissionLog> log = ArgumentCaptor.forClass(SubmissionLog.class);
        verify(logRepository, org.mockito.Mockito.times(2)).save(log.capture());
        assertThat(log.getAllValues()).extracting(SubmissionLog::getStatus)
                .containsExactly(SubmissionLog.STATUS_FAILED, SubmissionLog.STATUS_SUCCESS);
        assertThat(log.getAllValues().get(0).getErrorCode()).isEqualTo("BAIDU_REJECTED");
    }

    @Test
    void shouldRecordDecryptFailureAsConfigDecryptFailed() {
        PushConfig config = baiduConfig();
        config.setBaiduTokenEnc(cipher.encrypt("x"));
        // 换一把钥匙：模拟 master key 轮换后存量密文不可解
        service = new SearchEngineSubmissionService(configRepository, logRepository,
                autoSiteService, new CredentialCipher("", tempDir.resolve("other.key").toString()),
                baiduClient, gscClient);
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(config));
        when(autoSiteService.latestVersion(SITE_ID)).thenReturn(Optional.of(version(1)));

        SubmissionOutcome outcome = service.submit(SITE_ID);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.detail()).contains("凭据无法解密");
    }

    @Test
    void shouldTrimLogsBeyondFiftyPerSite() throws Exception {
        List<SubmissionLog> fiftyOne = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            SubmissionLog entry = new SubmissionLog();
            entry.setSiteId(SITE_ID);
            fiftyOne.add(entry);
        }
        when(logRepository.findBySiteIdOrderByIdDesc(SITE_ID)).thenReturn(fiftyOne);
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(baiduConfig()));
        when(autoSiteService.latestVersion(SITE_ID)).thenReturn(Optional.of(version(1)));
        when(baiduClient.push(anyString(), anyString(), any()))
                .thenReturn(new BaiduPushClient.BaiduPushResponse(1, 99));

        service.submit(SITE_ID);

        // brief 示例 argThat(list -> list.size()) 会被推导为 Iterable（无 size()），等价最小替代：
        verify(logRepository).deleteAll(org.mockito.ArgumentMatchers.argThat(
                list -> list instanceof List<?> tail && tail.size() == 1));
    }

    // ===== 全局约束补充用例：落库前脱敏 与 异常隔离红线 =====

    @Test
    void shouldSanitizeTokenInExceptionDetailBeforeRecording() throws Exception {
        // Spring RestClient 传输异常的 message 必含完整 URI（带 token 查询串）——落库前必须遮蔽
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(baiduConfig()));
        when(autoSiteService.latestVersion(SITE_ID)).thenReturn(Optional.of(version(1)));
        when(baiduClient.push(anyString(), anyString(), any())).thenThrow(
                new SubmissionClientException(SubmissionErrorCode.BAIDU_TRANSPORT,
                        "百度推送请求失败：I/O error on POST request for "
                                + "\"https://data.zz.baidu.com/urls?site=https%3A%2F%2Fexample.com&token=SECRETTOKEN9F3A\""
                                + ": 连接复位"));

        SubmissionOutcome outcome = service.submit(SITE_ID);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.detail()).doesNotContain("SECRETTOKEN9F3A").contains("token=***");
        ArgumentCaptor<SubmissionLog> log = ArgumentCaptor.forClass(SubmissionLog.class);
        verify(logRepository).save(log.capture());
        assertThat(log.getValue().getDetail()).doesNotContain("SECRETTOKEN9F3A").contains("token=***");
    }

    @Test
    void shouldRecordConfigInvalidAndNotThrowWhenBaiduSiteMissing() {
        PushConfig config = baiduConfig();
        config.setBaiduSite(null);
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(config));
        when(autoSiteService.latestVersion(SITE_ID)).thenReturn(Optional.of(version(1)));

        SubmissionOutcome outcome = service.submit(SITE_ID);

        assertThat(outcome.success()).isFalse();
        ArgumentCaptor<SubmissionLog> log = ArgumentCaptor.forClass(SubmissionLog.class);
        verify(logRepository).save(log.capture());
        assertThat(log.getValue().getStatus()).isEqualTo(SubmissionLog.STATUS_FAILED);
        assertThat(log.getValue().getErrorCode()).isEqualTo("CONFIG_INVALID");
    }
}
