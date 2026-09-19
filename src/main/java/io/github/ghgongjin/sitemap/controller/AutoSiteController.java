package io.github.ghgongjin.sitemap.controller;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.security.SecurityUtils;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.AutoSiteValidationException;
import io.github.ghgongjin.sitemap.service.Csv;
import io.github.ghgongjin.sitemap.service.SiteDiffEngine;
import io.github.ghgongjin.sitemap.service.push.PushConfigService;
import io.github.ghgongjin.sitemap.service.push.PushOutcome;
import io.github.ghgongjin.sitemap.service.push.PushSettings;
import io.github.ghgongjin.sitemap.service.push.SitemapPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @ClassName AutoSiteController
 * @Description 自动更新管理页（注册站点、启停、立即执行、版本下载、推送设置与执行），
 *              全部端点以当前登录用户为作用域，他人站点一律 404
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Controller
@RequestMapping("/auto")
@RequiredArgsConstructor
public class AutoSiteController {

    private static final String LIST_PATH = "/auto";

    private final AutoSiteService autoSiteService;
    private final PushConfigService pushConfigService;
    private final SitemapPushService sitemapPushService;

    @GetMapping
    public String list(Model model) {
        Long userId = SecurityUtils.currentUserId();
        List<SiteRow> rows = autoSiteService.listOwned(userId).stream()
                .map(site -> new SiteRow(site, autoSiteService.latestVersion(site.getId()).orElse(null)))
                .toList();
        model.addAttribute("rows", rows);
        return "auto";
    }

    @PostMapping
    public String create(@RequestParam String url,
                         @RequestParam(value = "includeImages", defaultValue = "false") boolean includeImages,
                         @RequestParam(value = "includeVideos", defaultValue = "false") boolean includeVideos,
                         @RequestParam(value = "includeNews", defaultValue = "false") boolean includeNews,
                         @RequestParam(value = "intervalHours", defaultValue = "24") int intervalHours,
                         RedirectAttributes redirect) {
        Long userId = SecurityUtils.currentUserId();
        try {
            AutoSite site = autoSiteService.create(
                    userId, url, includeImages, includeVideos, includeNews, intervalHours);
            log.info("自动更新站点已添加：{}（用户 {}）", site.getUrl(), userId);
            redirect.addFlashAttribute("flash", "auto.flash.added");
        } catch (IllegalArgumentException | SecurityException e) {
            flashError(redirect, e);
        }
        return "redirect:/auto";
    }

    @PostMapping("/{id}/run")
    public String runNow(@PathVariable Long id, RedirectAttributes redirect) {
        Long userId = SecurityUtils.currentUserId();
        requireOwned(id, userId);
        return mutate(redirect, "auto.flash.run", LIST_PATH, () -> autoSiteService.runNow(id, userId));
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes redirect) {
        Long userId = SecurityUtils.currentUserId();
        AutoSite site = requireOwned(id, userId);
        boolean target = !site.isEnabled();
        return mutate(redirect, target ? "auto.flash.enabled" : "auto.flash.disabled", LIST_PATH,
                () -> autoSiteService.setEnabled(id, target, userId));
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        Long userId = SecurityUtils.currentUserId();
        requireOwned(id, userId);
        return mutate(redirect, "auto.flash.deleted", LIST_PATH, () -> {
            autoSiteService.delete(id, userId);
            pushConfigService.delete(id);
        });
    }

    @PostMapping("/{id}/push/settings")
    public String savePushSettings(@PathVariable Long id,
                                   @RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
                                   @RequestParam String protocol,
                                   @RequestParam String host,
                                   @RequestParam int port,
                                   @RequestParam String username,
                                   @RequestParam(value = "authType", defaultValue = "PASSWORD") String authType,
                                   @RequestParam(value = "password", defaultValue = "") String password,
                                   @RequestParam(value = "privateKey", defaultValue = "") String privateKey,
                                   @RequestParam(value = "remoteDir", defaultValue = "") String remoteDir,
                                   @RequestParam(value = "sitemapFileName", defaultValue = "sitemap.xml")
                                   String sitemapFileName,
                                   @RequestParam(value = "indexNowEnabled", defaultValue = "false")
                                   boolean indexNowEnabled,
                                   @RequestParam(value = "indexNowKey", defaultValue = "") String indexNowKey,
                                   RedirectAttributes redirect) {
        requireOwned(id, SecurityUtils.currentUserId());
        PushSettings settings = new PushSettings(enabled, protocol, host, port, username, authType,
                password, privateKey, remoteDir, sitemapFileName, indexNowEnabled, indexNowKey);
        return mutate(redirect, "auto.push.flash.saved", detailPath(id),
                () -> pushConfigService.save(id, settings));
    }

