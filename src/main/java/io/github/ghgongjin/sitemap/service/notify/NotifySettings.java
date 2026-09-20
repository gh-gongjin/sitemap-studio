package io.github.ghgongjin.sitemap.service.notify;

/**
 * @ClassName NotifySettings
 * @Description 告警设置表单的写侧入参（控制器 → 服务的不可变传输对象）；
 *              webhookSecret 留空语义为「沿用已存 Secret」，email 留空语义为「清空=关邮件」
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public record NotifySettings(boolean notifyOnChange, boolean notifyOnFailure,
        String webhookUrl, String webhookSecret, String email, int seoErrorThreshold) {}
