package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @ClassName AutoSiteRepositoryTest
 * @Description 自动更新存储层 JPA 集成测试（内存 H2 验证建表与按归属用户的派生查询）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@DataJpaTest
@ActiveProfiles("test")
class AutoSiteRepositoryTest {

    @Autowired
    private AutoSiteRepository siteRepository;

    @Autowired
    private AutoSiteVersionRepository versionRepository;

    @Test
    void shouldPersistAndFindSiteWhenSaved() {
        // Given
        AutoSite site = site("https://example.com", true, LocalDateTime.now().minusMinutes(5), true);

        // When
        AutoSite saved = siteRepository.saveAndFlush(site);
        AutoSite found = siteRepository.findById(saved.getId()).orElseThrow();

        // Then
        assertThat(found.getUrl()).isEqualTo("https://example.com");
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getLastStatus()).isEqualTo("PENDING");
    }

    @Test
    void shouldSelectOnlyEnabledDueSitesOrderedByNextRun() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        AutoSite dueLater = siteRepository.saveAndFlush(
                site("https://a.example.com", true, now.minusMinutes(10), true));
        AutoSite dueSooner = siteRepository.saveAndFlush(
                site("https://b.example.com", true, now.minusMinutes(30), true));
        siteRepository.saveAndFlush(site("https://c.example.com", true, now.plusHours(1), true));
        siteRepository.saveAndFlush(site("https://d.example.com", false, now.minusMinutes(20), true));

        // When
        List<AutoSite> due = siteRepository.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);

        // Then
        assertThat(due).extracting(AutoSite::getId).containsExactly(dueSooner.getId(), dueLater.getId());
    }

    @Test
    void shouldListOnlySitesOwnedByGivenUser() {
        // Given: 两个用户各一个站点，另有一条存量无归属数据
        AutoSite mine = siteRepository.saveAndFlush(site(1L, "https://mine.example.com"));
        siteRepository.saveAndFlush(site(2L, "https://others.example.com"));
        AutoSite legacy = siteRepository.saveAndFlush(site(null, "https://legacy.example.com"));

        // When
        List<AutoSite> owned = siteRepository.findByUserIdOrderByCreatedAtDesc(1L);

        // Then: 任何真实用户都查不到别人的站点与无归属存量数据
        assertThat(owned).extracting(AutoSite::getId).containsExactly(mine.getId());
        assertThat(siteRepository.findByUserIdOrderByCreatedAtDesc(2L))
                .extracting(AutoSite::getUrl).containsExactly("https://others.example.com");
        assertThat(siteRepository.findByUserIdOrderByCreatedAtDesc(3L)).isEmpty();
        assertThat(legacy.getUserId()).isNull();
    }

    @Test
    void shouldCheckDuplicateUrlPerOwnerWhenExistsByUserIdAndUrl() {
        // Given
        siteRepository.saveAndFlush(site(1L, "https://example.com"));

        // When / Then: 判重按 (用户, URL) 维度
        assertThat(siteRepository.existsByUserIdAndUrl(1L, "https://example.com")).isTrue();
        assertThat(siteRepository.existsByUserIdAndUrl(2L, "https://example.com")).isFalse();
        assertThat(siteRepository.existsByUserIdAndUrl(1L, "https://other.example.com")).isFalse();
    }

    @Test
    void shouldReturnVersionsDescAndLatestWhenQueried() {
        // Given
        Long siteId = siteRepository.saveAndFlush(
                site("https://example.com", true, LocalDateTime.now(), true)).getId();
        versionRepository.saveAllAndFlush(List.of(
                version(siteId, 1, 10), version(siteId, 2, 20), version(siteId, 3, 30)));

        // When
        List<AutoSiteVersion> versions = versionRepository.findBySiteIdOrderByVersionNumberDesc(siteId);

        // Then
        assertThat(versions).extracting(AutoSiteVersion::getVersionNumber).containsExactly(3, 2, 1);
        assertThat(versionRepository.findTopBySiteIdOrderByVersionNumberDesc(siteId))
                .map(AutoSiteVersion::getUrlCount).hasValue(30);
        assertThat(versionRepository.countBySiteId(siteId)).isEqualTo(3);
    }

    @Test
    void shouldDeleteVersionsBySiteId() {
        // Given
        Long siteId = siteRepository.saveAndFlush(
                site("https://example.com", true, LocalDateTime.now(), true)).getId();
        Long otherId = siteRepository.saveAndFlush(
                site("https://other.example.com", true, LocalDateTime.now(), true)).getId();
        versionRepository.saveAllAndFlush(List.of(version(siteId, 1, 10), version(otherId, 1, 5)));

        // When
        versionRepository.deleteBySiteId(siteId);
        versionRepository.flush();

        // Then
        assertThat(versionRepository.countBySiteId(siteId)).isZero();
        assertThat(versionRepository.countBySiteId(otherId)).isEqualTo(1);
    }

    private AutoSite site(Long userId, String url) {
        AutoSite site = site(url, true, LocalDateTime.now(), true);
        site.setUserId(userId);
        return site;
    }

    private AutoSite site(String url, boolean enabled, LocalDateTime nextRunAt, boolean includeImages) {
        AutoSite site = new AutoSite();
        site.setUrl(url);
        site.setIncludeImages(includeImages);
        site.setIncludeVideos(false);
        site.setIncludeNews(false);
        site.setIntervalHours(24);
        site.setEnabled(enabled);
        site.setNextRunAt(nextRunAt);
        site.setLastStatus("PENDING");
        site.setCreatedAt(LocalDateTime.now());
        site.setUpdatedAt(LocalDateTime.now());
        return site;
    }

    private AutoSiteVersion version(Long siteId, int number, int urlCount) {
        AutoSiteVersion version = new AutoSiteVersion();
        version.setSiteId(siteId);
        version.setVersionNumber(number);
        version.setTaskId("task-" + number);
        version.setUrlCount(urlCount);
        version.setSitemapXml("<urlset></urlset>");
        version.setCreatedAt(LocalDateTime.now());
        return version;
    }
}
