package io.github.ghgongjin.sitemap.service.notify;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.AutoSiteValidationException;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.ThrowableAssert.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @ClassName NotifySettingsServiceTest
 * @Description 告警设置服务：URL/邮箱/阈值逐分支校验、secret 留空沿用、email 清空合法、view 有效值展开
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
class NotifySettingsServiceTest {

    private AutoSiteService autoSiteService;
    private AutoSiteRepository siteRepository;
    private WebhookUrlPolicy urlPolicy;
    private CredentialCipher cipher;
    private NotifySettingsService service;
    private AutoSite site;

    @BeforeEach
    void setUp() {
        autoSiteService = mock(AutoSiteService.class);
        siteRepository = mock(AutoSiteRepository.class);
        urlPolicy = mock(WebhookUrlPolicy.class);
        cipher = mock(CredentialCipher.class);
        service = new NotifySettingsService(autoSiteService, siteRepository, urlPolicy, cipher);
        site = new AutoSite();
        site.setId(1L);
        site.setUserId(7L);
        when(autoSiteService.findOwned(1L, 7L)).thenReturn(Optional.of(site));
        when(siteRepository.save(any(AutoSite.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static NotifySettings settings(String url, String secret, String email, int threshold) {
        return new NotifySettings(true, true, url, secret, email, threshold);
    }

    @Test
    void shouldPersistTrimmedValuesWhenValid() {
        when(urlPolicy.check("https://hooks.example.com/hook")).thenReturn(WebhookUrlPolicy.WebhookUrlCheck.OK);
        when(cipher.encrypt("s3cr3t")).thenReturn("v1:ciphered");

        AutoSite saved = service.save(1L, 7L,
                settings("  https://hooks.example.com/hook  ", "  s3cr3t  ", " ops@example.com ", 10));

        assertThat(saved).isSameAs(site);
        assertThat(site.getNotifyWebhookUrl()).isEqualTo("https://hooks.example.com/hook");
        assertThat(site.getNotifyWebhookSecretEnc()).isEqualTo("v1:ciphered");
        assertThat(site.getNotifyEmail()).isEqualTo("ops@example.com");
        assertThat(site.getNotifySeoErrorThreshold()).isEqualTo(10);
        assertThat(site.isNotifyOnChangeEffective()).isTrue();
        assertThat(site.isNotifyOnFailureEffective()).isTrue();
    }

    @Test
    void shouldThrowNotFoundAndSkipAllSettersWhenSiteNotOwned() {
        when(autoSiteService.findOwned(2L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(2L, 7L, settings(null, null, null, -1)))
                .isInstanceOf(AutoSiteValidationException.class)
                .hasMessage("auto.error.notFound");
        verify(siteRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldTreatBlankUrlAsClearedWithoutPolicyCall(String url) {
        AutoSite saved = service.save(1L, 7L, settings(url, null, null, -1));

        assertThat(saved.getNotifyWebhookUrl()).isNull();
        verify(urlPolicy, never()).check(anyString());
    }

    @ParameterizedTest
    @CsvSource({
            "MALFORMED, auto.notify.err.webhook.malformed",
            "FORBIDDEN_SCHEME, auto.notify.err.webhook.forbidden_scheme",
            "HAS_CREDENTIALS, auto.notify.err.webhook.has_credentials",
            "DNS_FAILED, auto.notify.err.webhook.dns_failed",
            "DENIED_ALWAYS, auto.notify.err.webhook.denied_always",
            "DENIED_PRIVATE, auto.notify.err.webhook.denied_private",
    })
    void shouldMapEveryRejectionToKeyedErrorWithUrlArg(WebhookUrlPolicy.WebhookUrlCheck check, String key) {
        when(urlPolicy.check("http://x.example.com")).thenReturn(check);

        AutoSiteValidationException thrown = catchThrowableOfType(AutoSiteValidationException.class,
                () -> service.save(1L, 7L, settings("http://x.example.com", null, null, -1)));

        assertThat(thrown).isNotNull();
        assertThat(thrown.messageKey()).isEqualTo(key);
        assertThat(thrown.args()).containsExactly("http://x.example.com");
        verify(siteRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "a@b", "a b@c.com", "a@@b.com", "a@b@c.com"})
    void shouldRejectMalformedEmail(String email) {
        assertThatThrownBy(() -> service.save(1L, 7L, settings(null, null, email, -1)))
                .isInstanceOf(AutoSiteValidationException.class)
                .hasMessage("auto.notify.err.email");
    }

    @Test
    void shouldRejectOverlongEmail() {
        String email = "a".repeat(250) + "@b.com";

        assertThatThrownBy(() -> service.save(1L, 7L, settings(null, null, email, -1)))
                .isInstanceOf(AutoSiteValidationException.class)
                .hasMessage("auto.notify.err.email");
    }

    @Test
    void shouldClearEmailWhenBlankSubmitted() {
        site.setNotifyEmail("ops@example.com");

        service.save(1L, 7L, settings(null, null, "  ", -1));

        assertThat(site.getNotifyEmail()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 100000})
    void shouldAcceptAllowedThresholds(int threshold) {
        service.save(1L, 7L, settings(null, null, null, threshold));

        assertThat(site.getNotifySeoErrorThreshold()).isEqualTo(threshold);
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, -100, 100001, Integer.MAX_VALUE})
    void shouldRejectThresholdOutsideMinusOneOrZeroToMax(int threshold) {
        assertThatThrownBy(() -> service.save(1L, 7L, settings(null, null, null, threshold)))
                .isInstanceOf(AutoSiteValidationException.class)
                .hasMessage("auto.notify.err.threshold");
    }

    @Test
    void shouldKeepExistingSecretWhenBlankSubmitted() {
        site.setNotifyWebhookSecretEnc("v1:old");

        service.save(1L, 7L, settings(null, "", null, -1));

        assertThat(site.getNotifyWebhookSecretEnc()).isEqualTo("v1:old");
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    void shouldKeepExistingSecretWhenNullSubmitted() {
        site.setNotifyWebhookSecretEnc("v1:old");

        service.save(1L, 7L, settings(null, null, null, -1));

        assertThat(site.getNotifyWebhookSecretEnc()).isEqualTo("v1:old");
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    void shouldOverwriteSecretWhenNewOneSubmitted() {
        site.setNotifyWebhookSecretEnc("v1:old");
        when(cipher.encrypt("new-secret")).thenReturn("v1:new");

        service.save(1L, 7L, settings(null, "new-secret", null, -1));

        assertThat(site.getNotifyWebhookSecretEnc()).isEqualTo("v1:new");
    }

    @Test
    void shouldExpandEffectiveValuesInView() {
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        NotifySettingsView view = service.view(1L).orElseThrow();

        assertThat(view.webhookUrl()).isNull();
        assertThat(view.hasWebhookSecret()).isFalse();
        assertThat(view.email()).isNull();
        // null 列展开为生效默认值：开关默认开、阈值默认关
        assertThat(view.notifyOnChange()).isTrue();
        assertThat(view.notifyOnFailure()).isTrue();
        assertThat(view.seoErrorThreshold()).isEqualTo(-1);
    }

    @Test
    void shouldReportHasSecretWithoutExposingCipherTextInView() {
        site.setNotifyWebhookSecretEnc("v1:ciphered");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        NotifySettingsView view = service.view(1L).orElseThrow();

        assertThat(view.hasWebhookSecret()).isTrue();
        assertThat(view.toString()).doesNotContain("v1:ciphered");
    }

    @Test
    void shouldReturnEmptyViewWhenIdNullOrSiteMissing() {
        assertThat(service.view(null)).isEmpty();
        when(siteRepository.findById(9L)).thenReturn(Optional.empty());
        assertThat(service.view(9L)).isEmpty();
    }
}
