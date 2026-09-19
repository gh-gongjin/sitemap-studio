package io.github.ghgongjin.sitemap.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * @ClassName EnhancedSitemapGeneratorService
 * @Description 增强版站点地图生成服务（修复所有已知问题）
 * @Author gj
 * @Date 2026/3/8
 * @Version 2.0
 */
@Slf4j
public class EnhancedSitemapGeneratorService implements SitemapGeneratorService {

    // 增强配置参数（优化后，接近 xml-sitemaps.com）
  private static final int MAX_THREADS = 20;           // 增加并发线程数到 20
  private static final int MAX_DEPTH = 10;             // 增加爬取深度到 10
  public static final int MAX_PAGES = 1000;            // 最大页面数 1000
  private static final int TIMEOUT_MS = 30000;         // 增加超时时间到 30 秒
  private static final int MAX_RETRIES = 3;            // 添加重试机制
  private static final int QUEUE_POLL_TIMEOUT_MS = 500; // 队列轮询超时时间
  private static final int CRAWL_DELAY_MS = 500;       // 同主机请求间隔（毫秒）
  private static final int VIDEO_TITLE_MAX = 100;      // video:title 字符上限（Google 规范）
  private static final int VIDEO_DESCRIPTION_MAX = 2048; // video:description 字符上限（Google 规范）
  private static final long NEWS_MAX_AGE_HOURS = 48;   // 新闻条目仅收录最近 48 小时内发布的文章（Google 规范）
    
    // 每个主机的最后请求时间（用于速率限制）
    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();
    
    /**
     * 页面数据类，存储URL及其关联的媒体信息
     */
    private static class PageData {
        final String url;
        final Set<String> images = Collections.synchronizedSet(new LinkedHashSet<>());
        final Map<String, VideoEntry> videos = Collections.synchronizedMap(new LinkedHashMap<>());
        volatile NewsEntry news;
        
        PageData(String url) {
            this.url = url;
        }
        
        void addImage(String imageUrl) {
            if (imageUrl != null && !imageUrl.trim().isEmpty()) {
                images.add(imageUrl.trim());
            }
        }
        
        void addVideo(VideoEntry entry) {
            if (entry == null || entry.url == null || entry.url.isBlank()) {
                return;
            }
            synchronized (videos) {
                VideoEntry existing = videos.get(entry.url);
                if (existing == null) {
                    videos.put(entry.url, entry);
                } else {
                    existing.fillMissingFrom(entry);
                }
            }
        }
    }
    
    /**
     * 视频条目：Google 视频站点地图要求 thumbnail_loc、title、description 三个必填字段，
     * 缺字段的条目会被搜索引擎拒绝，因此仅输出 isComplete() 的条目。
     */
    private static class VideoEntry {
        final String url;
        final boolean embed;   // true=播放器页面（player_loc），false=视频文件（content_loc）
        String thumbnail;
        String title;
        String description;
        
        VideoEntry(String url, boolean embed) {
            this.url = url;
            this.embed = embed;
        }
        
        void fillMissingFrom(VideoEntry other) {
            if (thumbnail == null) thumbnail = other.thumbnail;
            if (title == null) title = other.title;
            if (description == null) description = other.description;
        }
        
        boolean isComplete() {
            return thumbnail != null && !thumbnail.isBlank()
                    && title != null && !title.isBlank()
                    && description != null && !description.isBlank();
        }
    }
    
    /**
     * 新闻条目：Google 新闻站点地图要求 publication(name/language)、publication_date、title。
     */
    private static class NewsEntry {
        final String title;
        final String publicationName;
        final String publicationLanguage;
        final Instant publicationDate;
        
        NewsEntry(String title, String publicationName, String publicationLanguage, Instant publicationDate) {
            this.title = title;
            this.publicationName = publicationName;
            this.publicationLanguage = publicationLanguage;
            this.publicationDate = publicationDate;
        }
    }
    
    // 进度追踪服务（可选）
  private CrawlProgressService progressService;
    
    public void setProgressService(CrawlProgressService progressService) {
        this.progressService = progressService;
    }
    
    // 页面渲染器（可选，用于 JS 动态页面）
    private PageRenderer pageRenderer;
    
    // 智能渲染选择器
    private SmartRendererSelector rendererSelector;
    
    public void setPageRenderer(PageRenderer pageRenderer) {
        this.pageRenderer = pageRenderer;
    }
    
    public void setRendererSelector(SmartRendererSelector rendererSelector) {
        this.rendererSelector = rendererSelector;
    }
    
    // 代理池（可选）
    private ProxyPool proxyPool;
    private CrawlUrlPolicy crawlUrlPolicy = new CrawlUrlPolicy();
    private SafeHttpFetcher safeHttpFetcher = new SafeHttpFetcher(crawlUrlPolicy);
    
    public void setProxyPool(ProxyPool proxyPool) {
        this.proxyPool = proxyPool;
    }

    public void setCrawlUrlPolicy(CrawlUrlPolicy crawlUrlPolicy) {
        this.crawlUrlPolicy = crawlUrlPolicy;
        this.safeHttpFetcher = new SafeHttpFetcher(crawlUrlPolicy);
    }

    public void setSafeHttpFetcher(SafeHttpFetcher safeHttpFetcher) {
        this.safeHttpFetcher = safeHttpFetcher;
    }
    
    // SEO 健康度采集（可选，未注入时不影响爬取）
    private SeoAuditService seoAuditService;
    
    public void setSeoAuditService(SeoAuditService seoAuditService) {
        this.seoAuditService = seoAuditService;
    }
    
