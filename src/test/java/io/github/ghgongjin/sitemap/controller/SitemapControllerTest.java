package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.security.UserAccountDetails;
import io.github.ghgongjin.sitemap.service.CrawlProgressService;
import io.github.ghgongjin.sitemap.service.EnhancedSitemapGeneratorService;
import io.github.ghgongjin.sitemap.service.SeoAuditService;
import io.github.ghgongjin.sitemap.service.SeoReportService;
import io.github.ghgongjin.sitemap.service.SitemapGeneratorService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SitemapControllerTest {

    private static final Long USER_ID = 42L;
    private static final String URL = "https://original.example/site";
    private static final String OTHER_URL = "https://unrelated.example/other";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset><url><loc>"
            + URL + "/中文</loc></url></urlset>";
    private static final String FALLBACK_XML = "<urlset><url><loc>unexpected-fetch</loc></url></urlset>";
    private static final String RICH_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"\
             xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"\
             xmlns:video="http://www.google.com/schemas/sitemap-video/1.1">
              <url>
                <loc>https://original.example/site</loc>
                <lastmod>2026-09-18</lastmod>
                <priority>1.0</priority>
                <image:image><image:loc>https://original.example/a.png</image:loc></image:image>
                <image:image><image:loc>https://original.example/b.png</image:loc></image:image>
              </url>
              <url>
                <loc>https://original.example/site/about</loc>
                <lastmod>2026-09-12</lastmod>
                <priority>0.8</priority>
              </url>
              <url>
                <loc>https://original.example/site/watch</loc>
                <lastmod>2026-09-20</lastmod>
                <priority>0.9</priority>
                <video:video><video:player_loc>https://original.example/v.mp4</video:player_loc></video:video>
              </url>
            </urlset>
            """;
    private static final String NEWS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"\
             xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
              <url>
                <loc>https://original.example/site/news/1</loc>
                <lastmod>2026-09-18</lastmod>
                <news:news>
                  <news:publication><news:name>Example</news:name><news:language>zh</news:language></news:publication>
                  <news:publication_date>2026-09-18</news:publication_date>
                  <news:title>新闻标题</news:title>
                </news:news>
              </url>
            </urlset>
            """;

    private SitemapGeneratorService generator;
    private EnhancedSitemapGeneratorService enhanced;
    private CrawlProgressService progress;
    private SeoReportService seoReport;
    private SitemapController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 控制器在请求线程读取归属用户，standalone 场景需手工装配登录态
        UserAccountDetails details = new UserAccountDetails(USER_ID, "tester", "");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                details, "", details.getAuthorities()));
        generator = mock(SitemapGeneratorService.class);
        enhanced = mock(EnhancedSitemapGeneratorService.class);
        progress = new CrawlProgressService(mock(SimpMessagingTemplate.class));
        seoReport = mock(SeoReportService.class);
        controller = new SitemapController(generator, enhanced, progress, seoReport);

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
        views.setViewNames(new String[]{"index", "preview", "result-progress", "report", "reports"});
        // Only template rendering is under test; unrelated views need no physical template.
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setLocaleResolver(new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE))
                .setViewResolvers(views, new InternalResourceViewResolver("/", ".html")).build();
    }

    @AfterEach
    void tearDown() {
        controller.shutdown();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void shouldRenderOriginalMediaOptionsWhenPreviewingCompletedTask(boolean images, boolean videos)
            throws Exception {
        // Given: the crawler repeats startTask with the legacy two-argument call.
        String taskId = completeAsyncTask(images, videos);

        // When: the browser follows /preview?taskId=... without media parameters.
        Document page = render(get("/preview").param("taskId", taskId));

        // Then: assert the rendered HTML, not just model attributes.
        assertThat(page.select(".info-value").eachText())
                .containsExactly(URL, images ? "是" : "否", videos ? "是" : "否", "否");
        assertThat(page.selectFirst("code.language-xml").text()).isEqualTo(XML);
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void shouldIgnoreConflictingParametersWhenPreviewingTask(boolean images, boolean videos)
            throws Exception {
        // Given
        String taskId = completeAsyncTask(images, videos);

        // When
        Document page = render(get("/preview").param("taskId", taskId).param("url", OTHER_URL)
                .param("includeImages", Boolean.toString(!images))
                .param("includeVideos", Boolean.toString(!videos)));

        // Then
        assertThat(page.select(".info-value").eachText())
                .containsExactly(URL, images ? "是" : "否", videos ? "是" : "否", "否");
        assertThat(page.selectFirst("code.language-xml").text()).isEqualTo(XML);
        verifyNoInteractions(generator);
    }

    @Test
    void shouldRenderUrlListMetersAndEntriesFromRichXml() throws Exception {
        // Given: a cached sitemap with media entries, lastmod and priority metadata.
        String taskId = completeAsyncTask(true, true, RICH_XML);

        // When
        Document page = render(get("/preview").param("taskId", taskId));

        // Then: the address list is rendered server-side from the cached XML.
        assertThat(page.select(".meter b").eachText())
                .containsExactly("3", "2", "1", "0", "2026-09-20");
        assertThat(page.select(".url-table tbody tr")).hasSize(3);
        assertThat(page.select(".url-table .u").eachText()).containsExactly(
                "https://original.example/site",
                "https://original.example/site/about",
                "https://original.example/site/watch");
        assertThat(page.select(".url-table .lm").eachText())
                .containsExactly("2026-09-18", "2026-09-12", "2026-09-20");
        assertThat(page.select(".url-table .pr").eachText()).containsExactly("1.0", "0.8", "0.9");
        assertThat(page.select(".list-count").text()).contains("3");
        assertThat(page.select(".info-value").eachText()).containsExactly(URL, "是", "是", "否");
        assertThat(page.selectFirst("code.language-xml").wholeText()).isEqualTo(RICH_XML);
        verifyNoInteractions(generator);
    }

    @Test
    void shouldRenderNewsMeterAndOptionWhenTaskEnabledNews() throws Exception {
        // Given: a task whose result carries the news flag and a news:news entry.
        String taskId = completeAsyncTask(false, false, true, NEWS_XML);

        // When
        Document page = render(get("/preview").param("taskId", taskId));

        // Then
        assertThat(page.select(".info-value").eachText()).containsExactly(URL, "否", "否", "是");
        assertThat(page.select(".meter b").eachText())
                .containsExactly("1", "0", "0", "1", "2026-09-18");
        assertThat(page.selectFirst("code.language-xml").wholeText()).isEqualTo(NEWS_XML);
        verifyNoInteractions(generator);
    }

    @Test
    void shouldRenderPlaceholdersWhenXmlHasNoMetadata() throws Exception {
        // Given: the legacy two-argument crawler caches a minimal sitemap.
        String taskId = completeAsyncTask(false, false);

        // When
        Document page = render(get("/preview").param("taskId", taskId));

        // Then
        assertThat(page.select(".url-table .lm").eachText()).containsExactly("—");
        assertThat(page.select(".url-table .pr").eachText()).containsExactly("—");
        assertThat(page.select(".meter b").eachText()).containsExactly("1", "0", "0", "0", "—");
        verifyNoInteractions(generator);
    }

    @Test
    void shouldCarryTaskIdAndReuseExactXmlWhenFollowingRenderedDownloadLink() throws Exception {
        // Given
        String taskId = completeAsyncTask(true, true);
        Document page = render(get("/preview").param("taskId", taskId));
        String href = page.selectFirst("a[href^=/download]").attr("href");

        // When / Then
        assertThat(UriComponentsBuilder.fromUriString(href).build().getQueryParams().getFirst("taskId"))
                .isEqualTo(taskId);
        byte[] downloaded = mvc.perform(get(href)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(XML.getBytes(StandardCharsets.UTF_8));
        verifyNoInteractions(generator);
    }

    @Test
    void shouldUseOriginalFilenameWhenDownloadParametersConflict() throws Exception {
        // Given
        progress.startTask("completed", URL);
        progress.completeTask("completed", 1, XML);

        // When
        var response = mvc.perform(get("/download").param("taskId", "completed")
                        .param("url", OTHER_URL).param("includeImages", "true").param("includeVideos", "true"))
                .andExpect(status().isOk()).andReturn().getResponse();

        // Then
        assertThat(response.getHeader("Content-Disposition")).contains("sitemap-original-example.xml");
        assertThat(response.getContentAsByteArray()).isEqualTo(XML.getBytes(StandardCharsets.UTF_8));
        verifyNoInteractions(generator, enhanced);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> unavailableTasks() {
        return Stream.of("/preview", "/download").flatMap(endpoint ->
                Stream.of("missing", "running", "failed").flatMap(state ->
                        Stream.of(false, true).map(withUrl ->
                                org.junit.jupiter.params.provider.Arguments.of(endpoint, state, withUrl))));
    }

    @ParameterizedTest
    @MethodSource("unavailableTasks")
    void shouldRejectUnavailableTaskWithoutFetching(String endpoint, String state, boolean withUrl)
            throws Exception {
        // Given: a fallback URL must not allow a missing or unfinished task to trigger another crawl.
        if (!"missing".equals(state)) {
            progress.startTask("task", URL);
            if ("failed".equals(state)) {
                progress.failTask("task", "simulated failure");
            }
        }
        when(generator.generateSitemap(anyString(), anyBoolean(), anyBoolean())).thenReturn(FALLBACK_XML);
        MockHttpServletRequestBuilder request = get(endpoint).param("taskId", "task");
        if (withUrl) {
            request.param("url", OTHER_URL);
        }

        // When
        var response = mvc.perform(request).andReturn().getResponse();

        // Then: verify status and absence of network-generating calls independently.
        assertAll(
                () -> assertThat(response.getStatus()).isEqualTo("missing".equals(state) ? 404 : 409),
                () -> verifyNoInteractions(generator, enhanced));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/preview", "/download"})
    void shouldRejectExplicitEmptyTaskIdWithoutUsingLegacyUrl(String endpoint) throws Exception {
        // Given
        when(generator.generateSitemap(anyString(), anyBoolean(), anyBoolean())).thenReturn(FALLBACK_XML);

        // When
        var response = mvc.perform(get(endpoint).param("taskId", "").param("url", OTHER_URL))
                .andReturn().getResponse();

        // Then
        assertAll(() -> assertThat(response.getStatus()).isEqualTo(404),
                () -> verifyNoInteractions(generator, enhanced));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/preview", "/download"})
    void shouldRejectCompletedTaskWithoutXmlInsteadOfFetching(String endpoint) throws Exception {
        // Given: defend against a malformed cached result as well as running tasks.
        progress.startTask("no-xml", URL);
        progress.completeTask("no-xml", 0, null);
        when(generator.generateSitemap(anyString(), anyBoolean(), anyBoolean())).thenReturn(FALLBACK_XML);

        // When
        var response = mvc.perform(get(endpoint).param("taskId", "no-xml").param("url", OTHER_URL))
                .andReturn().getResponse();

        // Then
        assertAll(() -> assertThat(response.getStatus()).isEqualTo(409),
                () -> verifyNoInteractions(generator, enhanced));
    }

    @Test
    void shouldRenderProgressPageForKnownTask() throws Exception {
        // Given: the browser reloads the POST result page through its GET route.
        progress.startTask("running-task", URL, true, false);

        // When
        Document page = render(get("/task/running-task"));

        // Then
        assertThat(page.select(".mchip b").eachText()).containsExactly(URL, "running-task");
        assertThat(page.selectFirst(".prog-status #statusText").text()).isEqualTo("正在连接并发现页面…");
        assertThat(page.select(".meter b").eachText()).containsExactly("0", "1000", "0s", "抓取中");
    }

    @Test
    void shouldReturnNotFoundWhenProgressPageTaskIsUnknown() throws Exception {
        // When
        var response = mvc.perform(get("/task/missing-task")).andReturn().getResponse();

        // Then
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void shouldExposePercentageAndCurrentUrlFromProgressApi() throws Exception {
        // Given
        progress.startTask("api-task", URL);
        progress.updateProgress("api-task", 25, EnhancedSitemapGeneratorService.MAX_PAGES, URL + "/page-25");

        // When
        String body = mvc.perform(get("/api/task/api-task/progress")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Then
        assertThat(body).contains("\"crawledPages\":25",
                "\"totalPages\":" + EnhancedSitemapGeneratorService.MAX_PAGES, "\"percentage\":2",
                "\"currentUrl\":\"" + URL + "/page-25\"");
    }

    @Test
    void shouldPreserveLegacyPreviewOptionsAndDownloadLinkWhenTaskIdAbsent() throws Exception {
        // Given
        when(generator.generateSitemap(URL, true, false, false)).thenReturn(XML);

        // When
        Document page = render(get("/preview").param("url", URL)
                .param("includeImages", "true").param("includeVideos", "false"));
        String href = page.selectFirst("a[href^=/download]").attr("href");

        // Then
        assertThat(page.select(".info-value").eachText()).containsExactly(URL, "是", "否", "否");
        var query = UriComponentsBuilder.fromUriString(href).build().getQueryParams();
        assertThat(query).doesNotContainKey("taskId");
        assertThat(query.getFirst("includeImages")).isEqualTo("true");
        assertThat(query.getFirst("includeVideos")).isEqualTo("false");
        verify(generator).generateSitemap(URL, true, false, false);
        verifyNoMoreInteractions(generator);
    }

    @Test
    void shouldRenderNewsOptionInLegacyPreviewWhenRequested() throws Exception {
        // Given
        when(generator.generateSitemap(URL, false, false, true)).thenReturn(NEWS_XML);

        // When
        Document page = render(get("/preview").param("url", URL).param("includeNews", "true"));
        String href = page.selectFirst("a[href^=/download]").attr("href");

        // Then
        assertThat(page.select(".info-value").eachText()).containsExactly(URL, "否", "否", "是");
        assertThat(page.select(".meter b").eachText())
                .containsExactly("1", "0", "0", "1", "2026-09-18");
        assertThat(UriComponentsBuilder.fromUriString(href).build().getQueryParams().getFirst("includeNews"))
                .isEqualTo("true");
        verify(generator).generateSitemap(URL, false, false, true);
        verifyNoMoreInteractions(generator);
    }

    @Test
    void shouldPreserveLegacyDownloadWhenTaskIdAbsent() throws Exception {
        // Given
        when(generator.generateSitemap(URL, false, true, false)).thenReturn(XML);

        // When
        byte[] downloaded = mvc.perform(get("/download").param("url", URL)
                        .param("includeImages", "false").param("includeVideos", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

        // Then
        assertThat(downloaded).isEqualTo(XML.getBytes(StandardCharsets.UTF_8));
        verify(generator).generateSitemap(URL, false, true, false);
        verifyNoMoreInteractions(generator);
    }

    @Test
    void shouldPersistSeoReportWhenAsyncCrawlCompletes() throws Exception {
        // Given / When: the async crawler finishes successfully.
        String taskId = completeAsyncTask(true, false);

        // Then: the audit summary is written through the report service by the crawl thread.
        verify(seoReport, timeout(5000)).save(taskId, URL, USER_ID);
    }

    @Test
    void shouldPersistReportWithoutOwnerWhenGuestCrawls() throws Exception {
        // Given: 游客提交爬取，请求线程没有登录态
        SecurityContextHolder.clearContext();

        // When
        String taskId = completeAsyncTask(false, false);

        // Then: 归属为 null，报告不会被错绑到任何用户
        verify(seoReport, timeout(5000)).save(taskId, URL, null);
    }

    @Test
    void shouldReturn404WhenSeoReportMissing() throws Exception {
        // Given
        when(seoReport.findOwned("missing-task", USER_ID)).thenReturn(Optional.empty());

        // When / Then
        mvc.perform(get("/report/missing-task")).andExpect(status().isNotFound());
    }

    @Test
    void shouldRenderSeoReportWhenReportStored() throws Exception {
        // Given: a persisted report whose issue list is stored as JSON.
        when(seoReport.findOwned("task-report", USER_ID)).thenReturn(Optional.of(report(70, 2, 1, 1, 0, 0)));
        when(seoReport.parseIssues(anyString())).thenReturn(List.of(
                new SeoAuditService.Issue("broken-link", SeoAuditService.Severity.ERROR, URL + "/missing", "404"),
                new SeoAuditService.Issue("missing-title", SeoAuditService.Severity.WARNING, URL, "")));

        // When
        Document page = render(get("/report/task-report"));

        // Then
        assertThat(page.selectFirst(".score-box b").text()).isEqualTo("70");
        assertThat(page.selectFirst(".score-box span").text()).isEqualTo("良好");
        assertThat(page.select(".meters.side .meter b").eachText())
                .containsExactly("2", "1", "1", "0", "0");
        assertThat(page.select(".report-table tbody tr")).hasSize(2);
        assertThat(page.select(".report-table .sev").eachText()).containsExactly("错误", "警告");
        assertThat(page.select(".report-table .rule-name").eachText()).containsExactly("断链", "缺少 title");
        assertThat(page.select(".report-table .det").eachText()).containsExactly("404", "—");
        assertThat(page.selectFirst(".report-table .u").text()).isEqualTo(URL + "/missing");
    }

    @Test
    void shouldRenderCleanStateWhenReportHasNoIssues() throws Exception {
        // Given
        when(seoReport.findOwned("task-clean", USER_ID)).thenReturn(Optional.of(report(100, 3, 0, 0, 0, 0)));
        when(seoReport.parseIssues(anyString())).thenReturn(List.of());

        // When
        Document page = render(get("/report/task-clean"));

        // Then
        assertThat(page.selectFirst(".score-box b").text()).isEqualTo("100");
        assertThat(page.selectFirst(".score-box span").text()).isEqualTo("优秀");
        assertThat(page.select(".report-table")).isEmpty();
        assertThat(page.selectFirst(".empty-ok b").text()).isEqualTo("没有发现问题");
    }

    @Test
    void shouldRenderReportListWhenReportsStored() throws Exception {
        // Given
        when(seoReport.recent(USER_ID)).thenReturn(List.of(
                report(70, 2, 1, 1, 0, 0),
                report(100, 5, 0, 0, 0, 0)));

        // When
        Document page = render(get("/reports"));

        // Then
        assertThat(page.select(".rep-table tbody tr")).hasSize(2);
        assertThat(page.select(".rep-table .sc").eachText()).containsExactly("70", "100");
        assertThat(page.select(".rep-table .link").first().attr("href")).isEqualTo("/report/task-report");
        assertThat(page.selectFirst(".rep-table .u").text()).isEqualTo(URL);
    }

    @Test
    void shouldRenderEmptyStateWhenNoReportsStored() throws Exception {
        // Given
        when(seoReport.recent(USER_ID)).thenReturn(List.of());

        // When
        Document page = render(get("/reports"));

        // Then
        assertThat(page.select(".rep-table")).isEmpty();
        assertThat(page.selectFirst(".empty-ok b").text())
                .isEqualTo("还没有报告，先回到首页生成一次站点地图。");
    }

    @Test
    void shouldShowReportLinkInPreviewWhenReportStored() throws Exception {
        // Given: report lookup is stubbed before the crawl thread touches the same mock.
        doReturn(true).when(seoReport).hasReport(anyString());
        String taskId = completeAsyncTask(false, false);

        // When
        Document page = render(get("/preview").param("taskId", taskId));

        // Then
        assertThat(page.select("a[href^=\"/report/\"]")).hasSize(1);
        assertThat(page.select("a[href^=\"/report/\"]").first().attr("href")).isEqualTo("/report/" + taskId);
    }

    @Test
    void shouldHideReportLinkInPreviewWhenReportMissing() throws Exception {
        // Given: a finished task without a persisted report.
        String taskId = completeAsyncTask(false, false);

        // When
        Document page = render(get("/preview").param("taskId", taskId));

        // Then
        assertThat(page.select("a[href^=\"/report/\"]")).isEmpty();
    }

    private SeoReport report(int score, int pages, int broken, int errors, int warnings, int infos) {
        SeoReport report = new SeoReport();
        report.setTaskId("task-report");
        report.setSiteUrl(URL);
        report.setScore(score);
        report.setPagesAudited(pages);
        report.setBrokenLinks(broken);
        report.setErrorCount(errors);
        report.setWarningCount(warnings);
        report.setInfoCount(infos);
        report.setIssuesJson("[]");
        report.setCreatedAt(LocalDateTime.of(2026, 9, 18, 20, 30));
        return report;
    }

    @Test
    void shouldUseInlineStyledValidationInsteadOfNativeBubbleOnHome() throws Exception {
        // When
        Document page = render(get("/"));

        // Then
        assertThat(page.selectFirst("form#genForm").hasAttr("novalidate")).isTrue();
        assertThat(page.selectFirst("#urlErr").hasAttr("hidden")).isTrue();
        assertThat(page.selectFirst("#urlErr span")).isNotNull();
        assertThat(page.html()).contains("SitemapUI.urlGuard");
        assertThat(page.html()).doesNotContain("window.confirm");
    }

    private Document render(MockHttpServletRequestBuilder request) throws Exception {
        String html = mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Jsoup.parse(html);
    }

    private String completeAsyncTask(boolean images, boolean videos) throws Exception {
        return completeAsyncTask(images, videos, false, XML);
    }

    private String completeAsyncTask(boolean images, boolean videos, String sitemapXml) throws Exception {
        return completeAsyncTask(images, videos, false, sitemapXml);
    }

    private String completeAsyncTask(boolean images, boolean videos, boolean news, String sitemapXml)
            throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        doAnswer(invocation -> {
            String taskId = invocation.getArgument(4);
            // A duplicate startTask call is a no-op; options captured by the controller must survive.
            progress.startTask(taskId, URL);
            progress.completeTask(taskId, 1, sitemapXml);
            completed.countDown();
            return sitemapXml;
        }).when(enhanced).generateSitemapWithProgress(eq(URL), eq(images), eq(videos), eq(news), anyString());
        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.generateSitemapAsync(URL, images, videos, news, model, new RedirectAttributesModelMap());
        assertThat(view).isEqualTo("result-progress");
        assertThat(completed.await(5, TimeUnit.SECONDS)).as("mock crawler completion").isTrue();
        return (String) model.get("taskId");
    }
}