    @PostMapping("/{id}/push/test")
    public String testPush(@PathVariable Long id, RedirectAttributes redirect) {
        requireOwned(id, SecurityUtils.currentUserId());
        return flashOutcome(redirect, id, "auto.push.flash.testOk",
                () -> sitemapPushService.testConnection(id));
    }

    @PostMapping("/{id}/push/run")
    public String runPush(@PathVariable Long id, RedirectAttributes redirect) {
        requireOwned(id, SecurityUtils.currentUserId());
        return flashOutcome(redirect, id, "auto.push.flash.pushed",
                () -> sitemapPushService.push(id));
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Long userId = SecurityUtils.currentUserId();
        AutoSite site = requireOwned(id, userId);
        model.addAttribute("site", site);
        model.addAttribute("versions", autoSiteService.versions(id));
        model.addAttribute("pushConfig", pushConfigService.view(id).orElse(null));
        model.addAttribute("pushLogs", pushConfigService.logs(id));
        return "auto-detail";
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id,
                                           @RequestParam(value = "version", required = false) Integer version) {
        requireOwned(id, SecurityUtils.currentUserId());
        AutoSiteVersion target = version == null
                ? autoSiteService.latestVersion(id).orElse(null)
                : autoSiteService.version(id, version).orElse(null);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] body = target.getSitemapXml().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.setContentDispositionFormData("attachment", "sitemap-v" + target.getVersionNumber() + ".xml");
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/{id}/versions/{v}/diff")
    public String diffPage(@PathVariable Long id, @PathVariable("v") int v, Model model) {
        AutoSite site = requireOwned(id, SecurityUtils.currentUserId());
        AutoSiteVersion version = requireVersion(id, v);
        model.addAttribute("site", site);
        model.addAttribute("version", version);
        model.addAttribute("view", diffView(id, version));
        return "auto-diff";
    }

