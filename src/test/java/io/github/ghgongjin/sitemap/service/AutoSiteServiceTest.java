package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName AutoSiteServiceTest
 * @Description 自动更新存储层单元测试
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class AutoSiteServiceTest {

    private static final String SITE = "https://example.com";
    private static final String XML = "<urlset></urlset>";
    private static final Long OWNER = 7L;
    private static final Long OTHER = 8L;

    private AutoSiteRepository siteRepository;
    private AutoSiteVersionRepository versionRepository;
    private AutoSiteService service;

    @BeforeEach
    void setUp() {
        siteRepository = mock(AutoSiteRepository.class);
        versionRepository = mock(AutoSiteVersionRepository.class);
        when(siteRepository.save(any(AutoSite.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new AutoSiteService(siteRepository, versionRepository);
        service.setCrawlUrlPolicy(publicResolver());
    }

    @Test
    void shouldCreateSiteWithPendingStatusWhenValidUrl() {
        // Given
        when(siteRepository.existsByUserIdAndUrl(OWNER, SITE)).thenReturn(false);

        // When
        AutoSite site = service.create(OWNER, SITE, true, false, true, 24);

        // Then
        assertThat(site.getUserId()).isEqualTo(OWNER);
        assertThat(site.getUrl()).isEqualTo(SITE);
        assertThat(site.isIncludeImages()).isTrue();
        assertThat(site.isIncludeVideos()).isFalse();
        assertThat(site.isIncludeNews()).isTrue();
        assertThat(site.getIntervalHours()).isEqualTo(24);
        assertThat(site.isEnabled()).isTrue();
        assertThat(site.getLastStatus()).isEqualTo(AutoSiteService.STATUS_PENDING);
        assertThat(site.getNextRunAt()).isNotNull();
        assertThat(site.getCreatedAt()).isNotNull();
        assertThat(site.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldRejectDuplicateWhenUrlExistsForSameUser() {
        // Given
        when(siteRepository.existsByUserIdAndUrl(OWNER, SITE)).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> service.create(OWNER, SITE, false, false, false, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已在自动更新列表中");
        verify(siteRepository, never()).save(any(AutoSite.class));
    }

    @Test
    void shouldAllowSameUrlWhenOwnedByAnotherUser() {
        // Given: 同一 URL 只在本用户名下重复
        when(siteRepository.existsByUserIdAndUrl(OWNER, SITE)).thenReturn(false);

        // When
        AutoSite site = service.create(OWNER, SITE, false, false, false, 24);

        // Then: 判重按 (用户, URL) 维度，他人占用不阻塞本人
        assertThat(site.getUserId()).isEqualTo(OWNER);
        verify(siteRepository).existsByUserIdAndUrl(OWNER, SITE);
        verify(siteRepository, never()).existsByUserIdAndUrl(OTHER, SITE);
    }

    @Test
    void shouldRejectCreateWhenOwnerMissing() {
        // When & Then: 无归属用户不得落库，否则站点会对任何登录用户都不可见
        assertThatThrownBy(() -> service.create(null, SITE, false, false, false, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("登录");
        verify(siteRepository, never()).save(any(AutoSite.class));
        verify(siteRepository, never()).existsByUserIdAndUrl(any(), anyString());
    }

    @Test
    void shouldRejectIntervalWhenOutOfRange() {
        // When & Then
        assertThatThrownBy(() -> service.create(OWNER, SITE, false, false, false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("更新间隔");
        assertThatThrownBy(() -> service.create(OWNER, SITE, false, false, false,
                AutoSiteService.MAX_INTERVAL_HOURS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("更新间隔");
    }

    @Test
    void shouldRejectIntranetHostWhenUrlPointsToPrivateIp() {
        // Given
        service.setCrawlUrlPolicy(new CrawlUrlPolicy(host -> new InetAddress[]{
                address(10, 0, 0, 1)}));

        // When & Then
        assertThatThrownBy(() -> service.create(OWNER, "http://internal.example.com/",
                false, false, false, 24))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("内网");
    }

    @Test
    void shouldRejectCredentialUrlWhenUserInfoPresent() {
        // When & Then
        assertThatThrownBy(() -> service.create(OWNER, "https://user:pass@example.com/",
                false, false, false, 24))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("凭据");
    }

    @Test
    void shouldNormalizeUrlWhenCaseAndTrailingSlash() {
        // Given
        when(siteRepository.existsByUserIdAndUrl(OWNER, "https://example.com/docs")).thenReturn(false);

        // When
        AutoSite site = service.create(OWNER, "HTTPS://Example.COM/docs/?q=1#top", false, false, false, 24);

        // Then
        assertThat(site.getUrl()).isEqualTo("https://example.com/docs");
    }

    @Test
    void shouldDecoratePortAndPathWhenPresent() {
        // Given
        when(siteRepository.existsByUserIdAndUrl(OWNER, "http://example.com:8080/blog")).thenReturn(false);

        // When
        AutoSite site = service.create(OWNER, "http://example.com:8080/blog/", false, false, false, 12);

        // Then
        assertThat(site.getUrl()).isEqualTo("http://example.com:8080/blog");
    }

    @Test
    void shouldReturnOwnSitesFromRepository() {
        // Given
        when(siteRepository.findByUserIdOrderByCreatedAtDesc(OWNER)).thenReturn(List.of(site(1L, 24)));

        // When / Then
        assertThat(service.listOwned(OWNER)).hasSize(1);
        verify(siteRepository).findByUserIdOrderByCreatedAtDesc(OWNER);
    }

    @Test
    void shouldReturnEmptyListWhenUserIdNull() {
        // When / Then: 游客没有作用域，不查库
        assertThat(service.listOwned(null)).isEmpty();
        verify(siteRepository, never()).findByUserIdOrderByCreatedAtDesc(any());
    }

    @Test
    void shouldReturnDueSitesFromRepository() {
        // Given
        List<AutoSite> due = List.of(new AutoSite(), new AutoSite());
        when(siteRepository.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(any(LocalDateTime.class)))
                .thenReturn(due);

        // Then
        assertThat(service.dueSites()).hasSize(2);
    }

    @Test
    void shouldReturnSiteWhenFindOwnedMatchesUser() {
        // Given
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site(1L, 24)));

        // When / Then
        assertThat(service.findOwned(1L, OWNER)).map(AutoSite::getId).hasValue(1L);
    }

    @Test
    void shouldReturnEmptyWhenFindOwnedDoesNotMatchUser() {
        // Given: 站点归属他人
        AutoSite other = site(1L, 24);
        other.setUserId(OTHER);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(other));

        // When / Then
        assertThat(service.findOwned(1L, OWNER)).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenFindOwnedArgumentsMissing() {
        // Given: 存量数据没有归属
        AutoSite legacy = site(1L, 24);
        legacy.setUserId(null);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(legacy));

        // When / Then: 无归属站点与缺参数都不可见
        assertThat(service.findOwned(1L, OWNER)).isEmpty();
        assertThat(service.findOwned(null, OWNER)).isEmpty();
        assertThat(service.findOwned(1L, null)).isEmpty();
    }

    @Test
    void shouldResetNextRunWhenRunNow() {
        // Given
        AutoSite site = site(1L, 24);
        site.setNextRunAt(LocalDateTime.now().plusHours(20));
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        // When
        AutoSite updated = service.runNow(1L, OWNER);

        // Then
        assertThat(updated.getNextRunAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void shouldRejectRunNowWhenSiteOwnedByAnotherUser() {
        // Given
        AutoSite other = site(1L, 24);
        other.setUserId(OTHER);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(other));

        // When & Then: 越权与不存在归一，且不产生任何写入
        assertThatThrownBy(() -> service.runNow(1L, OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        verify(siteRepository, never()).save(any(AutoSite.class));
    }

    @Test
    void shouldScheduleImmediatelyWhenEnablingPastDueSite() {
        // Given
        AutoSite site = site(1L, 24);
        site.setEnabled(false);
        site.setNextRunAt(LocalDateTime.now().minusDays(3));
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        // When
        AutoSite updated = service.setEnabled(1L, true, OWNER);

        // Then
        assertThat(updated.isEnabled()).isTrue();
        assertThat(updated.getNextRunAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void shouldKeepNextRunWhenDisablingSite() {
        // Given
        LocalDateTime future = LocalDateTime.now().plusHours(5);
        AutoSite site = site(1L, 24);
        site.setNextRunAt(future);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        // When
        AutoSite updated = service.setEnabled(1L, false, OWNER);

        // Then
        assertThat(updated.isEnabled()).isFalse();
        assertThat(updated.getNextRunAt()).isEqualTo(future);
    }

    @Test
    void shouldRejectSetEnabledWhenSiteOwnedByAnotherUser() {
        // Given
        AutoSite other = site(1L, 24);
        other.setUserId(OTHER);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(other));

        // When & Then
        assertThatThrownBy(() -> service.setEnabled(1L, false, OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        verify(siteRepository, never()).save(any(AutoSite.class));
    }

    @Test
    void shouldPushNextRunFutureWhenMarkRunning() {
        // Given
        AutoSite site = site(1L, 24);
        site.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        // When
        AutoSite updated = service.markRunning(1L);

        // Then
        assertThat(updated.getLastStatus()).isEqualTo(AutoSiteService.STATUS_RUNNING);
        assertThat(updated.getNextRunAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void shouldWriteFirstVersionWhenSuccess() {
        // Given
        AutoSite site = site(1L, 24);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.empty());
        when(versionRepository.findBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(List.of());

        // When
        AutoSite updated = service.recordSuccess(1L, "task-1", XML, 42);

        // Then
        ArgumentCaptor<AutoSiteVersion> captor = ArgumentCaptor.forClass(AutoSiteVersion.class);
        verify(versionRepository).save(captor.capture());
        AutoSiteVersion version = captor.getValue();
        assertThat(version.getSiteId()).isEqualTo(1L);
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getTaskId()).isEqualTo("task-1");
        assertThat(version.getUrlCount()).isEqualTo(42);
        assertThat(version.getSitemapXml()).isEqualTo(XML);
        assertThat(version.getCreatedAt()).isNotNull();

        assertThat(updated.getLastStatus()).isEqualTo(AutoSiteService.STATUS_SUCCESS);
        assertThat(updated.getLastRunAt()).isNotNull();
        assertThat(updated.getLastMessage()).isNull();
        assertThat(updated.getNextRunAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void shouldIncrementVersionWhenSuccessRepeats() {
        // Given
        AutoSite site = site(1L, 24);
        AutoSiteVersion latest = new AutoSiteVersion();
        latest.setVersionNumber(3);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(latest));
        when(versionRepository.findBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(List.of(latest));

        // When
        service.recordSuccess(1L, "task-2", XML, 10);

        // Then
        ArgumentCaptor<AutoSiteVersion> captor = ArgumentCaptor.forClass(AutoSiteVersion.class);
        verify(versionRepository).save(captor.capture());
        assertThat(captor.getValue().getVersionNumber()).isEqualTo(4);
    }

    @Test
    void shouldTrimOldVersionsWhenExceedKeepLimit() {
        // Given
        AutoSite site = site(1L, 24);
        List<AutoSiteVersion> stored = new ArrayList<>();
        for (int i = AutoSiteService.KEEP_VERSIONS; i >= 1; i--) {
            AutoSiteVersion v = new AutoSiteVersion();
            v.setVersionNumber(i);
            stored.add(v);
        }
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(Optional.of(stored.get(0)));
        when(versionRepository.findBySiteIdOrderByVersionNumberDesc(1L)).thenReturn(overflowList(stored));

        // When
        service.recordSuccess(1L, "task-3", XML, 5);

        // Then
        ArgumentCaptor<List<AutoSiteVersion>> captor = ArgumentCaptor.forClass(List.class);
        verify(versionRepository).deleteAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getVersionNumber()).isEqualTo(1);
    }

    @Test
    void shouldKeepVersionsAndSetFailedWhenFailure() {
        // Given
        AutoSite site = site(1L, 24);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        // When
        AutoSite updated = service.recordFailure(1L, "连接超时");

        // Then
        assertThat(updated.getLastStatus()).isEqualTo(AutoSiteService.STATUS_FAILED);
        assertThat(updated.getLastMessage()).isEqualTo("连接超时");
        assertThat(updated.getLastRunAt()).isNotNull();
        assertThat(updated.getNextRunAt()).isAfter(LocalDateTime.now());
        verify(versionRepository, never()).save(any(AutoSiteVersion.class));
    }

    @Test
    void shouldDefaultMessageWhenFailureMessageBlank() {
        // Given
        AutoSite site = site(1L, 24);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        // When
        AutoSite updated = service.recordFailure(1L, "  ");

        // Then
        assertThat(updated.getLastMessage()).isEqualTo("未知错误");
    }

    @Test
    void shouldTrimMessageWhenTooLong() {
        // Given
        AutoSite site = site(1L, 24);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        // When
        AutoSite updated = service.recordFailure(1L, "x".repeat(900));

        // Then
        assertThat(updated.getLastMessage()).hasSize(512);
    }

    @Test
    void shouldDeleteVersionsWhenSiteDeleted() {
        // Given
        AutoSite site = site(1L, 24);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        // When
        service.delete(1L, OWNER);

        // Then
        verify(versionRepository).deleteBySiteId(1L);
        verify(siteRepository).delete(site);
    }

    @Test
    void shouldRejectDeleteWhenSiteOwnedByAnotherUser() {
        // Given
        AutoSite other = site(1L, 24);
        other.setUserId(OTHER);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(other));

        // When & Then: 越权删除被拒，站点与版本都还在
        assertThatThrownBy(() -> service.delete(1L, OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        verify(siteRepository, never()).delete(any(AutoSite.class));
        verify(versionRepository, never()).deleteBySiteId(any());
    }

    @Test
    void shouldThrowWhenSiteNotFound() {
        // Given
        when(siteRepository.findById(anyLong())).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.runNow(9L, OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void shouldReturnEmptyVersionsWhenSiteIdNull() {
        // Then
        assertThat(service.versions(null)).isEmpty();
        assertThat(service.latestVersion(null)).isEmpty();
        assertThat(service.find(null)).isEmpty();
    }

    @Test
    void shouldReturnLatestVersionFromRepository() {
        // Given
        AutoSiteVersion latest = new AutoSiteVersion();
        latest.setVersionNumber(7);
        when(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(2L)).thenReturn(Optional.of(latest));

        // Then
        assertThat(service.latestVersion(2L)).map(AutoSiteVersion::getVersionNumber).hasValue(7);
    }

    @Test
    void shouldReturnVersionByNumberWhenQueried() {
        // Given
        AutoSiteVersion target = new AutoSiteVersion();
        target.setVersionNumber(2);
        when(versionRepository.findBySiteIdAndVersionNumber(1L, 2)).thenReturn(Optional.of(target));

        // Then
        assertThat(service.version(1L, 2)).map(AutoSiteVersion::getVersionNumber).hasValue(2);
        assertThat(service.version(null, 2)).isEmpty();
    }

    private AutoSite site(Long id, int intervalHours) {
        AutoSite site = new AutoSite();
        site.setId(id);
        site.setUserId(OWNER);
        site.setUrl(SITE);
        site.setIntervalHours(intervalHours);
        site.setEnabled(true);
        site.setNextRunAt(LocalDateTime.now());
        site.setLastStatus(AutoSiteService.STATUS_PENDING);
        site.setCreatedAt(LocalDateTime.now());
        site.setUpdatedAt(LocalDateTime.now());
        return site;
    }

    private List<AutoSiteVersion> overflowList(List<AutoSiteVersion> stored) {
        List<AutoSiteVersion> all = new ArrayList<>();
        AutoSiteVersion newest = new AutoSiteVersion();
        newest.setVersionNumber(AutoSiteService.KEEP_VERSIONS + 1);
        all.add(newest);
        all.addAll(stored);
        return all;
    }

    private static CrawlUrlPolicy publicResolver() {
        return new CrawlUrlPolicy(host -> new InetAddress[]{address(93, 184, 216, 34)});
    }

    private static InetAddress address(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
