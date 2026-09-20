package io.github.ghgongjin.sitemap.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.ghgongjin.sitemap.entity.SeoReport;
import io.github.ghgongjin.sitemap.service.CrawlProgressService;
import io.github.ghgongjin.sitemap.service.CrawlProgressService.TaskResult;
import io.github.ghgongjin.sitemap.service.EnhancedSitemapGeneratorService;
import io.github.ghgongjin.sitemap.service.SeoReportService;
import io.github.ghgongjin.sitemap.service.SitemapEntryParser;
import io.github.ghgongjin.sitemap.service.SitemapGeneratorService;
import io.github.ghgongjin.sitemap.security.SecurityUtils;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName SitemapController
 * @Description 站点地图控制器（使用增强版服务）
 * @Author gj
 * @Date 2026/3/8
 * @Version 2.0
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class SitemapController {

    private final SitemapGeneratorService sitemapGeneratorService;
    private final EnhancedSitemapGeneratorService enhancedService;
    private final CrawlProgressService progressService;
    private final SeoReportService seoReportService;

    private ExecutorService crawlExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16),
            r -> {
                Thread t = new Thread(r, "crawl-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @PreDestroy
    public void shutdown() {
        crawlExecutor.shutdownNow();
    }

    /**
     * 首页
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("title", "站点地图生成器");
        model.addAttribute("description", "输入网址，一键生成站点地图XML文件");
        model.addAttribute("maxPages", EnhancedSitemapGeneratorService.MAX_PAGES);
        return "index";
    }

    /**
     * 生成站点地图
     */
    @PostMapping("/generate")
    public String generateSitemap(
            @RequestParam("url") String url,
            @RequestParam(value = "includeImages", defaultValue = "false") boolean includeImages,
            @RequestParam(value = "includeVideos", defaultValue = "false") boolean includeVideos,
            @RequestParam(value = "includeNews", defaultValue = "false") boolean includeNews,
            Model model,
            RedirectAttributes redirectAttributes) {
        
        try {
            log.info("收到生成请求: {}, images: {}, videos: {}, news: {}", url, includeImages, includeVideos, includeNews);
            
            // 获取网站统计信息
            Map<String, Object> stats = sitemapGeneratorService.getCrawlStats(url);
            
            // 生成站点地图
            String sitemapXml = sitemapGeneratorService.generateSitemap(url, includeImages, includeVideos, includeNews);
            
            // 准备模型数据
            model.addAttribute("url", url);
            model.addAttribute("sitemapXml", sitemapXml);
            model.addAttribute("stats", stats);
            model.addAttribute("includeImages", includeImages);
            model.addAttribute("includeVideos", includeVideos);
            model.addAttribute("includeNews", includeNews);
            model.addAttribute("success", true);
            
            // 计算文件大小
            int fileSize = sitemapXml.getBytes(StandardCharsets.UTF_8).length;
            model.addAttribute("fileSize", formatFileSize(fileSize));
            
            log.info("站点地图生成成功: {}, 大小: {}", url, formatFileSize(fileSize));
            
            return "result-fixed";
            
        } catch (Exception e) {
            log.error("生成站点地图失败: {}", e.getMessage(), e);
            
            redirectAttributes.addFlashAttribute("error", "生成失败: " + e.getMessage());
            redirectAttributes.addFlashAttribute("url", url);
            
            return "redirect:/";
        }
    }

    /**
     * 生成站点地图（异步，带进度追踪）
     */
    @PostMapping("/generate-async")
    public String generateSitemapAsync(
            @RequestParam("url") String url,
            @RequestParam(value = "includeImages", defaultValue = "false") boolean includeImages,
            @RequestParam(value = "includeVideos", defaultValue = "false") boolean includeVideos,
            @RequestParam(value = "includeNews", defaultValue = "false") boolean includeNews,
            Model model,
            RedirectAttributes redirectAttributes) {
        
        try {
            log.info("收到异步生成请求：{}, images: {}, videos: {}, news: {}", url, includeImages, includeVideos, includeNews);
            
            String taskId = UUID.randomUUID().toString();
            
            progressService.startTask(taskId, url, includeImages, includeVideos, includeNews);
            
            // 归属用户在请求线程内捕获：爬取线程池不会继承 SecurityContext，异步线程再取会是 null
            final Long ownerUserId = SecurityUtils.currentUserId();
            crawlExecutor.submit(() -> {
                try {
                    enhancedService.generateSitemapWithProgress(url, includeImages, includeVideos, includeNews, taskId);
                    seoReportService.save(taskId, url, ownerUserId);
                } catch (Exception e) {
                    log.error("异步爬取失败：{}", e.getMessage(), e);
                    progressService.failTask(taskId, e.getMessage());
                }
            });
            
            model.addAttribute("url", url);
            model.addAttribute("taskId", taskId);
            model.addAttribute("startedAt", System.currentTimeMillis());
            
            log.info("异步任务已启动：{}", taskId);
            
            return "result-progress";
            
        } catch (Exception e) {
            log.error("生成站点地图失败：{}", e.getMessage(), e);
            
            redirectAttributes.addFlashAttribute("error", "生成失败：" + e.getMessage());
            redirectAttributes.addFlashAttribute("url", url);
            
            return "redirect:/";
        }
    }

    /**
     * 进度页可刷新入口（POST /generate-async 的 GET 版本）
     */
    @GetMapping("/task/{taskId}")
    public String taskProgressPage(@PathVariable String taskId, Model model) {
        TaskResult result = progressService.getTaskResult(taskId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("url", result.getUrl());
        model.addAttribute("taskId", taskId);
        model.addAttribute("startedAt", result.getCreatedAt());
        model.addAttribute("maxPages", EnhancedSitemapGeneratorService.MAX_PAGES);
        return "result-progress";
    }

    /**
     * REST 进度查询（WebSocket 降级方案）
     */
    @GetMapping("/api/task/{taskId}/progress")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getTaskProgress(@PathVariable String taskId) {
        TaskResult result = progressService.getTaskResult(taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }

        CrawlProgressService.ProgressInfo info = progressService.getProgress(taskId);
        int cap = EnhancedSitemapGeneratorService.MAX_PAGES;
        int crawledPages = info != null ? info.getCrawledPages() : result.getCrawledPages();
        int percentage = "completed".equals(result.getStatus())
                ? 100
                : (int) Math.min(100, crawledPages * 100L / cap);

        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("taskId", result.getTaskId());
        progress.put("status", result.getStatus());
        progress.put("crawledPages", crawledPages);
        progress.put("totalPages", result.getTotalPages() > 0 ? result.getTotalPages() : cap);
        progress.put("percentage", percentage);
        progress.put("currentUrl", info != null ? info.getCurrentUrl() : "");
        progress.put("message", result.getStatus().equals("completed")
                ? "爬取完成！共找到 " + result.getTotalPages() + " 个 URL"
                : result.getStatus().equals("failed")
                    ? "爬取失败：" + result.getErrorMessage()
                    : "正在爬取...");
        return ResponseEntity.ok(progress);
    }

    /**
     * REST 结果获取（用于下载和预览复用缓存）
     */
    @GetMapping("/api/task/{taskId}/result")
    @ResponseBody
    public ResponseEntity<?> getTaskResult(@PathVariable String taskId) {
        TaskResult result = progressService.getTaskResult(taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        if ("running".equals(result.getStatus())) {
            return ResponseEntity.status(202).body(Map.of("status", "running"));
        }
        if ("failed".equals(result.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "failed",
                    "error", result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "sitemapXml", result.getSitemapXml() != null ? result.getSitemapXml() : "",
                "totalPages", result.getTotalPages()
        ));
    }

    /**
     * 下载站点地图文件
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadSitemap(
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "taskId", required = false) String taskId,
            @RequestParam(value = "includeImages", defaultValue = "false") boolean includeImages,
            @RequestParam(value = "includeVideos", defaultValue = "false") boolean includeVideos,
            @RequestParam(value = "includeNews", defaultValue = "false") boolean includeNews) {
        
        try {
            log.info("下载请求: url={}, taskId={}", url, taskId);

            TaskLookup lookup = lookupTask(taskId, url, includeImages, includeVideos, includeNews, true);
            if (lookup.errorStatus() != null) {
                return ResponseEntity.status(lookup.errorStatus()).build();
            }

            String sitemapXml = lookup.sitemapXml();
            if (sitemapXml == null) {
                sitemapXml = sitemapGeneratorService.generateSitemap(
                        lookup.url(), lookup.includeImages(), lookup.includeVideos(), lookup.includeNews());
            }
            url = lookup.url();
            
            byte[] xmlBytes = sitemapXml.getBytes(StandardCharsets.UTF_8);
            String domain = extractDomain(url);
            String filename = "sitemap-" + domain + ".xml";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(xmlBytes.length);
            
            return ResponseEntity.ok().headers(headers).body(xmlBytes);
            
        } catch (Exception e) {
            log.error("下载失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 预览站点地图
     */
    @GetMapping("/preview")
    public String previewSitemap(
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "taskId", required = false) String taskId,
            @RequestParam(value = "includeImages", defaultValue = "false") boolean includeImages,
            @RequestParam(value = "includeVideos", defaultValue = "false") boolean includeVideos,
            @RequestParam(value = "includeNews", defaultValue = "false") boolean includeNews,
            Model model) {
        
        try {
            log.info("预览请求: url={}, taskId={}", url, taskId);
            TaskLookup lookup = lookupTask(taskId, url, includeImages, includeVideos, includeNews, false);
            if (lookup.errorStatus() != null) {
                throw new ResponseStatusException(HttpStatus.valueOf(lookup.errorStatus()));
            }
            String sitemapXml = lookup.sitemapXml();
            if (sitemapXml == null) {
                sitemapXml = sitemapGeneratorService.generateSitemap(
                        lookup.url(), lookup.includeImages(), lookup.includeVideos(), lookup.includeNews());
            }
            SitemapEntryParser.SitemapEntries entries = SitemapEntryParser.parse(sitemapXml);
            model.addAttribute("url", lookup.url());
            model.addAttribute("sitemapXml", sitemapXml);
            model.addAttribute("taskId", lookup.taskId());
            model.addAttribute("includeImages", lookup.includeImages());
            model.addAttribute("includeVideos", lookup.includeVideos());
            model.addAttribute("includeNews", lookup.includeNews());
            model.addAttribute("urlEntries", entries.entries());
            model.addAttribute("urlCount", entries.entries().size());
            model.addAttribute("imageCount", entries.imageCount());
            model.addAttribute("videoCount", entries.videoCount());
            model.addAttribute("newsCount", entries.newsCount());
            model.addAttribute("lastmod", entries.lastmod());
            SeoReport seoReport = seoReportService.findByTaskId(lookup.taskId()).orElse(null);
            model.addAttribute("reportAvailable", seoReport != null);
            model.addAttribute("brokenLinks", seoReport == null ? 0 : seoReport.getBrokenLinks());
            model.addAttribute("skippedPages", seoReport == null ? 0 : seoReport.getSkippedPages());
            return "preview";
            
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("预览失败: {}", e.getMessage(), e);
            model.addAttribute("error", "预览失败: " + e.getMessage());
            return "error";
        }
    }

    /**
     * SEO 健康报告（仅归属用户可见；越权与不存在统一 404，不透露报告是否存在）
     */
    @GetMapping("/report/{taskId}")
    public String seoReport(@PathVariable String taskId, Model model) {
        SeoReport report = seoReportService.findOwned(taskId, SecurityUtils.currentUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("report", report);
        model.addAttribute("issues", seoReportService.parseIssues(report.getIssuesJson()));
        return "report";
    }

    @GetMapping("/report/{taskId}/export")
    public ResponseEntity<byte[]> exportSeoReport(@PathVariable String taskId,
                                                  @RequestParam(required = false) String format,
                                                  Locale locale)
            throws Exception {
        SeoReport report = seoReportService.findOwned(taskId, SecurityUtils.currentUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String fmt = (format == null) ? "csv" : format.toLowerCase(Locale.ROOT);
        String safeName = "seo-report-" + report.getTaskId().replaceAll("[^a-zA-Z0-9_-]", "_");

        return switch (fmt) {
            case "csv" -> {
                byte[] csv = seoReportService.exportCsv(report, locale);
                String filename = safeName + ".csv";
                yield ResponseEntity.ok()
                        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(filename).build().toString())
                        .contentLength(csv.length)
                        .body(csv);
            }
            case "pdf" -> {
                byte[] pdf = seoReportService.exportPdf(report, locale);
                String filename = safeName + ".pdf";
                yield ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(filename).build().toString())
                        .contentLength(pdf.length)
                        .body(pdf);
            }
            case "docx" -> {
                byte[] docx = seoReportService.exportDocx(report, locale);
                String filename = safeName + ".docx";
                yield ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(filename).build().toString())
                        .contentLength(docx.length)
                        .body(docx);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
    }

    /**
     * 历史 SEO 报告列表（仅列出本人归属的报告）
     */
    @GetMapping("/reports")
    public String seoReports(Model model) {
        model.addAttribute("reports", seoReportService.recent(SecurityUtils.currentUserId()));
        return "reports";
    }

    /**
     * 关于页面
     */
    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("title", "关于站点地图生成器");
        return "about";
    }

    /**
     * 帮助页面
     */
    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("title", "使用帮助");
        return "help";
    }

    private TaskLookup lookupTask(String taskId, String url, boolean includeImages, boolean includeVideos,
                                 boolean includeNews, boolean requireXml) {
        if (taskId != null) {
            if (taskId.isBlank()) {
                return TaskLookup.error(404);
            }
            TaskResult result = progressService.getTaskResult(taskId);
            if (result == null) {
                return TaskLookup.error(404);
            }
            if (!"completed".equals(result.getStatus())
                    || (requireXml && (result.getSitemapXml() == null || result.getSitemapXml().isEmpty()))
                    || (!requireXml && result.getSitemapXml() == null)) {
                return TaskLookup.error(409);
            }
            return new TaskLookup(taskId, result.getUrl(), result.isIncludeImages(), result.isIncludeVideos(),
                    result.isIncludeNews(), result.getSitemapXml(), null);
        }
        if (url == null || url.isBlank()) {
            return TaskLookup.error(400);
        }
        return new TaskLookup(null, url, includeImages, includeVideos, includeNews, null, null);
    }

    private record TaskLookup(String taskId, String url, boolean includeImages, boolean includeVideos,
                              boolean includeNews, String sitemapXml, Integer errorStatus) {
        static TaskLookup error(int status) {
            return new TaskLookup(null, null, false, false, false, null, status);
        }
    }

    /**
     * 提取域名
     */
    private String extractDomain(String url) {
        try {
            java.net.URL urlObj = new java.net.URL(url);
            String host = urlObj.getHost();
            // 移除www前缀
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            // 替换非字母数字字符
            return host.replaceAll("[^a-zA-Z0-9]", "-");
        } catch (Exception e) {
            return "website";
        }
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        }
    }
}