package io.github.ghgongjin.sitemap.service;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.repository.AutoSiteRepository;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * @ClassName AutoSiteService
 * @Description 自动更新站点的存储层：注册/启停/到期扫描/版本记录，读写均按归属用户隔离
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoSiteService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public static final int MIN_INTERVAL_HOURS = 1;
    public static final int MAX_INTERVAL_HOURS = 24 * 30;
    public static final int KEEP_VERSIONS = 5;
    static final int MESSAGE_MAX_LENGTH = 512;

    private final AutoSiteRepository siteRepository;
    private final AutoSiteVersionRepository versionRepository;

    private CrawlUrlPolicy crawlUrlPolicy = new CrawlUrlPolicy();

    public void setCrawlUrlPolicy(CrawlUrlPolicy crawlUrlPolicy) {
        this.crawlUrlPolicy = crawlUrlPolicy;
    }

    /**
     * 注册自动更新站点并绑定归属用户；URL 立即通过安全策略校验（公网 DNS、http/https、无凭据），
     * 判重按 (用户, URL) 维度：同一 URL 允许由不同用户各自托管；
     * 归属用户为空时直接拒绝，避免写入对任何登录用户都不可见的站点
     */
    @Transactional
    public AutoSite create(Long userId, String url, boolean includeImages, boolean includeVideos,
                           boolean includeNews, int intervalHours) {
        if (userId == null) {
            throw new IllegalArgumentException("请先登录后再添加自动更新站点");
        }
        if (intervalHours < MIN_INTERVAL_HOURS || intervalHours > MAX_INTERVAL_HOURS) {
            throw new IllegalArgumentException("更新间隔必须在 " + MIN_INTERVAL_HOURS
                    + " 到 " + MAX_INTERVAL_HOURS + " 小时之间");
        }
        String normalized = normalizeUrl(url);
        if (siteRepository.existsByUserIdAndUrl(userId, normalized)) {
            throw new IllegalArgumentException("该网站已在自动更新列表中：" + normalized);
        }
        LocalDateTime now = LocalDateTime.now();
        AutoSite site = new AutoSite();
        site.setUserId(userId);
        site.setUrl(normalized);
        site.setIncludeImages(includeImages);
        site.setIncludeVideos(includeVideos);
        site.setIncludeNews(includeNews);
        site.setIntervalHours(intervalHours);
        site.setEnabled(true);
        site.setNextRunAt(now);
        site.setLastStatus(STATUS_PENDING);
        site.setCreatedAt(now);
        site.setUpdatedAt(now);
        AutoSite saved = siteRepository.save(site);
        log.info("自动更新站点已注册：{}（用户 {}，每 {} 小时）", normalized, userId, intervalHours);
        return saved;
    }

    /**
     * 某用户名下的站点列表；用户为空（游客/存量数据）时返回空集
     */
    @Transactional(readOnly = true)
    public List<AutoSite> listOwned(Long userId) {
        return userId == null ? List.of() : siteRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Optional<AutoSite> find(Long id) {
        return id == null ? Optional.empty() : siteRepository.findById(id);
    }

    /**
     * 仅取归属当前用户的站点；id/用户任一为空或归属不符都返回 empty（越权与不存在统一按 404 处理）
     */
    @Transactional(readOnly = true)
    public Optional<AutoSite> findOwned(Long id, Long userId) {
        if (id == null || userId == null) {
            return Optional.empty();
        }
        return find(id).filter(site -> userId.equals(site.getUserId()));
    }

    /**
     * 到期的启用站点（按到期时间升序），供调度器消费；调度线程不属于任何请求用户，保持全局扫描
     */
    @Transactional(readOnly = true)
    public List<AutoSite> dueSites() {
        return siteRepository.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(LocalDateTime.now());
    }

    /**
     * 启用/停用；重新启用且已过期时立即排期
     */
    @Transactional
    public AutoSite setEnabled(Long id, boolean enabled, Long userId) {
        AutoSite site = requireOwned(id, userId);
        LocalDateTime now = LocalDateTime.now();
        site.setEnabled(enabled);
        if (enabled && site.getNextRunAt().isBefore(now)) {
            site.setNextRunAt(now);
        }
        site.setUpdatedAt(now);
        return siteRepository.save(site);
    }

    /**
     * 手动触发：把下次执行时间提前到现在
     */
    @Transactional
    public AutoSite runNow(Long id, Long userId) {
        AutoSite site = requireOwned(id, userId);
        LocalDateTime now = LocalDateTime.now();
        site.setNextRunAt(now);
        site.setUpdatedAt(now);
        log.info("自动更新站点已排期立即执行：{}", site.getUrl());
        return siteRepository.save(site);
    }

    /**
     * 标记执行中；同时把下次执行推到未来，避免扫描重复触发
     */
    @Transactional
    public AutoSite markRunning(Long id) {
        AutoSite site = requireSite(id);
        LocalDateTime now = LocalDateTime.now();
        site.setLastStatus(STATUS_RUNNING);
        site.setNextRunAt(now.plusHours(site.getIntervalHours()));
        site.setUpdatedAt(now);
        return siteRepository.save(site);
    }

    /**
     * 记录成功：写入新版本（版本号递增），裁剪超出保留数的旧版本
     */
    @Transactional
    public AutoSite recordSuccess(Long id, String taskId, String sitemapXml, int urlCount) {
        AutoSite site = requireSite(id);
        LocalDateTime now = LocalDateTime.now();

        AutoSiteVersion version = new AutoSiteVersion();
        version.setSiteId(site.getId());
        version.setVersionNumber(nextVersionNumber(site.getId()));
        version.setTaskId(taskId);
        version.setUrlCount(urlCount);
        version.setSitemapXml(sitemapXml);
        version.setCreatedAt(now);
        versionRepository.save(version);
        trimVersions(site.getId());

        site.setLastRunAt(now);
        site.setLastStatus(STATUS_SUCCESS);
        site.setLastMessage(null);
        site.setNextRunAt(now.plusHours(site.getIntervalHours()));
        site.setUpdatedAt(now);
        AutoSite saved = siteRepository.save(site);
        log.info("自动更新成功：{}，版本 {}，{} 个 URL", site.getUrl(), version.getVersionNumber(), urlCount);
        return saved;
    }

    /**
     * 记录失败：不写版本（历史版本保持可用），并按间隔排下一次
     */
    @Transactional
    public AutoSite recordFailure(Long id, String message) {
        AutoSite site = requireSite(id);
        LocalDateTime now = LocalDateTime.now();
        site.setLastRunAt(now);
        site.setLastStatus(STATUS_FAILED);
        site.setLastMessage(trimMessage(message));
        site.setNextRunAt(now.plusHours(site.getIntervalHours()));
        site.setUpdatedAt(now);
        AutoSite saved = siteRepository.save(site);
        log.warn("自动更新失败：{}，原因：{}", site.getUrl(), site.getLastMessage());
        return saved;
    }

    @Transactional
    public void delete(Long id, Long userId) {
        AutoSite site = requireOwned(id, userId);
        versionRepository.deleteBySiteId(site.getId());
        siteRepository.delete(site);
        log.info("自动更新站点已删除：{}", site.getUrl());
    }

    @Transactional(readOnly = true)
    public List<AutoSiteVersion> versions(Long siteId) {
        return siteId == null ? List.of() : versionRepository.findBySiteIdOrderByVersionNumberDesc(siteId);
    }

    @Transactional(readOnly = true)
    public Optional<AutoSiteVersion> latestVersion(Long siteId) {
        return siteId == null ? Optional.empty() : versionRepository.findTopBySiteIdOrderByVersionNumberDesc(siteId);
    }

    @Transactional(readOnly = true)
    public Optional<AutoSiteVersion> version(Long siteId, int versionNumber) {
        return siteId == null ? Optional.empty()
                : versionRepository.findBySiteIdAndVersionNumber(siteId, versionNumber);
    }

    private AutoSite requireSite(Long id) {
        return siteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("自动更新站点不存在：" + id));
    }

    /**
     * 写路径的归属校验：不存在与不属于该用户同样归一为「站点不存在」，
     * 由控制器统一按 404 透出（不区分不存在与无权限）
     */
    private AutoSite requireOwned(Long id, Long userId) {
        return findOwned(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("自动更新站点不存在：" + id));
    }

    private int nextVersionNumber(Long siteId) {
        return versionRepository.findTopBySiteIdOrderByVersionNumberDesc(siteId)
                .map(latest -> latest.getVersionNumber() + 1)
                .orElse(1);
    }

    private void trimVersions(Long siteId) {
        List<AutoSiteVersion> all = versionRepository.findBySiteIdOrderByVersionNumberDesc(siteId);
        if (all.size() > KEEP_VERSIONS) {
            versionRepository.deleteAll(all.subList(KEEP_VERSIONS, all.size()));
        }
    }

    private String trimMessage(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        String trimmed = message.trim();
        return trimmed.length() <= MESSAGE_MAX_LENGTH ? trimmed : trimmed.substring(0, MESSAGE_MAX_LENGTH);
    }

    /**
     * 规范化为 scheme://host[:port][/path]，去掉末尾斜杠与 query/fragment
     */
    private String normalizeUrl(String raw) {
        URI uri = crawlUrlPolicy.validate(raw);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        return sb.toString();
    }
}
