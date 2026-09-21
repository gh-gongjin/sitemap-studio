package io.github.ghgongjin.sitemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @ClassName SubmissionLog
 * @Description 搜索引擎提交日志（每通道每次一行，每站保留最近 50 条）
 * @Author gj
 * @Date 2026/9/21
 * @Version 1.0
 */
@Data
@Entity
@Table(name = "submission_log", indexes = @Index(
        name = "idx_submission_log_site_id", columnList = "site_id"))
public class SubmissionLog {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String CHANNEL_BAIDU = "BAIDU";
    public static final String CHANNEL_GSC = "GSC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "channel", nullable = false, length = 8)
    private String channel;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error_code", length = 32)
    private String errorCode;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
