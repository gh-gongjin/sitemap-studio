package io.github.ghgongjin.sitemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @ClassName SeoReport
 * @Description SEO 健康报告（按任务持久化并记录归属用户，问题清单以 JSON 存储）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Data
@Entity
@Table(name = "seo_report")
public class SeoReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    private String taskId;

    /**
     * 报告归属用户；游客爬取与历史存量数据为 null（对任何登录用户均不可见）
     */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "site_url", nullable = false, length = 2048)
    private String siteUrl;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "pages_audited", nullable = false)
    private int pagesAudited;

    @Column(name = "broken_links", nullable = false)
    private int brokenLinks;

    @Column(name = "skipped_pages", nullable = false)
    private int skippedPages;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "warning_count", nullable = false)
    private int warningCount;

    @Column(name = "info_count", nullable = false)
    private int infoCount;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @Lob
    @Column(name = "issues_json")
    private String issuesJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