    // 更真实的User-Agent
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    
    // 排除的文件扩展名
    private static final Set<String> EXCLUDED_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg", ".ico", ".tiff", ".tif", ".avif",
            ".css", ".js", ".mjs", ".woff", ".woff2", ".ttf", ".eot", ".otf",
            ".mp4", ".avi", ".mov", ".wmv", ".flv", ".webm", ".mkv", ".m4v",
            ".mp3", ".wav", ".ogg", ".flac", ".aac", ".wma",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods",
            ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz",
            ".exe", ".dmg", ".apk", ".msi", ".deb", ".rpm",
            ".json", ".xml", ".rss", ".atom", ".csv", ".tsv",
            ".swf", ".fla"
    ));
    
    // 排除的关键词（精简版，避免过度过滤）
   private static final Set<String> EXCLUDED_KEYWORDS = new HashSet<>(Arrays.asList(
            "login", "logout", "signin", "signup", "register",
            "admin", "dashboard", "wp-admin", "wp-login",
            "cart", "checkout", "wishlist", "compare",
            "api/", "feed", "rss", "xmlrpc",
            "tag/", "author/"
    ));
    
    // 包含的关键词（确保爬取重要页面）
    private static final Set<String> INCLUDED_KEYWORDS = new HashSet<>(Arrays.asList(
            "channel", "video", "download", "tool",
            "about", "contact", "faq", "help", "support",
            "privacy", "terms", "policy", "legal",
            "blog", "news", "article", "update"
    ));
    
    // 分页模式识别（宽松版，允许分页内容）
   private static final Pattern PAGINATION_PATTERN = Pattern.compile(
            ".*(session_id|csrf_token|auth_token|access_token|nonce|secret_key)=.+",
            Pattern.CASE_INSENSITIVE
    );
    
    @Override
   public String generateSitemap(String url, boolean includeImages, boolean includeVideos) {
    return generateSitemapWithProgress(url, includeImages, includeVideos, false, null);
    }
    
    @Override
   public String generateSitemap(String url, boolean includeImages, boolean includeVideos, boolean includeNews) {
    return generateSitemapWithProgress(url, includeImages, includeVideos, includeNews, null);
    }
    
    /**
     * 生成站点地图（支持进度追踪）
     */
   public String generateSitemapWithProgress(String url, boolean includeImages, 
                                         boolean includeVideos, String taskId) {
    return generateSitemapWithProgress(url, includeImages, includeVideos, false, taskId);
    }
    
    /**
     * 生成站点地图（支持进度追踪与新闻条目）
     */
   public String generateSitemapWithProgress(String url, boolean includeImages, 
                                         boolean includeVideos, boolean includeNews, String taskId) {
      log.info("开始增强爬取：{}, taskId: {}", url, taskId);
        
        try {
            java.net.URI validatedUri = crawlUrlPolicy.validate(url);
           String baseUrl = validatedUri.getScheme() + "://" + validatedUri.getHost()
                   + (validatedUri.getPort() == -1 ? "" : ":" + validatedUri.getPort());
            
            // 解析 robots.txt
           RobotsTxtParser robotsParser = new RobotsTxtParser();
           robotsParser.parse(baseUrl, crawlUrlPolicy, safeHttpFetcher, TIMEOUT_MS);
           log.info("robots.txt 解析完成，发现 {} 个 Sitemap URL", robotsParser.getSitemapUrls().size());
            
            // 解析 sitemap.xml 发现 URL
           SitemapXmlParser sitemapParser = new SitemapXmlParser();
           Map<String, String> rawSitemapUrls = sitemapParser.parseAll(
                   baseUrl, robotsParser.getSitemapUrls(), crawlUrlPolicy, safeHttpFetcher, TIMEOUT_MS);
           Map<String, String> sitemapDiscoveredUrls = new ConcurrentHashMap<>();
           for (Map.Entry<String, String> e : rawSitemapUrls.entrySet()) {
               if (crawlUrlPolicy.isAllowed(e.getKey(), baseUrl)) {
                   sitemapDiscoveredUrls.put(normalizeUrl(e.getKey()), e.getValue());
               }
           }
           log.info("sitemap.xml 解析完成，发现 {} 个 URL", sitemapDiscoveredUrls.size());
            
            // 执行增强爬取（带进度追踪，遵循 robots.txt 规则，预填充 sitemap 发现的 URL）
           Map<String, PageData> crawledPages = enhancedCrawlWithProgress(url, baseUrl, taskId, includeImages, includeVideos, includeNews, robotsParser, sitemapDiscoveredUrls);
            
            // 生成站点地图 XML
           String sitemapXml = generateSitemapXml(crawledPages, baseUrl, includeImages, includeVideos, includeNews, sitemapDiscoveredUrls);
            
           // 发送完成消息（传入 XML 结果供缓存）
        if (progressService != null && taskId != null) {
            progressService.completeTask(taskId, crawledPages.size(), sitemapXml);
           }
            
        return sitemapXml;
            
        } catch (Exception e) {
           log.error("增强爬取失败：{}", e.getMessage(), e);
            
           // 发送失败消息
        if (progressService != null && taskId != null) {
            progressService.failTask(taskId, e.getMessage());
           }
            
            // 抛出异常而不是回退到简单版本
            throw new RuntimeException("站点地图生成失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, Object> getCrawlStats(String url) {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // 提取域名
            URL parsedUrl = new URL(url);
            String domain = parsedUrl.getHost();
            
            // 基础信息
            stats.put("domain", domain);
            stats.put("url", url);
            stats.put("service", "EnhancedSitemapGeneratorService");
            stats.put("version", "2.0");
            stats.put("realCrawl", true);
            
            // 页面统计信息（模板需要的字段）
            stats.put("title", "站点地图生成器 - " + domain);
            stats.put("pageLinks", 0); // 实际爬取后会更新
            stats.put("pageImages", 0); // 实际爬取后会更新
            stats.put("description", "增强版站点地图生成器");
            stats.put("keywords", "sitemap, xml, seo, enhanced");
            
            // 特性列表
            stats.put("features", Arrays.asList(
                    "深度爬取（10 层）",
                    "多线程并发（20 线程）",
                    "重试机制（3 次重试）",
                    "智能 URL 过滤",
                    "分页识别",
                    "增强 User-Agent"
            ));
            
            // 配置信息
            Map<String, Object> config = new HashMap<>();
            config.put("maxThreads", MAX_THREADS);
            config.put("maxDepth", MAX_DEPTH);
            config.put("maxPages", MAX_PAGES);
            config.put("timeoutMs", TIMEOUT_MS);
            config.put("maxRetries", MAX_RETRIES);
            stats.put("config", config);
            
        } catch (Exception e) {
            // 如果URL解析失败，提供默认值
            stats.put("domain", "unknown");
            stats.put("url", url);
            stats.put("service", "EnhancedSitemapGeneratorService");
            stats.put("version", "2.0");
            stats.put("realCrawl", false);
            stats.put("title", "站点地图生成器");
            stats.put("pageLinks", 0);
            stats.put("pageImages", 0);
            stats.put("description", "自动生成的站点地图");
            stats.put("keywords", "sitemap, xml, seo");
        }
        
        return stats;
    }
    
    /**
     * 增强爬取方法（带进度追踪）
     */
  private Map<String, PageData> enhancedCrawlWithProgress(String startUrl, String baseUrl, String taskId,
                                                            boolean includeImages, boolean includeVideos,
                                                            boolean includeNews,
                                                            RobotsTxtParser robotsParser,
                                                            Map<String, String> sitemapDiscoveredUrls) {
       Map<String, PageData> crawledPages = new ConcurrentHashMap<>();
       Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());
       lastRequestTime.clear();
        
       ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
       BlockingQueue<CrawlTask> queue = new LinkedBlockingQueue<>();
       AtomicInteger activeTasks = new AtomicInteger(0);
       AtomicInteger processedCount = new AtomicInteger(0);
        
        // 发送开始消息
     if (progressService != null && taskId != null) {
         progressService.startTask(taskId, startUrl);
       }

        // SEO 健康度采集开始
        if (seoAuditService != null && taskId != null) {
            seoAuditService.beginAudit(taskId);
        }
        
        // 添加初始任务
       queue.offer(new CrawlTask(normalizeUrl(startUrl), 0));
        
        // 预填充 sitemap.xml 中发现的 URL（深度为 1，优先级高于普通链接发现）
       if (sitemapDiscoveredUrls != null && !sitemapDiscoveredUrls.isEmpty()) {
           int seeded = 0;
           String normalizedStart = normalizeUrl(startUrl);
           for (Map.Entry<String, String> sitemapEntry : sitemapDiscoveredUrls.entrySet()) {
               String sitemapUrl = sitemapEntry.getKey();
               if (!sitemapUrl.equals(normalizedStart)) {
                   queue.offer(new CrawlTask(sitemapUrl, 1));
                   seeded++;
               }
           }
           log.info("从 sitemap.xml 预填充 {} 个 URL 到爬取队列", seeded);
       }
        
       try {
           // 主循环：当队列不为空或还有活跃任务时继续
          while (!queue.isEmpty() || activeTasks.get() > 0) {
               if (crawledPages.size() >= MAX_PAGES) {
                  log.info("达到最大页面数限制：{}", MAX_PAGES);
                 break;
               }
                
               CrawlTask task = queue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
             if (task == null) {
                   // 队列为空但可能还有活跃任务在处理中，继续等待
                  continue;
               }
                
               activeTasks.incrementAndGet();
                
               executor.submit(() -> {
                   try {
                      processCrawlTaskWithProgress(task, baseUrl, crawledPages, 
                                                   visitedUrls, queue, taskId, 
                                                  processedCount.incrementAndGet(),
                                                  includeImages, includeVideos, includeNews,
                                                  robotsParser);
                   } catch (Exception e) {
                      log.error("处理爬取任务异常：{} - {}", task.url, e.getMessage(), e);
                   } finally {
                       activeTasks.decrementAndGet();
                   }
               });
           }
            
           // 优雅关闭线程池
          executor.shutdown();
           try {
              if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                  log.warn("线程池未能在60秒内完成，强制关闭");
                 executor.shutdownNow();
               }
           } catch (InterruptedException e) {
              log.warn("等待线程池关闭时被中断");
              executor.shutdownNow();
             Thread.currentThread().interrupt();
           }
            
        } catch (InterruptedException e) {
           log.error("爬取被中断", e);
          executor.shutdownNow();
           Thread.currentThread().interrupt();
        } catch (Exception e) {
           log.error("爬取过程中发生异常", e);
          executor.shutdownNow();
           throw e;
        }
        
       log.info("增强爬取完成，找到 {} 个页面", crawledPages.size());
     return crawledPages;
    }
    
    /**
     * 处理爬取任务（带进度追踪）
     */
  private void processCrawlTaskWithProgress(CrawlTask task, String baseUrl, 
                                           Map<String, PageData> crawledPages, Set<String> visitedUrls,
                                           BlockingQueue<CrawlTask> queue,
                                          String taskId, int taskNumber,
                                          boolean includeImages, boolean includeVideos, boolean includeNews,
                                          RobotsTxtParser robotsParser) {
        
     if (task.depth > MAX_DEPTH) return;
     if (!visitedUrls.add(task.url)) return;
        
        // robots.txt 规则检查
     if (robotsParser != null && !robotsParser.isAllowed(task.url)) {
         log.debug("robots.txt 禁止爬取: {}", task.url);
         return;
     }
        
       log.debug("爬取：{} (深度：{})", task.url, task.depth);
        
        // 更新进度
    if (progressService != null && taskId != null) {
        progressService.updateProgress(taskId, crawledPages.size() + 1, MAX_PAGES, task.url);
       }
        
       int retryCount = 0;
        
        // 速率限制：同主机请求间隔
        try {
            URL taskUrl = new URL(task.url);
            String host = taskUrl.getHost();
            long now = System.currentTimeMillis();
            Long lastTime = lastRequestTime.get(host);
            if (lastTime != null) {
                long elapsed = now - lastTime;
                if (elapsed < CRAWL_DELAY_MS) {
                    Thread.sleep(CRAWL_DELAY_MS - elapsed);
                }
            }
            lastRequestTime.put(host, System.currentTimeMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            // 忽略速率限制错误
        }
        
        // 重试机制
      while (retryCount <= MAX_RETRIES) {
           // 获取代理
           ProxyPool.ProxyInfo currentProxy = proxyPool != null ? proxyPool.getNextProxyInfo() : null;
           java.net.Proxy proxy = currentProxy != null ? currentProxy.toProxy() : null;
           
           try {
               long fetchStarted = System.currentTimeMillis();
               SafeHttpFetcher.Response response = safeHttpFetcher.fetch(task.url, baseUrl, TIMEOUT_MS, currentProxy);
               long fetchElapsed = System.currentTimeMillis() - fetchStarted;
                
               int statusCode = response.statusCode();
               
               // 只收录 2xx 成功的页面
               if (statusCode < 200 || statusCode >= 300) {
                   log.debug("跳过非成功状态码 {}：{}", statusCode, task.url);
                   if (seoAuditService != null && taskId != null) {
                       seoAuditService.recordBroken(taskId, task.url, statusCode);
                   }
                   return;
               }
               
               // 检查 Content-Type，只处理 HTML 页面
               String contentType = response.contentType();
               if (contentType != null && !contentType.contains("text/html") && 
                   !contentType.contains("application/xhtml") && !contentType.contains("application/xml")) {
                   log.debug("跳过非 HTML 内容类型 {}：{}", contentType, task.url);
                   return;
               }
               
               Document doc = response.parse();
               
               if (doc != null) {
                   // 检查是否需要 JS 渲染（SPA 检测）
                   if (pageRenderer != null && pageRenderer.isAvailable() && 
                       rendererSelector != null && rendererSelector.needsRendering(doc.html(), pageRenderer)) {
                       log.debug("检测到 SPA 页面，使用渲染器：{}", task.url);
                       try {
                           String renderedHtml = pageRenderer.render(response.url());
                           doc = Jsoup.parse(renderedHtml, response.url());
                       } catch (Exception e) {
                           log.warn("渲染失败，使用原始 HTML：{} - {}", task.url, e.getMessage());
                       }
                   }

                   if (!hasRobotsDirective(doc, "nofollow")) {
                       extractAndQueueLinks(doc, response.url(), baseUrl, task.depth, queue, robotsParser);
                   }

                   // 检查 noindex 标记
                   if (hasRobotsDirective(doc, "noindex")) {
                       log.debug("跳过 noindex 页面：{}", task.url);
                       if (seoAuditService != null && taskId != null) {
                           seoAuditService.recordPage(taskId, collectSeoData(doc, normalizeUrl(response.url()),
                                   statusCode, fetchElapsed, true));
                       }
                       return;
                   }

                   // 检查 canonical URL
                   String pageUrl = normalizeUrl(response.url());
                   Element canonical = doc.selectFirst("link[rel=canonical]");
                   if (canonical != null) {
                       String canonicalHref = canonical.attr("abs:href");
                       if (!canonicalHref.isEmpty()) {
                           String normalizedCanonical = normalizeUrl(canonicalHref);
                           if (isEnhancedValidUrl(normalizedCanonical, baseUrl, robotsParser)) {
                               pageUrl = normalizedCanonical;
                           }
                       }
                   }

                   // 创建页面数据
                  PageData pageData = new PageData(pageUrl);
                   
                   // 提取媒体内容
                  if (includeImages) {
                      extractImages(doc, task.url, baseUrl, pageData);
                   }
                  if (includeVideos) {
                      extractVideos(doc, task.url, baseUrl, pageData);
                   }
                  if (includeNews) {
                      extractNews(doc, task.url, baseUrl, pageData);
                   }
                   
                   // 存储页面数据
                  crawledPages.put(pageUrl, pageData);

                   // SEO 健康度采集
                   if (seoAuditService != null && taskId != null) {
                       seoAuditService.recordPage(taskId, collectSeoData(doc, pageUrl, statusCode, fetchElapsed, false));
                   }
               }
               
               // 标记代理成功
               if (proxyPool != null && currentProxy != null) {
                   proxyPool.markSuccess(currentProxy);
               }
               break;
                
           } catch (IOException e) {
              // 标记代理失败
              if (proxyPool != null && currentProxy != null) {
                  proxyPool.markFailure(currentProxy);
              }
              
              retryCount++;
            if (retryCount <= MAX_RETRIES) {
                  log.warn("爬取失败，第 {} 次重试：{} - {}", retryCount, task.url, e.getMessage());
                 try {
                     Thread.sleep(1000 * retryCount); // 指数退避
                 } catch (InterruptedException ie) {
                     Thread.currentThread().interrupt();
                   return;
                   }
               } else {
                  log.error("爬取失败，已达到最大重试次数：{} - {}", task.url, e.getMessage());
                  if (seoAuditService != null && taskId != null) {
                      seoAuditService.recordUnreachable(taskId, task.url, e.getMessage());
                  }
               }
           }
       }
    }
    
    /**
     * 采集单页 SEO 数据（标题、描述、H1、canonical、图片 alt 等）
     */
    private SeoAuditService.PageSeo collectSeoData(Document doc, String pageUrl, int statusCode,
                                                   long elapsedMs, boolean noindex) {
        String title = doc.title() != null ? doc.title().trim() : "";
        Element metaDesc = doc.selectFirst("meta[name=description]");
        String description = metaDesc != null ? metaDesc.attr("content").trim() : "";
        int h1Count = doc.select("h1").size();
        Element canonical = doc.selectFirst("link[rel=canonical]");
        String canonicalHref = canonical != null ? canonical.attr("abs:href") : "";

        Elements images = doc.select("img");
        int imagesTotal = images.size();
        int imagesMissingAlt = 0;
        for (Element img : images) {
            if (img.attr("alt").trim().isEmpty()) {
                imagesMissingAlt++;
            }
        }

        return new SeoAuditService.PageSeo(pageUrl, statusCode, elapsedMs, title, description,
                h1Count, canonicalHref, noindex, imagesTotal, imagesMissingAlt);
    }

    /**
     * 从页面提取图片URL
     */
    private void extractImages(Document doc, String pageUrl, String baseUrl, PageData pageData) {
        try {
            URL pageUrlObj = new URL(pageUrl);
            
            // 提取 <img> 标签的 src 和 data-src
            Elements images = doc.select("img[src], img[data-src]");
            for (Element img : images) {
                String src = img.attr("src");
                if (src.isEmpty()) {
                    src = img.attr("data-src");
                }
                String absoluteUrl = resolveMediaUrl(src, pageUrlObj, baseUrl);
                if (absoluteUrl != null && isImageUrl(absoluteUrl)) {
                    pageData.addImage(absoluteUrl);
                }
            }
            
            // 提取 <picture> 标签中的 <source>
            Elements sources = doc.select("picture source[srcset]");
            for (Element source : sources) {
                String srcset = source.attr("srcset");
                if (!srcset.isEmpty()) {
                    // srcset 可能包含多个URL，取第一个
                    String[] parts = srcset.split(",");
                    if (parts.length > 0) {
                        String firstUrl = parts[0].trim().split("\\s+")[0];
                        String absoluteUrl = resolveMediaUrl(firstUrl, pageUrlObj, baseUrl);
                        if (absoluteUrl != null && isImageUrl(absoluteUrl)) {
                            pageData.addImage(absoluteUrl);
                        }
                    }
                }
            }
            
            // 提取 Open Graph 图片
            Elements ogImages = doc.select("meta[property=og:image]");
            for (Element meta : ogImages) {
                String content = meta.attr("content");
                String absoluteUrl = resolveMediaUrl(content, pageUrlObj, baseUrl);
                if (absoluteUrl != null) {
                    pageData.addImage(absoluteUrl);
                }
            }
            
        } catch (Exception e) {
            log.debug("提取图片失败: {} - {}", pageUrl, e.getMessage());
        }
    }
    
    /**
     * 从页面提取视频，并为 Google 必填字段收集页面级兜底数据（均来自当前页面，不构造数据）
     */
    private void extractVideos(Document doc, String pageUrl, String baseUrl, PageData pageData) {
        try {
            URL pageUrlObj = new URL(pageUrl);
            
            String videoTitle = firstNonBlank(
                    metaContent(doc, "meta[property=og:title]"), doc.title());
            String videoDescription = firstNonBlank(
                    metaContent(doc, "meta[name=description]"),
                    metaContent(doc, "meta[property=og:description]"));
            String thumbnailFallback = resolveMediaUrl(firstNonBlank(
                    metaContent(doc, "meta[property=og:video:image]"),
                    metaContent(doc, "meta[property=og:image]")), pageUrlObj, baseUrl);
            
            // 提取 <video> 标签的 src（视频文件）
            Elements videos = doc.select("video[src]");
            for (Element video : videos) {
                String src = video.attr("src");
                String absoluteUrl = resolveMediaUrl(src, pageUrlObj, baseUrl);
                if (absoluteUrl != null && isVideoUrl(absoluteUrl)) {
                    pageData.addVideo(buildVideoEntry(absoluteUrl, false,
                            resolveMediaUrl(video.attr("poster"), pageUrlObj, baseUrl), thumbnailFallback,
                            firstNonBlank(video.attr("title"), videoTitle), videoDescription));
                }
            }
            
            // 提取 <video> 标签内的 <source>（视频文件）
            Elements videoSources = doc.select("video source[src]");
            for (Element source : videoSources) {
                String src = source.attr("src");
                String absoluteUrl = resolveMediaUrl(src, pageUrlObj, baseUrl);
                if (absoluteUrl != null && isVideoUrl(absoluteUrl)) {
                    Element parentVideo = source.parent();
                    String poster = parentVideo != null
                            ? resolveMediaUrl(parentVideo.attr("poster"), pageUrlObj, baseUrl) : null;
                    String title = parentVideo != null ? parentVideo.attr("title") : null;
                    pageData.addVideo(buildVideoEntry(absoluteUrl, false, poster, thumbnailFallback,
                            firstNonBlank(title, videoTitle), videoDescription));
                }
            }
            
            // 提取 <iframe> 中的视频嵌入（YouTube, Vimeo 等，播放器页面）
            Elements iframes = doc.select("iframe[src]");
            for (Element iframe : iframes) {
                String src = iframe.attr("src");
                if (isVideoEmbed(src)) {
                    String absoluteUrl = resolveMediaUrl(src, pageUrlObj, baseUrl);
                    if (absoluteUrl != null) {
                        pageData.addVideo(buildVideoEntry(absoluteUrl, true, null, thumbnailFallback,
                                firstNonBlank(iframe.attr("title"), videoTitle), videoDescription));
                    }
                }
            }
            
            // 提取 Open Graph 视频
            Elements ogVideos = doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]");
            for (Element meta : ogVideos) {
                String content = meta.attr("content");
                String absoluteUrl = resolveMediaUrl(content, pageUrlObj, baseUrl);
                if (absoluteUrl != null) {
                    pageData.addVideo(buildVideoEntry(absoluteUrl, !isVideoUrl(absoluteUrl), null,
                            thumbnailFallback, videoTitle, videoDescription));
                }
            }
            
        } catch (Exception e) {
            log.debug("提取视频失败: {} - {}", pageUrl, e.getMessage());
        }
    }
    
    private VideoEntry buildVideoEntry(String url, boolean embed, String ownThumbnail, String fallbackThumbnail,
                                       String title, String description) {
        VideoEntry entry = new VideoEntry(url, embed);
        entry.thumbnail = firstNonBlank(ownThumbnail, fallbackThumbnail);
        entry.title = truncate(firstNonBlank(title), VIDEO_TITLE_MAX);
        entry.description = truncate(firstNonBlank(description), VIDEO_DESCRIPTION_MAX);
        return entry;
    }
    
    private String metaContent(Document doc, String selector) {
        Element element = doc.selectFirst(selector);
        return element != null ? element.attr("content") : null;
    }
    
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
    
    /** 截断到 Google 视频字段上限（title 100 字符、description 2048 字符） */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
    
    /**
     * 从文章页提取新闻条目：识别 og:type=article 或 article:published_time，
     * 仅保留最近 48 小时内发布、且标题/语种/出版方齐备的文章（字段缺失时不生成，避免不实数据）
     */
    private void extractNews(Document doc, String pageUrl, String baseUrl, PageData pageData) {
        try {
            String ogType = metaContent(doc, "meta[property=og:type]");
            String publishedRaw = firstNonBlank(
                    metaContent(doc, "meta[property=article:published_time]"),
                    metaContent(doc, "meta[name=article:published_time]"));
            boolean articleLike = (ogType != null && ogType.toLowerCase().contains("article"))
                    || publishedRaw != null;
            if (!articleLike) {
                return;
            }
            
            Instant published = parsePublishedInstant(publishedRaw);
            if (published == null
                    || published.isBefore(Instant.now().minusSeconds(NEWS_MAX_AGE_HOURS * 3600))) {
                return;
            }
            
            String title = firstNonBlank(metaContent(doc, "meta[property=og:title]"), doc.title());
            String language = publicationLanguage(doc);
            String publicationName = firstNonBlank(metaContent(doc, "meta[property=og:site_name]"), hostOf(baseUrl));
            if (title == null || language == null || publicationName == null) {
                return;
            }
            
            pageData.news = new NewsEntry(title, publicationName, language, published);
        } catch (Exception e) {
            log.debug("提取新闻信息失败: {} - {}", pageUrl, e.getMessage());
        }
    }
    
    private static Instant parsePublishedInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            // 继续尝试其他格式
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            // 继续尝试其他格式
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }
    
    private String publicationLanguage(Document doc) {
        Element html = doc.selectFirst("html[lang]");
        if (html == null) {
            return null;
        }
        String lang = html.attr("lang").trim().toLowerCase();
        if (lang.isEmpty()) {
            return null;
        }
        String code = lang.split("[-_]")[0];
        return code.matches("[a-z]{2}") ? code : null;
    }
    
    private static String hostOf(String baseUrl) {
        try {
            return new URL(baseUrl).getHost();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 解析媒体URL为绝对URL
     */
    private String resolveMediaUrl(String url, URL pageUrl, String baseUrl) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        
        url = url.trim();
        
        // 跳过 data URI 和 javascript:
        if (url.startsWith("data:") || url.startsWith("javascript:")) {
            return null;
        }
        
        try {
            // 如果是绝对URL，直接返回
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
            }
            
            // 解析相对URL
            URL resolved = new URL(pageUrl, url);
            return resolved.toString();
            
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 检查URL是否为图片
     */
    private boolean isImageUrl(String url) {
        String lower = url.toLowerCase();
        // 检查常见图片扩展名
        return lower.contains(".jpg") || lower.contains(".jpeg") || 
               lower.contains(".png") || lower.contains(".gif") || 
               lower.contains(".webp") || lower.contains(".svg") ||
               lower.contains(".bmp") || lower.contains(".ico") ||
               // 也接受没有扩展名但来自img标签的URL
               !lower.contains(".");
    }
    
    /**
     * 检查URL是否为视频
     */
    private boolean isVideoUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".webm") || 
               lower.contains(".ogg") || lower.contains(".mov") ||
               lower.contains(".avi") || lower.contains(".flv");
    }
    
    /**
     * 检查URL是否为视频嵌入
     */
    private boolean isVideoEmbed(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be") ||
               lower.contains("vimeo.com") || lower.contains("dailymotion.com") ||
               lower.contains("youku.com") || lower.contains("iqiyi.com") ||
               lower.contains("bilibili.com") || lower.contains("tudou.com");
    }
    
    private boolean hasRobotsDirective(Document doc, String directive) {
        for (Element meta : doc.select("meta[name=robots]")) {
            for (String token : meta.attr("content").toLowerCase(Locale.ROOT).split("[,\\s]+")) {
                if (token.equals(directive) || token.equals("none")) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 提取并排队链接
     */
    private void extractAndQueueLinks(Document doc, String currentPageUrl, String baseUrl,
                                      int currentDepth, BlockingQueue<CrawlTask> queue,
                                      RobotsTxtParser robotsParser) {

        Set<String> collectedUrls = new LinkedHashSet<>();

        // 标准 <a href> 链接
        Elements links = doc.select("a[href]");
        for (Element link : links) {
            collectedUrls.add(link.attr("abs:href"));
        }

        // <area href> 图片映射链接
        Elements areas = doc.select("area[href]");
        for (Element area : areas) {
            collectedUrls.add(area.attr("abs:href"));
        }

        // data-href / data-url / data-link 属性（懒加载链接）
        for (String attr : new String[]{"data-href", "data-url", "data-link"}) {
            Elements dataLinks = doc.select("[" + attr + "]");
            for (Element el : dataLinks) {
                String val = el.attr(attr);
                if (!val.isEmpty() && (val.startsWith("http") || val.startsWith("/"))) {
                    collectedUrls.add(val);
                }
            }
        }

        for (String rawUrl : collectedUrls) {
            if (rawUrl == null || rawUrl.isEmpty() || rawUrl.startsWith("javascript:") || rawUrl.startsWith("mailto:") || rawUrl.startsWith("tel:")) {
                continue;
            }

            try {
                URL url = new URL(new URL(currentPageUrl), rawUrl);
                String fullUrl = normalizeUrl(url.toString());

                if (isEnhancedValidUrl(fullUrl, baseUrl, robotsParser)) {
                    boolean isImportant = isImportantUrl(fullUrl);

                    int nextDepth = currentDepth + 1;
                    if (isImportant && nextDepth <= MAX_DEPTH + 1) {
                        queue.offer(new CrawlTask(fullUrl, nextDepth));
                    } else if (nextDepth <= MAX_DEPTH) {
                        queue.offer(new CrawlTask(fullUrl, nextDepth));
                    }
                }

            } catch (Exception e) {
                // 忽略无效URL
            }
        }
    }
    
    /**
     * 增强URL验证
     */
    private boolean isEnhancedValidUrl(String url, String baseUrl, RobotsTxtParser robotsParser) {
        try {
            if (!crawlUrlPolicy.isAllowed(url, baseUrl)) {
                return false;
            }
            URL parsedUrl = new URL(url);
            
            // URL 长度限制（过长的 URL 通常是自动生成的）
            if (url.length() > 500) {
                return false;
            }
            
            // robots.txt 规则检查
            if (robotsParser != null && !robotsParser.isAllowed(url)) {
                log.debug("robots.txt 禁止爬取: {}", url);
                return false;
            }
            
            String path = parsedUrl.getPath().toLowerCase();
            String query = parsedUrl.getQuery();
            
            // 排除静态资源
            for (String ext : EXCLUDED_EXTENSIONS) {
                if (path.endsWith(ext)) {
                    return false;
                }
            }
            
            // 排除锚点
            if (parsedUrl.getRef() != null && !parsedUrl.getRef().isEmpty()) {
                return false;
            }
            
            // 检测重复路径段（如 /a/b/a/b/a）
            if (hasDuplicatePathSegments(path)) {
                return false;
            }
            
            // 检查排除关键词
            String urlLower = url.toLowerCase();
            for (String keyword : EXCLUDED_KEYWORDS) {
                if (urlLower.contains(keyword)) {
                    return false;
                }
            }
            
            // 检查查询参数
            if (query != null) {
                // 排除跟踪参数
                if (query.contains("utm_") || query.contains("fbclid") || 
                    query.contains("gclid") || query.contains("msclkid") ||
                    query.contains("_ga=") || query.contains("_gl=")) {
                    return false;
                }
                
                // 检查会话/认证参数
                if (PAGINATION_PATTERN.matcher(query).matches()) {
                    if (query.contains("page=1") || query.contains("p=1") || 
                        query.contains("offset=0") || query.contains("start=0")) {
                        return true;
                    }
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 检测路径是否包含大量重复段（如 /a/b/a/b/a）
     */
    private boolean hasDuplicatePathSegments(String path) {
        String[] segments = path.split("/");
        if (segments.length < 4) {
            return false;
        }
        Map<String, Integer> segmentCount = new HashMap<>();
        for (String seg : segments) {
            if (seg.isEmpty()) continue;
            segmentCount.merge(seg, 1, Integer::sum);
            if (segmentCount.get(seg) > 2) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 判断是否为重要URL
     */
    private boolean isImportantUrl(String url) {
        String urlLower = url.toLowerCase();
        
        // 检查包含关键词
        for (String keyword : INCLUDED_KEYWORDS) {
            if (urlLower.contains(keyword)) {
                return true;
            }
        }
        
        // 检查是否为首页或根路径
        try {
            URL parsedUrl = new URL(url);
            String path = parsedUrl.getPath();
            return path == null || path.isEmpty() || path.equals("/") || 
                   path.equals("/index.html") || path.equals("/index.php");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 生成站点地图XML
     */
    private String generateSitemapXml(Map<String, PageData> crawledPages, String baseUrl, 
                                      boolean includeImages, boolean includeVideos, boolean includeNews,
                                      Map<String, String> sitemapDiscoveredUrls) {
        
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"");
        
        if (includeImages) {
            xml.append(" xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\"");
        }
        if (includeVideos) {
            xml.append(" xmlns:video=\"http://www.google.com/schemas/sitemap-video/1.1\"");
        }
        if (includeNews) {
            xml.append(" xmlns:news=\"http://www.google.com/schemas/sitemap-news/0.9\"");
        }
        
        xml.append(">\n");
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        String today = sdf.format(new Date());
        
        for (Map.Entry<String, PageData> entry : crawledPages.entrySet()) {
            String url = entry.getKey();
            PageData pageData = entry.getValue();
            
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(url)).append("</loc>\n");
            
            // 优先使用 sitemap.xml 中的 lastmod，否则使用今天日期
            String lastmod = today;
            if (sitemapDiscoveredUrls != null && sitemapDiscoveredUrls.containsKey(url)) {
                String sitemapLastmod = sitemapDiscoveredUrls.get(url);
                if (sitemapLastmod != null && !sitemapLastmod.isEmpty()) {
                    lastmod = sitemapLastmod.length() > 10 ? sitemapLastmod.substring(0, 10) : sitemapLastmod;
                }
            }
            xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
            
            // 智能优先级设置
            String priority = calculatePriority(url, baseUrl);
            xml.append("    <priority>").append(priority).append("</priority>\n");
            
            // 智能更新频率设置
            String changefreq = calculateChangefreq(url);
            xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
            
            // 添加图片标签
            if (includeImages && !pageData.images.isEmpty()) {
                for (String imageUrl : pageData.images) {
                    xml.append("    <image:image>\n");
                    xml.append("      <image:loc>").append(escapeXml(imageUrl)).append("</image:loc>\n");
                    xml.append("    </image:image>\n");
                }
            }
            
            // 添加视频标签（仅输出必填字段齐备的条目）
            if (includeVideos && !pageData.videos.isEmpty()) {
                for (VideoEntry video : pageData.videos.values()) {
                    if (!video.isComplete()) {
                        continue;
                    }
                    xml.append("    <video:video>\n");
                    xml.append("      <video:thumbnail_loc>").append(escapeXml(video.thumbnail)).append("</video:thumbnail_loc>\n");
                    xml.append("      <video:title>").append(escapeXml(video.title)).append("</video:title>\n");
                    xml.append("      <video:description>").append(escapeXml(video.description)).append("</video:description>\n");
                    if (video.embed) {
                        xml.append("      <video:player_loc>").append(escapeXml(video.url)).append("</video:player_loc>\n");
                    } else {
                        xml.append("      <video:content_loc>").append(escapeXml(video.url)).append("</video:content_loc>\n");
                    }
                    xml.append("    </video:video>\n");
                }
            }
            
            // 添加新闻标签（仅文章页，且发布时间在 48 小时内）
            if (includeNews && pageData.news != null) {
                NewsEntry news = pageData.news;
                xml.append("    <news:news>\n");
                xml.append("      <news:publication>\n");
                xml.append("        <news:name>").append(escapeXml(news.publicationName)).append("</news:name>\n");
                xml.append("        <news:language>").append(escapeXml(news.publicationLanguage)).append("</news:language>\n");
                xml.append("      </news:publication>\n");
                xml.append("      <news:publication_date>").append(news.publicationDate).append("</news:publication_date>\n");
                xml.append("      <news:title>").append(escapeXml(news.title)).append("</news:title>\n");
                xml.append("    </news:news>\n");
            }
            
            xml.append("  </url>\n");
        }
        
        xml.append("</urlset>");
        return xml.toString();
    }
    
    /**
     * 计算优先级
     */
    private String calculatePriority(String url, String baseUrl) {
        if (url.equals(baseUrl) || url.equals(baseUrl + "/")) {
            return "1.0";
        }
        
        String urlLower = url.toLowerCase();
        
        if (urlLower.contains("/channel/") || urlLower.contains("/video/")) {
            return "0.9";
        }
        
        if (urlLower.contains("/about") || urlLower.contains("/contact") || 
            urlLower.contains("/faq") || urlLower.contains("/help")) {
            return "0.8";
        }
        
        if (urlLower.contains("/blog") || urlLower.contains("/news") || 
            urlLower.contains("/article")) {
            return "0.7";
        }
        
        return "0.5";
    }
    
    /**
     * 计算更新频率
     */
    private String calculateChangefreq(String url) {
        String urlLower = url.toLowerCase();
        
        if (urlLower.contains("/blog") || urlLower.contains("/news") || 
            urlLower.contains("/article")) {
            return "weekly";
        }
        
        if (urlLower.contains("/channel/") || urlLower.contains("/video/")) {
            return "daily";
        }
        
        return "monthly";
    }
    
    /**
     * XML转义
     */
    private String escapeXml(String input) {
        return input.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
    
    /**
     * URL 规范化：统一格式以便去重
     */
    private String normalizeUrl(String url) {
        try {
            URL parsed = new URL(url);
            
            String protocol = parsed.getProtocol().toLowerCase();
            String host = parsed.getHost().toLowerCase();
            int port = parsed.getPort();
            String path = parsed.getPath();
            String query = parsed.getQuery();
            
            // 移除默认端口
            if ((protocol.equals("http") && port == 80) || (protocol.equals("https") && port == 443)) {
                port = -1;
            }
            
            // 规范化路径：移除尾部斜杠（根路径除外）
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.isEmpty()) {
                path = "/";
            }
            
            // 清理查询参数：移除跟踪参数，保留有意义的参数
            if (query != null && !query.isEmpty()) {
                String[] params = query.split("&");
                StringBuilder cleanQuery = new StringBuilder();
                Arrays.sort(params);
                for (String param : params) {
                    String key = param.split("=")[0].toLowerCase();
                    if (key.startsWith("utm_") || key.equals("fbclid") || key.equals("gclid") ||
                        key.equals("msclkid") || key.equals("_ga") || key.equals("_gl") ||
                        key.equals("ref") || key.equals("source") || key.equals("medium") ||
                        key.equals("campaign")) {
                        continue;
                    }
                    if (cleanQuery.length() > 0) {
                        cleanQuery.append("&");
                    }
                    cleanQuery.append(param);
                }
                query = cleanQuery.length() > 0 ? cleanQuery.toString() : null;
            }
            
            // 重建 URL
            StringBuilder normalized = new StringBuilder();
            normalized.append(protocol).append("://").append(host);
            if (port != -1) {
                normalized.append(":").append(port);
            }
            normalized.append(path);
            if (query != null) {
                normalized.append("?").append(query);
            }
            
            return normalized.toString();
            
        } catch (Exception e) {
            return url;
        }
    }
    
    /**
     * 爬取任务类
     */
    private static class CrawlTask {
        String url;
        int depth;
        
        CrawlTask(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }
}