package io.github.ghgongjin.sitemap;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.PushLog;
import io.github.ghgongjin.sitemap.entity.UserAccount;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.PushLogRepository;
import io.github.ghgongjin.sitemap.repository.UserAccountRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.CrawlProgressService;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import io.github.ghgongjin.sitemap.service.SeoAuditService;
import io.github.ghgongjin.sitemap.service.SeoReportService;
import io.github.ghgongjin.sitemap.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可视化走查用 harness：仅在设置了 SITEMAP_VISUAL_HARNESS=true 时运行，CI 环境自动跳过。
 * 用真实控制器与模板在 8091 端口启动应用，并播种三个任务（完成/失败/进行中）与一个走查账号
 * harness / Passw0rd1：报告与自动站点都挂在该账号下，/reports、/report/**、/auto/** 需先登录才能看到。
 * 供浏览器在无公网抓取的前提下走查完成页、进度页与失败页。
 * 停留时长可用 SITEMAP_VISUAL_HARNESS_MS 覆盖（默认 15 分钟）。
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=${SITEMAP_VISUAL_PORT:8091}", "spring.main.banner-mode=off"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "SITEMAP_VISUAL_HARNESS", matches = "true")
class VisualHarnessTest {

    private static final String DEMO_URL = "https://demo.example.com";
    private static final long DEFAULT_WINDOW_MS = 15 * 60 * 1000L;
    /** 走查账号：报告与自动站点都归属该用户，浏览器需先登录才能看到播种数据 */
    private static final String HARNESS_USER = "harness";
    private static final String HARNESS_PASSWORD = "Passw0rd1";

    private static final String DEMO_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1" xmlns:video="http://www.google.com/schemas/sitemap-video/1.1" xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
              <url>
                <loc>https://demo.example.com/</loc>
                <lastmod>2026-09-18</lastmod>
                <priority>1.0</priority>
                <changefreq>daily</changefreq>
                <image:image><image:loc>https://demo.example.com/hero.png</image:loc></image:image>
                <image:image><image:loc>https://demo.example.com/cover.png</image:loc></image:image>
              </url>
              <url>
                <loc>https://demo.example.com/channel/start</loc>
                <lastmod>2026-09-16</lastmod>
                <priority>0.9</priority>
                <changefreq>weekly</changefreq>
                <video:video><video:player_loc>https://demo.example.com/media/start.mp4</video:player_loc></video:video>
              </url>
              <url>
                <loc>https://demo.example.com/help/faq</loc>
                <lastmod>2026-08-30</lastmod>
                <priority>0.8</priority>
                <changefreq>monthly</changefreq>
              </url>
              <url>
                <loc>https://demo.example.com/blog/release-notes</loc>
                <lastmod>2026-09-18</lastmod>
                <priority>0.7</priority>
                <changefreq>daily</changefreq>
                <news:news>
                  <news:publication><news:name>Demo News</news:name><news:language>zh</news:language></news:publication>
                  <news:publication_date>2026-09-18T09:30:00Z</news:publication_date>
                  <news:title>产品发布说明：站点地图新增新闻条目</news:title>
                </news:news>
              </url>
              <url>
                <loc>https://demo.example.com/about</loc>
                <lastmod>2026-07-05</lastmod>
                <priority>0.5</priority>
                <changefreq>yearly</changefreq>
              </url>
            </urlset>
            """;

    private static final List<String> DEMO_FEED = List.of(
            "/", "/channel/start", "/help/faq", "/blog/release-notes", "/about",
            "/channel/deep-dive", "/blog/tuning-crawl", "/help/limits");

    @LocalServerPort
    private int port;

    @Autowired
    private CrawlProgressService progress;

    @Autowired
    private SeoAuditService seoAuditService;

    @Autowired
    private SeoReportService seoReportService;

    @Autowired
    private AutoSiteRepository autoSiteRepository;

    @Autowired
    private AutoSiteVersionRepository autoSiteVersionRepository;

    @Autowired
    private PushConfigRepository pushConfigRepository;

    @Autowired
    private PushLogRepository pushLogRepository;

    @Autowired
    private CredentialCipher credentialCipher;

    @Autowired
    private AutoSiteService autoSiteService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    /** 走查账号的 id，播种的报告与自动站点都归属于它 */
    private Long harnessUserId;

    @Test
    void keepSeededTasksAvailableForVisualWalkthrough() throws Exception {
        harnessUserId = harnessUser().getId();
        progress.startTask("demo-done", DEMO_URL, true, true, true);
        progress.completeTask("demo-done", 5, DEMO_XML);
        seedSeoReports();
        long healthySiteId = seedAutoSites();
        seedPushDemo(healthySiteId);

        progress.startTask("demo-fail", "http://127.0.0.1/", false, false);
        progress.failTask("demo-fail", "拒绝包含内网地址的主机");

        progress.startTask("demo-run", DEMO_URL, true, false);
        Thread ticker = new Thread(this::tickRunningTask, "visual-harness-ticker");
        ticker.setDaemon(true);
        ticker.start();

        String baseUrl = "http://localhost:" + port;
        log.info("Visual harness ready on {}", baseUrl);
        log.info("  login : {}/login  (账号 {} / {})", baseUrl, HARNESS_USER, HARNESS_PASSWORD);
        log.info("  done  : {}/preview?taskId=demo-done  （游客可见）", baseUrl);
        log.info("  doneEN: {}/preview?taskId=demo-done&lang=en", baseUrl);
        log.info("  run   : {}/task/demo-run", baseUrl);
        log.info("  fail  : {}/task/demo-fail", baseUrl);
        log.info("  report: {}/report/demo-done  （需登录）", baseUrl);
        log.info("  list  : {}/reports  （需登录，只见本人报告）", baseUrl);
        log.info("  auto  : {}/auto  （需登录，只见本人站点）", baseUrl);
        log.info("  autoD : {}/auto/{}  （需登录）", baseUrl, healthySiteId);
        log.info("  push  : {}/auto/{} (push panel, SFTP + IndexNow)", baseUrl, healthySiteId);

        assertThat(progress.getTaskResult("demo-done").getStatus()).isEqualTo("completed");
        assertThat(progress.getTaskResult("demo-fail").getStatus()).isEqualTo("failed");
        assertThat(seoReportService.hasReport("demo-done")).isTrue();
        assertThat(autoSiteRepository.findAll()).hasSize(3);
        assertThat(autoSiteVersionRepository.countBySiteId(healthySiteId)).isEqualTo(3);
        assertThat(pushConfigRepository.count()).isEqualTo(2);
        assertThat(pushLogRepository.findBySiteIdOrderByIdDesc(healthySiteId)).hasSize(5);
        // 播种数据都挂在走查账号名下：列表按归属过滤后应全部可见
        assertThat(seoReportService.recent(harnessUserId)).hasSize(2);
        assertThat(autoSiteService.listOwned(harnessUserId)).hasSize(3);

        Thread.sleep(windowMs());
        ticker.interrupt();
    }

    /**
     * 走查账号：已存在则复用（同一 JVM 内重复启动 harness 不报错）
     */
    private UserAccount harnessUser() {
        return userAccountRepository.findByUsername(HARNESS_USER)
                .orElseGet(() -> userService.register(HARNESS_USER, HARNESS_PASSWORD));
    }

    private void seedSeoReports() {
        seoAuditService.beginAudit("demo-done");
        seoAuditService.recordPage("demo-done", new SeoAuditService.PageSeo(
                DEMO_URL + "/", 200, 320, "Demo 站点首页", "演示用首页描述", 1, DEMO_URL + "/", false, 2, 0));
        seoAuditService.recordPage("demo-done", new SeoAuditService.PageSeo(
                DEMO_URL + "/channel/start", 200, 2450, "频道页标题",
                "这是一个明显超过一百六十个字符的页面描述，用于演示描述过长的问题提示，让走查时可以看到 description 过长的告警条目以及它的具体字符数，方便确认中文文案与数字在表格里都能正常显示。",
                2, DEMO_URL + "/channel/start", false, 4, 2));
        seoAuditService.recordPage("demo-done", new SeoAuditService.PageSeo(
                DEMO_URL + "/blog/release-notes", 200, 480, "", "版本更新说明", 1,
                DEMO_URL + "/blog/release-notes", false, 0, 0));
        seoAuditService.recordBroken("demo-done", DEMO_URL + "/missing-page", 404);
        seoAuditService.recordUnreachable("demo-done", DEMO_URL + "/legacy", "Connection timed out");
        for (int i = 1; i <= 7; i++) {
            String url = DEMO_URL + "/help/page-" + i;
            seoAuditService.recordPage("demo-done", new SeoAuditService.PageSeo(
                    url, 200, 210 + i, "帮助中心第 " + i + " 页", "结构健康的演示页面", 1, url, false, 1, 0));
        }
        // 报告归属走查账号：/reports 与 /report/{taskId} 按登录用户过滤，播种数据才能被本人看到
        seoReportService.save("demo-done", DEMO_URL, harnessUserId);

        seoAuditService.beginAudit("demo-clean");
        seoAuditService.recordPage("demo-clean", new SeoAuditService.PageSeo(
                "https://clean.example.com/", 200, 200, "Clean 站点", "结构健康的站点", 1,
                "https://clean.example.com/", false, 0, 0));
        seoReportService.save("demo-clean", "https://clean.example.com", harnessUserId);
    }

    /**
     * 直接经仓储播种，绕过 AutoSiteService.create 的公网 DNS 校验，供无公网走查使用。
     * 返回成功站点 ID，便于日志中打印详情页地址。
     */
    private long seedAutoSites() {
        LocalDateTime now = LocalDateTime.now();

        AutoSite failed = newAutoSite("https://broken.example.com", false, false, false, 6, now.minusDays(3));
        failed.setLastStatus(AutoSiteService.STATUS_FAILED);
        failed.setLastMessage("拒绝包含内网地址的主机");
        failed.setLastRunAt(now.minusHours(5));
        failed.setNextRunAt(now.plusMinutes(30));
        autoSiteRepository.save(failed);

        AutoSite paused = newAutoSite("https://paused.example.com", true, false, false, 168, now.minusDays(2));
        paused.setEnabled(false);
        paused.setNextRunAt(now.plusHours(72));
        autoSiteRepository.save(paused);

        AutoSite healthy = newAutoSite(DEMO_URL, true, false, true, 24, now.minusDays(1));
        healthy.setLastStatus(AutoSiteService.STATUS_SUCCESS);
        healthy.setLastRunAt(now.minusHours(18));
        healthy.setNextRunAt(now.plusHours(6));
        autoSiteRepository.save(healthy);

        for (int v = 1; v <= 3; v++) {
            AutoSiteVersion version = new AutoSiteVersion();
            version.setSiteId(healthy.getId());
            version.setVersionNumber(v);
            version.setTaskId("auto-" + healthy.getId() + "-demo" + v);
            version.setUrlCount(v == 3 ? 5 : 4 + v);
            version.setSitemapXml(v == 3 ? DEMO_XML : legacyVersionXml(v, 4 + v));
            version.setCreatedAt(now.minusDays(4L - v));
            autoSiteVersionRepository.save(version);
        }
        return healthy.getId();
    }

    /**
     * 播种推送演示数据：健康站点为启用中的 SFTP + IndexNow 配置并带多条推送记录；
     * 停用站点为关闭状态的 FTP 配置（用于确认 FTP 模式下认证方式行隐藏）。
     * 主机名统一用 .invalid 保留域，连接只会得到 NXDOMAIN，不会触碰任何真实公网主机。
     */
    private void seedPushDemo(long healthySiteId) {
        LocalDateTime now = LocalDateTime.now();
        String demoPassword = credentialCipher.encrypt("demo-pass-2026");

        PushConfig sftp = new PushConfig();
        sftp.setSiteId(healthySiteId);
        sftp.setEnabled(true);
        sftp.setProtocol("SFTP");
        sftp.setHost("sftp.demo-host.invalid");
        sftp.setPort(22);
        sftp.setUsername("deployer");
        sftp.setAuthType("PASSWORD");
        sftp.setPasswordEnc(demoPassword);
        sftp.setRemoteDir("/var/www/demo/public");
        sftp.setSitemapFileName("sitemap.xml");
        sftp.setHostKeyFingerprint("SHA256:8f2ac41d7be54a2c8f0d6b1a9e4c7f2b8");
        sftp.setIndexNowEnabled(true);
        sftp.setIndexNowKey("9f3c1d7be54a2c8f0d6b1a9e4c7f2b83");
        sftp.setLastPushAt(now.minusMinutes(20));
        sftp.setLastPushStatus(PushLog.STATUS_SUCCESS);
        sftp.setCreatedAt(now.minusDays(3));
        sftp.setUpdatedAt(now.minusMinutes(20));
        pushConfigRepository.save(sftp);

        pushLogRepository.save(pushLog(healthySiteId, 3, PushLog.STATUS_SUCCESS, null,
                "已上传 sitemap.xml（版本 3），共 5 个地址", PushLog.INDEX_NOW_SUCCESS, 842, now.minusMinutes(20)));
        pushLogRepository.save(pushLog(healthySiteId, 3, PushLog.STATUS_FAILED, "TIMEOUT",
                "连接超时（第 1/2 次尝试）", null, 30512, now.minusHours(2)));
        pushLogRepository.save(pushLog(healthySiteId, 3, PushLog.STATUS_FAILED, "AUTH_FAILED",
                "认证失败（用户名或密码错误）", null, 412, now.minusDays(1)));
        pushLogRepository.save(pushLog(healthySiteId, 2, PushLog.STATUS_SUCCESS, null,
                "已上传 sitemap.xml（版本 2），共 5 个地址", PushLog.INDEX_NOW_FAILED, 934,
                now.minusDays(1).minusHours(3)));
        pushLogRepository.save(pushLog(healthySiteId, 1, PushLog.STATUS_SUCCESS, null,
                "已上传 sitemap.xml（版本 1），共 4 个地址", null, 812, now.minusDays(2)));

        AutoSite paused = autoSiteRepository.findAll().stream()
                .filter(site -> !site.isEnabled())
                .findFirst()
                .orElseThrow();
        PushConfig ftp = new PushConfig();
        ftp.setSiteId(paused.getId());
        ftp.setEnabled(false);
        ftp.setProtocol("FTP");
        ftp.setHost("ftp.demo-host.invalid");
        ftp.setPort(21);
        ftp.setUsername("uploader");
        ftp.setAuthType("PASSWORD");
        ftp.setPasswordEnc(demoPassword);
        ftp.setRemoteDir("/htdocs");
        ftp.setSitemapFileName("sitemap.xml");
        ftp.setIndexNowEnabled(false);
        ftp.setCreatedAt(now.minusDays(2));
        ftp.setUpdatedAt(now.minusDays(2));
        pushConfigRepository.save(ftp);
    }

    private static PushLog pushLog(long siteId, int versionNumber, String status, String errorCode,
                                   String detail, String indexNowStatus, long durationMs,
                                   LocalDateTime createdAt) {
        PushLog log = new PushLog();
        log.setSiteId(siteId);
        log.setVersionNumber(versionNumber);
        log.setProtocol("SFTP");
        log.setStatus(status);
        log.setErrorCode(errorCode);
        log.setDetail(detail);
        log.setIndexNowStatus(indexNowStatus);
        log.setDurationMs(durationMs);
        log.setCreatedAt(createdAt);
        return log;
    }

    private AutoSite newAutoSite(String url, boolean images, boolean videos, boolean news,
                                 int intervalHours, LocalDateTime createdAt) {
        AutoSite site = new AutoSite();
        site.setUserId(harnessUserId);
        site.setUrl(url);
        site.setIncludeImages(images);
        site.setIncludeVideos(videos);
        site.setIncludeNews(news);
        site.setIntervalHours(intervalHours);
        site.setEnabled(true);
        site.setLastStatus(AutoSiteService.STATUS_PENDING);
        site.setNextRunAt(createdAt.plusHours(intervalHours));
        site.setCreatedAt(createdAt);
        site.setUpdatedAt(createdAt);
        return site;
    }

    private static String legacyVersionXml(int version, int urlCount) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """);
        for (int i = 0; i < urlCount; i++) {
            xml.append("  <url><loc>").append(DEMO_URL).append("/v").append(version).append("/page-")
                    .append(i + 1).append("</loc></url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    private void tickRunningTask() {
        int tick = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                tick++;
                String path = DEMO_FEED.get(Math.min(tick - 1, DEMO_FEED.size() - 1));
                progress.updateProgress("demo-run", Math.min(tick, 1000), 1000, DEMO_URL + path);
                Thread.sleep(1500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long windowMs() {
        String raw = System.getenv("SITEMAP_VISUAL_HARNESS_MS");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WINDOW_MS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_WINDOW_MS;
        }
    }
}
