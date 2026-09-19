package io.github.ghgongjin.sitemap.repository;

import io.github.ghgongjin.sitemap.entity.PushLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * @ClassName PushLogRepository
 * @Description 推送日志仓储（按站点查询，保留最近若干条）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
public interface PushLogRepository extends JpaRepository<PushLog, Long> {

    List<PushLog> findBySiteIdOrderByIdDesc(Long siteId);

    void deleteBySiteId(Long siteId);
}
