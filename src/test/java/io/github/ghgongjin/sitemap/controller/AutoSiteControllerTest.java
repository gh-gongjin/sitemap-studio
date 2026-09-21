package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.config.NotifyProperties;
import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.PushLog;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.AutoSiteValidationException;
import io.github.ghgongjin.sitemap.service.notify.NotificationService;
import io.github.ghgongjin.sitemap.service.notify.NotifySettingsService;
import io.github.ghgongjin.sitemap.service.notify.NotifyTestLimiter;
import io.github.ghgongjin.sitemap.service.push.PushConfigService;
import io.github.ghgongjin.sitemap.service.push.PushConfigView;
import io.github.ghgongjin.sitemap.service.push.PushErrorCode;
import io.github.ghgongjin.sitemap.service.push.PushOutcome;
import io.github.ghgongjin.sitemap.service.push.PushSettings;
import io.github.ghgongjin.sitemap.service.push.SitemapPushService;
import io.github.ghgongjin.sitemap.service.push.SubmissionSettings;
import io.github.ghgongjin.sitemap.service.push.SubmissionView;
import io.github.ghgongjin.sitemap.service.submission.SearchEngineSubmissionService;
import io.github.ghgongjin.sitemap.service.submission.SubmissionOutcome;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @ClassName AutoSiteControllerTest
 * @Description 自动更新管理页与接口测试（真实模板渲染 + 表单流转 + 归属用户作用域）
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
class AutoSiteControllerTest {

    private static final String SITE = "https://example.com";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset/>";
    private static final Long USER_ID = 7L;

    private AutoSiteService autoSiteService;
    private PushConfigService pushConfigService;
    private SitemapPushService sitemapPushService;
    private SearchEngineSubmissionService submissionService;
    private MockMvc mvc;
    private MockMvc mvcEn;

