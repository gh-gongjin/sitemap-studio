package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @ClassName AutoSiteRepository
 * @Description 自动更新站点数据访问（站点列表与判重按归属用户维度）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public interface AutoSiteRepository extends JpaRepository<AutoSite, Long> {

    List<AutoSite> findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(LocalDateTime now);

    /**
     * 按归属用户查站点列表。
     * 注意：Spring Data 派生查询对 null 入参会按 {@code user_id = null OR user_id IS NULL} 处理，
     * 从而能查到存量无归属数据，因此入参必须非空；游客与空归属由 AutoSiteService 挡下
     */
    List<AutoSite> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<AutoSite> findByUrl(String url);

    /**
     * 同一用户下 URL 是否已存在（判重按归属维度，不同用户可各自托管同一 URL）；
     * userId 为 null 时同样会命中存量无归属数据，调用方须保证非空
     */
    boolean existsByUserIdAndUrl(Long userId, String url);
}
