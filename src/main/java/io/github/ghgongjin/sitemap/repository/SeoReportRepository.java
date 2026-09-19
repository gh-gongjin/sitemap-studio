package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.SeoReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @ClassName SeoReportRepository
 * @Description SEO 报告仓储
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public interface SeoReportRepository extends JpaRepository<SeoReport, Long> {

    /**
     * 保留：仅供预览页 reportAvailable 按钮显隐探测，越权拦截由各报告端点的归属校验负责
     */
    Optional<SeoReport> findByTaskId(String taskId);

    Optional<SeoReport> findByTaskIdAndUserId(String taskId, Long userId);

    List<SeoReport> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}
