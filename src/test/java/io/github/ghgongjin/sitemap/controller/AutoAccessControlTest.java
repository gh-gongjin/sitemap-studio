package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.AutoSiteUpdater;
import io.github.ghgongjin.sitemap.service.AutoSiteValidationException;
import io.github.ghgongjin.sitemap.service.CrawlUrlPolicy;
import io.github.ghgongjin.sitemap.service.EnhancedSitemapGeneratorService;
import io.github.ghgongjin.sitemap.service.SeoAuditService;
import io.github.ghgongjin.sitemap.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @ClassName AutoAccessControlTest
 * @Description 自动更新门禁与用户隔离：列表页游客可打开（空列表不泄漏数据）；详情页/下载/写接口先登录，他人站点读/写/推送一律 404
 *              且数据不被改动；同一 URL 允许归属不同用户；存量无归属站点（user_id 为空）对所有人不可见；
 *              自动更新产出的 SEO 报告归属站点主人
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AutoAccessControlTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AutoSiteService autoSiteService;

    @Autowired
    private AutoSiteRepository autoSites;

    @Autowired
    private PushConfigRepository pushConfigs;

    @Autowired
    private SeoAuditService seoAuditService;

    @Autowired
    private SeoReportRepository seoReports;

    @Autowired
    private AutoSiteUpdater autoSiteUpdater;

    @Autowired
    private UserService userService;

    /**
     * 只替换抓取环节，让自动更新主流程（记录版本 → 落 SEO 报告 → 推送）真实执行
     */
    @MockitoBean
    private EnhancedSitemapGeneratorService generator;

    private UserAccount alice;
    private UserAccount bob;
    private UserAccountDetails aliceDetails;
    private UserAccountDetails bobDetails;

    @BeforeEach
    void setUp() {
        alice = userService.register("alice", "Passw0rd1");
        bob = userService.register("bob", "Passw0rd1");
        // 以真实注册用户（register 返回的 id）构造认证主体，禁止硬编码用户 id
        aliceDetails = new UserAccountDetails(
                alice.getId(), alice.getUsername(), alice.getPasswordHash());
        bobDetails = new UserAccountDetails(
                bob.getId(), bob.getUsername(), bob.getPasswordHash());
        // 本用例只考察归属与门禁，不打真实 DNS：换成放行一切的策略桩，@AfterEach 复原
        autoSiteService.setCrawlUrlPolicy(new AnyHostPolicy());
    }

    @AfterEach
    void tearDown() {
        autoSiteService.setCrawlUrlPolicy(new CrawlUrlPolicy());
    }

    private AutoSite createSite(Long userId, String url) {
        return autoSiteService.create(userId, url, false, false, false, 24);
    }

    /**
     * 直接经仓储落一条存量数据（user_id 为空），模拟升级前的老站点
     */
    private AutoSite seedLegacySite(String url) {
        LocalDateTime now = LocalDateTime.now();
        AutoSite site = new AutoSite();
        site.setUrl(url);
        site.setIncludeImages(false);
        site.setIncludeVideos(false);
        site.setIncludeNews(false);
        site.setIntervalHours(24);
        site.setEnabled(true);
        site.setNextRunAt(now);
        site.setLastStatus(AutoSiteService.STATUS_PENDING);
        site.setCreatedAt(now);
        site.setUpdatedAt(now);
        return autoSites.saveAndFlush(site);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auto/1", "/auto/1/download"})
    void shouldRedirectGuestFromPages(String path) throws Exception {
        // When / Then: 游客访问详情页与下载接口都先被送到登录页，页面内容不外泄
        var response = mvc.perform(get(path)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("http://*/login*")).andReturn().getResponse();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void shouldOpenAutoPageForGuestWithoutLeakingData() throws Exception {
        // Given: alice 名下与存量无归属各一个站点
        createSite(alice.getId(), "https://alice-auto.example.com");
        seedLegacySite("https://legacy-auto.example.com");

        // When: 游客直接打开自动更新管理页
        String html = mvc.perform(get("/auto"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // Then: 页面可见但不出现任何用户的站点行；游客空态给出登录/注册引导
        assertThat(html).doesNotContain("alice-auto.example.com", "legacy-auto.example.com");
        assertThat(html).contains("guest-hint");
    }

    @Test
    void shouldRedirectGuestFromCreate() throws Exception {
        // When
        mvc.perform(post("/auto").with(csrf())
                        .param("url", "https://guest.example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("http://*/login*"));

        // Then: 游客请求没有落库
        assertThat(autoSiteService.listOwned(alice.getId())).isEmpty();
        assertThat(autoSiteService.listOwned(bob.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/auto/1/run", "/auto/1/toggle", "/auto/1/delete",
            "/auto/1/push/settings", "/auto/1/push/test", "/auto/1/push/run", "/auto/1", "/auto/1/download"
    })
    void shouldRedirectGuestFromEveryAutoSiteOperation(String path) throws Exception {
        // Given / When: 游客打全部写操作与详情/下载端点（刻意不传任何表单参数、站点也不存在）
        var response = mvc.perform(post(path).with(csrf())).andReturn().getResponse();

        // Then: 门禁在安全层即 302 到登录页，控制器与参数校验都不会被触达，响应体无内容泄漏
        assertThat(response.getStatus()).isBetween(300, 399);
        assertThat(response.getHeader("Location")).startsWith("http://localhost/login");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEmpty();
        // And: 没有任何站点数据被创建
        assertThat(autoSites.count()).isZero();
    }

    @Test
    void shouldReturn404WhenOperatingOtherUsersSite() throws Exception {
        // Given: 站点归属 bob
        Long bobSiteId = createSite(bob.getId(), "https://b.example.com").getId();

        // When / Then: alice 读详情、下载、执行、启停、删除、推送全部 404（不区分不存在与无权限）
        mvc.perform(get("/auto/" + bobSiteId).with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/auto/" + bobSiteId + "/download").with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auto/" + bobSiteId + "/run").with(csrf()).with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auto/" + bobSiteId + "/toggle").with(csrf()).with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auto/" + bobSiteId + "/push/test").with(csrf()).with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auto/" + bobSiteId + "/push/run").with(csrf()).with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auto/" + bobSiteId + "/push/settings").with(csrf()).with(user(aliceDetails))
                        .param("enabled", "true").param("protocol", "SFTP")
                        .param("host", "sftp.example.com").param("port", "22")
                        .param("username", "deployer").param("password", "s3cret"))
                .andExpect(status().isNotFound());

        // Then: bob 的站点数据未被误改误删，也没有被写入他人推送配置
        AutoSite stillThere = autoSiteService.find(bobSiteId).orElseThrow();
        assertThat(stillThere.isEnabled()).isTrue();
        assertThat(stillThere.getUserId()).isEqualTo(bob.getId());
        assertThat(pushConfigs.count()).isZero();

        // And: 删除越权同样 404 且站点仍在库
        mvc.perform(post("/auto/" + bobSiteId + "/delete").with(csrf()).with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        assertThat(autoSiteService.find(bobSiteId)).isPresent();
    }

    @Test
    void shouldAllowSameUrlForDifferentUsers() {
        // Given / When: 同一 URL 分属两个用户
        AutoSite aliceSite = createSite(alice.getId(), "https://dup.example.com");
        AutoSite bobSite = createSite(bob.getId(), "https://dup.example.com");

        // Then: 判重按用户维度，联合唯一不冲突
        assertThat(bobSite.getId()).isNotNull();
        assertThat(aliceSite.getUserId()).isEqualTo(alice.getId());
        assertThat(bobSite.getUserId()).isEqualTo(bob.getId());

        // And: 同一用户重复添加仍被拒绝（提示以 message key 抛出，模板侧本地化）
        assertThatThrownBy(() -> createSite(alice.getId(), "https://dup.example.com"))
                .isInstanceOf(AutoSiteValidationException.class)
                .hasMessage("auto.error.duplicate");
    }

    @Test
    void shouldListOnlyOwnSites() throws Exception {
        // Given: 本人、他人、存量无归属各一个站点
        createSite(alice.getId(), "https://a1.example.com");
        createSite(bob.getId(), "https://b1.example.com");
        seedLegacySite("https://legacy.example.com");

        // When
        String html = mvc.perform(get("/auto").with(user(aliceDetails)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // Then: 列表只见本人站点
        assertThat(html).contains("a1.example.com").doesNotContain("b1.example.com", "legacy.example.com");
        assertThat(autoSiteService.listOwned(alice.getId()))
                .extracting(AutoSite::getUrl).containsExactly("https://a1.example.com");
        assertThat(autoSiteService.listOwned(null)).isEmpty();
    }

    @Test
    void shouldHideOtherUsersLegacyAndMissingSiteOnDetailAndDownload() throws Exception {
        // Given: 存量无归属站点
        Long legacyId = seedLegacySite("https://legacy-detail.example.com").getId();

        // When / Then: 详情与下载对登录用户都是 404，无归属数据不可见
        mvc.perform(get("/auto/" + legacyId).with(user(aliceDetails))).andExpect(status().isNotFound());
        mvc.perform(get("/auto/" + legacyId + "/download").with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auto/" + legacyId + "/run").with(csrf()).with(user(aliceDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldBindSiteOwnerToReportGeneratedByUpdater() throws Exception {
        // Given: alice 的站点；爬虫只登记一条审计结果，避免真实抓取
        AutoSite site = createSite(alice.getId(), "https://report-owner.example.com");
        when(generator.generateSitemapWithProgress(anyString(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyString())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(4);
            seoAuditService.beginAudit(taskId);
            seoAuditService.recordBroken(taskId, site.getUrl() + "/missing", 404);
            return "<urlset></urlset>";
        });

        // When: 调度线程执行一次更新（此时没有任何请求上下文）
        assertThat(autoSiteUpdater.update(site)).isTrue();

        ArgumentCaptor<String> taskIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(generator).generateSitemapWithProgress(eq(site.getUrl()), anyBoolean(), anyBoolean(),
                anyBoolean(), taskIdCaptor.capture());
        String taskId = taskIdCaptor.getValue();

        // Then: 报告归属站点主人——本人可读、他人 404
        assertThat(seoReports.findByTaskIdAndUserId(taskId, alice.getId())).isPresent();
        assertThat(seoReports.findByTaskIdAndUserId(taskId, bob.getId())).isEmpty();
        mvc.perform(get("/report/" + taskId).with(user(aliceDetails))).andExpect(status().isOk());
        mvc.perform(get("/report/" + taskId).with(user(bobDetails))).andExpect(status().isNotFound());
    }

    /**
     * 放行任意主机的策略桩：只保留 URL 解析，不做真实 DNS 查询
     */
    private static class AnyHostPolicy extends CrawlUrlPolicy {

        @Override
        public URI validate(String url) {
            return URI.create(url.trim());
        }

        @Override
        public URI validate(String url, String scopeBase) {
            return validate(url);
        }
    }
}
