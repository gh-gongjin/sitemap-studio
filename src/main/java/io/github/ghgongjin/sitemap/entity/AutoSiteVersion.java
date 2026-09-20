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
 * @ClassName AutoSiteVersion
 * @Description 自动更新站点的历史版本（仅成功抓取时写入，失败保留旧版）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Data
@Entity
@Table(name = "auto_site_version")
public class AutoSiteVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "url_count", nullable = false)
    private int urlCount;

    @Lob
    @Column(name = "sitemap_xml", nullable = false)
    private String sitemapXml;

    @Column(name = "diff_added", nullable = false, columnDefinition = "integer default 0")
    private int diffAdded;

    @Column(name = "diff_removed", nullable = false, columnDefinition = "integer default 0")
    private int diffRemoved;

    @Column(name = "diff_changed", nullable = false, columnDefinition = "integer default 0")
    private int diffChanged;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
