package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.AutoSite;
import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.PushLog;
import io.github.ghgongjin.sitemap.repository.AutoSiteVersionRepository;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.PushLogRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @ClassName SitemapPushService
 * @Description 站点地图推送：取最新版本经传输通道上传，失败重试一次并分类记录日志
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Service
public class SitemapPushService {

    static final int MAX_ATTEMPTS = 2;
    static final int KEEP_LOGS = 50;
    static final int DETAIL_MAX_LENGTH = 512;

    private final PushConfigRepository configRepository;
    private final PushLogRepository logRepository;
    private final AutoSiteVersionRepository versionRepository;
    private final CredentialCipher credentialCipher;
    private final AutoSiteService autoSiteService;
    private final IndexNowClient indexNowClient;
    private final List<PushTransport> transports;

    public SitemapPushService(PushConfigRepository configRepository,
                              PushLogRepository logRepository,
                              AutoSiteVersionRepository versionRepository,
                              CredentialCipher credentialCipher,
                              AutoSiteService autoSiteService,
                              IndexNowClient indexNowClient,
                              List<PushTransport> transports) {
        this.configRepository = configRepository;
        this.logRepository = logRepository;
        this.versionRepository = versionRepository;
        this.credentialCipher = credentialCipher;
        this.autoSiteService = autoSiteService;
        this.indexNowClient = indexNowClient;
        this.transports = transports;
    }

