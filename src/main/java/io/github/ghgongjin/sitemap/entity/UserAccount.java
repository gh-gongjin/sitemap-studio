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
 * @ClassName UserAccount
 * @Description 平台用户账号（登录名唯一、密码存 BCrypt 散列）
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@Data
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 20)
    private String username;

    /**
     * 密码散列不参与 toString，避免 @Data 生成的 toString 被写入日志/异常信息时泄露密码哈希
     */
    @ToString.Exclude
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