    @GetMapping("/{id}/versions/{v}/diff.csv")
    public ResponseEntity<byte[]> diffCsv(@PathVariable Long id, @PathVariable("v") int v) {
        requireOwned(id, SecurityUtils.currentUserId());
        AutoSiteVersion version = requireVersion(id, v);
        DiffResolution resolution = resolveDiff(id, version);
        if (!"OK".equals(resolution.state())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Export the FULL diff (not the page's 100-row preview cap): same source/version resolution as the page.
        SiteDiffEngine.SiteDiff diff = resolution.diff();
        StringBuilder csv = new StringBuilder("\uFEFF");
        Csv.row(csv, "type", "url");
        diff.added().forEach(url -> Csv.row(csv, "added", url));
        diff.removed().forEach(url -> Csv.row(csv, "removed", url));
        diff.changed().forEach(url -> Csv.row(csv, "changed", url));
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "sitemap-diff-v" + v + ".csv");
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private AutoSiteVersion requireVersion(Long id, int v) {
        return autoSiteService.version(id, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * diff 明细统一实时算：v1=FIRST；上一版已被裁剪/XML 缺失/解析异常=UNAVAILABLE；否则 OK（携带全量 SiteDiff）。
     * 页面据此截断预览 100 条，CSV 导出全量——「无法比较」两处语义一致（页面占位 / CSV 404）。
     */
    private DiffResolution resolveDiff(Long siteId, AutoSiteVersion version) {
        if (version.getVersionNumber() == 1) {
            return new DiffResolution("FIRST", null);
        }
        AutoSiteVersion previous = autoSiteService.version(siteId, version.getVersionNumber() - 1)
                .orElse(null);
        if (previous == null) {
            return new DiffResolution("UNAVAILABLE", null);
        }
        try {
            return new DiffResolution("OK",
                    SiteDiffEngine.diff(previous.getSitemapXml(), version.getSitemapXml()));
        } catch (Exception e) {
            log.warn("版本 diff 实时计算失败：siteId={}, v={}, {}", siteId, version.getVersionNumber(), e.getMessage());
            return new DiffResolution("UNAVAILABLE", null);
        }
    }

    /** resolveDiff 结果：state ∈ {FIRST, UNAVAILABLE, OK}，diff 仅 OK 时非空（全量，未截断） */
    private record DiffResolution(String state, SiteDiffEngine.SiteDiff diff) {
    }

    /**
     * 详情页预览模型：由 resolveDiff 的全量 diff 每组截断至 PREVIEW_LIMIT；明细按需实时算
     */
    private DiffView diffView(Long siteId, AutoSiteVersion version) {
        DiffResolution resolution = resolveDiff(siteId, version);
        return DiffView.of(resolution.state(), resolution.diff());
    }

    // 模板 SpEL 需反射调用 overflow()：record 与方法必须 public，包私有会抛 IllegalAccessException
    public record DiffView(String state, List<String> added, List<String> removed, List<String> changed,
                    int addedCount, int removedCount, int changedCount) {

        static final int PREVIEW_LIMIT = 100;

        static DiffView of(String state, SiteDiffEngine.SiteDiff diff) {
            if (diff == null) {
                return new DiffView(state, List.of(), List.of(), List.of(), 0, 0, 0);
            }
            return new DiffView(state,
                    diff.added().stream().limit(PREVIEW_LIMIT).toList(),
                    diff.removed().stream().limit(PREVIEW_LIMIT).toList(),
                    diff.changed().stream().limit(PREVIEW_LIMIT).toList(),
                    diff.added().size(), diff.removed().size(), diff.changed().size());
        }

        public int overflow() {
            // 三组各自溢出求和：任一组超限时「另有 N 条未显示」都足量报告，取 max 会少报
            return Math.max(0, addedCount - PREVIEW_LIMIT)
                    + Math.max(0, removedCount - PREVIEW_LIMIT)
                    + Math.max(0, changedCount - PREVIEW_LIMIT);
        }
    }

    private String mutate(RedirectAttributes redirect, String flashKey, String path, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("flash", flashKey);
        } catch (IllegalArgumentException | SecurityException e) {
            flashError(redirect, e);
        }
        return "redirect:" + path;
    }

    private String flashOutcome(RedirectAttributes redirect, Long siteId, String successKey, OutcomeSupplier action) {
        try {
            PushOutcome outcome = action.get();
            if (outcome.success()) {
                redirect.addFlashAttribute("flash", successKey);
            } else {
                redirect.addFlashAttribute("flashError", outcome.detail());
            }
        } catch (IllegalArgumentException | SecurityException e) {
            flashError(redirect, e);
        }
        return "redirect:" + detailPath(siteId);
    }

    /**
     * 失败提示进 flashError：带 message key 的校验异常存 key（占位符参数存 flashErrorArgs），
     * 由模板经 MessageSource 解析成当前语言；其余异常（爬虫安全策略、推送失败原因等原始文本）原样透出
     */
    private void flashError(RedirectAttributes redirect, Exception e) {
        if (e instanceof AutoSiteValidationException keyed) {
            redirect.addFlashAttribute("flashError", keyed.messageKey());
            if (keyed.args().length > 0) {
                redirect.addFlashAttribute("flashErrorArgs", keyed.args());
            }
            return;
        }
        redirect.addFlashAttribute("flashError", e.getMessage());
    }

    private String detailPath(Long id) {
        return "/auto/" + id;
    }

    /**
     * 读路径与写操作入口的归属校验：不存在与不属于当前用户统一按 404 透出
     */
    private AutoSite requireOwned(Long id, Long userId) {
        return autoSiteService.findOwned(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    record SiteRow(AutoSite site, AutoSiteVersion latest) {
    }

    @FunctionalInterface
    private interface OutcomeSupplier {
        PushOutcome get();
    }
}
