package io.github.ghgongjin.sitemap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * @ClassName PushConfig
 * @Description 单站点地图推送配置（一个自动更新站点最多一份配置）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Data
@Entity
@Table(name = "push_config")
public class PushConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false, unique = true)
    private Long siteId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "protocol", nullable = false, length = 8)
    private String protocol;

    @Column(name = "host", nullable = false, length = 512)
    private String host;

    @Column(name = "port", nullable = false)
    private int port;

    @Column(name = "username", nullable = false, length = 256)
    private String username;

    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType;

    /** AES-256-GCM 加密后的密码（v1: 前缀） */
    @ToString.Exclude
    @Column(name = "password_enc", length = 4096)
    private String passwordEnc;

    /** AES-256-GCM 加密后的私钥 PEM（v1: 前缀） */
    @ToString.Exclude
    @Column(name = "private_key_enc", length = 8192)
    private String privateKeyEnc;

    @Column(name = "remote_dir", length = 1024)
    private String remoteDir;

    @Column(name = "sitemap_file_name", nullable = false, length = 255)
    private String sitemapFileName;

    @Column(name = "host_key_fingerprint", length = 128)
    private String hostKeyFingerprint;

    @Column(name = "index_now_enabled", nullable = false)
    private boolean indexNowEnabled;

    @Column(name = "index_now_key", length = 64)
    private String indexNowKey;

    @Column(name = "last_push_at")
    private LocalDateTime lastPushAt;

    @Column(name = "last_push_status", length = 16)
    private String lastPushStatus;

    @Column(name = "last_push_error", length = 512)
    private String lastPushError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
