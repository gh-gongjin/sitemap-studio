package io.github.ghgongjin.sitemap.service.push;

import io.github.ghgongjin.sitemap.entity.PushConfig;
import io.github.ghgongjin.sitemap.entity.PushLog;
import io.github.ghgongjin.sitemap.entity.SubmissionLog;
import io.github.ghgongjin.sitemap.repository.PushConfigRepository;
import io.github.ghgongjin.sitemap.repository.PushLogRepository;
import io.github.ghgongjin.sitemap.repository.SubmissionLogRepository;
import io.github.ghgongjin.sitemap.service.CredentialCipher;
import io.github.ghgongjin.sitemap.service.submission.GoogleServiceAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @ClassName PushConfigService
 * @Description 推送配置存储层：表单校验、凭据加密与保留、视图脱敏、日志查询
 * @Author gj
 * @Date 2026/9/18
 * @Version 1.0
 */
@Slf4j
@Service
public class PushConfigService {

    static final String AUTH_PASSWORD = "PASSWORD";
    static final String AUTH_PRIVATE_KEY = "PRIVATE_KEY";
    static final int MIN_PORT = 1;
    static final int MAX_PORT = 65535;
    static final String DEFAULT_SITEMAP_FILE_NAME = "sitemap.xml";

    private static final Pattern HOST_PATTERN = Pattern.compile("[^\\s/:]+");
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern INDEX_NOW_KEY_PATTERN = Pattern.compile("[0-9a-fA-F]{8,128}");
    /** 私钥长度按 PEM 文本限制，留出加密膨胀余量 */
    private static final int PRIVATE_KEY_MAX_LENGTH = 4096;
    private static final int PASSWORD_MAX_LENGTH = 512;

    static final String SUBMISSION_ONLY_HOST = "_submission_only_";
    /** 明文服务账号 JSON 上限：列宽 24576 覆盖 密文膨胀(iv+tag+base64) 后的余量 */
    private static final int SERVICE_ACCOUNT_JSON_MAX = 16384;
    private static final Pattern BAIDU_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final PushConfigRepository configRepository;
    private final PushLogRepository logRepository;
    private final SubmissionLogRepository submissionLogRepository;
    private final CredentialCipher credentialCipher;

    public PushConfigService(PushConfigRepository configRepository,
                             PushLogRepository logRepository,
                             SubmissionLogRepository submissionLogRepository,
                             CredentialCipher credentialCipher) {
        this.configRepository = configRepository;
        this.logRepository = logRepository;
        this.submissionLogRepository = submissionLogRepository;
        this.credentialCipher = credentialCipher;
    }

