package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.AutoSiteValidationException;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @ClassName NotifySettingsService
 * @Description 告警设置写侧服务：URL 经 WebhookUrlPolicy 逐结论映射为本地化键、邮箱与 SEO 阈值边界校验、
 *              Secret 留空沿用已存密文（加密落库）、归属二次校验兜底 TOCTOU（findOwned 口径与推送配置一致）
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@Service
@RequiredArgsConstructor
public class NotifySettingsService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final AutoSiteService autoSiteService;
    private final AutoSiteRepository siteRepository;
    private final WebhookUrlPolicy urlPolicy;
    private final CredentialCipher cipher;

    @Transactional
    public AutoSite save(Long id, Long userId, NotifySettings settings) {
        AutoSite site = autoSiteService.findOwned(id, userId)
                .orElseThrow(() -> new AutoSiteValidationException("auto.error.notFound", String.valueOf(id)));
        String url = settings.webhookUrl() == null || settings.webhookUrl().isBlank()
                ? null : settings.webhookUrl().trim();
        if (url != null) {
            WebhookUrlPolicy.WebhookUrlCheck check = urlPolicy.check(url);
            if (check != WebhookUrlPolicy.WebhookUrlCheck.OK) {
                throw new AutoSiteValidationException(
                        "auto.notify.err.webhook." + check.name().toLowerCase(Locale.ROOT), url);
            }
        }
        String email = settings.email() == null || settings.email().isBlank() ? null : settings.email().trim();

        if (email != null && (email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches())) {
            throw new AutoSiteValidationException("auto.notify.err.email");
        }
        int threshold = settings.seoErrorThreshold();
        if (threshold != -1 && (threshold < 0 || threshold > 100_000)) {
            throw new AutoSiteValidationException("auto.notify.err.threshold");
        }
        site.setNotifyOnChange(settings.notifyOnChange());
        site.setNotifyOnFailure(settings.notifyOnFailure());
        site.setNotifyWebhookUrl(url);
        site.setNotifyEmail(email);
        site.setNotifySeoErrorThreshold(threshold);
        if (settings.webhookSecret() != null && !settings.webhookSecret().isBlank()) {
            site.setNotifyWebhookSecretEnc(cipher.encrypt(settings.webhookSecret().trim()));
        }
        return siteRepository.save(site);
    }

    @Transactional(readOnly = true)
    public Optional<NotifySettingsView> view(Long id) {
        return id == null ? Optional.empty()
                : siteRepository.findById(id).map(NotifySettingsView::of);
    }
}