    /**
     * 推送站点已生成的最新版本；未配置、已停用或暂无版本时跳过（不写日志）
     */
    public PushOutcome push(Long siteId) {
        Optional<PushConfig> configOpt = configRepository.findBySiteId(siteId);
        if (configOpt.isEmpty()) {
            return PushOutcome.skipped("尚未配置推送");
        }
        PushConfig config = configOpt.get();
        if (!config.isEnabled()) {
            return PushOutcome.skipped("推送已停用");
        }
        Optional<AutoSiteVersion> versionOpt = versionRepository.findTopBySiteIdOrderByVersionNumberDesc(siteId);
        if (versionOpt.isEmpty()) {
            return PushOutcome.skipped("暂无已生成的站点地图版本");
        }
        AutoSiteVersion version = versionOpt.get();
        PushTransport transport = findTransport(config.getProtocol());
        if (transport == null) {
            return PushOutcome.skipped("不支持的推送协议：" + config.getProtocol());
        }

        long startedAt = System.nanoTime();
        PushTarget target;
        try {
            target = buildTarget(config);
        } catch (IllegalStateException e) {
            return failAndRecord(config, version.getVersionNumber(), PushErrorCode.AUTH_FAILED,
                    e.getMessage(), elapsedMs(startedAt));
        }

        byte[] content = version.getSitemapXml().getBytes(StandardCharsets.UTF_8);
        PushTransportException lastFailure = null;
        String observedFingerprint = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                observedFingerprint = transport.upload(target, config.getSitemapFileName(), content);
                lastFailure = null;
                break;
            } catch (PushTransportException e) {
                lastFailure = e;
                log.warn("推送尝试 {}/{} 失败：siteId={}，{}", attempt, MAX_ATTEMPTS, siteId, e.getMessage());
            }
        }
        long durationMs = elapsedMs(startedAt);

        if (lastFailure != null) {
            return failAndRecord(config, version.getVersionNumber(), lastFailure.errorCode(),
                    lastFailure.getMessage(), durationMs);
        }
        recordHostKeyFingerprint(config, observedFingerprint);
        String indexNowStatus = submitIndexNow(config, target, transport, version);
        String detail = "已上传 " + config.getSitemapFileName() + "（版本 " + version.getVersionNumber() + "）";
        recordSuccess(config, version.getVersionNumber(), detail, indexNowStatus, durationMs);
        log.info("推送成功：siteId={}，版本 {}，耗时 {}ms", siteId, version.getVersionNumber(), durationMs);
        return PushOutcome.success(version.getVersionNumber(), detail, durationMs);
    }

    /**
     * 测试连接：验证认证与远端目录可用；SFTP 首次连接时记录指纹（不写推送日志）
     */
    public PushOutcome testConnection(Long siteId) {
        Optional<PushConfig> configOpt = configRepository.findBySiteId(siteId);
        if (configOpt.isEmpty()) {
            return PushOutcome.skipped("尚未配置推送");
        }
        PushConfig config = configOpt.get();
        PushTransport transport = findTransport(config.getProtocol());
        if (transport == null) {
            return PushOutcome.skipped("不支持的推送协议：" + config.getProtocol());
        }
        long startedAt = System.nanoTime();
        PushTarget target;
        try {
            target = buildTarget(config);
        } catch (IllegalStateException e) {
            return PushOutcome.failure(PushErrorCode.AUTH_FAILED, e.getMessage(), null, elapsedMs(startedAt));
        }
        try {
            String observed = transport.verify(target);
            if (recordHostKeyFingerprint(config, observed)) {
                config.setUpdatedAt(LocalDateTime.now());
                configRepository.save(config);
            }
            log.info("推送连接测试成功：siteId={}，{}://{}:{}", siteId, config.getProtocol(),
                    config.getHost(), config.getPort());
            return PushOutcome.success("连接成功", elapsedMs(startedAt));
        } catch (PushTransportException e) {
            log.warn("推送连接测试失败：siteId={}，{}", siteId, e.getMessage());
            return PushOutcome.failure(e.errorCode(), e.getMessage(), null, elapsedMs(startedAt));
        }
    }

    private PushTransport findTransport(String protocolName) {
        PushProtocol protocol;
        try {
            protocol = PushProtocol.valueOf(protocolName);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return transports.stream().filter(t -> t.protocol() == protocol).findFirst().orElse(null);
    }

    private PushTarget buildTarget(PushConfig config) {
        return new PushTarget(
                PushProtocol.valueOf(config.getProtocol()),
                config.getHost(),
                config.getPort(),
                config.getUsername(),
                credentialCipher.decrypt(config.getPasswordEnc()),
                credentialCipher.decrypt(config.getPrivateKeyEnc()),
                config.getRemoteDir(),
                config.getHostKeyFingerprint(),
                PushTarget.DEFAULT_TIMEOUT_MS);
    }

    /**
     * SFTP 首次连接（TOFU）：把观察到的指纹写入配置，之后连接必须匹配
     *
     * @return 是否记录了新指纹
     */
    private boolean recordHostKeyFingerprint(PushConfig config, String observedFingerprint) {
        if (observedFingerprint == null || observedFingerprint.isBlank()) {
            return false;
        }
        if (config.getHostKeyFingerprint() != null && !config.getHostKeyFingerprint().isBlank()) {
            return false;
        }
        config.setHostKeyFingerprint(observedFingerprint);
        log.info("已记录 SFTP 主机密钥指纹（首次连接）：siteId={}，{}", config.getSiteId(), observedFingerprint);
        return true;
    }

    /**
     * IndexNow 提交：先经同一传输通道发布 key 文件，再提交版本中的全部 URL；
     * 失败仅记录状态，不影响站点地图推送结果
     */
    private String submitIndexNow(PushConfig config, PushTarget target, PushTransport transport,
                                  AutoSiteVersion version) {
        if (!config.isIndexNowEnabled()) {
            return null;
        }
        String key = config.getIndexNowKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        Optional<AutoSite> site = autoSiteService.find(config.getSiteId());
        if (site.isEmpty()) {
            return null;
        }
        List<String> urls = IndexNowClient.extractUrls(version.getSitemapXml());
        if (urls.isEmpty()) {
            return null;
        }
        try {
            transport.upload(target, key + ".txt", key.getBytes(StandardCharsets.UTF_8));
            indexNowClient.submit(site.get().getUrl(), key, urls);
            return PushLog.INDEX_NOW_SUCCESS;
        } catch (PushTransportException e) {
            log.warn("IndexNow 提交失败：siteId={}，{}", config.getSiteId(), e.getMessage());
            return PushLog.INDEX_NOW_FAILED;
        }
    }

    private void recordSuccess(PushConfig config, int versionNumber, String detail,
                               String indexNowStatus, long durationMs) {
        config.setLastPushAt(LocalDateTime.now());
        config.setLastPushStatus(PushLog.STATUS_SUCCESS);
        config.setLastPushError(null);
        config.setUpdatedAt(LocalDateTime.now());
        configRepository.save(config);
        saveLog(config, versionNumber, PushLog.STATUS_SUCCESS, null, detail, indexNowStatus, durationMs);
    }

    private PushOutcome failAndRecord(PushConfig config, Integer versionNumber, PushErrorCode errorCode,
                                      String message, long durationMs) {
        String detail = trimDetail(message);
        config.setLastPushAt(LocalDateTime.now());
        config.setLastPushStatus(PushLog.STATUS_FAILED);
        config.setLastPushError(detail);
        config.setUpdatedAt(LocalDateTime.now());
        configRepository.save(config);
        saveLog(config, versionNumber, PushLog.STATUS_FAILED, errorCode.name(), detail, null, durationMs);
        log.warn("推送失败：siteId={}，{}：{}", config.getSiteId(), errorCode, detail);
        return PushOutcome.failure(errorCode, detail, versionNumber, durationMs);
    }

    private void saveLog(PushConfig config, Integer versionNumber, String status, String errorCode,
                         String detail, String indexNowStatus, long durationMs) {
        PushLog entry = new PushLog();
        entry.setSiteId(config.getSiteId());
        entry.setVersionNumber(versionNumber);
        entry.setProtocol(config.getProtocol());
        entry.setStatus(status);
        entry.setErrorCode(errorCode);
        entry.setDetail(detail);
        entry.setIndexNowStatus(indexNowStatus);
        entry.setDurationMs(durationMs);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
        trimLogs(config.getSiteId());
    }

    private void trimLogs(Long siteId) {
        List<PushLog> all = logRepository.findBySiteIdOrderByIdDesc(siteId);
        if (all.size() > KEEP_LOGS) {
            logRepository.deleteAll(all.subList(KEEP_LOGS, all.size()));
        }
    }

    private String trimDetail(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        String trimmed = message.trim();
        return trimmed.length() <= DETAIL_MAX_LENGTH ? trimmed : trimmed.substring(0, DETAIL_MAX_LENGTH);
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