    /**
     * 保存（新建或更新）推送设置；凭据留空表示沿用已存值
     */
    @Transactional
    public PushConfig save(Long siteId, PushSettings settings) {
        PushProtocol protocol = parseProtocol(settings.protocol());
        String host = requireHost(settings.host());
        int port = requirePort(settings.port());
        String username = requireText(settings.username(), "用户名", 256);
        String authType = parseAuthType(settings.authType());
        if (protocol != PushProtocol.SFTP && AUTH_PRIVATE_KEY.equals(authType)) {
            throw new IllegalArgumentException("仅 SFTP 支持私钥认证");
        }
        String remoteDir = trimToNull(settings.remoteDir(), 1024, "远端目录");
        String fileName = requireFileName(settings.sitemapFileName());
        String indexNowKey = requireIndexNowKey(settings.indexNowEnabled(), settings.indexNowKey());

        LocalDateTime now = LocalDateTime.now();
        PushConfig config = configRepository.findBySiteId(siteId)
                .orElseGet(() -> newConfig(siteId, now));
        config.setEnabled(settings.enabled());
        config.setProtocol(protocol.name());
        config.setHost(host);
        config.setPort(port);
        config.setUsername(username);
        config.setAuthType(authType);
        config.setRemoteDir(remoteDir);
        config.setSitemapFileName(fileName);
        config.setIndexNowEnabled(settings.indexNowEnabled());
        config.setIndexNowKey(indexNowKey);
        applyCredential(config, authType, settings.password(), settings.privateKey());
        config.setUpdatedAt(now);

        PushConfig saved = configRepository.save(config);
        log.info("推送设置已保存：siteId={}，{} → {}:{}", siteId, protocol, host, port);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<PushConfigView> view(Long siteId) {
        return siteId == null ? Optional.empty() : configRepository.findBySiteId(siteId).map(PushConfigView::of);
    }

    @Transactional(readOnly = true)
    public List<PushLog> logs(Long siteId) {
        return siteId == null ? List.of() : logRepository.findBySiteIdOrderByIdDesc(siteId);
    }

    /**
     * 保存搜索引擎提交设置；凭据留空沿用已存值；坏凭据入口即拒
     */
    @Transactional
    public PushConfig saveSubmission(Long siteId, SubmissionSettings settings) {
        String baiduSite = requireBaiduSite(settings.baiduEnabled(), settings.baiduSite());
        String baiduToken = trimToNull(settings.baiduToken(), 64, "百度 token");
        if (baiduToken != null && !BAIDU_TOKEN_PATTERN.matcher(baiduToken).matches()) {
            throw new IllegalArgumentException("百度 token 只能是 8-64 位字母、数字、下划线或短横线");
        }
        String gscSiteUrl = requireGscSiteUrl(settings.gscEnabled(), settings.gscSiteUrl());
        String gscSitemapUrl = requireGscSitemapUrl(settings.gscEnabled(), settings.gscSitemapUrl());
        String json = trimToNull(settings.serviceAccountJson(), SERVICE_ACCOUNT_JSON_MAX, "服务账号 JSON");
        GoogleServiceAccount account = json == null ? null : GoogleServiceAccount.parse(json);

        LocalDateTime now = LocalDateTime.now();
        PushConfig config = configRepository.findBySiteId(siteId)
                .orElseGet(() -> submissionOnlyConfig(siteId, now));
        if (settings.baiduEnabled() && baiduToken == null && !hasText(config.getBaiduTokenEnc())) {
            throw new IllegalArgumentException("请输入百度推送 token");
        }
        if (settings.gscEnabled() && json == null && !hasText(config.getGscServiceAccountJsonEnc())) {
            throw new IllegalArgumentException("请粘贴服务账号 JSON");
        }

        config.setBaiduEnabled(settings.baiduEnabled());
        config.setBaiduSite(baiduSite);
        if (baiduToken != null) {
            config.setBaiduTokenEnc(credentialCipher.encrypt(baiduToken));
        }
        config.setGscEnabled(settings.gscEnabled());
        config.setGscSiteUrl(gscSiteUrl);
        config.setGscSitemapUrl(gscSitemapUrl);
        if (json != null) {
            config.setGscServiceAccountJsonEnc(credentialCipher.encrypt(json));
            config.setGscClientEmail(account.clientEmail());
        }
        config.setUpdatedAt(now);

        PushConfig saved = configRepository.save(config);
        log.info("搜索引擎提交设置已保存：siteId={}，百度={}，GSC={}",
                siteId, settings.baiduEnabled(), settings.gscEnabled());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<SubmissionView> submissionView(Long siteId) {
        return siteId == null ? Optional.empty()
                : configRepository.findBySiteId(siteId).map(SubmissionView::of);
    }

    @Transactional(readOnly = true)
    public List<SubmissionLog> submissionLogs(Long siteId) {
        return siteId == null ? List.of() : submissionLogRepository.findBySiteIdOrderByIdDesc(siteId);
    }

    @Transactional
    public void delete(Long siteId) {
        configRepository.deleteBySiteId(siteId);
        logRepository.deleteBySiteId(siteId);
        submissionLogRepository.deleteBySiteId(siteId);
    }

    private void applyCredential(PushConfig config, String authType, String password, String privateKey) {
        if (AUTH_PASSWORD.equals(authType)) {
            String value = trimToNull(password, PASSWORD_MAX_LENGTH, "密码");
            if (value != null) {
                config.setPasswordEnc(credentialCipher.encrypt(value));
            } else if (!hasText(config.getPasswordEnc())) {
                throw new IllegalArgumentException("请输入密码");
            }
        } else {
            String value = trimToNull(privateKey, PRIVATE_KEY_MAX_LENGTH, "私钥内容");
            if (value != null) {
                config.setPrivateKeyEnc(credentialCipher.encrypt(value));
            } else if (!hasText(config.getPrivateKeyEnc())) {
                throw new IllegalArgumentException("请粘贴私钥内容");
            }
        }
    }

    private PushConfig newConfig(Long siteId, LocalDateTime now) {
        PushConfig config = new PushConfig();
        config.setSiteId(siteId);
        config.setCreatedAt(now);
        return config;
    }

    private PushProtocol parseProtocol(String value) {
        try {
            return PushProtocol.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的推送协议：" + value);
        }
    }

    private String parseAuthType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!AUTH_PASSWORD.equals(normalized) && !AUTH_PRIVATE_KEY.equals(normalized)) {
            throw new IllegalArgumentException("不支持的认证方式：" + value);
        }
        return normalized;
    }

