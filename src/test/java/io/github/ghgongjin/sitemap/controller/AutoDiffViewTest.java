package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.CrawlUrlPolicy;
import io.github.ghgongjin.sitemap.service.EnhancedSitemapGeneratorService;
import io.github.ghgongjin.sitemap.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @ClassName AutoDiffViewTest
 * @Description 读侧：diff 详情页三组明细/首版/无法比较占位、diff.csv 内容与转义、他人站点与游客 404
 * @Author gj
 * @Date 2026-09-19
 * @Version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AutoDiffViewTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AutoSiteService autoSiteService;

    @Autowired
    private AutoSiteRepository autoSites;

    @Autowired
    private UserService userService;

    @MockitoBean
    private EnhancedSitemapGeneratorService enhancedService;

    private Long ownerId;
    private UserAccountDetails ownerDetails;

    @BeforeEach
    void loginOwnerAndSeedSite() {
        UserAccount owner = userService.register("diffowner", "Passw0rd1");
        ownerId = owner.getId();
        ownerDetails = new UserAccountDetails(owner.getId(), owner.getUsername(), owner.getPasswordHash());
        // 本用例只考察读侧渲染与导出，不打真实 DNS：换成放行一切的策略桩，@AfterEach 复原（与 AutoAccessControlTest 同法）
        autoSiteService.setCrawlUrlPolicy(new AnyHostPolicy());
    }

    @AfterEach
    void tearDown() {
        autoSiteService.setCrawlUrlPolicy(new CrawlUrlPolicy());
    }

    private Long seedTwoVersions() {
        // 真实走 recordSuccess 两次，diff 计数与版本行一致
        io.github.ghgongjin.sitemap.entity.AutoSite site =
                autoSiteService.create(ownerId, "https://diff-test.example.invalid", false, false, false, 24);
        autoSiteService.recordSuccess(site.getId(), "t1",
                "<urlset><url><loc>https://diff-test.example.invalid/a</loc><lastmod>2026-01-01</lastmod>"
                        + "<url><loc>https://diff-test.example.invalid/gone</loc><lastmod>2026-01-01</lastmod></url></urlset>", 2);
        autoSiteService.recordSuccess(site.getId(), "t2",
                "<urlset><url><loc>https://diff-test.example.invalid/a</loc><lastmod>2026-02-02</lastmod>"
                        + "<url><loc>https://diff-test.example.invalid/new</loc><lastmod>2026-02-02</lastmod>"
                        + // 带逗号与公式前缀的 URL 验证 CSV 转义
                        "<url><loc>=https://diff-test.example.invalid/evil,one</loc><lastmod>2026-02-02</lastmod></url></urlset>", 3);
        return site.getId();
    }

    /** 播种一对 150 新增 / 120 删除 / 0 改动 的版本：用于验证「页面每组截断 100 + 溢出求和 + CSV 全量」 */
    private Long seedLimitTwoVersions() {
        io.github.ghgongjin.sitemap.entity.AutoSite site =
                autoSiteService.create(ownerId, "https://diff-limit.example.invalid", false, false, false, 24);
        String base = "https://diff-limit.example.invalid/";
        StringBuilder v1 = new StringBuilder("<urlset>");
        for (int i = 0; i < 120; i++) {
            v1.append(urlEntry(base + "removed-" + pad3(i)));
        }
        for (int i = 0; i < 10; i++) {
            v1.append(urlEntry(base + "shared-" + pad3(i)));
        }
        StringBuilder v2 = new StringBuilder("<urlset>");
        for (int i = 0; i < 150; i++) {
            v2.append(urlEntry(base + "added-" + pad3(i)));
        }
        for (int i = 0; i < 10; i++) {
            v2.append(urlEntry(base + "shared-" + pad3(i)));
        }
        autoSiteService.recordSuccess(site.getId(), "t1", v1.append("</urlset>").toString(), 130);
        autoSiteService.recordSuccess(site.getId(), "t2", v2.append("</urlset>").toString(), 160);
        return site.getId();
    }

    private static String urlEntry(String loc) {
        return "<url><loc>" + loc + "</loc><lastmod>2026-01-01</lastmod></url>";
    }

    private static String pad3(int i) {
        return String.format("%03d", i);
    }

    @Test
    void shouldRenderDiffPageWithThreeGroupsForSecondVersion() throws Exception {
        Long id = seedTwoVersions();
        mvc.perform(get("/auto/{id}/versions/2/diff", id).with(user(ownerDetails)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("diff-test.example.invalid/new")))
                .andExpect(content().string(containsString("diff-test.example.invalid/gone")))
                .andExpect(content().string(containsString("+2")));
    }

    @Test
    void shouldShowFirstSnapshotLabelOnVersionOne() throws Exception {
        Long id = seedTwoVersions();
        mvc.perform(get("/auto/{id}/versions/1/diff", id).with(user(ownerDetails)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("首个快照")));
    }

    @Test
    void shouldExportDiffCsvWithEscapingAndBom() throws Exception {
        Long id = seedTwoVersions();
        String body = mvc.perform(get("/auto/{id}/versions/2/diff.csv", id).with(user(ownerDetails)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF");
        assertThat(body).contains("\"type\",\"url\"");
        // brief 原句在 new 行断言了多余的 ' 前缀（该 URL 无公式前缀），与逐字迁移的 Csv 转义规则矛盾，按现状修正
        assertThat(body).contains("\"added\",\"https://diff-test.example.invalid/new\"");
        // =前缀公式注入被强制转文本，逗号字段被引号包裹
        assertThat(body).contains("\"added\",\"'=https://diff-test.example.invalid/evil,one\"");
        assertThat(body).contains("\"removed\",\"https://diff-test.example.invalid/gone\"");
        assertThat(body).contains("\"changed\",\"https://diff-test.example.invalid/a\"");
    }

    @Test
    void shouldReturn404ForOtherUsersAndGuestsAndMissingVersion() throws Exception {
        Long id = seedTwoVersions();
        UserAccount other = userService.register("diffother", "Passw0rd1");
        UserAccountDetails otherDetails =
                new UserAccountDetails(other.getId(), other.getUsername(), other.getPasswordHash());
        mvc.perform(get("/auto/{id}/versions/2/diff", id).with(user(otherDetails)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/auto/{id}/versions/2/diff.csv", id).with(user(otherDetails)))
                .andExpect(status().isNotFound());
        // 与既有 /auto/{id} 游客口径一致（AutoAccessControlTest：302 → 登录页，响应体为空）
        mvc.perform(get("/auto/{id}/versions/2/diff", id))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("http://*/login*"));
        mvc.perform(get("/auto/{id}/versions/99/diff", id).with(user(ownerDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldShowChangeBadgesInVersionHistory() throws Exception {
        Long id = seedTwoVersions();
        mvc.perform(get("/auto/{id}", id).with(user(ownerDetails)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("+2 −1 ~1")))
                .andExpect(content().string(containsString("首个快照")));
    }

    @Test
    void shouldExportFullCsvAndSumOverflowWhenGroupsExceedPreviewLimit() throws Exception {
        // Given 150 新增 / 120 删除 / 0 改动 的版本对
        Long id = seedLimitTwoVersions();

        // When 渲染详情页
        String html = mvc.perform(get("/auto/{id}/versions/2/diff", id).with(user(ownerDetails)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Then 计数徽标取全量；每组明细截断至 100 条；溢出提示取三组之和 (150-100)+(120-100)+0=70
        assertThat(html).contains("+150").contains("−120");
        assertThat(html).contains("/added-099").doesNotContain("/added-100");
        assertThat(html).contains("/removed-099").doesNotContain("/removed-100");
        assertThat(html).contains("另有 70 条未显示");

        // When 下载完整 diff CSV
        String csv = mvc.perform(get("/auto/{id}/versions/2/diff.csv", id).with(user(ownerDetails)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Then CSV 不受 100 截断，270 条数据行全量导出（另含 BOM + 表头）
        assertThat(csv).startsWith("\uFEFF").contains("\"type\",\"url\"");
        assertThat(csv.lines().filter(l -> l.startsWith("\"added\"")).count()).isEqualTo(150);
        assertThat(csv.lines().filter(l -> l.startsWith("\"removed\"")).count()).isEqualTo(120);
    }

    /** 放行任意主机的抓取策略桩，仅用于建站校验，避免测试依赖 DNS */
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
