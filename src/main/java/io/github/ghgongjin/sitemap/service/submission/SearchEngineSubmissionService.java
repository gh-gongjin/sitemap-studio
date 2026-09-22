package io.github.ghgongjin.sitemap.service.submission;

import io.github.ghgongjin.sitemap.entity.AutoSiteVersion;
import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.SubmissionLog;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.SubmissionLogRepository;
import io.github.ghgongjin.sitemap.service.AutoSiteService;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import io.github.ghgongjin.sitemap.service.SiteDiffEngine;
import io.github.ghgongjin.sitemap.service.push.IndexNowClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 搜索引擎提交编排：百度只提 diff 新增/修改（首版全量、外域过滤、2000 截断），
 * GSC 提交 sitemap 地址；每通道一次，一切失败止步于 submission_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchEngineSubmissionService {

    static final int BAIDU_MAX_URLS = 2000;
    static final int KEEP_LOGS = 50;
    static final int DETAIL_MAX_LENGTH = 512;

    /**
     * 查询串凭据遮蔽（T4 评审强制约束）：Spring RestClient 传输异常 message 必含完整 URI
     * （百度 token 在查询串里），任何异常文案写入 submission_log / 返回调用方之前先脱敏。
     */
    private static final Pattern SECRET_QUERY_PARAM =
            Pattern.compile("(?i)(token=|client_secret=)[^&\\s\"']+");

    private final PushConfigRepository configRepository;
    private final SubmissionLogRepository logRepository;
    private final AutoSiteService autoSiteService;
    private final CredentialCipher credentialCipher;
    private final BaiduPushClient baiduPushClient;
    private final GoogleSitemapClient googleSitemapClient;

    /** 本批将提交百度的 URL 与被过滤的外域数量 */
    record BaiduUrls(List<String> batch, int filteredOut) {
    }

    private record ChannelResult(boolean executed, boolean success,
                                 SubmissionErrorCode errorCode, String detail) {
        static final ChannelResult SKIPPED = new ChannelResult(false, false, null, null);
    }

    public SubmissionOutcome submit(Long siteId) {
        Optional<PushConfig> configOpt = configRepository.findBySiteId(siteId);
        if (configOpt.isEmpty()) {
            return SubmissionOutcome.skipped("尚未配置搜索引擎提交");
        }
        PushConfig config = configOpt.get();
        if (!config.isBaiduEnabled() && !config.isGscEnabled()) {
            return SubmissionOutcome.skipped("未启用任何提交通道");
        }
        Optional<AutoSiteVersion> versionOpt = autoSiteService.latestVersion(siteId);
        if (versionOpt.isEmpty()) {
            return SubmissionOutcome.skipped("暂无已生成的站点地图版本");
        }
        AutoSiteVersion version = versionOpt.get();
        long startedAt = System.nanoTime();
        int executed = 0;
        List<String> failures = new ArrayList<>();
        if (config.isBaiduEnabled()) {
            ChannelResult result = submitBaidu(config, version);
            if (result.executed()) {
                executed++;
                record(SubmissionLog.CHANNEL_BAIDU, version, result, startedAt);
                if (!result.success()) {
                    failures.add("百度：" + result.detail());
                }
            }
        }
        if (config.isGscEnabled()) {
            ChannelResult result = submitGsc(config);
            if (result.executed()) {
                executed++;
                record(SubmissionLog.CHANNEL_GSC, version, result, startedAt);
                if (!result.success()) {
                    failures.add("GSC：" + result.detail());
                }
            }
        }
        if (executed == 0) {
            return SubmissionOutcome.skipped("没有可提交的 URL（检查站点与凭据配置）");
        }
        if (failures.isEmpty()) {
            return SubmissionOutcome.success("已提交 " + executed + " 个通道");
        }
        return SubmissionOutcome.failure(String.join("；", failures));
    }

    private ChannelResult submitBaidu(PushConfig config, AutoSiteVersion version) {
        String token;
        try {
            token = credentialCipher.decrypt(config.getBaiduTokenEnc());
        } catch (IllegalStateException e) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_DECRYPT_FAILED,
                    "凭据无法解密，请重新保存提交设置");
        }
        if (token == null || token.isBlank()) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "百度 token 未配置");
        }
        if (isBlank(config.getBaiduSite())) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "百度站点地址未配置");
        }
        BaiduUrls urls;
        try {
            urls = baiduUrls(config.getBaiduSite(), candidateUrls(config.getSiteId(), version));
        } catch (IllegalArgumentException e) {
            // 存量 baidu_site 不是合法 URL：不外抛，按配置非法记录（异常隔离红线）
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "百度站点地址无效：" + e.getClass().getSimpleName());
        }
        if (urls.batch().isEmpty()) {
            return ChannelResult.SKIPPED;
        }
        try {
            BaiduPushClient.BaiduPushResponse response =
                    baiduPushClient.push(config.getBaiduSite(), token, urls.batch());
            if (response == null) {
                return new ChannelResult(true, false, SubmissionErrorCode.BAIDU_REJECTED,
                        "百度响应为空");
            }
            String remain = response.remain() < 0 ? "未知" : String.valueOf(response.remain());
            return new ChannelResult(true, true, null,
                    "接收 " + response.success() + "，剩余配额 " + remain
                            + "，外域已过滤 " + urls.filteredOut()
                            + (urls.batch().size() >= BAIDU_MAX_URLS ? "，本批已截断 " + BAIDU_MAX_URLS : ""));
        } catch (SubmissionClientException e) {
            return new ChannelResult(true, false, e.errorCode(), sanitize(e.getMessage()));
        }
    }

    private ChannelResult submitGsc(PushConfig config) {
        String json;
        try {
            json = credentialCipher.decrypt(config.getGscServiceAccountJsonEnc());
        } catch (IllegalStateException e) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_DECRYPT_FAILED,
                    "凭据无法解密，请重新保存提交设置");
        }
        if (json == null || json.isBlank()) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "服务账号 JSON 未配置");
        }
        GoogleServiceAccount account;
        try {
            account = GoogleServiceAccount.parse(json);
        } catch (IllegalArgumentException e) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "已存服务账号 JSON 无效：" + sanitize(e.getMessage()));
        }
        if (isBlank(config.getGscSiteUrl()) || isBlank(config.getGscSitemapUrl())) {
            return new ChannelResult(true, false, SubmissionErrorCode.CONFIG_INVALID,
                    "GSC 站点地址或 sitemap 公开 URL 未配置");
        }
        try {
            googleSitemapClient.submitSitemap(account, config.getGscSiteUrl(), config.getGscSitemapUrl());
            return new ChannelResult(true, true, null, "GSC 已受理：" + config.getGscSitemapUrl());
        } catch (SubmissionClientException e) {
            String detail = e.errorCode() == SubmissionErrorCode.GSC_NOT_A_SITE_USER
                    ? sanitize(e.getMessage()) + "（账号：" + account.clientEmail() + "）"
                    : sanitize(e.getMessage());
            return new ChannelResult(true, false, e.errorCode(), detail);
        }
    }

    /** 本版 vs 上版的 新增+修改；首版、上版缺失或 XML 不可解析时退化为全量 */
    List<String> candidateUrls(Long siteId, AutoSiteVersion latest) {
        List<String> full = IndexNowClient.extractUrls(latest.getSitemapXml());
        if (latest.getVersionNumber() <= 1) {
            return full;
        }
        AutoSiteVersion previous = autoSiteService.version(siteId, latest.getVersionNumber() - 1)
                .orElse(null);
        if (previous == null) {
            return full;
        }
        try {
            SiteDiffEngine.SiteDiff diff = SiteDiffEngine.diff(
                    previous.getSitemapXml(), latest.getSitemapXml());
            return Stream.concat(diff.added().stream(), diff.changed().stream())
                    .distinct().sorted().toList();
        } catch (RuntimeException e) {
            log.warn("提交 diff 计算失败，退化为全量：siteId={}，{}", siteId, e.getMessage());
            return full;
        }
    }

    /** 仅保留 host 与 baidu_site 完全一致的 http(s) URL，截断到 BAIDU_MAX_URLS */
    BaiduUrls baiduUrls(String baiduSite, List<String> candidates) {
        String host = URI.create(baiduSite).getHost();
        List<String> sameHost = new ArrayList<>();
        int filteredOut = 0;
        for (String url : candidates) {
            if (isSameSiteHttpUrl(url, host)) {
                sameHost.add(url);
            } else {
                filteredOut++;
            }
        }
        return new BaiduUrls(sameHost.stream().limit(BAIDU_MAX_URLS).toList(), filteredOut);
    }

    static boolean isSameSiteHttpUrl(String url, String host) {
        try {
            URI uri = URI.create(url);
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && host != null && host.equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void record(String channel, AutoSiteVersion version, ChannelResult result, long startedAt) {
        SubmissionLog entry = new SubmissionLog();
        entry.setSiteId(version.getSiteId());
        entry.setVersionNumber(version.getVersionNumber());
        entry.setChannel(channel);
        entry.setStatus(result.success() ? SubmissionLog.STATUS_SUCCESS : SubmissionLog.STATUS_FAILED);
        entry.setErrorCode(result.errorCode() == null ? null : result.errorCode().name());
        entry.setDetail(trimDetail(result.detail()));
        entry.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
        entry.setCreatedAt(LocalDateTime.now());
        logRepository.save(entry);
        trimLogs(version.getSiteId());
    }

    private void trimLogs(Long siteId) {
        List<SubmissionLog> all = logRepository.findBySiteIdOrderByIdDesc(siteId);
        if (all.size() > KEEP_LOGS) {
            logRepository.deleteAll(all.subList(KEEP_LOGS, all.size()));
        }
    }

    private String trimDetail(String message) {
        if (message == null || message.isBlank()) {
            return "未知结果";
        }
        String trimmed = sanitize(message).trim();
        return trimmed.length() <= DETAIL_MAX_LENGTH ? trimmed : trimmed.substring(0, DETAIL_MAX_LENGTH);
    }

    /** 遮蔽查询串形态的凭据值：token=… / client_secret=… → token=*** */
    static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return SECRET_QUERY_PARAM.matcher(message).replaceAll("$1***");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
