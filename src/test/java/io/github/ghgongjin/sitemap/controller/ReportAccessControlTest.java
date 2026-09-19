package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import io.github.ghgongjin.sitemap.service.SeoAuditService;
import io.github.ghgongjin.sitemap.service.SeoReportService;
import io.github.ghgongjin.sitemap.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @ClassName ReportAccessControlTest
 * @Description SEO 报告用户隔离门禁：历史列表页游客可打开（空列表不泄漏数据）；
 *              报告详情/导出游客 302 到登录页且不泄漏下载头；他人报告一律 404；列表只见本人归属
 * @Author gj
 * @Date 2026/9/19
 * @Version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportAccessControlTest {

    private static final String SITE_URL = "https://report-seed.example.com";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SeoReportRepository reports;

    @Autowired
    private SeoReportService seoReportService;

    @Autowired
    private SeoAuditService seoAuditService;

    @Autowired
    private UserService userService;

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
    }

    private SeoReport seed(String taskId, Long userId) {
        SeoReport report = new SeoReport();
        report.setTaskId(taskId);
        report.setSiteUrl(SITE_URL);
        report.setScore(75);
        report.setPagesAudited(12);
        report.setBrokenLinks(1);
        report.setErrorCount(2);
        report.setWarningCount(3);
        report.setInfoCount(4);
        report.setIssuesJson("[]");
        report.setUserId(userId);
        report.setCreatedAt(LocalDateTime.of(2026, 9, 18, 20, 30, 15));
        return reports.saveAndFlush(report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/report/t-alice", "/report/t-alice/export"})
    void shouldRedirectGuestToLogin(String path) throws Exception {
        // Given: 一条归属 alice 的报告
        seed("t-alice", alice.getId());

        // When
        var response = mvc.perform(get(path)).andExpect(status().is3xxRedirection())
                .andReturn().getResponse();

        // Then: 游客被门禁送到登录页，且没有泄漏任何下载内容
        assertThat(response.getHeader("Location")).startsWith("http://localhost/login");
        assertThat(response.getHeader("Content-Disposition")).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void shouldOpenReportsPageForGuestWithoutLeakingData() throws Exception {
        // Given: 归属 alice 与他人、历史无归属各一份
        seed("t-alice", alice.getId());
        seed("t-bob", bob.getId());
        seed("t-legacy", null);

        // When: 游客直接打开历史列表页
        String html = mvc.perform(get("/reports"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // Then: 页面可见但不出现任何用户的报告行；游客空态给出登录/注册引导
        assertThat(html).doesNotContain("t-alice", "t-bob", "t-legacy");
        assertThat(html).contains("guest-hint");
    }

    @Test
    void shouldHideOtherUsersReport() throws Exception {
        // Given: 报告归属 bob
        seed("t-bob", bob.getId());

        // When / Then: alice 访问详情与导出均为 404（不区分不存在与无权限），且无下载头
        mvc.perform(get("/report/t-bob").with(user(aliceDetails)))
                .andExpect(status().isNotFound());
        var export = mvc.perform(get("/report/t-bob/export").with(user(aliceDetails)))
                .andExpect(status().isNotFound()).andReturn().getResponse();
        assertThat(export.getHeader("Content-Disposition")).isNull();
    }

    @Test
    void shouldListOnlyOwnReports() throws Exception {
        // Given: 本人、他人、历史无归属（user_id 为空）各一份
        seed("t-alice", alice.getId());
        seed("t-bob", bob.getId());
        seed("t-legacy", null);

        // When
        String html = mvc.perform(get("/reports").with(user(aliceDetails)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // Then
        assertThat(html).contains("t-alice").doesNotContain("t-bob", "t-legacy", "guest-hint");
    }

    @Test
    void shouldBindUserIdWhenLoggedInUserCrawls() throws Exception {
        // Given: 登录用户完成一次爬取，报告按本人归属落库
        seoAuditService.beginAudit("t-bind");
        seoAuditService.recordBroken("t-bind", SITE_URL + "/missing", 404);

        // When
        SeoReport saved = seoReportService.save("t-bind", SITE_URL, alice.getId());

        // Then
        assertThat(saved.getUserId()).isEqualTo(alice.getId());
        assertThat(reports.findByTaskIdAndUserId("t-bind", alice.getId())).isPresent();
        mvc.perform(get("/report/t-bind").with(user(aliceDetails))).andExpect(status().isOk());
        mvc.perform(get("/report/t-bind/export").with(user(bobDetails))).andExpect(status().isNotFound());
    }

    @Test
    void shouldLeaveGuestCrawlReportInvisibleToEveryone() throws Exception {
        // Given: 游客爬取的报告无归属
        seoAuditService.beginAudit("t-guest");
        seoAuditService.recordBroken("t-guest", SITE_URL + "/missing", 404);

        // When
        SeoReport saved = seoReportService.save("t-guest", SITE_URL, null);

        // Then: 数据保留但对任何登录用户不可见（详情 404、列表不出现）
        assertThat(saved.getUserId()).isNull();
        mvc.perform(get("/report/t-guest").with(user(aliceDetails))).andExpect(status().isNotFound());
        String html = mvc.perform(get("/reports").with(user(aliceDetails)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("t-guest");
    }
}
