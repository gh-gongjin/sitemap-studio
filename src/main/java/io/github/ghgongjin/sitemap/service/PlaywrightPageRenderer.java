package io.github.ghgongjin.sitemap.service;

import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Playwright 页面渲染器实现
 * 使用无头 Chromium 浏览器渲染 JS 动态页面
 */
@Slf4j
public class PlaywrightPageRenderer implements PageRenderer {

    private final Playwright playwright;
    private final Browser browser;
    private final BlockingQueue<BrowserContext> contextPool;
    private final int poolSize;
    private final int timeoutSeconds;
    private final CrawlUrlPolicy policy;
    private final SafeHttpFetcher fetcher;
    private final ConcurrentHashMap<BrowserContext, String> renderScopes = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    public PlaywrightPageRenderer(int poolSize, int timeoutSeconds) {
        this(poolSize, timeoutSeconds, new CrawlUrlPolicy());
    }

    public PlaywrightPageRenderer(int poolSize, int timeoutSeconds, CrawlUrlPolicy policy) {
        this(poolSize, timeoutSeconds, policy, new SafeHttpFetcher(policy));
    }

    public PlaywrightPageRenderer(int poolSize, int timeoutSeconds, CrawlUrlPolicy policy, SafeHttpFetcher fetcher) {
        this.poolSize = poolSize;
        this.timeoutSeconds = timeoutSeconds;
        this.policy = policy;
        this.fetcher = fetcher;
        this.contextPool = new LinkedBlockingQueue<>(poolSize);

        Playwright pw = null;
        Browser br = null;
        try {
            pw = Playwright.create();
            br = pw.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(java.util.Arrays.asList(
                            "--disable-blink-features=AutomationControlled",
                            "--disable-dev-shm-usage",
                            "--no-sandbox"
                    )));

            for (int i = 0; i < poolSize; i++) {
                BrowserContext ctx = br.newContext(new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .setViewportSize(1920, 1080)
                        .setOffline(true));
                installSafeRoute(ctx);
                contextPool.offer(ctx);
            }

            playwright = pw;
            browser = br;
            log.info("Playwright 渲染器初始化成功，池大小：{}", poolSize);
        } catch (Exception e) {
            log.warn("Playwright 初始化失败，将使用纯 Jsoup 模式：{}", e.getMessage());
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
            if (pw != null) {
                try { pw.close(); } catch (Exception ignored) {}
            }
            throw new RuntimeException("Playwright 初始化失败", e);
        }
    }

    @Override
    public String render(String url) throws Exception {
        if (!available) {
            throw new IllegalStateException("渲染器不可用");
        }

        policy.validate(url);
        BrowserContext context = contextPool.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (context == null) {
            throw new RuntimeException("获取浏览器上下文超时");
        }

        renderScopes.put(context, url);
        try {
            Page page = context.newPage();
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(timeoutSeconds * 1000)
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));

                // 等待页面稳定
                page.waitForTimeout(1000);

                String html = page.content();
                log.debug("渲染成功：{} (长度：{})", url, html.length());
                return html;
            } finally {
                page.close();
            }
        } catch (Exception e) {
            log.warn("渲染失败 {}：{}", url, e.getMessage());
            throw e;
        } finally {
            renderScopes.remove(context);
            contextPool.offer(context);
        }
    }

    private void installSafeRoute(BrowserContext context) {
        context.route("**/*", route -> {
            try {
                String requestUrl = route.request().url();
                if (!requestUrl.startsWith("http://") && !requestUrl.startsWith("https://")) {
                    route.abort();
                    return;
                }
                String scope = renderScopes.get(context);
                if (scope == null) {
                    route.abort();
                    return;
                }
                policy.validate(requestUrl, scope);
                SafeHttpFetcher.Response fetched = fetcher.fetch(requestUrl, scope, timeoutSeconds * 1000, null);
                route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(fetched.statusCode())
                        .setContentType(fetched.contentType())
                        .setHeaders(fetched.headers())
                        .setBodyBytes(fetched.body()));
            } catch (Exception e) {
                route.abort();
            }
        });
    }

    @Override
    public boolean isAvailable() {
        return available && browser != null && browser.isConnected();
    }

    @Override
    public void close() {
        available = false;
        try {
            while (!contextPool.isEmpty()) {
                BrowserContext ctx = contextPool.poll();
                if (ctx != null) {
                    try { ctx.close(); } catch (Exception ignored) {}
                }
            }
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
            log.info("Playwright 渲染器已关闭");
        } catch (Exception e) {
            log.warn("关闭渲染器时出错：{}", e.getMessage());
        }
    }
}
