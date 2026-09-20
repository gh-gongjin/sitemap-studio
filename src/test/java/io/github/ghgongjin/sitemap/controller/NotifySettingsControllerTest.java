package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import io.github.ghgongjin.sitemap.service.UserService;
import io.github.ghgongjin.sitemap.service.notify.NotificationService;
import io.github.ghgongjin.sitemap.service.notify.NotifyOutcome;
import io.github.ghgongjin.sitemap.service.notify.WebhookUrlPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @ClassName NotifySettingsControllerTest
 * @Description 告警设置写侧：合法保存/secret 留空沿用/三类校验拒绝/越权 404/详情面板渲染/测试通知与限流
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotifySettingsControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AutoSiteRepository autoSites;

    @Autowired
    private UserService userService;

    /** 只验证控制器接线（flash 键/限流/404），真实投递由 NotificationServiceTest 覆盖 */
    @MockitoBean
    private NotificationService notificationService;

    /**
     * 真实策略桩保留 scheme/凭据判定，但把 *.test 主机固定解析为公网 IP——
     * 离线测试环境不做真实 DNS，OK/DNS_FAILED 分支不可依赖外网解析结果
     */
    @MockitoBean
    private WebhookUrlPolicy urlPolicy;

    private UserAccountDetails alice;
    private AutoSite aliceSite;

    @BeforeEach
    void setUp() {
        UserAccount a = userService.register("alice", "Passw0rd1");
        userService.register("bob", "Passw0rd1");
        alice = new UserAccountDetails(a.getId(), a.getUsername(), a.getPasswordHash());
        aliceSite = seedSite(a.getId());
        lenient().when(urlPolicy.check("https://hooks.example.test/site"))
                .thenReturn(WebhookUrlPolicy.WebhookUrlCheck.OK);
        lenient().when(urlPolicy.check("ftp://example.test/hook"))
                .thenReturn(WebhookUrlPolicy.WebhookUrlCheck.FORBIDDEN_SCHEME);
    }

    private AutoSite seedSite(Long userId) {
        AutoSite site = new AutoSite();
        site.setUserId(userId);
        site.setUrl("https://notify-test.example.com");
        site.setIncludeImages(false);
        site.setIncludeVideos(false);
        site.setIncludeNews(false);
        site.setIntervalHours(24);
        site.setEnabled(false);
        site.setNextRunAt(LocalDateTime.now().plusHours(24));
        site.setLastStatus("PENDING");
        site.setCreatedAt(LocalDateTime.now());
        site.setUpdatedAt(LocalDateTime.now());
        return autoSites.saveAndFlush(site);
    }

    @Test
    void shouldSaveSettingsWhenValid() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("webhookUrl", "https://hooks.example.test/site")
                        .param("webhookSecret", "s3cr3t-key")
                        .param("email", "ops@example.test")
                        .param("seoErrorThreshold", "10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto/" + aliceSite.getId()))
                .andExpect(flash().attribute("flash", "auto.notify.flash.saved"));

        AutoSite saved = autoSites.findById(aliceSite.getId()).orElseThrow();
        assertThat(saved.getNotifyWebhookUrl()).isEqualTo("https://hooks.example.test/site");
        assertThat(saved.getNotifyEmail()).isEqualTo("ops@example.test");
        assertThat(saved.getNotifySeoErrorThreshold()).isEqualTo(10);
        assertThat(saved.isNotifyOnChangeEffective()).isTrue();
        // checkbox 未勾选 → defaultValue false → 显式关闭（区别于 null 的"沿用默认开"）
        assertThat(saved.isNotifyOnFailureEffective()).isFalse();
        assertThat(saved.getNotifyWebhookSecretEnc()).isNotNull()
                .doesNotContain("s3cr3t-key").startsWith("v1:");
    }

    @Test
    void shouldKeepExistingSecretWhenBlankSubmitted() throws Exception {
        shouldSaveSettingsWhenValid();
        String before = autoSites.findById(aliceSite.getId()).orElseThrow().getNotifyWebhookSecretEnc();

        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("webhookUrl", "https://hooks.example.test/site")
                        .param("webhookSecret", "")
                        .param("email", "ops@example.test")
                        .param("seoErrorThreshold", "10"))
                .andExpect(flash().attribute("flash", "auto.notify.flash.saved"));

        assertThat(autoSites.findById(aliceSite.getId()).orElseThrow()
                .getNotifyWebhookSecretEnc()).isEqualTo(before);
    }

    @Test
    void shouldRejectInvalidEmailWithoutSaving() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("notifyOnFailure", "true")
                        .param("email", "not-an-email"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", "auto.notify.err.email"));

        assertThat(autoSites.findById(aliceSite.getId()).orElseThrow().getNotifyEmail()).isNull();
    }

    @Test
    void shouldRejectNonHttpWebhookScheme() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("notifyOnFailure", "true")
                        .param("webhookUrl", "ftp://example.test/hook"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", "auto.notify.err.webhook.forbidden_scheme"))
                .andExpect(flash().attributeExists("flashErrorArgs"));

        assertThat(autoSites.findById(aliceSite.getId()).orElseThrow().getNotifyWebhookUrl()).isNull();
    }

    @Test
    void shouldRejectBadSeoThreshold() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(alice)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("notifyOnFailure", "true")
                        .param("seoErrorThreshold", "-2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", "auto.notify.err.threshold"));
    }

    @Test
    void shouldReturn404WhenSavingOtherUsersSite() throws Exception {
        UserAccount b = userService.register("carol", "Passw0rd1");
        UserAccountDetails carol = new UserAccountDetails(b.getId(), b.getUsername(), b.getPasswordHash());

        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId())
                        .with(user(carol)).with(csrf())
                        .param("notifyOnChange", "true")
                        .param("notifyOnFailure", "true"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRenderNotifyPanelOnDetail() throws Exception {
        String html = mvc.perform(get("/auto/{id}", aliceSite.getId()).with(user(alice)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("notifyForm", "webhookUrl", "seoErrorThreshold");
    }

    @Test
    void shouldFlashTestSentWhenTestSucceeds() throws Exception {
        when(notificationService.test(aliceSite.getId()))
                .thenReturn(new NotifyOutcome(true, "auto.notify.flash.testSent", null));

        mvc.perform(post("/auto/{id}/notify/test", aliceSite.getId())
                        .with(user(alice)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto/" + aliceSite.getId()))
                .andExpect(flash().attribute("flash", "auto.notify.flash.testSent"));
    }

    @Test
    void shouldRateLimitFourthTestOfTheMinute() throws Exception {
        when(notificationService.test(aliceSite.getId()))
                .thenReturn(new NotifyOutcome(true, "auto.notify.flash.testSent", null));
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/auto/{id}/notify/test", aliceSite.getId())
                            .with(user(alice)).with(csrf()))
                    .andExpect(flash().attribute("flash", "auto.notify.flash.testSent"));
        }
        mvc.perform(post("/auto/{id}/notify/test", aliceSite.getId())
                        .with(user(alice)).with(csrf()))
                .andExpect(flash().attribute("flashError", "auto.notify.flash.rateLimited"));
        verify(notificationService, times(3)).test(aliceSite.getId());
    }

    @Test
    void shouldRedirectGuestFromNotifyEndpoints() throws Exception {
        mvc.perform(post("/auto/{id}/notify/settings", aliceSite.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/auto/{id}/notify/test", aliceSite.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
