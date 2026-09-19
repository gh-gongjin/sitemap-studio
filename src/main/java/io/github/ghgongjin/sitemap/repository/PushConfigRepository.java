package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.PushConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @ClassName PushConfigRepository
 * @Description 推送配置仓储（每站点最多一份）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public interface PushConfigRepository extends JpaRepository<PushConfig, Long> {

    Optional<PushConfig> findBySiteId(Long siteId);

    boolean existsBySiteId(Long siteId);

    void deleteBySiteId(Long siteId);
}
