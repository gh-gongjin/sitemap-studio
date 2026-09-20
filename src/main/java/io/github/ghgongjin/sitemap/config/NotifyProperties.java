package io.github.ghgongjin.sitemap.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @ClassName NotifyProperties
 * @Description 变更告警全局配置（sitemap.notify.*）：一键关停、webhook 超时、内网放行、邮件日配额
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "sitemap.notify")
public class NotifyProperties {

    private boolean enabled = true;
    private int webhookTimeoutMs = 10000;
    private boolean allowPrivateNetwork = true;
    private int maxEmailsPerSitePerDay = 50;
}