    private String requireHost(String value) {
        String host = value == null ? "" : value.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("主机不能为空");
        }
        if (host.length() > 512 || !HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("主机格式不正确");
        }
        return host;
    }

    private int requirePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("端口必须在 " + MIN_PORT + " 到 " + MAX_PORT + " 之间");
        }
        return port;
    }

    private String requireFileName(String value) {
        String name = value == null || value.isBlank() ? DEFAULT_SITEMAP_FILE_NAME : value.trim();
        if (!FILE_NAME_PATTERN.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException("站点地图文件名不合法（仅限字母、数字、点、下划线与短横线）");
        }
        return name;
    }

    private String requireIndexNowKey(boolean enabled, String value) {
        String key = trimToNull(value, 64, "IndexNow key");
        if (enabled && (key == null || !INDEX_NOW_KEY_PATTERN.matcher(key).matches())) {
            throw new IllegalArgumentException("IndexNow key 必须是 8-128 位十六进制字符");
        }
        return key;
    }

    private String requireText(String value, String label, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "过长（最多 " + maxLength + " 字符）");
        }
        return trimmed;
    }

    private String trimToNull(String value, int maxLength, String label) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "过长（最多 " + maxLength + " 字符）");
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String requireBaiduSite(boolean enabled, String value) {
        String site = trimToNull(value, 512, "百度站点");
        if (site == null) {
            if (enabled) {
                throw new IllegalArgumentException("百度站点不能为空（如 https://example.com）");
            }
            return null;
        }
        URI uri = parseHttpUri(site, "百度站点");
        if (uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("百度站点必须是 https://example.com 形式（不含端口与路径）");
        }
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
    }

    private String requireGscSiteUrl(boolean enabled, String value) {
        String url = trimToNull(value, 512, "GSC 站点地址");
        if (url == null) {
            if (enabled) {
                throw new IllegalArgumentException("GSC 站点地址不能为空（sc-domain:example.com 或 https://example.com/）");
            }
            return null;
        }
        if (url.startsWith("sc-domain:")) {
            if (url.length() <= "sc-domain:".length() || url.substring("sc-domain:".length()).isBlank()) {
                throw new IllegalArgumentException("sc-domain: 后必须跟域名");
            }
            return url;
        }
        parseHttpUri(url, "GSC 站点地址");
        return url;
    }

    private String requireGscSitemapUrl(boolean enabled, String value) {
        String url = trimToNull(value, 1024, "sitemap 公开 URL");
        if (url == null) {
            if (enabled) {
                throw new IllegalArgumentException("sitemap 公开 URL 不能为空");
            }
            return null;
        }
        parseHttpUri(url, "sitemap 公开 URL");
        return url;
    }

    private URI parseHttpUri(String value, String label) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + "格式不正确");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(label + "必须是 http(s) 完整地址");
        }
        return uri;
    }

    /** 从未配过推送的站点：传输列用占位默认满足 not-null，推送表单保存时会被真实值覆盖 */
    private PushConfig submissionOnlyConfig(Long siteId, LocalDateTime now) {
        PushConfig config = new PushConfig();
        config.setSiteId(siteId);
        config.setEnabled(false);
        config.setProtocol(PushProtocol.SFTP.name());
        config.setHost(SUBMISSION_ONLY_HOST);
        config.setPort(22);
        config.setUsername("-");
        config.setAuthType(AUTH_PASSWORD);
        config.setSitemapFileName(DEFAULT_SITEMAP_FILE_NAME);
        config.setCreatedAt(now);
        return config;
    }
}
