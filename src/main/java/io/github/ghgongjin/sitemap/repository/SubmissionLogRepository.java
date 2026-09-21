package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.SubmissionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * @ClassName SubmissionLogRepository
 * @Description 搜索引擎提交日志仓储（按站点查询，保留最近若干条）
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
public interface SubmissionLogRepository extends JpaRepository<SubmissionLog, Long> {

    List<SubmissionLog> findBySiteIdOrderByIdDesc(Long siteId);

    void deleteBySiteId(Long siteId);
}
