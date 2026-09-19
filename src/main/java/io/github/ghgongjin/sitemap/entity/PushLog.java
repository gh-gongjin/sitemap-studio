package io.github.ghgongjin.sitemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @ClassName PushLog
 * @Description 推送执行日志（每次上传/提交一行，保留最近 50 条）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Data
@Entity
@Table(name = "push_log")
public class PushLog {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public static final String INDEX_NOW_SUCCESS = "SUCCESS";
    public static final String INDEX_NOW_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "protocol", nullable = false, length = 8)
    private String protocol;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error_code", length = 32)
    private String errorCode;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "index_now_status", length = 16)
    private String indexNowStatus;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
