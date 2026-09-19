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
 * @ClassName AutoSite
 * @Description 自动更新站点（按间隔定时重新抓取并生成新版本站点地图）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Data
@Entity
@Table(name = "auto_site")
public class AutoSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 归属用户；为空表示升级前的存量数据，对任何登录用户都不可见。
     * site_url 不再声明全局唯一（唯一性改为 (user_id, site_url) 联合唯一，
     * 由 AutoSiteSchemaMigration 建立并负责迁移旧约束）
     */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "site_url", nullable = false, length = 2048)
    private String url;

    @Column(name = "include_images", nullable = false)
    private boolean includeImages;

    @Column(name = "include_videos", nullable = false)
    private boolean includeVideos;

    @Column(name = "include_news", nullable = false)
    private boolean includeNews;

    @Column(name = "interval_hours", nullable = false)
    private int intervalHours;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "next_run_at", nullable = false)
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_status", nullable = false, length = 16)
    private String lastStatus;

    @Column(name = "last_message", length = 512)
    private String lastMessage;

    /** 连续爬取失败次数；成功即清零。包装类型：存量行升级后该列为 NULL */
    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures;

    @Column(name = "notify_webhook_url", length = 2048)
    private String notifyWebhookUrl;

    @Column(name = "notify_webhook_secret_enc", length = 1024)
    private String notifyWebhookSecretEnc;

    @Column(name = "notify_email", length = 256)
    private String notifyEmail;

    @Column(name = "notify_on_change")
    private Boolean notifyOnChange;

    @Column(name = "notify_on_failure")
    private Boolean notifyOnFailure;

    @Column(name = "notify_seo_error_threshold")
    private Integer notifySeoErrorThreshold;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isNotifyOnChangeEffective() {
        return notifyOnChange == null || notifyOnChange;
    }

    public boolean isNotifyOnFailureEffective() {
        return notifyOnFailure == null || notifyOnFailure;
    }

    public int consecutiveFailuresOrZero() {
        return consecutiveFailures == null ? 0 : consecutiveFailures;
    }

    public int notifySeoErrorThresholdOrOff() {
        return notifySeoErrorThreshold == null ? -1 : notifySeoErrorThreshold;
    }

    public boolean hasNotifyChannelConfigured() {
        return (notifyWebhookUrl != null && !notifyWebhookUrl.isBlank())
                || (notifyEmail != null && !notifyEmail.isBlank());
    }
}
