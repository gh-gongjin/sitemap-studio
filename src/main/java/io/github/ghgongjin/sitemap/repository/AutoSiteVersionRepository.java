package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @ClassName AutoSiteVersionRepository
 * @Description 自动更新站点历史版本数据访问
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public interface AutoSiteVersionRepository extends JpaRepository<AutoSiteVersion, Long> {

    List<AutoSiteVersion> findBySiteIdOrderByVersionNumberDesc(Long siteId);

    Optional<AutoSiteVersion> findTopBySiteIdOrderByVersionNumberDesc(Long siteId);

    Optional<AutoSiteVersion> findBySiteIdAndVersionNumber(Long siteId, int versionNumber);

    long countBySiteId(Long siteId);

    void deleteBySiteId(Long siteId);
}
