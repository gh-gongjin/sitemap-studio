package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.PushLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @ClassName PushConfigRepositoryTest
 * @Description 推送配置与日志 JPA 集成测试（内存 H2 验证建表与派生查询）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@DataJpaTest
@ActiveProfiles("test")
class PushConfigRepositoryTest {

    @Autowired
    private PushConfigRepository configRepository;

    @Autowired
    private PushLogRepository logRepository;

    @Test
    void shouldPersistAndFindConfigWhenSaved() {
        // Given
        PushConfig config = config(100L);

        // When
        configRepository.saveAndFlush(config);
        PushConfig found = configRepository.findBySiteId(100L).orElseThrow();

        // Then
        assertThat(found.getProtocol()).isEqualTo("SFTP");
        assertThat(found.getHost()).isEqualTo("ftp.example.com");
        assertThat(found.getPort()).isEqualTo(22);
        assertThat(found.getRemoteDir()).isEqualTo("/var/www/html");
        assertThat(found.getSitemapFileName()).isEqualTo("sitemap.xml");
        assertThat(found.isIndexNowEnabled()).isTrue();
        assertThat(configRepository.existsBySiteId(100L)).isTrue();
        assertThat(configRepository.existsBySiteId(999L)).isFalse();
    }

    @Test
    void shouldRejectDuplicateSiteIdWhenSameSiteSavedTwice() {
        // Given
        configRepository.saveAndFlush(config(100L));

        // When & Then
        assertThatThrownBy(() -> configRepository.saveAndFlush(config(100L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldDeleteConfigBySiteId() {
        // Given
        configRepository.saveAndFlush(config(100L));
        configRepository.saveAndFlush(config(200L));

        // When
        configRepository.deleteBySiteId(100L);
        configRepository.flush();

        // Then
        assertThat(configRepository.findBySiteId(100L)).isEmpty();
        assertThat(configRepository.findBySiteId(200L)).isPresent();
    }

    @Test
    void shouldReturnLogsNewestFirstWhenMultipleLogsSaved() {
        // Given
        logRepository.saveAllAndFlush(List.of(log(100L, 1), log(100L, 2), log(200L, 3)));

        // When
        List<PushLog> logs = logRepository.findBySiteIdOrderByIdDesc(100L);

        // Then
        assertThat(logs).extracting(PushLog::getVersionNumber).containsExactly(2, 1);
    }

    @Test
    void shouldDeleteLogsBySiteId() {
        // Given
        logRepository.saveAllAndFlush(List.of(log(100L, 1), log(100L, 2), log(200L, 1)));

        // When
        logRepository.deleteBySiteId(100L);
        logRepository.flush();

        // Then
        assertThat(logRepository.findBySiteIdOrderByIdDesc(100L)).isEmpty();
        assertThat(logRepository.findBySiteIdOrderByIdDesc(200L)).hasSize(1);
    }

    @Test
    void shouldHideCredentialsWhenToStringCalled() {
        // Given
        PushConfig config = config(100L);
        config.setPasswordEnc("v1:secret-cipher");
        config.setPrivateKeyEnc("v1:secret-key-cipher");

        // When
        String text = config.toString();

        // Then
        assertThat(text).doesNotContain("secret-cipher").doesNotContain("secret-key-cipher");
    }

    private PushConfig config(Long siteId) {
        PushConfig config = new PushConfig();
        config.setSiteId(siteId);
        config.setEnabled(true);
        config.setProtocol("SFTP");
        config.setHost("ftp.example.com");
        config.setPort(22);
        config.setUsername("deployer");
        config.setAuthType("PASSWORD");
        config.setPasswordEnc("v1:cipher");
        config.setRemoteDir("/var/www/html");
        config.setSitemapFileName("sitemap.xml");
        config.setHostKeyFingerprint("SHA256:abc");
        config.setIndexNowEnabled(true);
        config.setIndexNowKey("a1b2c3d4e5f60718293a4b5c6d7e8f90");
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private PushLog log(Long siteId, int versionNumber) {
        PushLog log = new PushLog();
        log.setSiteId(siteId);
        log.setVersionNumber(versionNumber);
        log.setProtocol("SFTP");
        log.setStatus(PushLog.STATUS_SUCCESS);
        log.setIndexNowStatus(PushLog.STATUS_SUCCESS);
        log.setDurationMs(1200L);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }
}
