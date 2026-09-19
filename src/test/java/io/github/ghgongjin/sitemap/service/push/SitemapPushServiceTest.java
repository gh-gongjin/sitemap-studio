package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.PushLog;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.PushLogRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName SitemapPushServiceTest
 * @Description 站点地图推送服务单元测试：版本取用、重试、错误分类、指纹 TOFU、日志裁剪
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class SitemapPushServiceTest {

    private static final Long SITE_ID = 1L;
    private static final String XML = "<urlset><url><loc>https://example.com/a</loc></url></urlset>";

    @TempDir
    Path tempDir;

    private PushConfigRepository configRepository;
    private PushLogRepository logRepository;
    private AutoSiteVersionRepository versionRepository;
    private CredentialCipher cipher;
    private AutoSiteService autoSiteService;
    private IndexNowClient indexNowClient;
    private FakeTransport sftpTransport;
    private FakeTransport ftpTransport;
    private SitemapPushService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(PushConfigRepository.class);
        logRepository = mock(PushLogRepository.class);
        versionRepository = mock(AutoSiteVersionRepository.class);
        cipher = new CredentialCipher("", tempDir.resolve("push.key").toString());
        autoSiteService = mock(AutoSiteService.class);
        indexNowClient = mock(IndexNowClient.class);
        sftpTransport = new FakeTransport(PushProtocol.SFTP);
        ftpTransport = new FakeTransport(PushProtocol.FTP);
        service = new SitemapPushService(configRepository, logRepository, versionRepository, cipher,
                autoSiteService, indexNowClient, List.of(sftpTransport, ftpTransport));
        when(logRepository.findBySiteIdOrderByIdDesc(SITE_ID)).thenReturn(List.of());
    }

    @Test
    void shouldUploadLatestVersionToConfiguredFileWhenPushSucceeds() {
        // Given
        PushConfig config = config("SFTP");
        config.setHostKeyFingerprint("SHA256:recorded");
        givenConfigAndVersion(config, version(3));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.versionNumber()).isEqualTo(3);
        assertThat(sftpTransport.uploadedNames).containsExactly("sitemap.xml");
        assertThat(sftpTransport.uploadedContents)
                .containsExactly(XML.getBytes(StandardCharsets.UTF_8));

        PushTarget target = sftpTransport.uploadedTargets.get(0);
        assertThat(target.protocol()).isEqualTo(PushProtocol.SFTP);
        assertThat(target.host()).isEqualTo("sftp.example.com");
        assertThat(target.port()).isEqualTo(22);
        assertThat(target.username()).isEqualTo("deployer");
        assertThat(target.password()).isEqualTo("s3cret");
        assertThat(target.remoteDir()).isEqualTo("/var/www/html");
        assertThat(target.hostKeyFingerprint()).isEqualTo("SHA256:recorded");

        ArgumentCaptor<PushLog> logCaptor = ArgumentCaptor.forClass(PushLog.class);
        verify(logRepository).save(logCaptor.capture());
        PushLog log = logCaptor.getValue();
        assertThat(log.getStatus()).isEqualTo(PushLog.STATUS_SUCCESS);
        assertThat(log.getErrorCode()).isNull();
        assertThat(log.getIndexNowStatus()).isNull();
        assertThat(log.getProtocol()).isEqualTo("SFTP");
        assertThat(log.getVersionNumber()).isEqualTo(3);
        assertThat(log.getSiteId()).isEqualTo(SITE_ID);

        assertThat(config.getLastPushStatus()).isEqualTo(PushLog.STATUS_SUCCESS);
        assertThat(config.getLastPushAt()).isNotNull();
        assertThat(config.getLastPushError()).isNull();
    }

    @Test
    void shouldRetryOnceAndSucceedWhenFirstUploadFails() {
        // Given
        givenConfigAndVersion(config("SFTP"), version(1));
        sftpTransport.failNext(new PushTransportException(PushErrorCode.CONNECT_FAILED, "连接被拒绝"));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isTrue();
        assertThat(sftpTransport.uploadedTargets).hasSize(2);
        verify(logRepository).save(any(PushLog.class));
    }

    @Test
    void shouldRecordFailureWhenAllAttemptsFail() {
        // Given
        PushConfig config = config("SFTP");
        givenConfigAndVersion(config, version(2));
        sftpTransport.failNext(new PushTransportException(PushErrorCode.TIMEOUT, "连接超时"));
        sftpTransport.failNext(new PushTransportException(PushErrorCode.TIMEOUT, "连接超时"));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(PushErrorCode.TIMEOUT);
        assertThat(outcome.versionNumber()).isEqualTo(2);
        assertThat(sftpTransport.uploadedTargets).hasSize(2);

        ArgumentCaptor<PushLog> logCaptor = ArgumentCaptor.forClass(PushLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo(PushLog.STATUS_FAILED);
        assertThat(logCaptor.getValue().getErrorCode()).isEqualTo("TIMEOUT");

        assertThat(config.getLastPushStatus()).isEqualTo(PushLog.STATUS_FAILED);
        assertThat(config.getLastPushError()).isNotBlank();
        assertThat(config.getLastPushAt()).isNotNull();
    }

    @Test
    void shouldSkipWhenConfigMissing() {
        // Given
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.skipped()).isTrue();
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.detail()).isNotBlank();
        verify(logRepository, never()).save(any(PushLog.class));
        verify(configRepository, never()).save(any(PushConfig.class));
    }

    @Test
    void shouldSkipWhenPushDisabled() {
        // Given
        PushConfig config = config("SFTP");
        config.setEnabled(false);
        givenConfigAndVersion(config, version(1));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.skipped()).isTrue();
        assertThat(sftpTransport.uploadedTargets).isEmpty();
        verify(logRepository, never()).save(any(PushLog.class));
    }

    @Test
    void shouldSkipWhenNoVersionAvailable() {
        // Given
        PushConfig config = config("SFTP");
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(config));
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(SITE_ID)).thenReturn(Optional.empty());

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.skipped()).isTrue();
        assertThat(sftpTransport.uploadedTargets).isEmpty();
        verify(logRepository, never()).save(any(PushLog.class));
    }

    @Test
    void shouldRecordObservedFingerprintWhenSftpFirstConnects() {
        // Given
        PushConfig config = config("SFTP");
        config.setHostKeyFingerprint(null);
        givenConfigAndVersion(config, version(1));
        sftpTransport.observedFingerprint = "SHA256:freshly-observed";

        // When
        service.push(SITE_ID);

        // Then
        assertThat(config.getHostKeyFingerprint()).isEqualTo("SHA256:freshly-observed");
        assertThat(sftpTransport.uploadedTargets.get(0).hostKeyFingerprint()).isNull();
    }

    @Test
    void shouldKeepExistingFingerprintWhenAlreadyRecorded() {
        // Given
        PushConfig config = config("SFTP");
        config.setHostKeyFingerprint("SHA256:trusted");
        givenConfigAndVersion(config, version(1));
        sftpTransport.observedFingerprint = "SHA256:unexpected";

        // When
        service.push(SITE_ID);

        // Then
        assertThat(config.getHostKeyFingerprint()).isEqualTo("SHA256:trusted");
        assertThat(sftpTransport.uploadedTargets.get(0).hostKeyFingerprint()).isEqualTo("SHA256:trusted");
    }

    @Test
    void shouldUseTransportMatchingConfiguredProtocol() {
        // Given
        givenConfigAndVersion(config("FTP"), version(1));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isTrue();
        assertThat(ftpTransport.uploadedTargets).hasSize(1);
        assertThat(sftpTransport.uploadedTargets).isEmpty();
    }

    @Test
    void shouldPublishIndexNowKeyFileAndSubmitWhenEnabled() throws Exception {
        // Given
        PushConfig config = config("SFTP");
        config.setIndexNowEnabled(true);
        config.setIndexNowKey("a".repeat(32));
        givenConfigAndVersion(config, version(5));
        when(autoSiteService.find(SITE_ID)).thenReturn(Optional.of(site()));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isTrue();
        assertThat(sftpTransport.uploadedNames)
                .containsExactly("sitemap.xml", "a".repeat(32) + ".txt");
        assertThat(sftpTransport.uploadedContents.get(1))
                .isEqualTo("a".repeat(32).getBytes(StandardCharsets.UTF_8));
        verify(indexNowClient).submit("https://example.com", "a".repeat(32),
                List.of("https://example.com/a"));

        ArgumentCaptor<PushLog> logCaptor = ArgumentCaptor.forClass(PushLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo(PushLog.STATUS_SUCCESS);
        assertThat(logCaptor.getValue().getIndexNowStatus()).isEqualTo(PushLog.INDEX_NOW_SUCCESS);
    }

    @Test
    void shouldKeepPushSuccessWhenIndexNowSubmitFails() throws Exception {
        // Given
        PushConfig config = config("SFTP");
        config.setIndexNowEnabled(true);
        config.setIndexNowKey("b".repeat(32));
        givenConfigAndVersion(config, version(1));
        when(autoSiteService.find(SITE_ID)).thenReturn(Optional.of(site()));
        doThrow(new PushTransportException(PushErrorCode.INDEXNOW_FAILED, "IndexNow 拒绝提交（HTTP 422）"))
                .when(indexNowClient).submit(anyString(), anyString(), anyList());

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isTrue();
        ArgumentCaptor<PushLog> logCaptor = ArgumentCaptor.forClass(PushLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo(PushLog.STATUS_SUCCESS);
        assertThat(logCaptor.getValue().getIndexNowStatus()).isEqualTo(PushLog.INDEX_NOW_FAILED);
    }

    @Test
    void shouldSkipIndexNowWhenDisabled() throws Exception {
        // Given
        givenConfigAndVersion(config("SFTP"), version(1));

        // When
        service.push(SITE_ID);

        // Then
        assertThat(sftpTransport.uploadedNames).containsExactly("sitemap.xml");
        verify(indexNowClient, never()).submit(anyString(), anyString(), anyList());
    }

    @Test
    void shouldSkipIndexNowWhenKeyMissing() throws Exception {
        // Given
        PushConfig config = config("SFTP");
        config.setIndexNowEnabled(true);
        givenConfigAndVersion(config, version(1));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isTrue();
        assertThat(sftpTransport.uploadedNames).containsExactly("sitemap.xml");
        verify(indexNowClient, never()).submit(anyString(), anyString(), anyList());
    }

    @Test
    void shouldVerifyConnectionAndRecordFingerprintWhenTestSucceeds() throws Exception {
        // Given
        PushConfig config = config("SFTP");
        config.setHostKeyFingerprint(null);
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(config));
        sftpTransport.observedFingerprint = "SHA256:test-observed";

        // When
        PushOutcome outcome = service.testConnection(SITE_ID);

        // Then
        assertThat(outcome.success()).isTrue();
        assertThat(sftpTransport.verifyTargets).hasSize(1);
        assertThat(sftpTransport.verifyTargets.get(0).password()).isEqualTo("s3cret");
        assertThat(config.getHostKeyFingerprint()).isEqualTo("SHA256:test-observed");
        verify(configRepository).save(config);
    }

    @Test
    void shouldReportFailureWhenConnectionTestFails() throws Exception {
        // Given
        PushConfig config = config("SFTP");
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(config));
        sftpTransport.verifyFailure =
                new PushTransportException(PushErrorCode.AUTH_FAILED, "认证失败（用户名或密码错误）");

        // When
        PushOutcome outcome = service.testConnection(SITE_ID);

        // Then
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(PushErrorCode.AUTH_FAILED);
        verify(configRepository, never()).save(any(PushConfig.class));
    }

    @Test
    void shouldSkipConnectionTestWhenConfigMissing() {
        // Given
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.empty());

        // When
        PushOutcome outcome = service.testConnection(SITE_ID);

        // Then
        assertThat(outcome.skipped()).isTrue();
        assertThat(sftpTransport.verifyTargets).isEmpty();
    }

    @Test
    void shouldRecordFailureWhenStoredCredentialsCannotDecrypt() {
        // Given
        PushConfig config = config("SFTP");
        config.setPasswordEnc("v1:not-a-valid-payload");
        givenConfigAndVersion(config, version(1));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(PushErrorCode.AUTH_FAILED);
        assertThat(sftpTransport.uploadedTargets).isEmpty();

        ArgumentCaptor<PushLog> logCaptor = ArgumentCaptor.forClass(PushLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo(PushLog.STATUS_FAILED);
        assertThat(config.getLastPushStatus()).isEqualTo(PushLog.STATUS_FAILED);
    }

    @Test
    void shouldTrimOldPushLogsWhenExceedingLimit() {
        // Given
        givenConfigAndVersion(config("SFTP"), version(1));
        List<PushLog> existing = new ArrayList<>();
        for (long id = 51L; id >= 1L; id--) {
            PushLog log = new PushLog();
            log.setId(id);
            log.setSiteId(SITE_ID);
            existing.add(log);
        }
        when(logRepository.findBySiteIdOrderByIdDesc(SITE_ID)).thenReturn(existing);

        // When
        service.push(SITE_ID);

        // Then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PushLog>> listCaptor = ArgumentCaptor.forClass(List.class);
        verify(logRepository).deleteAll(listCaptor.capture());
        assertThat(listCaptor.getValue()).extracting(PushLog::getId).containsExactly(1L);
    }

    @Test
    void shouldTruncateLongFailureDetailWhenPersisting() {
        // Given
        PushConfig config = config("SFTP");
        givenConfigAndVersion(config, version(1));
        String longMessage = "错误详情".repeat(200);
        sftpTransport.failNext(new PushTransportException(PushErrorCode.UPLOAD_FAILED, longMessage));
        sftpTransport.failNext(new PushTransportException(PushErrorCode.UPLOAD_FAILED, longMessage));

        // When
        PushOutcome outcome = service.push(SITE_ID);

        // Then
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.detail().length()).isLessThanOrEqualTo(512);
        assertThat(config.getLastPushError().length()).isLessThanOrEqualTo(512);
    }

    private void givenConfigAndVersion(PushConfig config, AutoSiteVersion version) {
        when(configRepository.findBySiteId(SITE_ID)).thenReturn(Optional.of(config));
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(SITE_ID))
                .thenReturn(Optional.of(version));
    }

    private PushConfig config(String protocol) {
        PushConfig config = new PushConfig();
        config.setSiteId(SITE_ID);
        config.setEnabled(true);
        config.setProtocol(protocol);
        config.setHost("sftp.example.com");
        config.setPort(22);
        config.setUsername("deployer");
        config.setAuthType("PASSWORD");
        config.setPasswordEnc(cipher.encrypt("s3cret"));
        config.setRemoteDir("/var/www/html");
        config.setSitemapFileName("sitemap.xml");
        config.setIndexNowEnabled(false);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private AutoSite site() {
        AutoSite site = new AutoSite();
        site.setId(SITE_ID);
        site.setUrl("https://example.com");
        return site;
    }

    private AutoSiteVersion version(int number) {
        AutoSiteVersion version = new AutoSiteVersion();
        version.setSiteId(SITE_ID);
        version.setVersionNumber(number);
        version.setTaskId("task-" + number);
        version.setUrlCount(10);
        version.setSitemapXml(XML);
        version.setCreatedAt(LocalDateTime.now());
        return version;
    }

    private static final class FakeTransport implements PushTransport {

        private final PushProtocol protocol;
        private final List<PushTarget> uploadedTargets = new ArrayList<>();
        private final List<PushTarget> verifyTargets = new ArrayList<>();
        private final List<String> uploadedNames = new ArrayList<>();
        private final List<byte[]> uploadedContents = new ArrayList<>();
        private final Queue<PushTransportException> scriptedFailures = new ArrayDeque<>();
        String observedFingerprint;
        PushTransportException verifyFailure;

        private FakeTransport(PushProtocol protocol) {
            this.protocol = protocol;
        }

        void failNext(PushTransportException failure) {
            scriptedFailures.add(failure);
        }

        @Override
        public PushProtocol protocol() {
            return protocol;
        }

        @Override
        public String verify(PushTarget target) throws PushTransportException {
            verifyTargets.add(target);
            if (verifyFailure != null) {
                throw verifyFailure;
            }
            return observedFingerprint;
        }

        @Override
        public String upload(PushTarget target, String remoteFileName, byte[] content)
                throws PushTransportException {
            uploadedTargets.add(target);
            uploadedNames.add(remoteFileName);
            uploadedContents.add(content);
            PushTransportException failure = scriptedFailures.poll();
            if (failure != null) {
                throw failure;
            }
            return observedFingerprint;
        }
    }
}