    @BeforeEach
    void setUp() {
        // 控制器在请求线程读取归属用户，standalone 场景需手工装配登录态
        UserAccountDetails details = new UserAccountDetails(USER_ID, "tester", "");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                details, "", details.getAuthorities()));
        autoSiteService = mock(AutoSiteService.class);
        pushConfigService = mock(PushConfigService.class);
        sitemapPushService = mock(SitemapPushService.class);
        submissionService = mock(SearchEngineSubmissionService.class);
        // 控制器 @RequiredArgsConstructor：新依赖字段声明在最后，构造调用尾部同序追加
        AutoSiteController controller = new AutoSiteController(autoSiteService, pushConfigService,
                sitemapPushService, mock(NotifySettingsService.class), mock(NotificationService.class),
                mock(NotifyTestLimiter.class), new NotifyProperties(), submissionService);

        mvc = mockMvc(controller, Locale.SIMPLIFIED_CHINESE);
        mvcEn = mockMvc(controller, Locale.ENGLISH);
    }

    /**
     * standalone 装配真实 Thymeleaf 引擎与 messages 资源束，按入参语言解析 flash 文案
     */
    private MockMvc mockMvc(AutoSiteController controller, Locale locale) {
        ClassLoaderTemplateResolver templates = new ClassLoaderTemplateResolver();
        templates.setPrefix("templates/");
        templates.setSuffix(".html");
        templates.setTemplateMode("HTML");
        templates.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        engine.setMessageSource(messages);
        ThymeleafViewResolver views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding(StandardCharsets.UTF_8.name());
        views.setViewNames(new String[]{"auto", "auto-detail"});
        return MockMvcBuilders.standaloneSetup(controller)
                .setLocaleResolver(new FixedLocaleResolver(locale))
                .setViewResolvers(views, new InternalResourceViewResolver("/", ".html")).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRenderSiteRowsWithStatusAndVersionWhenListHasSites() throws Exception {
        // Given
        when(autoSiteService.listOwned(USER_ID)).thenReturn(List.of(site(true, "SUCCESS")));
        when(autoSiteService.latestVersion(1L)).thenReturn(Optional.of(version(3, 42)));

        // When
        Document page = render(get("/auto"));

        // Then
        assertThat(page.select(".url-table tbody tr")).hasSize(1);
        assertThat(page.selectFirst(".u a").text()).isEqualTo(SITE);
        assertThat(page.select(".flags span").eachText()).containsExactly("图片");
        assertThat(page.selectFirst(".state").text()).isEqualTo("成功");
        assertThat(page.selectFirst(".state").hasClass("state-success")).isTrue();
        assertThat(page.selectFirst(".ver a").text()).isEqualTo("v3 · 42");
        assertThat(page.selectFirst(".lm").text()).isEqualTo("每天");
    }

    @Test
    void shouldRenderEmptyStateWhenNoSites() throws Exception {
        // Given
        when(autoSiteService.listOwned(USER_ID)).thenReturn(List.of());

        // When
        Document page = render(get("/auto"));

        // Then
        assertThat(page.selectFirst(".empty-ok b").text()).contains("还没有自动更新站点");
        assertThat(page.select(".url-table")).isEmpty();
    }

    @Test
    void shouldRedirectWithFlashWhenCreateSucceeds() throws Exception {
        // Given
        when(autoSiteService.create(eq(USER_ID), anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(site(true, "PENDING"));

        // When & Then
        mvc.perform(post("/auto")
                        .param("url", SITE)
                        .param("includeImages", "true")
                        .param("intervalHours", "24"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto"))
                .andExpect(flash().attribute("flash", "auto.flash.added"));
    }

    @Test
    void shouldShowSuccessFlashWhenFollowingRedirect() throws Exception {
        // Given
        when(autoSiteService.create(eq(USER_ID), anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(site(true, "PENDING"));
        when(autoSiteService.listOwned(USER_ID)).thenReturn(List.of());
        MockHttpSession session = new MockHttpSession();

        // When
        mvc.perform(post("/auto").session(session).param("url", SITE));
        Document page = render(get("/auto").session(session));

        // Then
        assertThat(page.selectFirst(".flash.ok").text()).contains("已加入自动更新列表");
    }

    @Test
    void shouldFlashErrorWhenCreateRejected() throws Exception {
        // Given: 非 message key 的原始原因（爬虫安全策略、推送失败等）保持原文透出
        when(autoSiteService.create(eq(USER_ID), anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenThrow(new SecurityException("拒绝包含内网地址的主机"));

        // When & Then
        mvc.perform(post("/auto").param("url", SITE))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto"))
                .andExpect(flash().attribute("flashError", "拒绝包含内网地址的主机"));
    }

    @Test
    void shouldFlashMessageKeyAndArgsWhenCreateValidationFails() throws Exception {
        // Given
        when(autoSiteService.create(eq(USER_ID), anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenThrow(new AutoSiteValidationException("auto.error.duplicate", SITE));

        // When
        MvcResult result = mvc.perform(post("/auto").param("url", SITE))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto"))
                .andExpect(flash().attribute("flashError", "auto.error.duplicate"))
                .andReturn();

        // Then: flashError 存 key，占位符参数单独放进 flashErrorArgs
        assertThat((Object[]) result.getFlashMap().get("flashErrorArgs")).containsExactly(SITE);
    }

    @Test
    void shouldRenderLocalizedDuplicateMessageWhenFollowingRedirectInChinese() throws Exception {
        // Given
        givenCreateRejects(new AutoSiteValidationException("auto.error.duplicate", SITE));

        // When
        Document page = renderFlash(Locale.SIMPLIFIED_CHINESE);

        // Then: key 由 MessageSource 解析成中文，参数填进占位符
        assertThat(page.selectFirst(".flash span").text())
                .isEqualTo("该网站已在自动更新列表中：" + SITE);
        assertThat(page.html()).doesNotContain("auto.error.duplicate", "??auto", "null");
    }

    @Test
    void shouldRenderLocalizedDuplicateMessageWhenFollowingRedirectInEnglish() throws Exception {
        // Given
        givenCreateRejects(new AutoSiteValidationException("auto.error.duplicate", SITE));

        // When
        Document page = renderFlash(Locale.ENGLISH);

        // Then: 同一 key 按当前语言解析为英文
        assertThat(page.selectFirst(".flash span").text())
                .isEqualTo("This site is already in your auto-update list: " + SITE);
        assertThat(page.html()).doesNotContain("auto.error.duplicate", "??auto", "请先登录");
    }

    @Test
    void shouldRenderLocalizedIntervalMessageWithBoundsWhenFollowingRedirect() throws Exception {
        // Given
        givenCreateRejects(new AutoSiteValidationException("auto.error.interval",
                AutoSiteService.MIN_INTERVAL_HOURS, AutoSiteService.MAX_INTERVAL_HOURS));

        // When & Then: 参数为整数时按英文/中文各自模板渲染
        assertThat(renderFlash(Locale.ENGLISH).selectFirst(".flash span").text())
                .isEqualTo("Update interval must be between 1 and 720 hours");
    }

    @Test
    void shouldRenderNeedLoginMessageWhenFlashErrorKeyHasNoArgs() throws Exception {
        // Given
        givenCreateRejects(new AutoSiteValidationException("auto.error.needLogin"));

        // When
        Document page = renderFlash(Locale.SIMPLIFIED_CHINESE);

        // Then: 无参 key 也走同一条解析路径
        assertThat(page.selectFirst(".flash span").text()).isEqualTo("请先登录后再添加自动更新站点");
        assertThat(page.html()).doesNotContain("auto.error.needLogin", "??auto");
    }

    @Test
    void shouldRenderRawMessageWhenFlashErrorIsNotAMessageKey() throws Exception {
        // Given
        givenCreateRejects(new SecurityException("拒绝包含内网地址的主机"));

        // When
        Document page = renderFlash(Locale.SIMPLIFIED_CHINESE);

        // Then: 不是 key 就原样展示，且不能被 MessageSource 兜底成 ??key??
        assertThat(page.selectFirst(".flash span").text()).isEqualTo("拒绝包含内网地址的主机");
        assertThat(page.html()).doesNotContain("??");
    }

    @Test
    void shouldRunNowAndRedirect() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));

        // When & Then
        mvc.perform(post("/auto/1/run"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto"))
                .andExpect(flash().attribute("flash", "auto.flash.run"));
        verify(autoSiteService).runNow(1L, USER_ID);
    }

    @Test
    void shouldDisableSiteWhenTogglingEnabledSite() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));

        // When & Then
        mvc.perform(post("/auto/1/toggle"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "auto.flash.disabled"));
        verify(autoSiteService).setEnabled(1L, false, USER_ID);
    }

    @Test
    void shouldEnableSiteWhenTogglingDisabledSite() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(false, "FAILED")));

        // When & Then
        mvc.perform(post("/auto/1/toggle"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "auto.flash.enabled"));
        verify(autoSiteService).setEnabled(1L, true, USER_ID);
    }

    @Test
    void shouldDeleteAndRedirect() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));

        // When & Then
        mvc.perform(post("/auto/1/delete"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "auto.flash.deleted"));
        verify(autoSiteService).delete(1L, USER_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auto/1/run", "/auto/1/toggle", "/auto/1/delete",
            "/auto/1/push/test", "/auto/1/push/run",
            "/auto/1/submission/settings", "/auto/1/submission/run"})
    void shouldReturn404AndSkipWriteWhenSiteNotOwned(String path) throws Exception {
        // Given: 站点不存在或属于他人，findOwned 一律 empty
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.empty());

        // When & Then: 越权写操作 404，不触发任何业务写入（也不落到 flash 内联提示）
        mvc.perform(post(path)).andExpect(status().isNotFound());
        verify(autoSiteService, never()).runNow(any(), any());
        verify(autoSiteService, never()).setEnabled(any(), anyBoolean(), any());
        verify(autoSiteService, never()).delete(any(), any());
        verifyNoInteractions(pushConfigService);
        verifyNoInteractions(sitemapPushService);
        verifyNoInteractions(submissionService);
    }

    @Test
    void shouldReturn404AndSkipSaveWhenPushSettingsTargetForeignSite() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.empty());

        // When & Then
        mvc.perform(post("/auto/1/push/settings")
                        .param("protocol", "SFTP").param("host", "sftp.example.com")
                        .param("port", "22").param("username", "deployer"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(pushConfigService);
    }

    @Test
    void shouldRenderDetailWithVersions() throws Exception {
        // Given
        AutoSite site = site(true, "SUCCESS");
        site.setLastRunAt(LocalDateTime.of(2026, 9, 18, 10, 0));
        site.setNextRunAt(LocalDateTime.of(2026, 9, 19, 10, 0));
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site));
        when(autoSiteService.versions(1L)).thenReturn(List.of(version(3, 42), version(2, 40)));

        // When
        Document page = render(get("/auto/1"));

        // Then
        assertThat(page.selectFirst(".prose-head h2").text()).isEqualTo(SITE);
        assertThat(page.select(".meters .meter b").eachText())
                .containsExactly("每天", "成功", "09-18 10:00", "09-19 10:00");
        assertThat(page.select(".url-table tbody tr")).hasSize(2);
        assertThat(page.select(".url-table .pr").eachText()).containsExactly("v3", "v2");
        // 版本表新增「变化」列（Task 10）：非首版渲染 +n −n ~n 徽标链接（− 为 U+2212）
        assertThat(page.select(".url-table .lm").eachText()).containsExactly("42", "+0 −0 ~0", "2026-09-18 10:00",
                "40", "+0 −0 ~0", "2026-09-18 10:00");
    }

    @Test
    void shouldRenderFailureReasonWhenLastRunFailed() throws Exception {
        // Given
        AutoSite site = site(true, "FAILED");
        site.setLastMessage("拒绝包含内网地址的主机");
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site));
        when(autoSiteService.versions(1L)).thenReturn(List.of());

        // When
        Document page = render(get("/auto/1"));

        // Then
        assertThat(page.selectFirst(".detail-alert").text()).isEqualTo("拒绝包含内网地址的主机");
        assertThat(page.selectFirst(".empty-ok b").text()).contains("还没有成功版本");
    }

    @Test
    void shouldReturn404WhenSiteMissing() throws Exception {
        // Given
        when(autoSiteService.findOwned(9L, USER_ID)).thenReturn(Optional.empty());

        // Then
        mvc.perform(get("/auto/9")).andExpect(status().isNotFound());
        mvc.perform(get("/auto/9/download")).andExpect(status().isNotFound());
    }

    @Test
    void shouldDownloadLatestVersionWhenNoVersionParam() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.latestVersion(1L)).thenReturn(Optional.of(version(3, 42)));

        // When & Then
        mvc.perform(get("/auto/1/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/xml"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sitemap-v3.xml")))
                .andExpect(content().bytes(XML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldDownloadGivenVersionWhenVersionParam() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.version(1L, 2)).thenReturn(Optional.of(version(2, 40)));

        // When & Then
        mvc.perform(get("/auto/1/download").param("version", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sitemap-v2.xml")));
        verify(autoSiteService, never()).latestVersion(any());
    }

    @Test
    void shouldReturn404WhenVersionMissing() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.version(1L, 99)).thenReturn(Optional.empty());

        // Then
        mvc.perform(get("/auto/1/download").param("version", "99")).andExpect(status().isNotFound());
    }

    @Test
    void shouldUseCustomConfirmDialogWhenDeleteFormRendered() throws Exception {
        // Given
        when(autoSiteService.listOwned(USER_ID)).thenReturn(List.of(site(true, "SUCCESS")));

        // When
        Document page = render(get("/auto"));

        // Then
        assertThat(page.selectFirst("form.js-del")).isNotNull();
        assertThat(page.html()).contains("SitemapUI.confirm");
        assertThat(page.html()).doesNotContain("window.confirm");
    }

    @Test
    void shouldUseCustomIntervalSelectAndInlineValidationWhenFormRendered() throws Exception {
        // Given
        when(autoSiteService.listOwned(USER_ID)).thenReturn(List.of());

        // When
        Document page = render(get("/auto"));

        // Then
        assertThat(page.selectFirst("form#autoForm").hasAttr("novalidate")).isTrue();
        assertThat(page.select("select[name=intervalHours]")).isEmpty();
        assertThat(page.selectFirst("#intervalSel input[name=intervalHours]").attr("value")).isEqualTo("24");
        assertThat(page.select(".sel-menu [role=option]").eachText())
                .containsExactly("每 6 小时", "每 12 小时", "每天", "每 3 天", "每周");
        assertThat(page.selectFirst("#intervalSel .sel-label").text()).isEqualTo("每天");
        assertThat(page.selectFirst("#urlErr").hasAttr("hidden")).isTrue();
        assertThat(page.html()).contains("SitemapUI.urlGuard").contains("SitemapUI.selectMenu");
    }

    @Test
    void shouldSavePushSettingsAndRedirectToDetail() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));

        // When & Then
        mvc.perform(post("/auto/1/push/settings")
                        .param("enabled", "true")
                        .param("protocol", "SFTP")
                        .param("host", "sftp.example.com")
                        .param("port", "22")
                        .param("username", "deployer")
                        .param("authType", "PASSWORD")
                        .param("password", "s3cret")
                        .param("remoteDir", "/var/www")
                        .param("sitemapFileName", "sitemap.xml"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto/1"))
                .andExpect(flash().attribute("flash", "auto.push.flash.saved"));
        verify(pushConfigService).save(eq(1L), any(PushSettings.class));
    }

    @Test
    void shouldFlashErrorWhenPushSettingsRejected() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(pushConfigService.save(eq(1L), any(PushSettings.class)))
                .thenThrow(new IllegalArgumentException("端口必须在 1 到 65535 之间"));

        // When & Then
        mvc.perform(post("/auto/1/push/settings")
                        .param("protocol", "SFTP")
                        .param("host", "sftp.example.com")
                        .param("port", "0")
                        .param("username", "deployer"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", "端口必须在 1 到 65535 之间"));
    }

    @Test
    void shouldFlashOkWhenConnectionTestSucceeds() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(sitemapPushService.testConnection(1L)).thenReturn(PushOutcome.success("连接成功", 5));

        // When & Then
        mvc.perform(post("/auto/1/push/test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto/1"))
                .andExpect(flash().attribute("flash", "auto.push.flash.testOk"));
    }

    @Test
    void shouldFlashErrorWhenConnectionTestFails() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(sitemapPushService.testConnection(1L)).thenReturn(
                PushOutcome.failure(PushErrorCode.AUTH_FAILED, "认证失败（用户名或密码错误）", null, 3));

        // When & Then
        mvc.perform(post("/auto/1/push/test"))
                .andExpect(flash().attribute("flashError", "认证失败（用户名或密码错误）"));
    }

    @Test
    void shouldFlashOkWhenManualPushSucceeds() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(sitemapPushService.push(1L))
                .thenReturn(PushOutcome.success(3, "已上传 sitemap.xml（版本 3）", 42));

        // When & Then
        mvc.perform(post("/auto/1/push/run"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auto/1"))
                .andExpect(flash().attribute("flash", "auto.push.flash.pushed"));
    }

    @Test
    void shouldFlashErrorWithReasonWhenManualPushSkipped() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(sitemapPushService.push(1L)).thenReturn(PushOutcome.skipped("尚未配置推送"));

        // When & Then
        mvc.perform(post("/auto/1/push/run"))
                .andExpect(redirectedUrl("/auto/1"))
                .andExpect(flash().attribute("flashError", "尚未配置推送"));
    }

    @Test
    void shouldSaveSubmissionSettingsAndFlashWhenValid() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));

        // When & Then
        mvc.perform(post("/auto/1/submission/settings")
                        .param("baiduEnabled", "true")
                        .param("baiduSite", "https://example.com")
                        .param("baiduToken", "tok123456")
                        .param("gscEnabled", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flash", "auto.submission.flash.saved"));
        verify(pushConfigService).saveSubmission(eq(1L), argThat(settings ->
                settings.baiduEnabled() && "https://example.com".equals(settings.baiduSite())));
    }

    @Test
    void shouldFlashRawErrorWhenSubmissionValidationFails() throws Exception {
        // Given: 非 message key 的校验原始文本经 flashError 原样透出
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        doThrow(new IllegalArgumentException("百度站点必须是 https://example.com 形式（不含端口与路径）"))
                .when(pushConfigService).saveSubmission(anyLong(), any(SubmissionSettings.class));

        // When & Then
        mvc.perform(post("/auto/1/submission/settings")
                        .param("baiduEnabled", "true"))
                .andExpect(redirectedUrl("/auto/1"))
                .andExpect(flash().attribute("flashError",
                        "百度站点必须是 https://example.com 形式（不含端口与路径）"));
    }

    @Test
    void shouldFlashSubmittedWhenRunSubmissionSucceeds() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(submissionService.submit(1L)).thenReturn(SubmissionOutcome.success("已提交 2 个通道"));

        // When & Then
        mvc.perform(post("/auto/1/submission/run"))
                .andExpect(redirectedUrl("/auto/1"))
                .andExpect(flash().attribute("flash", "auto.submission.flash.submitted"));
    }

    @Test
    void shouldFlashErrorDetailWhenRunSubmissionFails() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(submissionService.submit(1L)).thenReturn(SubmissionOutcome.failure("百度：配额已用尽"));

        // When & Then
        mvc.perform(post("/auto/1/submission/run"))
                .andExpect(flash().attribute("flashError", "百度：配额已用尽"));
    }

    @Test
    void shouldSkipFlashErrorWhenRunSubmissionSkipped() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(submissionService.submit(1L)).thenReturn(SubmissionOutcome.skipped("尚未配置搜索引擎提交"));

        // When & Then
        mvc.perform(post("/auto/1/submission/run"))
                .andExpect(flash().attribute("flashError", "尚未配置搜索引擎提交"));
    }

    @Test
    void shouldReturn404WhenSubmissionRunOnForeignSite() throws Exception {
        // Given: requireOwned 语义——他人站点 findOwned 一律 empty
        when(autoSiteService.findOwned(999L, USER_ID)).thenReturn(Optional.empty());

        // When & Then
        mvc.perform(post("/auto/999/submission/run"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(submissionService);
    }

    @Test
    void shouldExposePushConfigAndLogsWhenRenderingDetail() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.versions(1L)).thenReturn(List.of());
        PushConfigView view = new PushConfigView(true, "SFTP", "sftp.example.com", 22, "deployer",
                "PASSWORD", "/var/www", "sitemap.xml", "SHA256:abc", true, "a".repeat(32),
                true, false, LocalDateTime.of(2026, 9, 18, 11, 0), "SUCCESS", null);
        when(pushConfigService.view(1L)).thenReturn(Optional.of(view));
        when(pushConfigService.logs(1L)).thenReturn(List.of());
        SubmissionView submission = new SubmissionView(true, SITE, true, false, null, null, false, null);
        when(pushConfigService.submissionView(1L)).thenReturn(Optional.of(submission));
        when(pushConfigService.submissionLogs(1L)).thenReturn(List.of());

        // When & Then: submission/submissionLogs 与 pushConfig/pushLogs 同路暴露给详情页
        mvc.perform(get("/auto/1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pushConfig", view))
                .andExpect(model().attribute("pushLogs", List.of()))
                .andExpect(model().attribute("submission", submission))
                .andExpect(model().attribute("submissionLogs", List.of()));
    }

    @Test
    void shouldRenderPushDefaultsWhenNoConfigSaved() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.versions(1L)).thenReturn(List.of());

        // When
        Document page = render(get("/auto/1"));

        // Then
        assertThat(page.selectFirst(".push-panel .state").text()).isEqualTo("未启用推送");
        Element form = page.selectFirst("form#pushForm");
        assertThat(form.attr("action")).isEqualTo("/auto/1/push/settings");
        assertThat(form.hasAttr("novalidate")).isTrue();
        assertThat(form.selectFirst("input[name=enabled]").hasAttr("checked")).isFalse();
        assertThat(form.selectFirst("#protoSel input[name=protocol]").attr("value")).isEqualTo("SFTP");
        assertThat(form.select("#protoSel .sel-menu [role=option]").eachText())
                .containsExactly("SFTP", "FTP", "FTPS");
        assertThat(form.selectFirst("#authSel input[name=authType]").attr("value")).isEqualTo("PASSWORD");
        assertThat(form.select("#authSel .sel-menu [role=option]").eachText())
                .containsExactly("密码", "私钥");
        assertThat(form.selectFirst("input[name=host]").attr("value")).isEmpty();
        assertThat(form.selectFirst("input[name=port]").attr("value")).isEqualTo("22");
        assertThat(form.selectFirst("input[name=username]").attr("value")).isEmpty();
        assertThat(form.selectFirst("input[name=sitemapFileName]").attr("value")).isEqualTo("sitemap.xml");
        assertThat(form.selectFirst("input[name=indexNowEnabled]").hasAttr("checked")).isFalse();
        assertThat(page.selectFirst(".push-empty").text()).contains("还没有推送记录");
        assertThat(page.select(".push-logs")).isEmpty();
    }

    @Test
    void shouldFillPushFormFromSavedConfig() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.versions(1L)).thenReturn(List.of());
        PushConfigView view = new PushConfigView(true, "SFTP", "sftp.example.com", 2222, "deployer",
                "PRIVATE_KEY", "/var/www/html", "sitemap-news.xml", "SHA256:abc123", true, "a".repeat(32),
                true, true, LocalDateTime.of(2026, 9, 18, 11, 0), "SUCCESS", null);
        when(pushConfigService.view(1L)).thenReturn(Optional.of(view));

        // When
        Document page = render(get("/auto/1"));

        // Then
        assertThat(page.selectFirst(".push-panel .state").text()).isEqualTo("推送已启用");
        Element form = page.selectFirst("form#pushForm");
        assertThat(form.selectFirst("input[name=enabled]").hasAttr("checked")).isTrue();
        assertThat(form.selectFirst("input[name=host]").attr("value")).isEqualTo("sftp.example.com");
        assertThat(form.selectFirst("input[name=port]").attr("value")).isEqualTo("2222");
        assertThat(form.selectFirst("input[name=username]").attr("value")).isEqualTo("deployer");
        assertThat(form.selectFirst("input[name=remoteDir]").attr("value")).isEqualTo("/var/www/html");
        assertThat(form.selectFirst("input[name=sitemapFileName]").attr("value")).isEqualTo("sitemap-news.xml");
        assertThat(form.selectFirst("input[name=indexNowEnabled]").hasAttr("checked")).isTrue();
        assertThat(form.selectFirst("input[name=indexNowKey]").attr("value")).isEqualTo("a".repeat(32));
        assertThat(form.selectFirst("#authSel .sel-label").text()).isEqualTo("私钥");
        assertThat(form.selectFirst("#authSel [data-value=PRIVATE_KEY]").attr("aria-selected")).isEqualTo("true");
        assertThat(page.selectFirst(".js-fingerprint").text()).contains("SHA256:abc123");
    }

    @Test
    void shouldNotExposeStoredCredentialsWhenRenderingPushForm() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.versions(1L)).thenReturn(List.of());
        PushConfigView view = new PushConfigView(true, "SFTP", "sftp.example.com", 22, "deployer",
                "PASSWORD", "", "sitemap.xml", null, false, null, true, true, null, null, null);
        when(pushConfigService.view(1L)).thenReturn(Optional.of(view));

        // When
        Document page = render(get("/auto/1"));

        // Then
        Element form = page.selectFirst("form#pushForm");
        assertThat(form.selectFirst("input[name=password]").attr("type")).isEqualTo("password");
        assertThat(form.selectFirst("input[name=password]").attr("value")).isEmpty();
        assertThat(form.selectFirst("input[name=password]").attr("placeholder")).contains("留空则保持不变");
        assertThat(form.selectFirst("textarea[name=privateKey]").text()).isEmpty();
        assertThat(form.selectFirst("textarea[name=privateKey]").hasAttr("placeholder")).isTrue();
        assertThat(page.selectFirst(".js-fingerprint")).isNull();
    }

    @Test
    void shouldUseSystemControlsInPushForm() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.versions(1L)).thenReturn(List.of());

        // When
        Document page = render(get("/auto/1"));

        // Then
        Element panel = page.selectFirst(".push-panel");
        assertThat(panel.select("select")).isEmpty();
        assertThat(panel.select(".field-error")).hasSizeGreaterThanOrEqualTo(6);
        assertThat(panel.select(".field-error[hidden]")).hasSize(panel.select(".field-error").size());
        assertThat(page.selectFirst("form#pushForm").hasAttr("novalidate")).isTrue();
        assertThat(page.html()).contains("SitemapUI.formGuard").contains("SitemapUI.selectMenu");
        assertThat(page.html()).doesNotContain("window.confirm");
        assertThat(page.selectFirst("form.js-push-test").attr("action")).isEqualTo("/auto/1/push/test");
        Element runForm = page.selectFirst("form.js-push-now");
        assertThat(runForm.attr("action")).isEqualTo("/auto/1/push/run");
        assertThat(runForm.selectFirst("button").text()).isEqualTo("立即推送");
    }

    @Test
    void shouldRenderPushLogsWhenLogsExist() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.versions(1L)).thenReturn(List.of());
        PushLog ok = pushLog("SUCCESS", null, "已上传 sitemap.xml（版本 3）", "SUCCESS", 128);
        ok.setVersionNumber(3);
        PushLog bad = pushLog("FAILED", "UPLOAD_FAILED", "连接超时", null, 4200);
        when(pushConfigService.logs(1L)).thenReturn(List.of(ok, bad));

        // When
        Document page = render(get("/auto/1"));

        // Then
        assertThat(page.select(".push-logs tbody tr")).hasSize(2);
        Element okRow = page.select(".push-logs tbody tr").get(0);
        assertThat(okRow.selectFirst(".ld-time").text()).isEqualTo("09-18 10:00");
        assertThat(okRow.selectFirst(".ld-state").text()).isEqualTo("成功");
        assertThat(okRow.selectFirst(".ld-state").hasClass("state-success")).isTrue();
        assertThat(okRow.selectFirst(".ld-version").text()).isEqualTo("v3");
        assertThat(okRow.selectFirst(".ld-proto").text()).isEqualTo("SFTP");
        assertThat(okRow.selectFirst(".ld-index").text()).isEqualTo("已提交");
        assertThat(okRow.selectFirst(".ld-dur").text()).isEqualTo("128 ms");
        assertThat(okRow.selectFirst(".ld-detail").text()).isEqualTo("已上传 sitemap.xml（版本 3）");
        Element badRow = page.select(".push-logs tbody tr").get(1);
        assertThat(badRow.selectFirst(".ld-state").text()).isEqualTo("失败");
        assertThat(badRow.selectFirst(".ld-state").hasClass("state-failed")).isTrue();
        assertThat(badRow.selectFirst(".ld-version").text()).isEqualTo("—");
        assertThat(badRow.selectFirst(".ld-index").text()).isEqualTo("—");
        assertThat(page.select(".push-empty")).isEmpty();
    }

    @Test
    void shouldRenderPushFlashWhenFollowingRedirect() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.versions(1L)).thenReturn(List.of());
        when(sitemapPushService.push(1L))
                .thenReturn(PushOutcome.success(3, "已上传 sitemap.xml（版本 3）", 42));
        MockHttpSession session = new MockHttpSession();

        // When
        mvc.perform(post("/auto/1/push/run").session(session));
        Document page = render(get("/auto/1").session(session));

        // Then
        assertThat(page.selectFirst(".panel.flash.ok").text()).contains("站点地图已推送");
    }

    @Test
    void shouldRenderPushErrorFlashWhenFollowingRedirect() throws Exception {
        // Given
        when(autoSiteService.findOwned(1L, USER_ID)).thenReturn(Optional.of(site(true, "SUCCESS")));
        when(autoSiteService.versions(1L)).thenReturn(List.of());
        when(sitemapPushService.push(1L))
                .thenReturn(PushOutcome.failure(PushErrorCode.UPLOAD_FAILED, "连接超时", 3, 4200));
        MockHttpSession session = new MockHttpSession();

        // When
        mvc.perform(post("/auto/1/push/run").session(session));
        Document page = render(get("/auto/1").session(session));

        // Then
        assertThat(page.selectFirst(".panel.flash:not(.ok)").text()).contains("连接超时");
    }

    private Document render(MockHttpServletRequestBuilder request) throws Exception {
        return render(mvc, request);
    }

    private Document render(MockMvc target, MockHttpServletRequestBuilder request) throws Exception {
        String html = target.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Jsoup.parse(html);
    }

    /**
     * 让 /auto 添加表单以指定异常失败，便于走「提交 → 重定向 → 回显 flashError」全链路
     */
    private void givenCreateRejects(RuntimeException failure) {
        when(autoSiteService.create(eq(USER_ID), anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenThrow(failure);
        when(autoSiteService.listOwned(USER_ID)).thenReturn(List.of());
    }

    private Document renderFlash(Locale locale) throws Exception {
        MockMvc target = Locale.ENGLISH.equals(locale) ? mvcEn : mvc;
        MockHttpSession session = new MockHttpSession();
        target.perform(post("/auto").session(session).param("url", SITE));
        return render(target, get("/auto").session(session));
    }

    private AutoSite site(boolean enabled, String lastStatus) {
        AutoSite site = new AutoSite();
        site.setId(1L);
        site.setUserId(USER_ID);
        site.setUrl(SITE);
        site.setIncludeImages(true);
        site.setIncludeVideos(false);
        site.setIncludeNews(false);
        site.setIntervalHours(24);
        site.setEnabled(enabled);
        site.setNextRunAt(LocalDateTime.of(2026, 9, 19, 10, 0));
        site.setLastRunAt(LocalDateTime.of(2026, 9, 18, 10, 0));
        site.setLastStatus(lastStatus);
        site.setCreatedAt(LocalDateTime.of(2026, 9, 17, 10, 0));
        site.setUpdatedAt(LocalDateTime.of(2026, 9, 18, 10, 0));
        return site;
    }

    private AutoSiteVersion version(int number, int urlCount) {
        AutoSiteVersion version = new AutoSiteVersion();
        version.setId((long) number);
        version.setSiteId(1L);
        version.setVersionNumber(number);
        version.setTaskId("auto-1-task" + number);
        version.setUrlCount(urlCount);
        version.setSitemapXml(XML);
        version.setCreatedAt(LocalDateTime.of(2026, 9, 18, 10, 0));
        return version;
    }

    private PushLog pushLog(String status, String errorCode, String detail, String indexNowStatus, long durationMs) {
        PushLog log = new PushLog();
        log.setSiteId(1L);
        log.setProtocol("SFTP");
        log.setStatus(status);
        log.setErrorCode(errorCode);
        log.setDetail(detail);
        log.setIndexNowStatus(indexNowStatus);
        log.setDurationMs(durationMs);
        log.setCreatedAt(LocalDateTime.of(2026, 9, 18, 10, 0));
        return log;
    }
}
