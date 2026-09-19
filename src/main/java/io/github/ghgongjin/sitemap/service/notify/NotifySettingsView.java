package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;

/**
 * @ClassName NotifySettingsView
 * @Description 告警设置回显视图：null 列展开为生效默认值（开关默认开、阈值 -1=关），
 *              Secret 永不回显明文/密文，仅以 hasWebhookSecret 指示「已配置」
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
public record NotifySettingsView(String webhookUrl, boolean hasWebhookSecret, String email,
        boolean notifyOnChange, boolean notifyOnFailure, Integer seoErrorThreshold) {

    public static NotifySettingsView of(AutoSite site) {
        return new NotifySettingsView(
                site.getNotifyWebhookUrl(),
                site.getNotifyWebhookSecretEnc() != null && !site.getNotifyWebhookSecretEnc().isBlank(),
                site.getNotifyEmail(),
                site.isNotifyOnChangeEffective(),
                site.isNotifyOnFailureEffective(),
                site.getNotifySeoErrorThreshold() == null ? -1 : site.getNotifySeoErrorThreshold());
    }
}
