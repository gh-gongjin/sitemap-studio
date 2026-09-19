package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @ClassName AutoSiteRepository
 * @Description 自动更新站点数据访问
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public interface AutoSiteRepository extends JpaRepository<AutoSite, Long> {

    List<AutoSite> findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(LocalDateTime now);

    List<AutoSite> findAllByOrderByCreatedAtDesc();

    Optional<AutoSite> findByUrl(String url);

    boolean existsByUrl(String url);
}
