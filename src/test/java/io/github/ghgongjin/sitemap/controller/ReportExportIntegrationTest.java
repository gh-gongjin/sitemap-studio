package io.github.ghgongjin.sitemap.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.repository.SeoReportRepository;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import io.github.ghgongjin.sitemap.service.SeoAuditService;
import io.github.ghgongjin.sitemap.service.UserService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportExportIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SeoReportRepository repository;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 报告归属用户：真实注册后取其 id 构造登录主体，禁止硬编码用户 id
     */
    private UserAccountDetails tester;

    @BeforeEach
    void setUp() {
        var account = userService.register("tester", "Passw0rd1");
        tester = new UserAccountDetails(account.getId(), account.getUsername(), account.getPasswordHash());
    }

    @Test
    void shouldDownloadPersistedReportWithoutLiveCrawlTask() throws Exception {
        SeoReport report = saveReport("[]");

        MockHttpServletResponse response = download("zh-CN");

        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        ContentDisposition disposition = ContentDisposition.parse(response.getHeader("Content-Disposition"));
        assertThat(disposition.getType()).isEqualTo("attachment");
        assertThat(disposition.getFilename()).isEqualTo("seo-report-export-test.csv");
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
        assertThat(response.getContentAsByteArray()).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains(
                "\"网站\",\"" + report.getSiteUrl() + "\",\"\",\"\"\r\n",
                "\"任务\",\"export-test\"", "\"生成时间\",\"2026-09-18 20:30:15\"",
                "\"评分\",\"75\"", "\"已审核页面\",\"12\"", "\"断链\",\"1\"", "\"跳过\",\"5\"",
                "\"错误\",\"2\"", "\"警告\",\"3\"", "\"提示\",\"4\"");
    }

    @Test
    void shouldExportLocalizedIssueDetailsInEnglish() throws Exception {
        saveReport(issues(
                new SeoAuditService.Issue("broken-link", SeoAuditService.Severity.ERROR,
                        "https://example.com/missing", "404"),
                new SeoAuditService.Issue("slow-page", SeoAuditService.Severity.WARNING,
                        "https://example.com/slow", "2500"),
                new SeoAuditService.Issue("noindex", SeoAuditService.Severity.INFO,
                        "https://example.com/private", null)));

        String csv = download("en").getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv).contains("\"Site\",\"https://example.com/中文\"",
                "\"Severity\",\"Rule\",\"URL\",\"Detail\"\r\n",
                "\"Error\",\"Broken link\",\"https://example.com/missing\",\"404\"\r\n",
                "\"Warning\",\"Slow response\",\"https://example.com/slow\",\"2500 ms\"\r\n",
                "\"Notice\",\"Marked noindex\",\"https://example.com/private\",\"\"\r\n");
        assertThat(csv).doesNotContain("report.rule.", "report.sev.");
    }

    @Test
    void shouldEscapeCommasQuotesAndLineBreaksWithoutLosingChinese() throws Exception {
        saveReport(issues(new SeoAuditService.Issue("long-title", SeoAuditService.Severity.WARNING,
                "https://example.com/a,b", "标题,\"测试\"\r\n第二行")));

        String csv = download("zh-CN").getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv).contains("\"警告\",\"title 过长\",\"https://example.com/a,b\",\"标题,\"\"测试\"\"\r\n第二行\"\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+SUM(1,2)", "-1+1", "@SUM(1,2)", "\t=1+1", "\r=1+1", "\n=1+1", "  =1+1"})
    void shouldNeutralizeSpreadsheetFormulasInUntrustedCells(String value) throws Exception {
        SeoReport report = saveReport(issues(new SeoAuditService.Issue(value,
                SeoAuditService.Severity.WARNING, value, value)));
        report.setSiteUrl(value);
        repository.flush();

        String csv = download("en").getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv).contains("\"Site\",\"'" + value + "\"");
        assertThat(csv).contains("\"Warning\",\"'" + value + "\",\"'" + value + "\",\"'" + value + "\"\r\n");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"[]", " "})
    void shouldKeepSummaryAndIssueHeaderWhenReportHasNoIssues(String json) throws Exception {
        saveReport(json);

        String csv = download("en").getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv).contains("\"Score\",\"75\"")
                .endsWith("\"Severity\",\"Rule\",\"URL\",\"Detail\"\r\n");
        assertThat(csv).doesNotContain("null", "only the first 500");
    }

    @ParameterizedTest
    @CsvSource({"zh-CN,问题清单超过上限", "en,only the first 500"})
    void shouldDiscloseTruncationInsteadOfImplyingCompleteExport(String language, String warning) throws Exception {
        SeoReport report = saveReport("[]");
        report.setTruncated(true);
        repository.flush();

        String csv = download(language).getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv).contains(warning);
    }

    @Test
    void shouldRejectMissingReportWithoutDownloadHeaders() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/report/not-found/export").with(user(tester)))
                .andExpect(status().isNotFound()).andReturn().getResponse();

        assertThat(response.getHeader("Content-Disposition")).isNull();
    }

    @Test
    void shouldRejectLegacyReportWithoutOwner() throws Exception {
        // Given: 历史存量报告没有归属（user_id 为空），对任何登录用户都不可见
        SeoReport legacy = saveReport("[]");
        legacy.setUserId(null);
        repository.flush();

        MockHttpServletResponse response = mvc.perform(get("/report/export-test/export").with(user(tester)))
                .andExpect(status().isNotFound()).andReturn().getResponse();

        assertThat(response.getHeader("Content-Disposition")).isNull();
    }

    @Test
    void shouldRedirectGuestToLoginWithoutLeakingExport() throws Exception {
        saveReport("[]");

        MockHttpServletResponse response = mvc.perform(get("/report/export-test/export"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();

        assertThat(response.getHeader("Location")).startsWith("http://localhost/login");
        assertThat(response.getHeader("Content-Disposition")).isNull();
    }

    @Test
    void shouldFailExportRatherThanSilentlyDropCorruptedIssues() {
        saveReport("not-json");

        assertThatThrownBy(() -> mvc.perform(get("/report/export-test/export").with(user(tester))))
                .isInstanceOf(JsonProcessingException.class);
    }

    @ParameterizedTest
    @CsvSource({"/report/export-test,zh-CN,导出 CSV", "/reports,zh-CN,导出 CSV",
            "/report/export-test,en,Export CSV", "/reports,en,Export CSV"})
    void shouldRenderDownloadEntryInCurrentLanguage(String page, String language, String label) throws Exception {
        saveReport("[]");

        String html = mvc.perform(get(page).param("lang", language).with(user(tester))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var links = Jsoup.parse(html).select("a[download][href^=/report/export-test/export]");

        assertThat(links).hasSize(3);
        assertThat(links.first().text()).isEqualTo(label);
        assertThat(links.first().attr("href")).contains("lang=" + language);
        assertThat(links.eachText()).contains(language.equals("en") ? "Export PDF" : "导出 PDF",
                language.equals("en") ? "Export Word" : "导出 Word");
        assertThat(html).doesNotContain("??report.export");
        for (var link : links) {
            mvc.perform(get(link.attr("href")).with(user(tester))).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @CsvSource({"pdf,application/pdf,%PDF-", "docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document,PK"})
    void shouldDownloadRealDocumentInsteadOfRenamedCsv(String format, String contentType, String magic) throws Exception {
        saveReport(issues(new SeoAuditService.Issue("broken-link", SeoAuditService.Severity.ERROR,
                "https://example.com/缺失", "404")));

        var response = mvc.perform(get("/report/export-test/export")
                        .param("format", format).param("lang", "zh-CN").with(user(tester)))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getContentType()).isEqualTo(contentType);
        assertThat(response.getContentAsByteArray()).startsWith(magic.getBytes(StandardCharsets.US_ASCII));
        assertThat(ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename())
                .isEqualTo("seo-report-export-test." + format);
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "exe", ""})
    void shouldRejectUnsupportedDocumentFormat(String format) throws Exception {
        saveReport("[]");

        mvc.perform(get("/report/export-test/export").param("format", format).with(user(tester)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldExportPdfWhenIssuesSpanMultiplePages() throws Exception {
        // Given: 200 条长 URL 问题必然触发换页——旧库真实报告（81 条）曾在 doc.save 处
        //        抛 "Cannot read while there is an open stream writer"（换页后新页流未被关闭）
        List<SeoAuditService.Issue> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new SeoAuditService.Issue("broken-link", SeoAuditService.Severity.ERROR,
                    "https://example.com/page-" + i + "/a-path-long-enough-to-wrap-lines/", "404"));
        }
        saveReport(objectMapper.writeValueAsString(many));

        // When
        var response = mvc.perform(get("/report/export-test/export")
                        .param("format", "pdf").param("lang", "zh-CN").with(user(tester)))
                .andExpect(status().isOk()).andReturn().getResponse();

        // Then: 合法多页 PDF——每个页流都已收尾，save 才能成功
        assertThat(response.getContentAsByteArray()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        try (PDDocument doc = Loader.loadPDF(response.getContentAsByteArray())) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
        }
    }

    private MockHttpServletResponse download(String language) throws Exception {
        return mvc.perform(get("/report/export-test/export").param("lang", language).with(user(tester)))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private String issues(SeoAuditService.Issue... issues) throws JsonProcessingException {
        return objectMapper.writeValueAsString(List.of(issues));
    }

    private SeoReport saveReport(String json) {
        SeoReport report = new SeoReport();
        report.setTaskId("export-test");
        report.setSiteUrl("https://example.com/中文");
        report.setScore(75);
        report.setPagesAudited(12);
        report.setBrokenLinks(1);
        report.setSkippedPages(5);
        report.setErrorCount(2);
        report.setWarningCount(3);
        report.setInfoCount(4);
        report.setIssuesJson(json);
        report.setUserId(tester.id());
        report.setCreatedAt(LocalDateTime.of(2026, 9, 18, 20, 30, 15));
        return repository.saveAndFlush(report);
    }
}
